package hu.mclsaat.legacy.activation.client;

import hu.mclsaat.legacy.activation.billing.gen.CreateInvoiceRequest;
import hu.mclsaat.legacy.activation.billing.gen.CreateInvoiceResponse;
import hu.mclsaat.legacy.activation.semantics.ActivationSemantics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.stereotype.Component;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.SoapFaultClientException;

import java.math.BigDecimal;

/**
 * Activation's client for subsystem 3, over SOAP.
 *
 * <p>The JAXB types are generated from a checked-in copy of billing's schema, the way an
 * integration team that was handed a WSDL actually works. {@code BillingContractCopyTest} compares
 * the copy against billing's original so it cannot drift unnoticed.
 *
 * <p>Two translations happen on the way out. The date goes from activation's {@code "20260929"} to
 * billing's {@code "2026-09-29"}, and the amount goes from the gross HUF activation got out of the
 * catalog to the net amount billing invoices on - which is where the documented rounding artefact
 * comes from.
 */
@Component
public class BillingSoapClient {

    private static final Logger log = LoggerFactory.getLogger(BillingSoapClient.class);

    private final WebServiceTemplate soap;
    private final String endpoint;

    public BillingSoapClient(@Value("${activation.billing.endpoint}") String endpoint) {
        this.endpoint = endpoint;
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath("hu.mclsaat.legacy.activation.billing.gen");
        this.soap = new WebServiceTemplate(marshaller);
        this.soap.setDefaultUri(endpoint);
        log.info("billing client talking SOAP to {}", endpoint);
    }

    /**
     * Issues the first invoice for a subscription.
     *
     * @param requestRef idempotency key. Flowable's async executor retries, and a retried job must
     *                   not produce a second invoice, so the order number goes in here.
     * @param grossHuf   the gross monthly fee as activation knows it; converted to net for billing
     */
    public CreatedInvoice createInvoice(int customerRef, String subscriptionRef,
                                        String periodStartYyyyMMdd, String periodEndYyyyMMdd,
                                        BigDecimal grossHuf, String requestRef,
                                        String accountNameIfNew, String billingEmailIfNew) {
        CreateInvoiceRequest request = new CreateInvoiceRequest();
        request.setCustomerRef(customerRef);
        request.setSubscriptionRef(subscriptionRef);
        request.setPeriodStart(ActivationSemantics.toBillingDate(periodStartYyyyMMdd));
        request.setPeriodEnd(ActivationSemantics.toBillingDate(periodEndYyyyMMdd));
        request.setNetAmount(ActivationSemantics.toBillingNet(grossHuf));
        request.setCurrency("HUF");
        request.setRequestRef(requestRef);
        request.setAccountName(accountNameIfNew);
        request.setBillingEmail(billingEmailIfNew);

        try {
            CreateInvoiceResponse response =
                    (CreateInvoiceResponse) soap.marshalSendAndReceive(request);
            var invoice = response.getInvoice();
            log.info("billing issued {} for {} ({} net + {} VAT = {} {}){}",
                    invoice.getInvoiceNo(), subscriptionRef, invoice.getNetAmount(),
                    invoice.getVatAmount(), invoice.getGrossAmount(), invoice.getCurrency(),
                    response.isAlreadyExisted() ? " [already existed]" : "");
            return new CreatedInvoice(invoice.getInvoiceNo(), invoice.getBillingAccountNo(),
                    invoice.getNetAmount(), invoice.getVatAmount(), invoice.getGrossAmount(),
                    invoice.getCurrency(), invoice.getStatus(), response.isAlreadyExisted());
        } catch (SoapFaultClientException ex) {
            // A client fault means billing rejected the content; retrying sends the same content.
            throw new BillingClientException("billing refused to invoice " + subscriptionRef
                    + ": " + ex.getFaultStringOrReason(), false, ex);
        } catch (WebServiceIOException ex) {
            throw new BillingClientException("billing at " + endpoint + " is unreachable", true, ex);
        }
    }

    public record CreatedInvoice(String invoiceNo, String billingAccountNo, BigDecimal netAmount,
                                 BigDecimal vatAmount, BigDecimal grossAmount, String currency,
                                 String status, boolean alreadyExisted) {
    }

    /** See {@link CatalogClientException} for what {@code retryable} decides. */
    public static class BillingClientException extends RuntimeException {

        private final boolean retryable;

        public BillingClientException(String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.retryable = retryable;
        }

        public boolean isRetryable() {
            return retryable;
        }
    }
}

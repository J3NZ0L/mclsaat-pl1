package hu.mclsaat.legacy.clients.protocol;

import hu.mclsaat.legacy.clients.billing.gen.ExportPaymentBatchRequest;
import hu.mclsaat.legacy.clients.billing.gen.ExportPaymentBatchResponse;
import hu.mclsaat.legacy.clients.billing.gen.GetInvoicesRequest;
import hu.mclsaat.legacy.clients.billing.gen.GetInvoicesResponse;
import hu.mclsaat.legacy.clients.billing.gen.GetPaymentBatchRequest;
import hu.mclsaat.legacy.clients.billing.gen.GetPaymentBatchResponse;
import hu.mclsaat.legacy.clients.billing.gen.InvoiceType;
import hu.mclsaat.legacy.clients.billing.gen.ListUnconfirmedBatchesRequest;
import hu.mclsaat.legacy.clients.billing.gen.ListUnconfirmedBatchesResponse;
import hu.mclsaat.legacy.clients.billing.gen.PayInvoiceRequest;
import hu.mclsaat.legacy.clients.billing.gen.PayInvoiceResponse;
import hu.mclsaat.legacy.clients.billing.gen.PaymentBatchType;
import hu.mclsaat.legacy.clients.billing.gen.ReconcileBatchRequest;
import hu.mclsaat.legacy.clients.billing.gen.ReconcileBatchResponse;
import hu.mclsaat.legacy.clients.billing.gen.StartPaymentRequest;
import hu.mclsaat.legacy.clients.billing.gen.StartPaymentResponse;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CanonicalBatch;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CanonicalInvoice;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CustomerRef;
import hu.mclsaat.legacy.clients.canonical.Money;
import hu.mclsaat.legacy.clients.mapping.SemanticMappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.SoapFaultClientException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Subsystem 3 over SOAP, translated into the canonical model.
 *
 * <p>The JAXB stubs are generated at build time from billing's own XSD by relative path, so there is
 * exactly one source of truth — unlike {@code activation-service}, which keeps a checked-in copy on
 * purpose. See {@code docs/decision-log.md} DL-010.
 *
 * <p>Billing reports errors as SOAP faults whose {@code faultstring} carries the code, so this client
 * turns them into a single exception type with the code preserved. Callers that also talk to the REST
 * subsystems would otherwise have two unrelated error models to reason about.
 */
public class BillingSoapClient {

    private static final Logger log = LoggerFactory.getLogger(BillingSoapClient.class);

    /** Hand the settlement file to the clearing house again and wait for a real acknowledgement. */
    public static final String RECONCILE_RESEND = "RESEND";
    /** Rebuild the acknowledgement from the file that was already sent, and apply it. */
    public static final String RECONCILE_RE_DRIVE_ACK = "RE_DRIVE_ACK";

    private final WebServiceTemplate soap;
    private final String endpoint;

    public BillingSoapClient(String endpoint) {
        this.endpoint = endpoint;
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath("hu.mclsaat.legacy.clients.billing.gen");
        this.soap = new WebServiceTemplate(marshaller);
        this.soap.setDefaultUri(endpoint);
        log.info("billing client talking SOAP to {}", endpoint);
    }

    /** Service 5a, by the integer customer reference rather than billing's own account number. */
    public List<CanonicalInvoice> invoicesOfCustomer(int customerReference, String status) {
        GetInvoicesRequest request = new GetInvoicesRequest();
        request.setCustomerRef(customerReference);
        request.setStatus(status);
        GetInvoicesResponse response = (GetInvoicesResponse) send(request,
                "invoices of customer " + customerReference);
        List<CanonicalInvoice> invoices = new ArrayList<>();
        response.getInvoice().forEach(invoice -> invoices.add(toInvoice(invoice)));
        return invoices;
    }

    /** Service 5b. Returns Stripe's client secret, which is the UCP checkout hand-off in phase 3. */
    public PaymentStarted startPayment(String invoiceNo) {
        StartPaymentRequest request = new StartPaymentRequest();
        request.setInvoiceNo(invoiceNo);
        StartPaymentResponse response = (StartPaymentResponse) send(request,
                "payment of invoice " + invoiceNo);
        return new PaymentStarted(response.getInvoiceNo(), response.getPaymentRef(),
                response.getClientSecret(),
                Money.ofMinorUnits(response.getAmountMinor(), response.getCurrency()),
                response.getPaymentStatus());
    }

    /**
     * Service 5c: pay an invoice with no browser, which is how the chat and agent channels pay.
     * Billing confirms the payment, hands the invoice to settlement and returns while it is
     * {@code SETTLEMENT_PENDING}; the caller polls {@link #invoicesOfCustomer} until it is
     * {@code PAID}. Safe to repeat.
     */
    public PaymentCompleted payInvoice(String invoiceNo) {
        PayInvoiceRequest request = new PayInvoiceRequest();
        request.setInvoiceNo(invoiceNo);
        PayInvoiceResponse response = (PayInvoiceResponse) send(request,
                "browserless payment of invoice " + invoiceNo);
        return new PaymentCompleted(response.getInvoiceNo(), response.getPaymentRef(),
                SemanticMappers.fromBillingInvoiceStatus(response.getInvoiceStatus()),
                response.isChanged(), response.getBatchId(), response.getMessage());
    }

    /** Ops: cut a settlement batch now rather than waiting for the nightly run. */
    public Optional<CanonicalBatch> exportPaymentBatch() {
        ExportPaymentBatchResponse response =
                (ExportPaymentBatchResponse) send(new ExportPaymentBatchRequest(), "a batch export");
        log.info("billing says: {}", response.getMessage());
        return Optional.ofNullable(response.getBatch()).map(BillingSoapClient::toBatch);
    }

    /** Ops: the billing half of failure branch B. */
    public List<CanonicalBatch> unconfirmedBatches(Integer olderThanMinutes) {
        ListUnconfirmedBatchesRequest request = new ListUnconfirmedBatchesRequest();
        request.setOlderThanMinutes(olderThanMinutes);
        ListUnconfirmedBatchesResponse response =
                (ListUnconfirmedBatchesResponse) send(request, "the unconfirmed batch list");
        List<CanonicalBatch> batches = new ArrayList<>();
        response.getBatch().forEach(batch -> batches.add(toBatch(batch)));
        return batches;
    }

    /** Ops: one batch and exactly which money is stuck in it. */
    public BatchDetail paymentBatch(String batchId) {
        GetPaymentBatchRequest request = new GetPaymentBatchRequest();
        request.setBatchId(batchId);
        GetPaymentBatchResponse response = (GetPaymentBatchResponse) send(request, "batch " + batchId);
        List<CanonicalInvoice> invoices = new ArrayList<>();
        response.getInvoice().forEach(invoice -> invoices.add(toInvoice(invoice)));
        return new BatchDetail(toBatch(response.getBatch()), invoices);
    }

    /**
     * Ops: the remediation for failure branch B.
     *
     * @param mode {@link #RECONCILE_RESEND} or {@link #RECONCILE_RE_DRIVE_ACK}
     */
    public Reconciliation reconcileBatch(String batchId, String mode) {
        ReconcileBatchRequest request = new ReconcileBatchRequest();
        request.setBatchId(batchId);
        request.setMode(mode);
        ReconcileBatchResponse response = (ReconcileBatchResponse) send(request,
                "reconciliation of batch " + batchId);
        return new Reconciliation(toBatch(response.getBatch()), response.getInvoicesSettled(),
                response.getMessage());
    }

    public boolean reachable() {
        try {
            unconfirmedBatches(100_000);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    // ------------------------------------------------------------------ mapping

    private static CanonicalInvoice toInvoice(InvoiceType invoice) {
        String currency = invoice.getCurrency();
        return new CanonicalInvoice(
                invoice.getInvoiceNo(),
                invoice.getBillingAccountNo(),
                CustomerRef.of(invoice.getCustomerRef())
                        .withBillingAccount(invoice.getBillingAccountNo()),
                invoice.getSubscriptionRef(),
                SemanticMappers.fromBillingDate(invoice.getPeriodStart()),
                SemanticMappers.fromBillingDate(invoice.getPeriodEnd()),
                Money.ofMajorUnits(invoice.getNetAmount(), currency),
                Money.ofMajorUnits(invoice.getVatAmount(), currency),
                Money.ofMajorUnits(invoice.getGrossAmount(), currency),
                SemanticMappers.fromBillingInvoiceStatus(invoice.getStatus()),
                invoice.getPaymentRef(),
                invoice.getBatchId(),
                parseInstant(invoice.getIssuedTs()));
    }

    private static CanonicalBatch toBatch(PaymentBatchType batch) {
        return new CanonicalBatch(
                batch.getBatchId(),
                batch.getFileName(),
                SemanticMappers.fromBillingBatchStatus(batch.getStatus()),
                batch.getItemCount(),
                Money.ofMajorUnits(batch.getTotalAmount(), batch.getCurrency()),
                parseInstant(batch.getCreatedTs()),
                parseInstant(batch.getSentTs()),
                parseInstant(batch.getAckedTs()),
                batch.getAckFileName(),
                batch.getAgeMinutes());
    }

    private static Instant parseInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    // ----------------------------------------------------------------- plumbing

    private Object send(Object request, String what) {
        try {
            return soap.marshalSendAndReceive(request);
        } catch (SoapFaultClientException ex) {
            throw new BillingClientException(ex.getFaultStringOrReason(),
                    "billing refused " + what + ": " + ex.getFaultStringOrReason(), ex);
        } catch (WebServiceIOException ex) {
            throw new BillingClientException("UNREACHABLE",
                    "billing at " + endpoint + " is unreachable while requesting " + what, ex);
        }
    }

    /** Billing's SOAP fault, with its code kept so a caller can branch on it. */
    public static class BillingClientException extends RuntimeException {

        private final String faultCode;

        public BillingClientException(String faultCode, String message, Throwable cause) {
            super(message, cause);
            this.faultCode = faultCode;
        }

        /** {@code NOT_FOUND}, {@code BAD_REQUEST}, {@code ILLEGAL_STATE}, … */
        public String faultCode() {
            return faultCode;
        }
    }

    public record PaymentStarted(String invoiceNo, String paymentRef, String clientSecret,
                                Money amount, String paymentStatus) {
    }

    /** {@code status} is the invoice's, in the canonical vocabulary, at the moment the call returned. */
    public record PaymentCompleted(String invoiceNo, String paymentRef,
                                  CanonicalModel.InvoiceStatus status, boolean changed,
                                  String batchId, String message) {
    }

    public record BatchDetail(CanonicalBatch batch, List<CanonicalInvoice> invoices) {
    }

    public record Reconciliation(CanonicalBatch batch, int invoicesSettled, String message) {
    }
}

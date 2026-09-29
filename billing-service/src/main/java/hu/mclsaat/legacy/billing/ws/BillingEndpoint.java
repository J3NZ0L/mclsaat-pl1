package hu.mclsaat.legacy.billing.ws;

import hu.mclsaat.legacy.billing.domain.InvoiceRow;
import hu.mclsaat.legacy.billing.service.BillingException;
import hu.mclsaat.legacy.billing.service.InvoiceService;
import hu.mclsaat.legacy.billing.ws.gen.CreateInvoiceRequest;
import hu.mclsaat.legacy.billing.ws.gen.CreateInvoiceResponse;
import hu.mclsaat.legacy.billing.ws.gen.GetInvoicesRequest;
import hu.mclsaat.legacy.billing.ws.gen.GetInvoicesResponse;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

import java.util.List;

/** SOAP endpoint for the invoice operations (service 5, query half, plus invoice issuing). */
@Endpoint
public class BillingEndpoint {

    static final String NAMESPACE = "http://mclsaat.hu/legacy/billing/v1";

    private final InvoiceService invoices;
    private final WsMapper mapper;

    public BillingEndpoint(InvoiceService invoices, WsMapper mapper) {
        this.invoices = invoices;
        this.mapper = mapper;
    }

    /**
     * Service 5a: look up a customer's invoices.
     *
     * <p>Accepts either billing's own {@code billingAccountNo} or the integer {@code customerRef}.
     * It does not accept the catalog's {@code "00000042"} form - a caller holding that string has
     * to strip the padding and parse it first.
     */
    @PayloadRoot(namespace = NAMESPACE, localPart = "getInvoicesRequest")
    @ResponsePayload
    public GetInvoicesResponse getInvoices(@RequestPayload GetInvoicesRequest request) {
        boolean hasBaNo = request.getBillingAccountNo() != null && !request.getBillingAccountNo().isBlank();
        boolean hasCustomerRef = request.getCustomerRef() != null;
        if (hasBaNo == hasCustomerRef) {
            throw new BillingException.BadRequest(
                    "supply exactly one of billingAccountNo or customerRef");
        }

        String status = blankToNull(request.getStatus());
        String subscriptionRef = blankToNull(request.getSubscriptionRef());

        List<InvoiceRow> rows;
        int customerRef;
        if (hasBaNo) {
            customerRef = invoices.requireAccount(request.getBillingAccountNo()).customerRef();
            rows = invoices.findByBillingAccount(request.getBillingAccountNo(), status, subscriptionRef);
        } else {
            customerRef = request.getCustomerRef();
            rows = invoices.findByCustomerRef(customerRef, status, subscriptionRef);
        }

        GetInvoicesResponse response = new GetInvoicesResponse();
        for (InvoiceRow row : rows) {
            response.getInvoice().add(mapper.toWs(row, customerRef));
        }
        return response;
    }

    /**
     * Issues an invoice. Called by the activation process once a subscription goes live.
     *
     * <p>The amount arriving here is NET. The catalog's price is gross, so the caller has already
     * divided by {@code 1 + vatRate}; billing multiplies the VAT back on. Both sides round to two
     * decimals, which is where the documented one-fillér artefact comes from.
     */
    @PayloadRoot(namespace = NAMESPACE, localPart = "createInvoiceRequest")
    @ResponsePayload
    public CreateInvoiceResponse createInvoice(@RequestPayload CreateInvoiceRequest request) {
        var result = invoices.createInvoice(
                request.getCustomerRef(),
                blankToNull(request.getSubscriptionRef()),
                mapper.parseDate("periodStart", request.getPeriodStart()),
                mapper.parseDate("periodEnd", request.getPeriodEnd()),
                request.getNetAmount(),
                request.getCurrency() == null || request.getCurrency().isBlank()
                        ? "HUF" : request.getCurrency(),
                blankToNull(request.getRequestRef()),
                blankToNull(request.getAccountName()),
                blankToNull(request.getBillingEmail()));

        CreateInvoiceResponse response = new CreateInvoiceResponse();
        response.setInvoice(mapper.toWs(result.invoice(), request.getCustomerRef()));
        response.setAlreadyExisted(result.alreadyExisted());
        return response;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

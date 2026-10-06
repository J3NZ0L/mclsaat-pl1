package hu.mclsaat.legacy.billing.ws;

import hu.mclsaat.legacy.billing.batch.PaymentBatchService;
import hu.mclsaat.legacy.billing.domain.InvoiceRow;
import hu.mclsaat.legacy.billing.domain.PaymentBatchRow;
import hu.mclsaat.legacy.billing.payment.PaymentService;
import hu.mclsaat.legacy.billing.service.BillingException;
import hu.mclsaat.legacy.billing.service.InvoiceService;
import hu.mclsaat.legacy.billing.ws.gen.CreateInvoiceRequest;
import hu.mclsaat.legacy.billing.ws.gen.CreateInvoiceResponse;
import hu.mclsaat.legacy.billing.ws.gen.GetInvoicesRequest;
import hu.mclsaat.legacy.billing.ws.gen.GetInvoicesResponse;
import hu.mclsaat.legacy.billing.ws.gen.ExportPaymentBatchRequest;
import hu.mclsaat.legacy.billing.ws.gen.ExportPaymentBatchResponse;
import hu.mclsaat.legacy.billing.ws.gen.GetPaymentBatchRequest;
import hu.mclsaat.legacy.billing.ws.gen.GetPaymentBatchResponse;
import hu.mclsaat.legacy.billing.ws.gen.ListUnconfirmedBatchesRequest;
import hu.mclsaat.legacy.billing.ws.gen.ListUnconfirmedBatchesResponse;
import hu.mclsaat.legacy.billing.ws.gen.ReconcileBatchRequest;
import hu.mclsaat.legacy.billing.ws.gen.ReconcileBatchResponse;
import hu.mclsaat.legacy.billing.ws.gen.PayInvoiceRequest;
import hu.mclsaat.legacy.billing.ws.gen.PayInvoiceResponse;
import hu.mclsaat.legacy.billing.ws.gen.StartPaymentRequest;
import hu.mclsaat.legacy.billing.ws.gen.StartPaymentResponse;
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
    private final PaymentService payments;
    private final PaymentBatchService batches;
    private final WsMapper mapper;

    public BillingEndpoint(InvoiceService invoices, PaymentService payments,
                           PaymentBatchService batches, WsMapper mapper) {
        this.invoices = invoices;
        this.payments = payments;
        this.batches = batches;
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

    /**
     * Service 5b: start a card payment for an invoice.
     *
     * <p>Returns Stripe's client secret, which is the natural hand-off point for the UCP checkout
     * in phase 3. Note the currency amount changes representation again on the way out: the
     * invoice holds NUMERIC(12,2) major units, Stripe is told an integer count of minor units.
     */
    @PayloadRoot(namespace = NAMESPACE, localPart = "startPaymentRequest")
    @ResponsePayload
    public StartPaymentResponse startPayment(@RequestPayload StartPaymentRequest request) {
        if (request.getInvoiceNo() == null || request.getInvoiceNo().isBlank()) {
            throw new BillingException.BadRequest("invoiceNo is required");
        }
        var intent = payments.startPayment(request.getInvoiceNo().trim());

        StartPaymentResponse response = new StartPaymentResponse();
        response.setInvoiceNo(request.getInvoiceNo().trim());
        response.setPaymentRef(intent.id());
        response.setClientSecret(intent.clientSecret());
        response.setAmountMinor(intent.amountMinor());
        response.setCurrency(intent.currency());
        response.setPaymentStatus(intent.status());
        return response;
    }

    /**
     * Service 5c: pay an invoice from a channel that has no browser. Billing confirms the payment
     * at Stripe itself and hands the invoice to settlement; see {@code PaymentService#payInvoiceNow}.
     * {@link #startPayment} remains the browser hand-off, and both can coexist on one invoice.
     */
    @PayloadRoot(namespace = NAMESPACE, localPart = "payInvoiceRequest")
    @ResponsePayload
    public PayInvoiceResponse payInvoice(@RequestPayload PayInvoiceRequest request) {
        if (request.getInvoiceNo() == null || request.getInvoiceNo().isBlank()) {
            throw new BillingException.BadRequest("invoiceNo is required");
        }
        var outcome = payments.payInvoiceNow(request.getInvoiceNo().trim());

        PayInvoiceResponse response = new PayInvoiceResponse();
        response.setInvoiceNo(outcome.invoice().invoiceNo());
        response.setPaymentRef(outcome.invoice().paymentRef());
        response.setInvoiceStatus(outcome.invoice().status());
        response.setChanged(outcome.changed());
        response.setBatchId(outcome.invoice().batchId());
        response.setMessage(outcome.message());
        return response;
    }

    // ------------------------------------------------------------------------
    // Ops-only operations. These are how failure branch B is found and repaired; no
    // customer-facing channel reaches them.
    // ------------------------------------------------------------------------

    /**
     * Cuts a settlement batch now, instead of waiting for the nightly export. Writes the
     * fixed-width file to the outbox and hands it to the clearing house.
     */
    @PayloadRoot(namespace = NAMESPACE, localPart = "exportPaymentBatchRequest")
    @ResponsePayload
    public ExportPaymentBatchResponse exportPaymentBatch(
            @RequestPayload ExportPaymentBatchRequest request) {
        ExportPaymentBatchResponse response = new ExportPaymentBatchResponse();
        var batch = batches.exportBatch();
        if (batch.isEmpty()) {
            response.setMessage("nothing to settle: no unbatched SETTLEMENT_PENDING invoices");
            return response;
        }
        response.setBatch(mapper.toWs(batch.get()));
        response.setMessage("batch " + batch.get().batchId() + " written to the outbox as "
                + batch.get().fileName() + " and handed to the clearing house");
        return response;
    }

    /**
     * Batches that were handed over and never acknowledged. The billing half of failure branch B:
     * from here it looks like money that left and never landed.
     */
    @PayloadRoot(namespace = NAMESPACE, localPart = "listUnconfirmedBatchesRequest")
    @ResponsePayload
    public ListUnconfirmedBatchesResponse listUnconfirmedBatches(
            @RequestPayload ListUnconfirmedBatchesRequest request) {
        ListUnconfirmedBatchesResponse response = new ListUnconfirmedBatchesResponse();
        for (PaymentBatchRow row : batches.listUnconfirmed(request.getOlderThanMinutes())) {
            response.getBatch().add(mapper.toWs(row));
        }
        return response;
    }

    @PayloadRoot(namespace = NAMESPACE, localPart = "getPaymentBatchRequest")
    @ResponsePayload
    public GetPaymentBatchResponse getPaymentBatch(@RequestPayload GetPaymentBatchRequest request) {
        if (request.getBatchId() == null || request.getBatchId().isBlank()) {
            throw new BillingException.BadRequest("batchId is required");
        }
        String batchId = request.getBatchId().trim();

        GetPaymentBatchResponse response = new GetPaymentBatchResponse();
        response.setBatch(mapper.toWs(batches.requireBatch(batchId)));
        for (InvoiceRow invoice : batches.invoicesOf(batchId)) {
            response.getInvoice().add(
                    mapper.toWs(invoice, invoices.requireAccount(invoice.baNo()).customerRef()));
        }
        return response;
    }

    /** The ops remediation for failure branch B. See {@code PaymentBatchService#reconcile}. */
    @PayloadRoot(namespace = NAMESPACE, localPart = "reconcileBatchRequest")
    @ResponsePayload
    public ReconcileBatchResponse reconcileBatch(@RequestPayload ReconcileBatchRequest request) {
        if (request.getBatchId() == null || request.getBatchId().isBlank()) {
            throw new BillingException.BadRequest("batchId is required");
        }
        var result = batches.reconcile(request.getBatchId().trim(), request.getMode());

        ReconcileBatchResponse response = new ReconcileBatchResponse();
        response.setBatch(mapper.toWs(result.batch()));
        response.setInvoicesSettled(result.invoicesSettled());
        response.setMessage(result.message());
        return response;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

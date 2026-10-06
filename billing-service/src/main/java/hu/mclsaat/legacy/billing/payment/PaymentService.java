package hu.mclsaat.legacy.billing.payment;

import hu.mclsaat.legacy.billing.batch.PaymentBatchService;
import hu.mclsaat.legacy.billing.domain.InvoiceRow;
import hu.mclsaat.legacy.billing.repo.InvoiceRepository;
import hu.mclsaat.legacy.billing.service.BillingException;
import hu.mclsaat.legacy.billing.service.InvoiceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

/**
 * Service 5b: start a card payment, take the news that one succeeded, or do both at once for a
 * channel with no browser.
 *
 * <p>The two halves arrive by different routes on purpose. Starting a payment is a SOAP call from
 * inside the enterprise; hearing that it succeeded is a REST/JSON webhook from Stripe. The same
 * invoice is therefore touched over two protocols within one subsystem.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final BigDecimal MINOR_UNITS_PER_MAJOR = new BigDecimal("100");

    private final InvoiceService invoices;
    private final InvoiceRepository invoiceRepo;
    private final StripeGateway stripe;
    private final PaymentBatchService batches;
    private final TransactionTemplate tx;

    public PaymentService(InvoiceService invoices, InvoiceRepository invoiceRepo, StripeGateway stripe,
                          PaymentBatchService batches, PlatformTransactionManager transactionManager) {
        this.invoices = invoices;
        this.invoiceRepo = invoiceRepo;
        this.stripe = stripe;
        this.batches = batches;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public StripeGateway.PaymentIntentView startPayment(String invoiceNo) {
        InvoiceRow invoice = invoices.requireInvoice(invoiceNo);
        if (!InvoiceRow.STATUS_OPEN.equals(invoice.status())) {
            throw new BillingException.IllegalState("invoice " + invoiceNo
                    + " cannot be paid from status " + invoice.status());
        }
        return createAndRecordIntent(invoice);
    }

    /**
     * Pays an invoice with no browser involved, and closes the payment leg on the system's side:
     * confirm at Stripe, move the invoice to {@code SETTLEMENT_PENDING}, cut the settlement batch.
     * The invoice reaches {@code PAID} when the clearing house answers, so the caller polls.
     *
     * <p>The three steps are deliberately not one transaction. Once Stripe has the money, billing
     * has to remember it whatever happens to the batch file, so the transition commits first and
     * the export is attempted afterwards; if it fails the invoice simply waits in
     * {@code SETTLEMENT_PENDING} for a retry of this call or for ops. The Stripe call also stays
     * outside any database transaction, so a slow Stripe holds no connection or row lock.
     *
     * <p>Idempotent: an invoice that is not {@code OPEN} is reported as it is. One that is
     * {@code SETTLEMENT_PENDING} and still unbatched, because an earlier call or the webhook got it
     * that far, is exported now. An intent already created by {@link #startPayment} is reused.
     */
    public PaymentOutcome payInvoiceNow(String invoiceNo) {
        // transaction 1: look at the invoice and make sure it carries a PaymentIntent
        Prepared prepared = tx.execute(status -> {
            InvoiceRow invoice = invoices.requireInvoice(invoiceNo);
            if (InvoiceRow.STATUS_CANCELLED.equals(invoice.status())) {
                throw new BillingException.IllegalState("invoice " + invoiceNo
                        + " is CANCELLED and cannot be paid");
            }
            if (!InvoiceRow.STATUS_OPEN.equals(invoice.status())) {
                return new Prepared(invoice, null);
            }
            String paymentRef = invoice.paymentRef() != null
                    ? invoice.paymentRef() : createAndRecordIntent(invoice).id();
            return new Prepared(invoice, paymentRef);
        });

        boolean changed = false;
        if (prepared.paymentIntentId() != null) {
            // no transaction: this is the call that takes the customer's money
            var confirmed = stripe.confirmPaymentIntent(prepared.paymentIntentId());
            if (!"succeeded".equals(confirmed.status())) {
                throw new BillingException.IllegalState("Stripe did not take the payment for invoice "
                        + invoiceNo + ": PaymentIntent " + prepared.paymentIntentId() + " is "
                        + confirmed.status());
            }
            // transaction 2: the same transition the webhook makes
            changed = tx.execute(status -> settle(invoices.requireInvoice(invoiceNo),
                    prepared.paymentIntentId()).changed());
        }

        InvoiceRow current = invoices.requireInvoice(invoiceNo);
        String note = null;
        if (InvoiceRow.STATUS_SETTLEMENT_PENDING.equals(current.status()) && current.batchId() == null) {
            try {
                batches.exportBatch();
            } catch (RuntimeException ex) {
                log.error("settlement export after paying {} failed; the invoice stays in "
                        + "SETTLEMENT_PENDING", invoiceNo, ex);
                note = "the settlement export failed (" + ex.getMessage() + "); the invoice waits in "
                        + "SETTLEMENT_PENDING for a retry or for ops";
            }
            current = invoices.requireInvoice(invoiceNo);
        }

        return new PaymentOutcome(current, changed, note != null ? note : describe(current, changed));
    }

    private static String describe(InvoiceRow invoice, boolean changed) {
        return switch (invoice.status()) {
            case InvoiceRow.STATUS_PAID -> changed
                    ? "paid, and the clearing house has already acknowledged the batch"
                    : "already PAID; nothing was done";
            case InvoiceRow.STATUS_SETTLEMENT_PENDING -> invoice.batchId() == null
                    ? "payment taken; no settlement batch was cut"
                    : "payment taken; waiting for the clearing house to acknowledge batch "
                      + invoice.batchId() + ", poll the invoice for PAID";
            default -> "invoice is " + invoice.status();
        };
    }

    private StripeGateway.PaymentIntentView createAndRecordIntent(InvoiceRow invoice) {
        long amountMinor = toMinorUnits(invoice.grossAmount());
        var intent = stripe.createPaymentIntent(invoice.invoiceNo(), amountMinor, invoice.currency());
        invoiceRepo.markPaymentStarted(invoice.invoiceNo(), intent.id());
        return intent;
    }

    /**
     * Handles a {@code payment_intent.succeeded} notification.
     *
     * <p>Idempotent: Stripe retries webhooks, and an invoice that has already moved on is left
     * alone. The transition itself is {@link #settle}, shared with {@link #payInvoiceNow}.
     */
    @Transactional
    public SettlementResult paymentSucceeded(String paymentIntentId, String invoiceNoFromMetadata) {
        return settle(resolveInvoice(paymentIntentId, invoiceNoFromMetadata), paymentIntentId);
    }

    /**
     * The single place an invoice goes {@code OPEN} -> {@code SETTLEMENT_PENDING}.
     *
     * <p>The invoice does not become {@code PAID} here. Stripe has the money, but this business
     * only considers it posted once the clearing house acknowledges the settlement batch, so the
     * invoice waits for the file exchange. That gap is where failure branch B lives.
     */
    private SettlementResult settle(InvoiceRow invoice, String paymentIntentId) {
        if (!InvoiceRow.STATUS_OPEN.equals(invoice.status())
                || invoiceRepo.markSettlementPending(invoice.invoiceNo(), paymentIntentId) == 0) {
            // the conditional UPDATE also covers a webhook and a SOAP call racing each other
            InvoiceRow now = invoices.requireInvoice(invoice.invoiceNo());
            log.info("payment of {} on {} changes nothing: invoice is already {}",
                    invoice.invoiceNo(), paymentIntentId, now.status());
            return new SettlementResult(now.invoiceNo(), now.status(), false);
        }
        log.info("invoice {} moved to SETTLEMENT_PENDING on {}", invoice.invoiceNo(), paymentIntentId);
        return new SettlementResult(invoice.invoiceNo(), InvoiceRow.STATUS_SETTLEMENT_PENDING, true);
    }

    private InvoiceRow resolveInvoice(String paymentIntentId, String invoiceNoFromMetadata) {
        if (invoiceNoFromMetadata != null && !invoiceNoFromMetadata.isBlank()) {
            return invoices.requireInvoice(invoiceNoFromMetadata);
        }
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            throw new BillingException.BadRequest(
                    "the event carries neither a payment intent id nor an invoice_no in its metadata");
        }
        return invoiceRepo.findByPaymentRef(paymentIntentId)
                .orElseThrow(() -> new BillingException.NotFound("invoice for payment intent",
                        paymentIntentId));
    }

    /**
     * HUF major units with two decimals to Stripe's integer minor units. Stripe treats HUF as a
     * two-decimal currency, so a fillér here is a minor unit there and the scale matches the
     * catalog's {@code monthly_fee_minor} again - after the trip through NUMERIC(12,2).
     */
    public static long toMinorUnits(BigDecimal majorUnits) {
        return majorUnits.multiply(MINOR_UNITS_PER_MAJOR).longValueExact();
    }

    public record SettlementResult(String invoiceNo, String status, boolean changed) {
    }

    /** What {@link #payInvoiceNow} did, with the invoice as it stands after it. */
    public record PaymentOutcome(InvoiceRow invoice, boolean changed, String message) {
    }

    private record Prepared(InvoiceRow invoice, String paymentIntentId) {
    }
}

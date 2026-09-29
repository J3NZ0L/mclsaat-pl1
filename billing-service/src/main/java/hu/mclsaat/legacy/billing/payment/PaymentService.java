package hu.mclsaat.legacy.billing.payment;

import hu.mclsaat.legacy.billing.domain.InvoiceRow;
import hu.mclsaat.legacy.billing.repo.InvoiceRepository;
import hu.mclsaat.legacy.billing.service.BillingException;
import hu.mclsaat.legacy.billing.service.InvoiceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Service 5b: start a card payment, and take the news that one succeeded.
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

    public PaymentService(InvoiceService invoices, InvoiceRepository invoiceRepo, StripeGateway stripe) {
        this.invoices = invoices;
        this.invoiceRepo = invoiceRepo;
        this.stripe = stripe;
    }

    @Transactional
    public StripeGateway.PaymentIntentView startPayment(String invoiceNo) {
        InvoiceRow invoice = invoices.requireInvoice(invoiceNo);
        if (!InvoiceRow.STATUS_OPEN.equals(invoice.status())) {
            throw new BillingException.IllegalState("invoice " + invoiceNo
                    + " cannot be paid from status " + invoice.status());
        }

        long amountMinor = toMinorUnits(invoice.grossAmount());
        var intent = stripe.createPaymentIntent(invoiceNo, amountMinor, invoice.currency());
        invoiceRepo.markPaymentStarted(invoiceNo, intent.id());
        return intent;
    }

    /**
     * Handles a {@code payment_intent.succeeded} notification.
     *
     * <p>The invoice does not become {@code PAID} here. Stripe has the money, but this business
     * only considers it posted once the clearing house acknowledges the settlement batch, so the
     * invoice goes to {@code SETTLEMENT_PENDING} and waits for the file exchange. That gap is
     * where failure branch B lives.
     *
     * <p>Idempotent: Stripe retries webhooks, and an invoice that has already moved on is left
     * alone.
     */
    @Transactional
    public SettlementResult paymentSucceeded(String paymentIntentId, String invoiceNoFromMetadata) {
        InvoiceRow invoice = resolveInvoice(paymentIntentId, invoiceNoFromMetadata);
        if (!InvoiceRow.STATUS_OPEN.equals(invoice.status())) {
            log.info("payment_intent.succeeded for {} ignored: invoice is already {}",
                    invoice.invoiceNo(), invoice.status());
            return new SettlementResult(invoice.invoiceNo(), invoice.status(), false);
        }
        invoiceRepo.markSettlementPending(invoice.invoiceNo(), paymentIntentId);
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
}

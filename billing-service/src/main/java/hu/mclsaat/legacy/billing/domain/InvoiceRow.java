package hu.mclsaat.legacy.billing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One row of {@code billing.invoice}.
 *
 * <p>Amounts are HUF major units with two decimals. The catalog holds the same figures as an
 * integer count of fillér, and Stripe wants them back as an integer count of minor units, so
 * one monthly fee is represented three different ways on its way through the system.
 */
public record InvoiceRow(
        String invoiceNo,
        String baNo,
        String subscriptionRef,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal netAmount,
        BigDecimal vatRate,
        BigDecimal vatAmount,
        BigDecimal grossAmount,
        String currency,
        String status,
        String paymentRef,
        String requestRef,
        String batchId,
        Instant issuedTs,
        Instant updatedTs) {

    public static final String STATUS_OPEN = "OPEN";
    /** Stripe took the money, but the clearing-house batch has not been acknowledged yet. */
    public static final String STATUS_SETTLEMENT_PENDING = "SETTLEMENT_PENDING";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_CANCELLED = "CANCELLED";
}

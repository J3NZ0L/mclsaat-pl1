package hu.mclsaat.legacy.billing.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** One row of {@code billing.payment_batch}: a settlement file handed to the clearing house. */
public record PaymentBatchRow(
        String batchId,
        String fileName,
        String status,
        int itemCount,
        BigDecimal totalAmount,
        String currency,
        Instant createdTs,
        Instant sentTs,
        Instant ackedTs,
        String ackFileName) {

    public static final String STATUS_OPEN = "OPEN";
    /** The file is in the outbox and the clearing house owes us an acknowledgement. */
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_ACKED = "ACKED";
    public static final String STATUS_FAILED = "FAILED";
}

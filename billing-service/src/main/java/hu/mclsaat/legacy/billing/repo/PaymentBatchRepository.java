package hu.mclsaat.legacy.billing.repo;

import hu.mclsaat.legacy.billing.domain.PaymentBatchRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Repository
public class PaymentBatchRepository {

    private static final DateTimeFormatter BATCH_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String COLUMNS = """
            batch_id, file_name, status, item_count, total_amount, currency,
            created_ts, sent_ts, acked_ts, ack_file_name
            """;

    private static final RowMapper<PaymentBatchRow> MAPPER = (rs, rowNum) -> new PaymentBatchRow(
            rs.getString("batch_id"),
            rs.getString("file_name"),
            rs.getString("status"),
            rs.getInt("item_count"),
            rs.getBigDecimal("total_amount"),
            rs.getString("currency").trim(),
            rs.getTimestamp("created_ts").toInstant(),
            rs.getTimestamp("sent_ts") == null ? null : rs.getTimestamp("sent_ts").toInstant(),
            rs.getTimestamp("acked_ts") == null ? null : rs.getTimestamp("acked_ts").toInstant(),
            rs.getString("ack_file_name"));

    private final JdbcTemplate jdbc;

    public PaymentBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PaymentBatchRow> findById(String batchId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM billing.payment_batch WHERE batch_id = ?",
                MAPPER, batchId).stream().findFirst();
    }

    /**
     * Batches that were handed to the clearing house and never acknowledged. This is the whole
     * of failure branch B as far as billing can see it: the money left, nothing came back.
     */
    public List<PaymentBatchRow> findUnconfirmed(int olderThanMinutes) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM billing.payment_batch
                 WHERE status = 'SENT'
                   AND sent_ts < now() - make_interval(mins => ?)
                 ORDER BY sent_ts
                """, MAPPER, olderThanMinutes);
    }

    public List<PaymentBatchRow> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM billing.payment_batch ORDER BY created_ts DESC", MAPPER);
    }

    /** Allocates {@code BATCH-yyyyMMdd-nnn} and inserts the batch in {@code OPEN}. */
    public PaymentBatchRow insertOpen(String currency) {
        long next = jdbc.queryForObject("SELECT nextval('billing.batch_seq')", Long.class);
        String batchId = "BATCH-%s-%03d".formatted(LocalDate.now().format(BATCH_DAY), next % 1000);
        jdbc.update("""
                INSERT INTO billing.payment_batch (batch_id, status, currency)
                VALUES (?, 'OPEN', ?)
                """, batchId, currency);
        return findById(batchId).orElseThrow();
    }

    public int markSent(String batchId, String fileName, int itemCount, BigDecimal totalAmount) {
        return jdbc.update("""
                UPDATE billing.payment_batch
                   SET status = 'SENT', file_name = ?, item_count = ?, total_amount = ?, sent_ts = now()
                 WHERE batch_id = ?
                """, fileName, itemCount, totalAmount, batchId);
    }

    public int markAcked(String batchId, String ackFileName) {
        return jdbc.update("""
                UPDATE billing.payment_batch
                   SET status = 'ACKED', ack_file_name = ?, acked_ts = now()
                 WHERE batch_id = ? AND status = 'SENT'
                """, ackFileName, batchId);
    }

    public int markFailed(String batchId) {
        return jdbc.update("UPDATE billing.payment_batch SET status = 'FAILED' WHERE batch_id = ?", batchId);
    }

    /** Test/demo support: makes a freshly sent batch look old enough to be reported. */
    public int backdateSentTs(String batchId, int minutes) {
        return jdbc.update("UPDATE billing.payment_batch SET sent_ts = ? WHERE batch_id = ?",
                Timestamp.from(java.time.Instant.now().minusSeconds(60L * minutes)), batchId);
    }
}

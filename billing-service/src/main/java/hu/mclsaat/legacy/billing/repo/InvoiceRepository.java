package hu.mclsaat.legacy.billing.repo;

import hu.mclsaat.legacy.billing.domain.InvoiceRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class InvoiceRepository {

    private static final String COLUMNS = """
            invoice_no, ba_no, subscription_ref, period_start, period_end,
            net_amount, vat_rate, vat_amount, gross_amount, currency, status,
            payment_ref, request_ref, batch_id, issued_ts, updated_ts
            """;

    private static final RowMapper<InvoiceRow> MAPPER = (rs, rowNum) -> new InvoiceRow(
            rs.getString("invoice_no"),
            rs.getString("ba_no"),
            rs.getString("subscription_ref"),
            rs.getDate("period_start").toLocalDate(),
            rs.getDate("period_end").toLocalDate(),
            rs.getBigDecimal("net_amount"),
            rs.getBigDecimal("vat_rate"),
            rs.getBigDecimal("vat_amount"),
            rs.getBigDecimal("gross_amount"),
            rs.getString("currency").trim(),
            rs.getString("status"),
            rs.getString("payment_ref"),
            rs.getString("request_ref"),
            rs.getString("batch_id"),
            rs.getTimestamp("issued_ts").toInstant(),
            rs.getTimestamp("updated_ts").toInstant());

    private final JdbcTemplate jdbc;

    public InvoiceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<InvoiceRow> findByInvoiceNo(String invoiceNo) {
        return jdbc.query("SELECT " + COLUMNS + " FROM billing.invoice WHERE invoice_no = ?",
                MAPPER, invoiceNo).stream().findFirst();
    }

    public Optional<InvoiceRow> findByRequestRef(String requestRef) {
        return jdbc.query("SELECT " + COLUMNS + " FROM billing.invoice WHERE request_ref = ?",
                MAPPER, requestRef).stream().findFirst();
    }

    public Optional<InvoiceRow> findByPaymentRef(String paymentRef) {
        return jdbc.query("SELECT " + COLUMNS + " FROM billing.invoice WHERE payment_ref = ?",
                MAPPER, paymentRef).stream().findFirst();
    }

    public List<InvoiceRow> search(String baNo, String status, String subscriptionRef) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM billing.invoice WHERE ba_no = ?");
        List<Object> args = new ArrayList<>();
        args.add(baNo);
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (subscriptionRef != null) {
            sql.append(" AND subscription_ref = ?");
            args.add(subscriptionRef);
        }
        sql.append(" ORDER BY invoice_no");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public List<InvoiceRow> findByBatchId(String batchId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM billing.invoice WHERE batch_id = ? ORDER BY invoice_no",
                MAPPER, batchId);
    }

    /** Invoices Stripe has been paid for but that no settlement batch has claimed yet. */
    public List<InvoiceRow> findUnbatchedSettlementPending() {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM billing.invoice
                 WHERE status = 'SETTLEMENT_PENDING' AND batch_id IS NULL
                 ORDER BY invoice_no
                """, MAPPER);
    }

    public InvoiceRow insert(String baNo, String subscriptionRef, LocalDate periodStart, LocalDate periodEnd,
                             BigDecimal netAmount, BigDecimal vatRate, BigDecimal vatAmount,
                             BigDecimal grossAmount, String currency, String requestRef) {
        long next = jdbc.queryForObject("SELECT nextval('billing.invoice_seq')", Long.class);
        String invoiceNo = "%d/INV/%06d".formatted(LocalDate.now().getYear(), next);
        jdbc.update("""
                INSERT INTO billing.invoice
                    (invoice_no, ba_no, subscription_ref, period_start, period_end,
                     net_amount, vat_rate, vat_amount, gross_amount, currency, status, request_ref)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)
                """, invoiceNo, baNo, subscriptionRef, Date.valueOf(periodStart), Date.valueOf(periodEnd),
                netAmount, vatRate, vatAmount, grossAmount, currency, requestRef);
        return findByInvoiceNo(invoiceNo).orElseThrow();
    }

    public int markPaymentStarted(String invoiceNo, String paymentRef) {
        return jdbc.update("""
                UPDATE billing.invoice
                   SET payment_ref = ?, updated_ts = now()
                 WHERE invoice_no = ?
                """, paymentRef, invoiceNo);
    }

    /**
     * {@code OPEN} -> {@code SETTLEMENT_PENDING}: Stripe confirmed the card payment, but the money
     * is not posted until the clearing house acknowledges the settlement batch.
     */
    public int markSettlementPending(String invoiceNo, String paymentRef) {
        return jdbc.update("""
                UPDATE billing.invoice
                   SET status = 'SETTLEMENT_PENDING', payment_ref = COALESCE(?, payment_ref), updated_ts = now()
                 WHERE invoice_no = ? AND status = 'OPEN'
                """, paymentRef, invoiceNo);
    }

    public int assignToBatch(String invoiceNo, String batchId) {
        return jdbc.update("""
                UPDATE billing.invoice
                   SET batch_id = ?, updated_ts = now()
                 WHERE invoice_no = ? AND batch_id IS NULL
                """, batchId, invoiceNo);
    }

    /** {@code SETTLEMENT_PENDING} -> {@code PAID}, once the batch acknowledgement lands. */
    public int markPaidByBatch(String batchId) {
        return jdbc.update("""
                UPDATE billing.invoice
                   SET status = 'PAID', updated_ts = now()
                 WHERE batch_id = ? AND status = 'SETTLEMENT_PENDING'
                """, batchId);
    }

    /** Test/demo support: back-dates a row so the stale detectors can see it. */
    public int backdateUpdatedTs(String invoiceNo, int minutes) {
        return jdbc.update("""
                UPDATE billing.invoice SET updated_ts = ? WHERE invoice_no = ?
                """, Timestamp.from(java.time.Instant.now().minusSeconds(60L * minutes)), invoiceNo);
    }
}

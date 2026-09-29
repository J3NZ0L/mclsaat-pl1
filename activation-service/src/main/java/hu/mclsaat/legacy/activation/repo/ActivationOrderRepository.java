package hu.mclsaat.legacy.activation.repo;

import hu.mclsaat.legacy.activation.domain.ActivationOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class ActivationOrderRepository {

    private static final String COLUMNS = """
            order_no, change_type, customer_ref, offer_id, msisdn, requested_start_date,
            data_allowance_gb, monthly_fee_huf, target_subscription_ref, subscription_ref,
            status, process_instance_id, sim_iccid, invoice_ref, failure_reason,
            simulate_stuck, created_ts, updated_ts
            """;

    private static final RowMapper<ActivationOrder> MAPPER = (rs, rowNum) -> new ActivationOrder(
            rs.getString("order_no"),
            rs.getString("change_type"),
            rs.getInt("customer_ref"),
            rs.getString("offer_id"),
            rs.getString("msisdn"),
            rs.getString("requested_start_date").trim(),
            rs.getBigDecimal("data_allowance_gb"),
            rs.getBigDecimal("monthly_fee_huf"),
            rs.getString("target_subscription_ref"),
            rs.getString("subscription_ref"),
            rs.getString("status"),
            rs.getString("process_instance_id"),
            rs.getString("sim_iccid"),
            rs.getString("invoice_ref"),
            rs.getString("failure_reason"),
            rs.getBoolean("simulate_stuck"),
            rs.getTimestamp("created_ts").toInstant(),
            rs.getTimestamp("updated_ts").toInstant());

    private final JdbcTemplate jdbc;

    public ActivationOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ActivationOrder> findByOrderNo(String orderNo) {
        return jdbc.query("SELECT " + COLUMNS + " FROM activation.activation_order WHERE order_no = ?",
                MAPPER, orderNo).stream().findFirst();
    }

    public List<ActivationOrder> findByCustomerRef(int customerRef) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM activation.activation_order
                 WHERE customer_ref = ?
                 ORDER BY created_ts DESC
                """, MAPPER, customerRef);
    }

    /**
     * Orders whose provisioning callback never arrived. This is the activation-side half of
     * failure branch A; the catalog's half is a subscription left in {@code PA}. Neither side can
     * see the other's, which is why the ops console has to join them by hand.
     */
    public List<ActivationOrder> findStuck(int olderThanMinutes) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM activation.activation_order
                 WHERE status IN ('STUCK', 'AWAITING_PROVISIONING')
                   AND updated_ts < now() - make_interval(mins => ?)
                 ORDER BY updated_ts
                """, MAPPER, olderThanMinutes);
    }

    /** Allocates the next {@code ORD/<year>/<7 digits>} and inserts the order in {@code RECEIVED}. */
    public ActivationOrder insertReceived(String changeType, int customerRef, String offerId,
                                          String msisdn, String requestedStartDate,
                                          String targetSubscriptionRef, boolean simulateStuck) {
        long next = jdbc.queryForObject("SELECT nextval('activation.order_seq')", Long.class);
        String orderNo = "ORD/%d/%07d".formatted(LocalDate.now().getYear(), next);
        jdbc.update("""
                INSERT INTO activation.activation_order
                    (order_no, change_type, customer_ref, offer_id, msisdn, requested_start_date,
                     target_subscription_ref, status, simulate_stuck)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'RECEIVED', ?)
                """, orderNo, changeType, customerRef, offerId, msisdn, requestedStartDate,
                targetSubscriptionRef, simulateStuck);
        return findByOrderNo(orderNo).orElseThrow();
    }

    public int updateProcessInstanceId(String orderNo, String processInstanceId) {
        return jdbc.update("""
                UPDATE activation.activation_order
                   SET process_instance_id = ?, updated_ts = now()
                 WHERE order_no = ?
                """, processInstanceId, orderNo);
    }

    public int updateStatus(String orderNo, String status) {
        return jdbc.update("""
                UPDATE activation.activation_order
                   SET status = ?, updated_ts = now()
                 WHERE order_no = ?
                """, status, orderNo);
    }

    /** Records what validation resolved from the catalog, in activation's own units. */
    public int updateValidated(String orderNo, BigDecimal dataAllowanceGb, BigDecimal monthlyFeeHuf) {
        return jdbc.update("""
                UPDATE activation.activation_order
                   SET status = 'VALIDATED', data_allowance_gb = ?, monthly_fee_huf = ?, updated_ts = now()
                 WHERE order_no = ?
                """, dataAllowanceGb, monthlyFeeHuf, orderNo);
    }

    public int updateSubscriptionRef(String orderNo, String subscriptionRef) {
        return jdbc.update("""
                UPDATE activation.activation_order
                   SET subscription_ref = ?, updated_ts = now()
                 WHERE order_no = ?
                """, subscriptionRef, orderNo);
    }

    public int updateSimIccid(String orderNo, String simIccid) {
        return jdbc.update("""
                UPDATE activation.activation_order
                   SET sim_iccid = ?, updated_ts = now()
                 WHERE order_no = ?
                """, simIccid, orderNo);
    }

    public int updateInvoiceRef(String orderNo, String invoiceRef) {
        return jdbc.update("""
                UPDATE activation.activation_order
                   SET invoice_ref = ?, updated_ts = now()
                 WHERE order_no = ?
                """, invoiceRef, orderNo);
    }

    public int updateFailure(String orderNo, String status, String failureReason) {
        return jdbc.update("""
                UPDATE activation.activation_order
                   SET status = ?, failure_reason = ?, updated_ts = now()
                 WHERE order_no = ?
                """, status, truncate(failureReason), orderNo);
    }

    /** Test/demo support: makes a fresh order look old enough for the stale detectors. */
    public int backdateUpdatedTs(String orderNo, int minutes) {
        return jdbc.update("""
                UPDATE activation.activation_order
                   SET updated_ts = now() - make_interval(mins => ?)
                 WHERE order_no = ?
                """, minutes, orderNo);
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 400 ? reason : reason.substring(0, 397) + "...";
    }
}

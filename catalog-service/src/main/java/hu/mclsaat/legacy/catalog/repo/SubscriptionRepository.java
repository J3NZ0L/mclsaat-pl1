package hu.mclsaat.legacy.catalog.repo;

import hu.mclsaat.legacy.catalog.domain.SubscriptionRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class SubscriptionRepository {

    private static final String COLUMNS = """
            sub_id, cust_no, plan_code, status_code, activated_on, msisdn, sim_iccid,
            data_allowance_mb, parent_sub_id, created_ts, updated_ts
            """;

    private static final RowMapper<SubscriptionRow> MAPPER = (rs, rowNum) -> {
        Date activatedOn = rs.getDate("activated_on");
        return new SubscriptionRow(
                rs.getString("sub_id"),
                rs.getString("cust_no").trim(),
                rs.getString("plan_code"),
                rs.getString("status_code"),
                activatedOn == null ? null : activatedOn.toLocalDate(),
                rs.getString("msisdn"),
                rs.getString("sim_iccid"),
                rs.getInt("data_allowance_mb"),
                rs.getString("parent_sub_id"),
                rs.getTimestamp("created_ts").toInstant(),
                rs.getTimestamp("updated_ts").toInstant());
    };

    private final JdbcTemplate jdbc;

    public SubscriptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SubscriptionRow> findById(String subId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM catalog.subscription WHERE sub_id = ?",
                MAPPER, subId).stream().findFirst();
    }

    public List<SubscriptionRow> findByCustNo(String custNo) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM catalog.subscription
                 WHERE cust_no = ?
                 ORDER BY created_ts, sub_id
                """, MAPPER, custNo);
    }

    public List<SubscriptionRow> findActiveAddonsOf(String parentSubId) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM catalog.subscription
                 WHERE parent_sub_id = ? AND status_code = 'AC'
                 ORDER BY created_ts
                """, MAPPER, parentSubId);
    }

    /**
     * Rows that have been waiting for activation longer than {@code olderThanMinutes}.
     *
     * <p>This is the catalog-side half of failure branch A. The activation subsystem knows its
     * process instance is stuck; the catalog only knows it has a subscription that never became
     * active. Neither side can see the whole picture, which is why the ops console has to join
     * them by hand.
     */
    public List<SubscriptionRow> findStalePendingActivation(int olderThanMinutes) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM catalog.subscription
                 WHERE status_code = 'PA'
                   AND updated_ts < now() - make_interval(mins => ?)
                 ORDER BY updated_ts
                """, MAPPER, olderThanMinutes);
    }

    /**
     * Allocates the next subscription id ({@code SUB-<year>-<6 digits>}) and inserts the row
     * in {@code PA} (pending activation).
     */
    public SubscriptionRow insertPendingActivation(String custNo, String planCode, String msisdn,
                                                  int dataAllowanceMb, String parentSubId) {
        long next = jdbc.queryForObject("SELECT nextval('catalog.subscription_seq')", Long.class);
        String subId = "SUB-%d-%06d".formatted(LocalDate.now().getYear(), next);
        jdbc.update("""
                INSERT INTO catalog.subscription
                    (sub_id, cust_no, plan_code, status_code, msisdn, data_allowance_mb, parent_sub_id)
                VALUES (?, ?, ?, 'PA', ?, ?, ?)
                """, subId, custNo, planCode, msisdn, dataAllowanceMb, parentSubId);
        return findById(subId).orElseThrow();
    }

    public int markActive(String subId, String simIccid, LocalDate activatedOn) {
        return jdbc.update("""
                UPDATE catalog.subscription
                   SET status_code = 'AC',
                       sim_iccid = COALESCE(?, sim_iccid),
                       activated_on = ?,
                       updated_ts = now()
                 WHERE sub_id = ? AND status_code IN ('PA', 'NW')
                """, simIccid, Date.valueOf(activatedOn), subId);
    }

    public int updateStatus(String subId, String statusCode) {
        return jdbc.update("""
                UPDATE catalog.subscription
                   SET status_code = ?, updated_ts = now()
                 WHERE sub_id = ?
                """, statusCode, subId);
    }

    public int updatePlan(String subId, String planCode, int dataAllowanceMb) {
        return jdbc.update("""
                UPDATE catalog.subscription
                   SET plan_code = ?, data_allowance_mb = ?, updated_ts = now()
                 WHERE sub_id = ?
                """, planCode, dataAllowanceMb, subId);
    }

    public int updateEffectiveAllowance(String subId, int dataAllowanceMb) {
        return jdbc.update("""
                UPDATE catalog.subscription
                   SET data_allowance_mb = ?, updated_ts = now()
                 WHERE sub_id = ?
                """, dataAllowanceMb, subId);
    }

    /** Test/demo support: back-dates a row so the stale-pending detector can see it. */
    public int backdateUpdatedTs(String subId, int minutes) {
        return jdbc.update("""
                UPDATE catalog.subscription
                   SET updated_ts = now() - make_interval(mins => ?)
                 WHERE sub_id = ?
                """, minutes, subId);
    }
}

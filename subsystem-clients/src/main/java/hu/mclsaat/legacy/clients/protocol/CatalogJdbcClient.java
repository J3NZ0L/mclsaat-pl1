package hu.mclsaat.legacy.clients.protocol;

import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CanonicalPlan;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CanonicalSubscriber;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CanonicalSubscription;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CustomerRef;
import hu.mclsaat.legacy.clients.canonical.Money;
import hu.mclsaat.legacy.clients.mapping.SemanticMappers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Date;
import java.util.List;
import java.util.Optional;

/**
 * Subsystem 1 over **direct JDBC**, with no API in between.
 *
 * <p>This is the fourth interface style in the landscape and the least defensible one: the ops console
 * reaches into another subsystem's schema and runs SQL against its tables. It is also completely
 * realistic — it is what every back-office reporting tool in every enterprise does — and it is here on
 * purpose, because the heterogeneity minimum in {@code INITIAL_DESIGN.md} names direct database access
 * as one of the interaction styles.
 *
 * <p>The consequence is that {@code catalog.plan}'s column names and units are part of this module's
 * compile-time contract. If the catalog renames {@code data_allowance_mb}, this breaks — and nothing
 * in the catalog's build will notice. That coupling is the point.
 *
 * <p>Reads only. Nothing in the ops story needs to write to the catalog behind its back, and doing so
 * would skip the business rules in {@code SubscriptionRegistry} (effective allowance recomputation,
 * idempotent activation). Remediation goes through activation's REST API instead.
 */
public class CatalogJdbcClient {

    private static final String PLAN_COLUMNS = """
            plan_code, service_kind, display_name, plan_kind, addon_category,
            monthly_fee_minor, data_allowance_mb, speed_kbps, active_flag
            """;

    private static final String SUBSCRIPTION_COLUMNS = """
            sub_id, cust_no, plan_code, status_code, activated_on, msisdn, sim_iccid,
            data_allowance_mb, parent_sub_id, updated_ts
            """;

    /** Translates the catalog's row straight into the canonical model, units and all. */
    private static final RowMapper<CanonicalPlan> PLAN_MAPPER = (rs, rowNum) -> new CanonicalPlan(
            rs.getString("plan_code"),
            SemanticMappers.toOfferId(rs.getString("plan_code")),
            SemanticMappers.fromCatalogServiceKind(rs.getString("service_kind")),
            rs.getString("display_name"),
            SemanticMappers.fromCatalogPlanKind(rs.getString("plan_kind")),
            rs.getString("addon_category"),
            // an integer count of fillér, gross
            Money.ofMinorUnits(rs.getLong("monthly_fee_minor"), "HUF"),
            SemanticMappers.fromCatalogAllowanceMb(rs.getInt("data_allowance_mb")),
            (Integer) rs.getObject("speed_kbps"),
            SemanticMappers.fromCatalogFlag(rs.getString("active_flag")));

    private static final RowMapper<CanonicalSubscription> SUBSCRIPTION_MAPPER = (rs, rowNum) -> {
        Date activatedOn = rs.getDate("activated_on");
        return SemanticMappers.fromCatalogSubscription(
                rs.getString("sub_id"),
                rs.getString("cust_no"),
                rs.getString("plan_code"),
                rs.getString("status_code"),
                activatedOn == null ? null : activatedOn.toLocalDate(),
                rs.getString("msisdn"),
                rs.getString("sim_iccid"),
                rs.getInt("data_allowance_mb"),
                rs.getString("parent_sub_id"),
                rs.getTimestamp("updated_ts").toInstant());
    };

    private static final RowMapper<CanonicalSubscriber> SUBSCRIBER_MAPPER =
            (rs, rowNum) -> new CanonicalSubscriber(
                    CustomerRef.of(SemanticMappers.fromCatalogCustNo(rs.getString("cust_no"))),
                    rs.getString("full_name"),
                    rs.getString("email"),
                    hu.mclsaat.legacy.clients.canonical.CanonicalModel.PhoneNumber
                            .of(rs.getString("msisdn")));

    private final JdbcTemplate jdbc;

    public CatalogJdbcClient(JdbcTemplate catalogJdbcTemplate) {
        this.jdbc = catalogJdbcTemplate;
    }

    public List<CanonicalPlan> listPlans(boolean includeWithdrawn) {
        String sql = "SELECT " + PLAN_COLUMNS + " FROM catalog.plan"
                + (includeWithdrawn ? "" : " WHERE active_flag = 'Y'")
                + " ORDER BY plan_kind, service_kind, monthly_fee_minor";
        return jdbc.query(sql, PLAN_MAPPER);
    }

    public Optional<CanonicalPlan> findPlan(String productCode) {
        return jdbc.query("SELECT " + PLAN_COLUMNS + " FROM catalog.plan WHERE plan_code = ?",
                PLAN_MAPPER, productCode).stream().findFirst();
    }

    public Optional<CanonicalSubscription> findSubscription(String subscriptionId) {
        return jdbc.query("SELECT " + SUBSCRIPTION_COLUMNS
                        + " FROM catalog.subscription WHERE sub_id = ?",
                SUBSCRIPTION_MAPPER, subscriptionId).stream().findFirst();
    }

    public List<CanonicalSubscription> subscriptionsOf(int customerReference) {
        return jdbc.query("SELECT " + SUBSCRIPTION_COLUMNS + """
                 FROM catalog.subscription
                 WHERE cust_no = ?
                 ORDER BY created_ts, sub_id
                """, SUBSCRIPTION_MAPPER, SemanticMappers.toCatalogCustNo(customerReference));
    }

    public Optional<CanonicalSubscriber> findSubscriber(int customerReference) {
        return jdbc.query("""
                SELECT cust_no, full_name, email, msisdn FROM catalog.subscriber WHERE cust_no = ?
                """, SUBSCRIBER_MAPPER, SemanticMappers.toCatalogCustNo(customerReference))
                .stream().findFirst();
    }

    /**
     * Subscriptions that have been waiting to go live longer than they should.
     *
     * <p>The catalog half of failure branch A. On its own this list cannot distinguish a subscription
     * whose activation is merely slow from one whose process died — the catalog has no word for
     * "stuck". That is what the join in {@code LandscapeDiagnostics} is for.
     */
    public List<CanonicalSubscription> stalePendingActivation(int olderThanMinutes) {
        return jdbc.query("SELECT " + SUBSCRIPTION_COLUMNS + """
                 FROM catalog.subscription
                 WHERE status_code = 'PA'
                   AND updated_ts < now() - make_interval(mins => ?)
                 ORDER BY updated_ts
                """, SUBSCRIPTION_MAPPER, olderThanMinutes);
    }

    /** Proof that the connection works, for the ops console's health reporting. */
    public boolean reachable() {
        try {
            Integer one = jdbc.queryForObject("SELECT 1 FROM catalog.plan LIMIT 1", Integer.class);
            return one != null;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}

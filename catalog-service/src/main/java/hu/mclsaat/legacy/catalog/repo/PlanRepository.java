package hu.mclsaat.legacy.catalog.repo;

import hu.mclsaat.legacy.catalog.domain.PlanRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Hand-written SQL over {@code catalog.plan}. There is no ORM in this subsystem on purpose:
 * "direct database access" is one of the four API styles the legacy landscape has to offer,
 * and the ops console reads these very tables over plain JDBC.
 */
@Repository
public class PlanRepository {

    private static final String COLUMNS = """
            plan_code, service_kind, display_name, plan_kind, addon_category,
            monthly_fee_minor, data_allowance_mb, speed_kbps, active_flag
            """;

    static final RowMapper<PlanRow> MAPPER = (rs, rowNum) -> new PlanRow(
            rs.getString("plan_code"),
            rs.getString("service_kind").charAt(0),
            rs.getString("display_name"),
            rs.getString("plan_kind"),
            rs.getString("addon_category"),
            rs.getLong("monthly_fee_minor"),
            rs.getInt("data_allowance_mb"),
            (Integer) rs.getObject("speed_kbps"),
            "Y".equals(rs.getString("active_flag")));

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param serviceKind    {@code 'I'} or {@code 'M'}, or {@code null} for both
     * @param planKind       {@code "BASE"} or {@code "ADDON"}, or {@code null} for both
     * @param addonCategory  {@code "DATA_PACK"} or {@code "ROAMING"}, or {@code null} for all
     * @param includeWithdrawn when false, only rows with {@code active_flag = 'Y'} are returned
     */
    public List<PlanRow> search(Character serviceKind, String planKind, String addonCategory,
                               boolean includeWithdrawn) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM catalog.plan WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (!includeWithdrawn) {
            sql.append(" AND active_flag = 'Y'");
        }
        if (serviceKind != null) {
            sql.append(" AND service_kind = ?");
            args.add(String.valueOf(serviceKind));
        }
        if (planKind != null) {
            sql.append(" AND plan_kind = ?");
            args.add(planKind);
        }
        if (addonCategory != null) {
            sql.append(" AND addon_category = ?");
            args.add(addonCategory);
        }
        sql.append(" ORDER BY plan_kind, service_kind, monthly_fee_minor");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public Optional<PlanRow> findByCode(String planCode) {
        return jdbc.query("SELECT " + COLUMNS + " FROM catalog.plan WHERE plan_code = ?",
                MAPPER, planCode).stream().findFirst();
    }
}

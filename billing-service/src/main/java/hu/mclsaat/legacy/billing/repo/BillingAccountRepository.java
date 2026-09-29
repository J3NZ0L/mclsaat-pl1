package hu.mclsaat.legacy.billing.repo;

import hu.mclsaat.legacy.billing.domain.BillingAccountRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class BillingAccountRepository {

    private static final String COLUMNS =
            "ba_no, customer_ref, account_name, billing_email, currency, created_ts";

    private static final RowMapper<BillingAccountRow> MAPPER = (rs, rowNum) -> new BillingAccountRow(
            rs.getString("ba_no"),
            rs.getInt("customer_ref"),
            rs.getString("account_name"),
            rs.getString("billing_email"),
            rs.getString("currency").trim(),
            rs.getTimestamp("created_ts").toInstant());

    private final JdbcTemplate jdbc;

    public BillingAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BillingAccountRow> findByCustomerRef(int customerRef) {
        return jdbc.query("SELECT " + COLUMNS + " FROM billing.billing_account WHERE customer_ref = ?",
                MAPPER, customerRef).stream().findFirst();
    }

    public Optional<BillingAccountRow> findByBaNo(String baNo) {
        return jdbc.query("SELECT " + COLUMNS + " FROM billing.billing_account WHERE ba_no = ?",
                MAPPER, baNo).stream().findFirst();
    }

    /** Opens a billing account and allocates its {@code BA-nnnnn} number. */
    public BillingAccountRow insert(int customerRef, String accountName, String billingEmail,
                                    String currency) {
        long next = jdbc.queryForObject("SELECT nextval('billing.billing_account_seq')", Long.class);
        String baNo = "BA-%05d".formatted(next);
        jdbc.update("""
                INSERT INTO billing.billing_account (ba_no, customer_ref, account_name, billing_email, currency)
                VALUES (?, ?, ?, ?, ?)
                """, baNo, customerRef, accountName, billingEmail, currency);
        return findByBaNo(baNo).orElseThrow();
    }
}

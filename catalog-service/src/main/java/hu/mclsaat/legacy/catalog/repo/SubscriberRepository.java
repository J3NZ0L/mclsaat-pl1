package hu.mclsaat.legacy.catalog.repo;

import hu.mclsaat.legacy.catalog.domain.SubscriberRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class SubscriberRepository {

    private static final String COLUMNS = "cust_no, full_name, email, msisdn, created_ts";

    private static final RowMapper<SubscriberRow> MAPPER = (rs, rowNum) -> new SubscriberRow(
            rs.getString("cust_no").trim(),
            rs.getString("full_name"),
            rs.getString("email"),
            rs.getString("msisdn"),
            rs.getTimestamp("created_ts").toInstant());

    private final JdbcTemplate jdbc;

    public SubscriberRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SubscriberRow> findByCustNo(String custNo) {
        return jdbc.query("SELECT " + COLUMNS + " FROM catalog.subscriber WHERE cust_no = ?",
                MAPPER, custNo).stream().findFirst();
    }

    /**
     * Allocates the next zero-padded eight-digit customer number and inserts the row.
     */
    public SubscriberRow insert(String fullName, String email, String msisdn) {
        long next = jdbc.queryForObject("SELECT nextval('catalog.subscriber_seq')", Long.class);
        String custNo = "%08d".formatted(next);
        jdbc.update("""
                INSERT INTO catalog.subscriber (cust_no, full_name, email, msisdn)
                VALUES (?, ?, ?, ?)
                """, custNo, fullName, email, msisdn);
        return findByCustNo(custNo).orElseThrow();
    }
}

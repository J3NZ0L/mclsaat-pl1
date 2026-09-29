package hu.mclsaat.legacy.ops;

import com.zaxxer.hikari.HikariDataSource;
import hu.mclsaat.legacy.clients.LandscapeDiagnostics;
import hu.mclsaat.legacy.clients.protocol.ActivationRestClient;
import hu.mclsaat.legacy.clients.protocol.BatchFileClient;
import hu.mclsaat.legacy.clients.protocol.BillingSoapClient;
import hu.mclsaat.legacy.clients.protocol.CatalogJdbcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;

/**
 * Wires the four protocol clients.
 *
 * <p>{@code subsystem-clients} contains no Spring annotations on purpose: it is a library, and a
 * library that component-scans itself into whatever context happens to import it is a nuisance. The
 * beans are declared here, where the configuration actually lives.
 *
 * <p>Reading the whole configuration at once makes the shape of the landscape obvious: four
 * dependencies, four different ways of reaching them, and one of those is a direct database
 * connection to a schema this service does not own.
 */
@Configuration
public class OpsClientConfiguration {

    /**
     * A second connection pool, straight into the catalog's database.
     *
     * <p>There is no API in this path. The ops console holds credentials for another subsystem's
     * schema and runs SQL against its tables, which is the fourth interface style the heterogeneity
     * minimum asks for and the one that would get flagged in a real architecture review. It is small
     * (two connections) and read-only by convention, because anything that wrote here would bypass
     * the catalog's own business rules.
     */
    @Bean
    public HikariDataSource catalogDataSource(
            @Value("${ops.catalog.db-url}") String url,
            @Value("${ops.catalog.db-user}") String username,
            @Value("${ops.catalog.db-password}") String password) {

        HikariDataSource dataSource = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(url)
                .username(username)
                .password(password)
                .build();
        dataSource.setMaximumPoolSize(2);
        dataSource.setPoolName("catalog-direct-jdbc");
        dataSource.setReadOnly(true);
        return dataSource;
    }

    @Bean
    public CatalogJdbcClient catalogJdbcClient(HikariDataSource catalogDataSource) {
        return new CatalogJdbcClient(new JdbcTemplate(catalogDataSource));
    }

    @Bean
    public ActivationRestClient activationRestClient(RestClient.Builder builder,
                                                    @Value("${ops.activation.base-url}") String baseUrl) {
        return new ActivationRestClient(builder, baseUrl);
    }

    @Bean
    public BillingSoapClient billingSoapClient(@Value("${ops.billing.soap-endpoint}") String endpoint) {
        return new BillingSoapClient(endpoint);
    }

    @Bean
    public BatchFileClient batchFileClient(@Value("${ops.billing.outbox-dir}") String outboxDir) {
        return new BatchFileClient(Path.of(outboxDir));
    }

    @Bean
    public LandscapeDiagnostics landscapeDiagnostics(CatalogJdbcClient catalog,
                                                    ActivationRestClient activation,
                                                    BillingSoapClient billing,
                                                    BatchFileClient batchFiles) {
        return new LandscapeDiagnostics(catalog, activation, billing, batchFiles);
    }
}

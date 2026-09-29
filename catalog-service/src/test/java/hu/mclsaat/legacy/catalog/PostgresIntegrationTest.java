package hu.mclsaat.legacy.catalog;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for the catalog integration tests.
 *
 * <p>Tests run against a real PostgreSQL container and the real Flyway migrations, because the
 * legacy shape of the schema (CHAR padding, check constraints, sequences) is part of what is
 * being tested. An in-memory database would quietly paper over exactly those details.
 *
 * <p>One container is shared by every subclass: it is started once per JVM and never stopped,
 * which Testcontainers' JVM shutdown hook takes care of.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class PostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("catalogdb")
                    .withUsername("catalog_app")
                    .withPassword("catalog_app");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}

package hu.mclsaat.legacy.billing;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/** Shared PostgreSQL container and Spring context for the billing integration tests. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class BillingIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("billingdb")
                    .withUsername("billing_app")
                    .withPassword("billing_app");

    /**
     * The official Stripe mock, the same image docker compose runs. The payment tests therefore
     * exercise the real Stripe Java SDK against a real Stripe-shaped HTTP server rather than a
     * hand-written stub, which is the only way to find out whether swapping in a real key would
     * actually work.
     */
    static final GenericContainer<?> STRIPE_MOCK =
            new GenericContainer<>("stripe/stripe-mock:latest").withExposedPorts(12111);

    static {
        POSTGRES.start();
        STRIPE_MOCK.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("billing.stripe.api-base", () ->
                "http://" + STRIPE_MOCK.getHost() + ":" + STRIPE_MOCK.getMappedPort(12111));
        registry.add("billing.stripe.api-key", () -> "sk_test_mclsaatit123");
    }
}

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
     * The official Stripe mock, the same image the {@code official-stripe-mock} compose profile
     * runs. The payment tests therefore exercise the real Stripe Java SDK against a real
     * Stripe-shaped HTTP server rather than a hand-written stub, which is the only way to find out
     * whether swapping in a real key would actually work.
     *
     * <p>Pinned, because {@code stripe-mock} is generated from the live Stripe OpenAPI spec and
     * {@code :latest} therefore moves the API under a checkout that has not changed: it dropped
     * {@code payment_method_types}, and this class's tests started failing on a clean clone of a
     * green commit. Keep the tag in step with {@code docker-compose.yml}'s {@code stripe-mock}
     * service, and raise both deliberately. See DL-024.
     */
    static final String STRIPE_MOCK_IMAGE = "stripe/stripe-mock:v0.206.0";

    static final GenericContainer<?> STRIPE_MOCK =
            new GenericContainer<>(STRIPE_MOCK_IMAGE).withExposedPorts(12111);

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
        registry.add("billing.batch.outbox-dir", () -> EXCHANGE_ROOT.resolve("outbox").toString());
        registry.add("billing.batch.inbox-dir", () -> EXCHANGE_ROOT.resolve("inbox").toString());
        registry.add("billing.batch.archive-dir", () -> EXCHANGE_ROOT.resolve("archive").toString());
        registry.add("billing.batch.poll-interval-ms", () -> "500");
    }

    /** Real directories on a real filesystem; the file exchange is not stubbed out. */
    static final java.nio.file.Path EXCHANGE_ROOT = createExchangeRoot();

    private static java.nio.file.Path createExchangeRoot() {
        try {
            return java.nio.file.Files.createTempDirectory("billing-batch-exchange-");
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }
}

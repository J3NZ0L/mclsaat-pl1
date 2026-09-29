package hu.mclsaat.legacy.activation;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

/**
 * Base class for the activation integration tests.
 *
 * <p>A real PostgreSQL container and a real Flowable engine with its async executor running. The
 * timer and the message correlation are the things worth testing here and neither of them works
 * without a live job executor, so nothing is stubbed at the engine level.
 *
 * <p>The neighbouring subsystems are mocked at the client bean, not at the HTTP level: what this
 * class is testing is the process - its gateway, its asynchrony, its correlation and its timer -
 * and the cross-subsystem wiring is verified for real by {@code scripts/demo.sh} against all three
 * services running at once.
 */
// DEFINED_PORT rather than RANDOM_PORT: the simulated provisioning platform calls back over real
// HTTP to this service's own callback endpoint, so its configured base URL has to name a port that
// is known before the context starts.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
public abstract class ActivationIntegrationTest {

    /** A fixed, unlikely-to-clash port so the callback URL below can be written down. */
    static final int TEST_PORT = 18082;

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("flowabledb")
                    .withUsername("activation_app")
                    .withPassword("activation_app");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("server.port", () -> String.valueOf(TEST_PORT));
        registry.add("activation.provisioning.callback-base-url", () -> "http://localhost:" + TEST_PORT);
        // short enough that a stuck order can be observed inside a test
        registry.add("activation.provisioning.timeout", () -> "PT3S");
        registry.add("activation.provisioning.callback-delay", () -> "PT0.2S");
    }

    /** Waits for an effect the job executor produces on its own schedule. */
    protected static void awaitUntil(String what, BooleanSupplier condition) {
        awaitUntil(what, Duration.ofSeconds(30), condition);
    }

    protected static void awaitUntil(String what, Duration timeout, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(100);
        }
        throw new AssertionError("timed out after " + timeout + " waiting for: " + what);
    }

    protected static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}

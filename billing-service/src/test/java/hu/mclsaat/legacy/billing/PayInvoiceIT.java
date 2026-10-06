package hu.mclsaat.legacy.billing;

import com.fasterxml.jackson.databind.JsonNode;
import hu.mclsaat.legacy.billing.batch.ClearingHouseSimulator;
import hu.mclsaat.legacy.billing.repo.InvoiceRepository;
import hu.mclsaat.legacy.billing.repo.PaymentBatchRepository;
import hu.mclsaat.legacy.billing.ws.gen.CreateInvoiceRequest;
import hu.mclsaat.legacy.billing.ws.gen.CreateInvoiceResponse;
import hu.mclsaat.legacy.billing.ws.gen.PayInvoiceRequest;
import hu.mclsaat.legacy.billing.ws.gen.PayInvoiceResponse;
import hu.mclsaat.legacy.billing.ws.gen.ReconcileBatchRequest;
import hu.mclsaat.legacy.billing.ws.gen.StartPaymentRequest;
import hu.mclsaat.legacy.billing.ws.gen.StartPaymentResponse;
import hu.mclsaat.legacy.stripesim.StripeSimApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.SoapFaultClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The browserless payment path: one SOAP call, no Stripe.js and no webhook posted by the caller,
 * and the invoice still ends {@code PAID} through the real file exchange. The Stripe leg is the
 * real SDK against a real stripe-mock container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PayInvoiceIT {

    /**
     * stripe-mock is stateless: a confirm against it still reports {@code requires_payment_method},
     * so it cannot show a payment completing. The stateful stripe-sim, started in this JVM on a free
     * port, can - and it is reached through the same Stripe SDK over real HTTP.
     */
    static final ConfigurableApplicationContext STRIPE_SIM = new SpringApplicationBuilder(StripeSimApplication.class)
            .properties("server.port=0", "spring.config.name=stripe-sim-it", "spring.main.banner-mode=off",
                    // billing's JDBC is on this classpath too; the stand-in has no database
                    "spring.autoconfigure.exclude="
                            + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                            + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                            + "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration,"
                            + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration")
            .run();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        var postgres = BillingIntegrationTest.POSTGRES;
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("billing.stripe.api-base", () -> "http://localhost:"
                + ((ServletWebServerApplicationContext) STRIPE_SIM).getWebServer().getPort());
        registry.add("billing.stripe.api-key", () -> "sk_test_mclsaatit123");
        registry.add("billing.batch.outbox-dir", () -> BillingIntegrationTest.EXCHANGE_ROOT.resolve("outbox").toString());
        registry.add("billing.batch.inbox-dir", () -> BillingIntegrationTest.EXCHANGE_ROOT.resolve("inbox").toString());
        registry.add("billing.batch.archive-dir", () -> BillingIntegrationTest.EXCHANGE_ROOT.resolve("archive").toString());
        registry.add("billing.batch.poll-interval-ms", () -> "500");
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired InvoiceRepository invoices;
    @Autowired PaymentBatchRepository batches;
    @Autowired ClearingHouseSimulator clearingHouse;

    private WebServiceTemplate soap;

    @BeforeEach
    void setUp() {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath("hu.mclsaat.legacy.billing.ws.gen");
        soap = new WebServiceTemplate(marshaller);
        soap.setDefaultUri("http://localhost:" + port + "/ws");
        clearingHouse.setAckEnabled(true);
    }

    @AfterEach
    void restoreClearingHouse() {
        clearingHouse.setAckEnabled(true);
    }

    @Test
    void oneSoapCallTakesTheInvoiceToSettlementAndTheBatchFileExchangeFinishesIt() {
        String invoiceNo = issueInvoice("10228.35");

        PayInvoiceResponse response = pay(invoiceNo);

        assertThat(response.getInvoiceNo()).isEqualTo(invoiceNo);
        assertThat(response.getPaymentRef()).startsWith("pi_");
        assertThat(response.isChanged()).isTrue();
        assertThat(response.getBatchId()).isNotBlank();
        // the poller may already have run, but the invoice can never still be OPEN
        assertThat(response.getInvoiceStatus()).isIn("SETTLEMENT_PENDING", "PAID");

        awaitUntil(invoiceNo + " becomes PAID", () ->
                "PAID".equals(invoices.findByInvoiceNo(invoiceNo).orElseThrow().status()));
        assertThat(batches.findById(response.getBatchId()).orElseThrow().status()).isEqualTo("ACKED");
    }

    @Test
    void payingTwiceReportsTheCurrentStateAndChangesNothing() {
        String invoiceNo = issueInvoice("1000.00");
        PayInvoiceResponse first = pay(invoiceNo);

        PayInvoiceResponse second = pay(invoiceNo);

        assertThat(second.isChanged()).isFalse();
        assertThat(second.getPaymentRef()).isEqualTo(first.getPaymentRef());
        assertThat(second.getBatchId()).isEqualTo(first.getBatchId());
    }

    @Test
    void anIntentAlreadyStartedForTheBrowserPathIsReusedNotDuplicated() {
        String invoiceNo = issueInvoice("2000.00");
        StartPaymentRequest start = new StartPaymentRequest();
        start.setInvoiceNo(invoiceNo);
        StartPaymentResponse started = (StartPaymentResponse) soap.marshalSendAndReceive(start);

        PayInvoiceResponse response = pay(invoiceNo);

        assertThat(response.getPaymentRef()).isEqualTo(started.getPaymentRef());
        assertThat(response.isChanged()).isTrue();
    }

    @Test
    void anInvoiceTheWebhookGotToSettlementPendingIsStillExportedByAnotherCall() {
        String invoiceNo = issueInvoice("3000.00");
        StartPaymentRequest start = new StartPaymentRequest();
        start.setInvoiceNo(invoiceNo);
        StartPaymentResponse started = (StartPaymentResponse) soap.marshalSendAndReceive(start);
        rest.postForEntity("/webhook/stripe", Map.of(
                "id", "evt_" + UUID.randomUUID(),
                "type", "payment_intent.succeeded",
                "data", Map.of("object", Map.of("id", started.getPaymentRef(),
                        "metadata", Map.of("invoice_no", invoiceNo)))), JsonNode.class);
        assertThat(invoices.findByInvoiceNo(invoiceNo).orElseThrow().batchId()).isNull();

        PayInvoiceResponse response = pay(invoiceNo);

        // nothing to transition, but the stranded-at-the-gate invoice gets its batch
        assertThat(response.isChanged()).isFalse();
        assertThat(response.getBatchId()).isNotBlank();
    }

    @Test
    void withTheClearingHouseSilentThePaymentStillStrandsAtSettlementPending() {
        clearingHouse.setAckEnabled(false);
        String invoiceNo = issueInvoice("4716.54");

        PayInvoiceResponse response = pay(invoiceNo);

        assertThat(response.getInvoiceStatus()).isEqualTo("SETTLEMENT_PENDING");
        assertThat(response.getBatchId()).isNotBlank();
        assertThat(batches.findById(response.getBatchId()).orElseThrow().status()).isEqualTo("SENT");
        assertThat(response.getMessage()).contains("waiting for the clearing house");

        // and the ops remediation still works on it - which also leaves the shared database clean
        ReconcileBatchRequest reconcile = new ReconcileBatchRequest();
        reconcile.setBatchId(response.getBatchId());
        reconcile.setMode("RE_DRIVE_ACK");
        soap.marshalSendAndReceive(reconcile);
        assertThat(invoices.findByInvoiceNo(invoiceNo).orElseThrow().status()).isEqualTo("PAID");
    }

    @Test
    void anUnknownInvoiceAndABlankOneAreFaults() {
        assertThatThrownBy(() -> pay("2026/INV/999999"))
                .isInstanceOf(SoapFaultClientException.class)
                .hasMessageContaining("NOT_FOUND");
        assertThatThrownBy(() -> pay(" "))
                .isInstanceOf(SoapFaultClientException.class)
                .hasMessageContaining("BAD_REQUEST");
    }

    private String issueInvoice(String netAmount) {
        CreateInvoiceRequest request = new CreateInvoiceRequest();
        request.setCustomerRef(42);
        request.setSubscriptionRef("SUB-2026-000001");
        request.setPeriodStart("2027-01-01");
        request.setPeriodEnd("2027-01-31");
        request.setNetAmount(new BigDecimal(netAmount));
        request.setCurrency("HUF");
        request.setRequestRef("pay-invoice-it-" + UUID.randomUUID());
        return ((CreateInvoiceResponse) soap.marshalSendAndReceive(request)).getInvoice().getInvoiceNo();
    }

    private PayInvoiceResponse pay(String invoiceNo) {
        PayInvoiceRequest request = new PayInvoiceRequest();
        request.setInvoiceNo(invoiceNo);
        return (PayInvoiceResponse) soap.marshalSendAndReceive(request);
    }

    private static void awaitUntil(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}

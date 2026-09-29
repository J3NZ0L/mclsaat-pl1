package hu.mclsaat.legacy.billing;

import com.fasterxml.jackson.databind.JsonNode;
import hu.mclsaat.legacy.billing.batch.ClearingHouseSimulator;
import hu.mclsaat.legacy.billing.repo.InvoiceRepository;
import hu.mclsaat.legacy.billing.repo.PaymentBatchRepository;
import hu.mclsaat.legacy.billing.ws.gen.CreateInvoiceRequest;
import hu.mclsaat.legacy.billing.ws.gen.CreateInvoiceResponse;
import hu.mclsaat.legacy.billing.ws.gen.ExportPaymentBatchRequest;
import hu.mclsaat.legacy.billing.ws.gen.ExportPaymentBatchResponse;
import hu.mclsaat.legacy.billing.ws.gen.GetPaymentBatchRequest;
import hu.mclsaat.legacy.billing.ws.gen.GetPaymentBatchResponse;
import hu.mclsaat.legacy.billing.ws.gen.ListUnconfirmedBatchesRequest;
import hu.mclsaat.legacy.billing.ws.gen.ListUnconfirmedBatchesResponse;
import hu.mclsaat.legacy.billing.ws.gen.PaymentBatchType;
import hu.mclsaat.legacy.billing.ws.gen.ReconcileBatchRequest;
import hu.mclsaat.legacy.billing.ws.gen.ReconcileBatchResponse;
import hu.mclsaat.legacy.billing.ws.gen.StartPaymentRequest;
import hu.mclsaat.legacy.billing.ws.gen.StartPaymentResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.SoapFaultClientException;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The settlement leg, end to end, over real files on a real filesystem: SOAP cuts the batch, a
 * fixed-width file lands in the outbox, the clearing house answers with an acknowledgement file,
 * and a background poller picks it up and posts the money.
 *
 * <p>Both outcomes are exercised: the acknowledged one, and failure branch B where acknowledgement
 * is suppressed and ops has to find and repair the stranded batch.
 */
class BatchSettlementIT extends BillingIntegrationTest {

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
    void theHappyPathEndsWithTheInvoicePaidByABackgroundPoller() throws IOException {
        String invoiceNo = payInvoice(new BigDecimal("10228.35"));
        assertThat(invoices.findByInvoiceNo(invoiceNo).orElseThrow().status())
                .isEqualTo("SETTLEMENT_PENDING");

        ExportPaymentBatchResponse export = exportBatch();
        assertThat(export.getBatch()).isNotNull();
        String batchId = export.getBatch().getBatchId();
        assertThat(export.getBatch().getStatus()).isEqualTo("SENT");
        assertThat(export.getBatch().getFileName()).isEqualTo("PMT-" + batchId + ".txt");

        // the file really is on disk, in the documented fixed-width layout
        Path settlementFile = EXCHANGE_ROOT.resolve("outbox").resolve("PMT-" + batchId + ".txt");
        List<String> lines = Files.readAllLines(settlementFile, StandardCharsets.US_ASCII);
        assertThat(lines.get(0)).startsWith("HDR").hasSize(61);
        assertThat(lines).anySatisfy(line -> {
            if (line.startsWith("DTL")) {
                assertThat(line).hasSize(85);
                assertThat(line).contains(invoiceNo);
            }
        });
        assertThat(lines.get(lines.size() - 1)).startsWith("TRL").hasSize(24);

        // nothing in the request that paid the invoice is waiting for this
        awaitUntil("invoice " + invoiceNo + " becomes PAID", () ->
                "PAID".equals(invoices.findByInvoiceNo(invoiceNo).orElseThrow().status()));

        awaitUntil("batch " + batchId + " becomes ACKED", () ->
                "ACKED".equals(batches.findById(batchId).orElseThrow().status()));
        var acked = batches.findById(batchId).orElseThrow();
        assertThat(acked.ackFileName()).isEqualTo("ACK-" + batchId + ".txt");
        assertThat(acked.ackedTs()).isNotNull();
        // the acknowledgement file was archived out of the inbox
        assertThat(EXCHANGE_ROOT.resolve("archive").resolve("ACK-" + batchId + ".txt")).exists();
    }

    @Test
    void exportingWithNothingToSettleIsAPlainNoOp() {
        drainPendingSettlements();

        ExportPaymentBatchResponse export = exportBatch();

        assertThat(export.getBatch()).isNull();
        assertThat(export.getMessage()).contains("nothing to settle");
    }

    @Test
    void whenTheClearingHouseGoesQuietTheBatchStrandsAndOpsCanFindIt() {
        drainPendingSettlements();
        clearingHouse.setAckEnabled(false);

        String invoiceNo = payInvoice(new BigDecimal("4716.54"));
        String batchId = exportBatch().getBatch().getBatchId();

        // Stripe has the money; the batch left; nothing is coming back
        assertThat(batches.findById(batchId).orElseThrow().status()).isEqualTo("SENT");
        assertThat(invoices.findByInvoiceNo(invoiceNo).orElseThrow().status())
                .isEqualTo("SETTLEMENT_PENDING");
        assertThat(EXCHANGE_ROOT.resolve("inbox").resolve("ACK-" + batchId + ".txt")).doesNotExist();

        ListUnconfirmedBatchesResponse unconfirmed = listUnconfirmed(0);
        assertThat(unconfirmed.getBatch()).extracting(PaymentBatchType::getBatchId).contains(batchId);

        // ops drills into it and sees exactly which money is stuck
        GetPaymentBatchRequest detailRequest = new GetPaymentBatchRequest();
        detailRequest.setBatchId(batchId);
        GetPaymentBatchResponse detail = (GetPaymentBatchResponse) soap.marshalSendAndReceive(detailRequest);
        assertThat(detail.getBatch().getStatus()).isEqualTo("SENT");
        assertThat(detail.getInvoice()).extracting(i -> i.getInvoiceNo()).contains(invoiceNo);
        assertThat(detail.getInvoice()).allSatisfy(i ->
                assertThat(i.getStatus()).isEqualTo("SETTLEMENT_PENDING"));

        // ops rebuilds the acknowledgement from the file that was actually sent
        ReconcileBatchResponse reconcile = reconcile(batchId, "RE_DRIVE_ACK");
        assertThat(reconcile.getInvoicesSettled()).isEqualTo(1);
        assertThat(reconcile.getBatch().getStatus()).isEqualTo("ACKED");
        assertThat(reconcile.getMessage()).contains("rebuilt from PMT-" + batchId + ".txt");
        assertThat(invoices.findByInvoiceNo(invoiceNo).orElseThrow().status()).isEqualTo("PAID");

        // and it no longer shows up as unconfirmed
        assertThat(listUnconfirmed(0).getBatch()).extracting(PaymentBatchType::getBatchId)
                .doesNotContain(batchId);
    }

    @Test
    void resendGetsARealAcknowledgementOnceTheClearingHouseIsBack() {
        drainPendingSettlements();
        clearingHouse.setAckEnabled(false);
        String invoiceNo = payInvoice(new BigDecimal("2000.00"));
        String batchId = exportBatch().getBatch().getBatchId();

        // still down: a resend changes nothing, and says so
        ReconcileBatchResponse whileDown = reconcile(batchId, "RESEND");
        assertThat(whileDown.getMessage()).contains("still suppressed");
        assertThat(whileDown.getBatch().getStatus()).isEqualTo("SENT");

        clearingHouse.setAckEnabled(true);
        ReconcileBatchResponse whenBack = reconcile(batchId, "RESEND");
        assertThat(whenBack.getMessage()).contains("waiting in the inbox for the poller");

        awaitUntil("invoice " + invoiceNo + " becomes PAID after the resend", () ->
                "PAID".equals(invoices.findByInvoiceNo(invoiceNo).orElseThrow().status()));
        assertThat(batches.findById(batchId).orElseThrow().status()).isEqualTo("ACKED");
    }

    @Test
    void reconcilingAnAlreadyAcknowledgedBatchIsSafeToRepeat() {
        drainPendingSettlements();
        clearingHouse.setAckEnabled(false);
        payInvoice(new BigDecimal("1500.00"));
        String batchId = exportBatch().getBatch().getBatchId();

        assertThat(reconcile(batchId, "RE_DRIVE_ACK").getInvoicesSettled()).isEqualTo(1);
        ReconcileBatchResponse again = reconcile(batchId, "RE_DRIVE_ACK");
        assertThat(again.getInvoicesSettled()).isZero();
        assertThat(again.getMessage()).contains("already acknowledged");
    }

    @Test
    void theSeededStrandedBatchIsReportedFromAColdStart() {
        // BATCH-20260925-001 was seeded as SENT four days ago with one SETTLEMENT_PENDING invoice
        assertThat(listUnconfirmed(60).getBatch()).extracting(PaymentBatchType::getBatchId)
                .contains("BATCH-20260925-001");
    }

    @Test
    void aBatchWithNoSettlementFileOnDiskCannotBeReconciled() {
        // the seeded batch names a file that was never written, since seeding only touched the DB
        assertThatThrownBy(() -> reconcile("BATCH-20260925-001", "RE_DRIVE_ACK"))
                .isInstanceOf(SoapFaultClientException.class)
                .hasMessageContaining("ILLEGAL_STATE");
    }

    @Test
    void anUnknownBatchAndAnUnknownModeAreFaults() {
        assertThatThrownBy(() -> reconcile("BATCH-NOPE-999", "RE_DRIVE_ACK"))
                .isInstanceOf(SoapFaultClientException.class)
                .hasMessageContaining("NOT_FOUND");

        drainPendingSettlements();
        clearingHouse.setAckEnabled(false);
        payInvoice(new BigDecimal("900.00"));
        String batchId = exportBatch().getBatch().getBatchId();
        assertThatThrownBy(() -> reconcile(batchId, "PLEASE_JUST_FIX_IT"))
                .isInstanceOf(SoapFaultClientException.class)
                .hasMessageContaining("BAD_REQUEST");
    }

    @Test
    void theClearingHouseSimulatorCanBeSteeredOverItsOwnRestEndpoint() {
        var before = rest.getForObject("/sim/clearing-house/config", JsonNode.class);
        assertThat(before.get("ackEnabled").asBoolean()).isTrue();

        var response = rest.postForObject("/sim/clearing-house/config",
                Map.of("ackEnabled", false), JsonNode.class);
        assertThat(response.get("ackEnabled").asBoolean()).isFalse();
        assertThat(response.get("previousAckEnabled").asBoolean()).isTrue();
        assertThat(clearingHouse.isAckEnabled()).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    /** Issues an invoice, starts its payment and reports the Stripe success back over the webhook. */
    private String payInvoice(BigDecimal netAmount) {
        CreateInvoiceRequest create = new CreateInvoiceRequest();
        create.setCustomerRef(42);
        create.setSubscriptionRef("SUB-2026-000001");
        create.setPeriodStart("2026-12-01");
        create.setPeriodEnd("2026-12-31");
        create.setNetAmount(netAmount);
        create.setCurrency("HUF");
        create.setRequestRef("batch-it-" + UUID.randomUUID());
        String invoiceNo = ((CreateInvoiceResponse) soap.marshalSendAndReceive(create))
                .getInvoice().getInvoiceNo();

        StartPaymentRequest start = new StartPaymentRequest();
        start.setInvoiceNo(invoiceNo);
        StartPaymentResponse payment = (StartPaymentResponse) soap.marshalSendAndReceive(start);

        rest.postForEntity("/webhook/stripe", Map.of(
                "id", "evt_" + UUID.randomUUID(),
                "type", "payment_intent.succeeded",
                "data", Map.of("object", Map.of(
                        "id", payment.getPaymentRef(),
                        "object", "payment_intent",
                        "metadata", Map.of("invoice_no", invoiceNo)))), JsonNode.class);
        return invoiceNo;
    }

    /**
     * Clears whatever other tests (and the seed data) left in SETTLEMENT_PENDING, so a test that
     * asserts on the contents of "the next batch" gets a batch it owns.
     */
    private void drainPendingSettlements() {
        if (invoices.findUnbatchedSettlementPending().isEmpty()) {
            return;
        }
        boolean wasEnabled = clearingHouse.isAckEnabled();
        clearingHouse.setAckEnabled(true);
        ExportPaymentBatchResponse drained = exportBatch();
        if (drained.getBatch() != null) {
            String batchId = drained.getBatch().getBatchId();
            awaitUntil("drain batch " + batchId + " to be acknowledged", () ->
                    "ACKED".equals(batches.findById(batchId).orElseThrow().status()));
        }
        clearingHouse.setAckEnabled(wasEnabled);
    }

    private ExportPaymentBatchResponse exportBatch() {
        return (ExportPaymentBatchResponse) soap.marshalSendAndReceive(new ExportPaymentBatchRequest());
    }

    private ListUnconfirmedBatchesResponse listUnconfirmed(int olderThanMinutes) {
        ListUnconfirmedBatchesRequest request = new ListUnconfirmedBatchesRequest();
        request.setOlderThanMinutes(olderThanMinutes);
        return (ListUnconfirmedBatchesResponse) soap.marshalSendAndReceive(request);
    }

    private ReconcileBatchResponse reconcile(String batchId, String mode) {
        ReconcileBatchRequest request = new ReconcileBatchRequest();
        request.setBatchId(batchId);
        request.setMode(mode);
        return (ReconcileBatchResponse) soap.marshalSendAndReceive(request);
    }

    /** Waits for an asynchronous effect, since the poller is on its own schedule. */
    private static void awaitUntil(String what, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
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
        throw new AssertionError("timed out after 20s waiting for: " + what);
    }
}

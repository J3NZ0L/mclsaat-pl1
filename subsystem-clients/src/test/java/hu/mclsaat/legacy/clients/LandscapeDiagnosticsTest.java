package hu.mclsaat.legacy.clients;

import hu.mclsaat.legacy.clients.canonical.CanonicalModel.BatchStatus;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CanonicalBatch;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CanonicalInvoice;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CanonicalOrder;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CanonicalSubscription;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CustomerRef;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.InvoiceStatus;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.OrderStatus;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.PhoneNumber;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.StuckActivation;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.SubscriptionStatus;
import hu.mclsaat.legacy.clients.canonical.DataVolume;
import hu.mclsaat.legacy.clients.canonical.Money;
import hu.mclsaat.legacy.clients.protocol.ActivationRestClient;
import hu.mclsaat.legacy.clients.protocol.BatchFileClient;
import hu.mclsaat.legacy.clients.protocol.BillingSoapClient;
import hu.mclsaat.legacy.clients.protocol.CatalogJdbcClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The cross-subsystem correlation, tested on its own.
 *
 * <p>The clients are mocked here because what is under test is the *join*: which combinations of
 * activation state and catalog state mean what, and which remedy each one admits. The clients
 * themselves are exercised against the real subsystems by {@code scripts/demo.sh}.
 */
class LandscapeDiagnosticsTest {

    private CatalogJdbcClient catalog;
    private ActivationRestClient activation;
    private BillingSoapClient billing;
    private BatchFileClient batchFiles;
    private LandscapeDiagnostics diagnostics;

    @BeforeEach
    void setUp() {
        catalog = mock(CatalogJdbcClient.class);
        activation = mock(ActivationRestClient.class);
        billing = mock(BillingSoapClient.class);
        batchFiles = mock(BatchFileClient.class);
        diagnostics = new LandscapeDiagnostics(catalog, activation, billing, batchFiles);

        when(activation.stuckOrders(anyInt())).thenReturn(List.of());
        when(catalog.stalePendingActivation(anyInt())).thenReturn(List.of());
        when(catalog.findSubscription(anyString())).thenReturn(Optional.empty());
        when(billing.unconfirmedBatches(any())).thenReturn(List.of());
    }

    // ------------------------------------------------------ failure branch A

    @Test
    void aParkedProcessWithAPendingCatalogRowIsTheRepairableCase() {
        CanonicalOrder order = order("ORD/2026/0000010", OrderStatus.STUCK, "SUB-2026-001000", true);
        CanonicalSubscription pending =
                subscription("SUB-2026-001000", SubscriptionStatus.PENDING_ACTIVATION, "PA");
        when(activation.stuckOrders(1)).thenReturn(List.of(report(order, true, true, true)));
        when(catalog.stalePendingActivation(1)).thenReturn(List.of(pending));

        List<StuckActivation> findings = diagnostics.stuckActivations(1);

        assertThat(findings).hasSize(1);
        StuckActivation finding = findings.get(0);
        assertThat(finding.category()).isEqualTo(StuckActivation.Category.STUCK_PROCESS);
        assertThat(finding.repairableByCallback()).isTrue();
        // both halves are present, which is the whole point of the join
        assertThat(finding.order()).isNotNull();
        assertThat(finding.subscription()).isNotNull();
        assertThat(finding.diagnosis())
                .contains("never confirmed provisioning")
                .contains("SUB-2026-001000")
                .contains("left in PA")
                .contains("force-provision");
    }

    @Test
    void aStuckOrderWhoseProcessIsGoneCanOnlyBeCancelled() {
        CanonicalOrder order = order("ORD/2026/0000011", OrderStatus.STUCK, "SUB-2026-001001", false);
        when(activation.stuckOrders(1)).thenReturn(List.of(report(order, false, false, false)));
        when(catalog.findSubscription("SUB-2026-001001")).thenReturn(Optional.of(
                subscription("SUB-2026-001001", SubscriptionStatus.PENDING_ACTIVATION, "PA")));

        StuckActivation finding = diagnostics.stuckActivations(1).get(0);

        assertThat(finding.category()).isEqualTo(StuckActivation.Category.ORPHANED_STUCK_ORDER);
        assertThat(finding.repairableByCallback()).isFalse();
        assertThat(finding.diagnosis())
                .contains("process instance is gone")
                .contains("can only be cancelled");
    }

    @Test
    void aStaleCatalogRowWithNoOrderBehindItIsTheOrphanedCase() {
        // this is the shape of the seeded SUB-2026-000009
        when(catalog.stalePendingActivation(1)).thenReturn(List.of(
                subscription("SUB-2026-000009", SubscriptionStatus.PENDING_ACTIVATION, "PA")));

        StuckActivation finding = diagnostics.stuckActivations(1).get(0);

        assertThat(finding.category())
                .isEqualTo(StuckActivation.Category.ORPHANED_PENDING_SUBSCRIPTION);
        assertThat(finding.orderNo()).isNull();
        assertThat(finding.order()).isNull();
        assertThat(finding.subscription()).isNotNull();
        assertThat(finding.repairableByCallback()).isFalse();
        assertThat(finding.diagnosis())
                .contains("no activation order refers to it")
                .contains("re-ordered");
    }

    @Test
    void aCatalogRowIsReportedOnceEvenWhenAnOrderAlsoClaimsIt() {
        // the join must not double-count: the row belongs to the order that names it
        CanonicalOrder order = order("ORD/2026/0000012", OrderStatus.STUCK, "SUB-2026-001002", true);
        when(activation.stuckOrders(1)).thenReturn(List.of(report(order, true, true, true)));
        when(catalog.stalePendingActivation(1)).thenReturn(List.of(
                subscription("SUB-2026-001002", SubscriptionStatus.PENDING_ACTIVATION, "PA"),
                subscription("SUB-2026-000009", SubscriptionStatus.PENDING_ACTIVATION, "PA")));

        List<StuckActivation> findings = diagnostics.stuckActivations(1);

        assertThat(findings).hasSize(2);
        assertThat(findings).filteredOn(f -> f.category() == StuckActivation.Category.STUCK_PROCESS)
                .singleElement()
                .satisfies(f -> assertThat(f.subscription().subscriptionId())
                        .isEqualTo("SUB-2026-001002"));
        assertThat(findings)
                .filteredOn(f -> f.category() == StuckActivation.Category.ORPHANED_PENDING_SUBSCRIPTION)
                .singleElement()
                .satisfies(f -> assertThat(f.subscription().subscriptionId())
                        .isEqualTo("SUB-2026-000009"));
    }

    @Test
    void anOrderWaitingWithNothingReservedYetHasNoCatalogInconsistencyToRepair() {
        CanonicalOrder order = order("ORD/2026/0000013", OrderStatus.AWAITING_PROVISIONING, null, true);
        when(activation.stuckOrders(1)).thenReturn(List.of(report(order, true, true, true)));

        StuckActivation finding = diagnostics.stuckActivations(1).get(0);

        assertThat(finding.subscription()).isNull();
        assertThat(finding.diagnosis())
                .contains("still AWAITING_PROVISIONING")
                .contains("Nothing was reserved in the catalog");
    }

    @Test
    void anOrderWhoseCatalogRowHasSinceGoneLiveOnlyNeedsClosing() {
        CanonicalOrder order = order("ORD/2026/0000014", OrderStatus.STUCK, "SUB-2026-001003", true);
        when(activation.stuckOrders(1)).thenReturn(List.of(report(order, true, true, true)));
        when(catalog.findSubscription("SUB-2026-001003")).thenReturn(Optional.of(
                subscription("SUB-2026-001003", SubscriptionStatus.ACTIVE, "AC")));

        StuckActivation finding = diagnostics.stuckActivations(1).get(0);

        assertThat(finding.diagnosis())
                .contains("already ACTIVE")
                .contains("only the order needs closing");
    }

    // ------------------------------------------------------ failure branch B

    @Test
    void anUnconfirmedBatchWithItsFileOnDiskCanBeReDriven() {
        CanonicalBatch batch = batch("BATCH-20260929-001", "PMT-BATCH-20260929-001.txt", "6990.00", 5L);
        CanonicalInvoice invoice = invoice("2026/INV/000004", "6990.00");
        when(billing.unconfirmedBatches(0)).thenReturn(List.of(batch));
        when(billing.paymentBatch("BATCH-20260929-001"))
                .thenReturn(new BillingSoapClient.BatchDetail(batch, List.of(invoice)));
        when(batchFiles.readSettlementFile("PMT-BATCH-20260929-001.txt")).thenReturn(Optional.of(
                List.of("HDRBATCH-20260929-001  ...", "DTL2026/INV/000004 ...", "TRL000001...")));

        var findings = diagnostics.unconfirmedSettlements(0);

        assertThat(findings).hasSize(1);
        var finding = findings.get(0);
        assertThat(finding.strandedAmount()).isEqualTo(Money.ofMajorUnits(new BigDecimal("6990.00"), "HUF"));
        assertThat(finding.settlementFilePresent()).isTrue();
        assertThat(finding.settlementFileLines()).hasSize(3);
        assertThat(finding.diagnosis())
                .contains("has not been acknowledged")
                .contains("for 5 minute(s)")
                .contains("6990.00 HUF has already been taken at Stripe")
                .contains("RE_DRIVE_ACK can rebuild the acknowledgement");
    }

    @Test
    void anUnconfirmedBatchWithNoFileOnDiskNeedsAHuman() {
        // the shape of the seeded BATCH-20260925-001: the row exists, the file never did
        CanonicalBatch batch = batch("BATCH-20260925-001", "PMT-BATCH-20260925-001.txt", "12990.00", 5825L);
        when(billing.unconfirmedBatches(null)).thenReturn(List.of(batch));
        when(billing.paymentBatch("BATCH-20260925-001")).thenReturn(
                new BillingSoapClient.BatchDetail(batch, List.of(invoice("2026/INV/000005", "12990.00"))));
        when(batchFiles.readSettlementFile(anyString())).thenReturn(Optional.empty());

        var finding = diagnostics.unconfirmedSettlements(null).get(0);

        assertThat(finding.settlementFilePresent()).isFalse();
        assertThat(finding.settlementFileLines()).isEmpty();
        assertThat(finding.diagnosis())
                .contains("is NOT in the outbox")
                .contains("Neither RESEND nor RE_DRIVE_ACK can be trusted")
                .contains("needs a human");
    }

    @Test
    void theStrandedAmountIsTheSumOfEveryInvoiceInTheBatch() {
        CanonicalBatch batch = batch("BATCH-20260929-002", "PMT-BATCH-20260929-002.txt", "18980.01", 3L);
        when(billing.unconfirmedBatches(0)).thenReturn(List.of(batch));
        when(billing.paymentBatch("BATCH-20260929-002")).thenReturn(
                new BillingSoapClient.BatchDetail(batch, List.of(
                        invoice("2026/INV/000002", "12990.00"),
                        invoice("2026/INV/000003", "5990.01"))));
        when(batchFiles.readSettlementFile(anyString())).thenReturn(Optional.of(List.of("HDR...")));

        var finding = diagnostics.unconfirmedSettlements(0).get(0);

        // note the .01: one of the two invoices carries the VAT round-trip artefact
        assertThat(finding.strandedAmount())
                .isEqualTo(Money.ofMajorUnits(new BigDecimal("18980.01"), "HUF"));
    }

    @Test
    void anEmptyBatchStillReportsAZeroAmountRatherThanFailing() {
        CanonicalBatch batch = batch("BATCH-20260929-004", "PMT-BATCH-20260929-004.txt", "0.00", 1L);
        when(billing.unconfirmedBatches(0)).thenReturn(List.of(batch));
        when(billing.paymentBatch("BATCH-20260929-004"))
                .thenReturn(new BillingSoapClient.BatchDetail(batch, List.of()));
        when(batchFiles.readSettlementFile(anyString())).thenReturn(Optional.empty());

        var finding = diagnostics.unconfirmedSettlements(0).get(0);

        assertThat(finding.strandedAmount()).isEqualTo(Money.zero("HUF"));
    }

    // ---------------------------------------------------------- reachability

    @Test
    void reachabilityNamesTheProtocolEachSubsystemIsReachedOver() {
        when(catalog.reachable()).thenReturn(true);
        when(activation.reachable()).thenReturn(false);
        when(billing.reachable()).thenReturn(true);
        when(batchFiles.reachable()).thenReturn(false);

        assertThat(diagnostics.reachability())
                .containsEntry("catalog (direct JDBC)", true)
                .containsEntry("activation (REST)", false)
                .containsEntry("billing (SOAP)", true)
                .containsEntry("settlement outbox (files)", false);
    }

    @Test
    void nothingWrongProducesNoFindings() {
        assertThat(diagnostics.stuckActivations(1)).isEmpty();
        assertThat(diagnostics.unconfirmedSettlements(null)).isEmpty();
    }

    // -------------------------------------------------------------- fixtures

    private static CanonicalOrder order(String orderNo, OrderStatus status, String subscriptionId,
                                       boolean waiting) {
        return new CanonicalOrder(orderNo, "NEW_SUBSCRIPTION", CustomerRef.of(101),
                "MOB-VOICE-0010", status, subscriptionId, null, null,
                status == OrderStatus.STUCK ? "the network platform did not confirm provisioning" : null,
                waiting, waiting, waiting ? "provisioningCallback" : null,
                Instant.parse("2026-09-29T18:00:00Z"));
    }

    private static ActivationRestClient.StuckOrderReport report(CanonicalOrder order, boolean waiting,
                                                               boolean running, boolean repairable) {
        return new ActivationRestClient.StuckOrderReport(order, waiting, running, repairable);
    }

    private static CanonicalSubscription subscription(String subId, SubscriptionStatus status,
                                                      String catalogCode) {
        return new CanonicalSubscription(subId, CustomerRef.of(101), "MOB-VOICE-0010", status,
                catalogCode, status == SubscriptionStatus.ACTIVE ? LocalDate.of(2026, 9, 29) : null,
                PhoneNumber.of("+36707654321"), null, DataVolume.ofMegabytes(10_240), null,
                Instant.parse("2026-09-25T17:20:57Z"));
    }

    private static CanonicalBatch batch(String batchId, String fileName, String total, Long ageMinutes) {
        return new CanonicalBatch(batchId, fileName, BatchStatus.SENT, 1,
                Money.ofMajorUnits(new BigDecimal(total), "HUF"),
                Instant.parse("2026-09-29T18:00:00Z"), Instant.parse("2026-09-29T18:00:00Z"),
                null, null, ageMinutes);
    }

    private static CanonicalInvoice invoice(String invoiceNo, String gross) {
        Money grossMoney = Money.ofMajorUnits(new BigDecimal(gross), "HUF");
        return new CanonicalInvoice(invoiceNo, "BA-00043",
                CustomerRef.of(43).withBillingAccount("BA-00043"), "SUB-2026-000003",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                Money.ofMajorUnits(new BigDecimal("5503.94"), "HUF"),
                Money.ofMajorUnits(new BigDecimal("1486.06"), "HUF"),
                grossMoney, InvoiceStatus.SETTLEMENT_PENDING, "pi_test", "BATCH-X",
                Instant.parse("2026-09-09T00:00:00Z"));
    }
}

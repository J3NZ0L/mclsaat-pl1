package hu.mclsaat.legacy.ops;

import hu.mclsaat.legacy.clients.LandscapeDiagnostics;
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
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.UnconfirmedSettlement;
import hu.mclsaat.legacy.clients.canonical.DataVolume;
import hu.mclsaat.legacy.clients.canonical.Money;
import hu.mclsaat.legacy.clients.protocol.ActivationRestClient;
import hu.mclsaat.legacy.clients.protocol.BillingSoapClient;
import hu.mclsaat.legacy.ops.web.OpsDiagnosticsController;
import hu.mclsaat.legacy.ops.web.OpsExceptionHandler;
import hu.mclsaat.legacy.ops.web.OpsRemediationController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ops console's HTTP surface, with the subsystems mocked.
 *
 * <p>This checks the shapes an operator (and, in phase 2, an ops agent) actually sees: that both halves
 * of a stuck activation are exposed side by side, that the remediation advice matches the category, and
 * that the three unrelated downstream error models arrive as one. The real cross-subsystem behaviour is
 * exercised by {@code scripts/demo.sh} against all four services running.
 */
@WebMvcTest(controllers = {OpsDiagnosticsController.class, OpsRemediationController.class})
@Import(OpsExceptionHandler.class)
class OpsApiTest {

    @Autowired MockMvc mvc;

    @MockitoBean LandscapeDiagnostics diagnostics;
    @MockitoBean ActivationRestClient activation;
    @MockitoBean BillingSoapClient billing;

    // ---------------------------------------------------------- diagnostics

    @Test
    void aStuckActivationIsReportedWithBothHalvesAndTheRightRemedy() throws Exception {
        when(diagnostics.stuckActivations(anyInt())).thenReturn(List.of(new StuckActivation(
                "ORD/2026/0000010", stuckOrder(), pendingSubscription(),
                StuckActivation.Category.STUCK_PROCESS, true, "the diagnosis text")));

        mvc.perform(get("/ops/v1/diagnostics/stuck-activations?olderThanMinutes=0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderNo").value("ORD/2026/0000010"))
                .andExpect(jsonPath("$[0].category").value("STUCK_PROCESS"))
                .andExpect(jsonPath("$[0].repairableByCallback").value(true))
                .andExpect(jsonPath("$[0].diagnosis").value("the diagnosis text"))
                .andExpect(jsonPath("$[0].suggestedRemedy").value(
                        org.hamcrest.Matchers.containsString("force-provision")))
                // activation's half, over REST
                .andExpect(jsonPath("$[0].activation.status").value("STUCK"))
                .andExpect(jsonPath("$[0].activation.customerRef").value(101))
                .andExpect(jsonPath("$[0].activation.waitingForCallback").value(true))
                // the catalog's half, over direct JDBC, with its raw code kept alongside
                .andExpect(jsonPath("$[0].catalog.subscriptionId").value("SUB-2026-001000"))
                .andExpect(jsonPath("$[0].catalog.catalogStatusCode").value("PA"))
                .andExpect(jsonPath("$[0].catalog.status").value("PENDING_ACTIVATION"))
                .andExpect(jsonPath("$[0].catalog.custNo").value("00000101"))
                .andExpect(jsonPath("$[0].catalog.msisdn").value("+36707654321"))
                .andExpect(jsonPath("$[0].catalog.dataAllowance").value("10.000 GB"));
    }

    @Test
    void anOrphanedCatalogRowIsReportedWithNoActivationHalfAndACancelRemedy() throws Exception {
        when(diagnostics.stuckActivations(anyInt())).thenReturn(List.of(new StuckActivation(
                null, null, pendingSubscription(),
                StuckActivation.Category.ORPHANED_PENDING_SUBSCRIPTION, false, "orphaned")));

        mvc.perform(get("/ops/v1/diagnostics/stuck-activations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderNo").doesNotExist())
                .andExpect(jsonPath("$[0].activation").doesNotExist())
                .andExpect(jsonPath("$[0].catalog.subscriptionId").value("SUB-2026-001000"))
                .andExpect(jsonPath("$[0].repairableByCallback").value(false))
                .andExpect(jsonPath("$[0].suggestedRemedy").value(
                        org.hamcrest.Matchers.containsString("place a fresh order")));
    }

    @Test
    void anUnconfirmedBatchExposesItsSettlementFileVerbatim() throws Exception {
        when(diagnostics.unconfirmedSettlements(eq(0))).thenReturn(List.of(new UnconfirmedSettlement(
                sentBatch(), List.of(strandedInvoice()),
                Money.ofMajorUnits(new BigDecimal("6990.00"), "HUF"), true,
                List.of("HDRBATCH-20260929-001  ...", "TRL000001..."), "the diagnosis")));

        mvc.perform(get("/ops/v1/diagnostics/unconfirmed-batches?olderThanMinutes=0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].batch.batchId").value("BATCH-20260929-001"))
                .andExpect(jsonPath("$[0].batch.status").value("SENT"))
                .andExpect(jsonPath("$[0].strandedAmount").value("6990.00 HUF"))
                .andExpect(jsonPath("$[0].settlementFilePresent").value(true))
                .andExpect(jsonPath("$[0].settlementFileLines.length()").value(2))
                .andExpect(jsonPath("$[0].suggestedRemedy").value(
                        org.hamcrest.Matchers.containsString("RE_DRIVE_ACK")))
                // all three amounts, because net + VAT does not always equal gross here
                .andExpect(jsonPath("$[0].invoices[0].netAmount").value("5503.94 HUF"))
                .andExpect(jsonPath("$[0].invoices[0].vatAmount").value("1486.06 HUF"))
                .andExpect(jsonPath("$[0].invoices[0].grossAmount").value("6990.00 HUF"));
    }

    @Test
    void aBatchWithoutItsFileIsNotOfferedAnAutomaticRemedy() throws Exception {
        when(diagnostics.unconfirmedSettlements(null)).thenReturn(List.of(new UnconfirmedSettlement(
                sentBatch(), List.of(strandedInvoice()),
                Money.ofMajorUnits(new BigDecimal("6990.00"), "HUF"), false, List.of(), "no file")));

        mvc.perform(get("/ops/v1/diagnostics/unconfirmed-batches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].settlementFilePresent").value(false))
                .andExpect(jsonPath("$[0].suggestedRemedy").value(
                        org.hamcrest.Matchers.containsString("needs a human")));
    }

    @Test
    void theOverviewSumsBothBranchesAndReportsReachabilityPerProtocol() throws Exception {
        when(diagnostics.reachability()).thenReturn(Map.of(
                "catalog (direct JDBC)", true, "activation (REST)", true,
                "billing (SOAP)", true, "settlement outbox (files)", false));
        when(diagnostics.stuckActivations(anyInt())).thenReturn(List.of(new StuckActivation(
                "ORD/2026/0000010", stuckOrder(), pendingSubscription(),
                StuckActivation.Category.STUCK_PROCESS, true, "d")));
        when(diagnostics.unconfirmedSettlements(null)).thenReturn(List.of(
                new UnconfirmedSettlement(sentBatch(), List.of(strandedInvoice()),
                        Money.ofMajorUnits(new BigDecimal("6990.00"), "HUF"), true, List.of(), "d"),
                new UnconfirmedSettlement(sentBatch(), List.of(strandedInvoice()),
                        Money.ofMajorUnits(new BigDecimal("12990.00"), "HUF"), false, List.of(), "d")));

        mvc.perform(get("/ops/v1/diagnostics/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stuckActivationCount").value(1))
                .andExpect(jsonPath("$.unconfirmedSettlementCount").value(2))
                .andExpect(jsonPath("$.strandedAmount").value("19980.00 HUF"))
                .andExpect(jsonPath("$.subsystemsReachable['settlement outbox (files)']").value(false));
    }

    // ---------------------------------------------------------- remediation

    @Test
    void forceProvisionPassesTheOrderNumberAndIccidThrough() throws Exception {
        when(activation.forceProvision("ORD/2026/0000010", "89360100111222333"))
                .thenReturn(stuckOrder());

        mvc.perform(post("/ops/v1/remediation/activation/force-provision")
                        .contentType("application/json")
                        .content("{\"orderNo\":\"ORD/2026/0000010\",\"simIccid\":\"89360100111222333\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("force-provision"))
                .andExpect(jsonPath("$.target").value("ORD/2026/0000010"))
                .andExpect(jsonPath("$.succeeded").value(true))
                .andExpect(jsonPath("$.outcome").value(
                        org.hamcrest.Matchers.containsString("correlated into the waiting process")));

        verify(activation).forceProvision("ORD/2026/0000010", "89360100111222333");
    }

    @Test
    void cancellationSaysPlainlyThatItCannotBeUndone() throws Exception {
        when(activation.cancelOrder("ORD/2026/0000010", "SIM stock exhausted")).thenReturn(stuckOrder());

        mvc.perform(post("/ops/v1/remediation/activation/cancel")
                        .contentType("application/json")
                        .content("{\"orderNo\":\"ORD/2026/0000010\",\"reason\":\"SIM stock exhausted\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value(
                        org.hamcrest.Matchers.containsString("cannot be undone")));
    }

    @Test
    void reconcileDefaultsToReDriveAckAndUppercasesTheMode() throws Exception {
        when(billing.reconcileBatch("BATCH-20260929-001", "RE_DRIVE_ACK")).thenReturn(
                new BillingSoapClient.Reconciliation(sentBatch(), 1, "rebuilt and applied"));

        mvc.perform(post("/ops/v1/remediation/billing/reconcile")
                        .contentType("application/json")
                        .content("{\"batchId\":\"BATCH-20260929-001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("reconcile:RE_DRIVE_ACK"))
                .andExpect(jsonPath("$.detail.invoicesSettled").value(1));

        mvc.perform(post("/ops/v1/remediation/billing/reconcile")
                        .contentType("application/json")
                        .content("{\"batchId\":\"BATCH-20260929-001\",\"mode\":\"re_drive_ack\"}"))
                .andExpect(status().isOk());

        verify(billing, org.mockito.Mockito.times(2))
                .reconcileBatch("BATCH-20260929-001", "RE_DRIVE_ACK");
    }

    @Test
    void aMissingOrderNumberIsRejectedBeforeAnythingIsCalled() throws Exception {
        mvc.perform(post("/ops/v1/remediation/activation/force-provision")
                        .contentType("application/json").content("{\"simIccid\":\"893601001\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("orderNo")));

        org.mockito.Mockito.verifyNoInteractions(activation);
    }

    // -------------------------------------------- three error models, one shape

    @Test
    void billingsSoapFaultsBecomeTheHttpStatusTheirCodeImplies() throws Exception {
        when(billing.reconcileBatch(eq("BATCH-NOPE"), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new BillingSoapClient.BillingClientException("NOT_FOUND",
                        "billing refused it: NOT_FOUND", null));

        mvc.perform(post("/ops/v1/remediation/billing/reconcile")
                        .contentType("application/json").content("{\"batchId\":\"BATCH-NOPE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BILLING_NOT_FOUND"));
    }

    @Test
    void aBatchThatCannotBeReconciledIsAConflictNotAServerError() throws Exception {
        when(billing.reconcileBatch(eq("BATCH-20260925-001"), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new BillingSoapClient.BillingClientException("ILLEGAL_STATE",
                        "the settlement file is missing from the outbox", null));

        mvc.perform(post("/ops/v1/remediation/billing/reconcile")
                        .contentType("application/json").content("{\"batchId\":\"BATCH-20260925-001\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_ILLEGAL_STATE"));
    }

    @Test
    void anUnreachableSubsystemIsServiceUnavailable() throws Exception {
        when(activation.forceProvision(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new ActivationRestClient.ActivationClientException(
                        "activation at http://localhost:8082 is unreachable while requesting it", null));

        mvc.perform(post("/ops/v1/remediation/activation/force-provision")
                        .contentType("application/json").content("{\"orderNo\":\"ORD/2026/0000010\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACTIVATION_FAILURE"));
    }

    @Test
    void aBrokenDirectJdbcConnectionIsAlsoServiceUnavailable() throws Exception {
        when(diagnostics.stuckActivations(anyInt())).thenThrow(
                new org.springframework.jdbc.CannotGetJdbcConnectionException(
                        "could not get JDBC connection", new java.sql.SQLException("connection refused")));

        mvc.perform(get("/ops/v1/diagnostics/stuck-activations"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CATALOG_JDBC_FAILURE"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("connection refused")));
    }

    // -------------------------------------------------------------- fixtures

    private static CanonicalOrder stuckOrder() {
        return new CanonicalOrder("ORD/2026/0000010", "NEW_SUBSCRIPTION", CustomerRef.of(101),
                "MOB-VOICE-0010", OrderStatus.STUCK, "SUB-2026-001000", null, null,
                "the network platform did not confirm provisioning", true, true,
                "provisioningCallback", Instant.parse("2026-09-29T18:00:00Z"));
    }

    private static CanonicalSubscription pendingSubscription() {
        return new CanonicalSubscription("SUB-2026-001000", CustomerRef.of(101), "MOB-VOICE-0010",
                SubscriptionStatus.PENDING_ACTIVATION, "PA", null, PhoneNumber.of("+36707654321"),
                null, DataVolume.ofMegabytes(10_240), null, Instant.parse("2026-09-29T18:00:00Z"));
    }

    private static CanonicalBatch sentBatch() {
        return new CanonicalBatch("BATCH-20260929-001", "PMT-BATCH-20260929-001.txt", BatchStatus.SENT,
                1, Money.ofMajorUnits(new BigDecimal("6990.00"), "HUF"),
                Instant.parse("2026-09-29T18:00:00Z"), Instant.parse("2026-09-29T18:00:00Z"),
                null, null, 5L);
    }

    private static CanonicalInvoice strandedInvoice() {
        return new CanonicalInvoice("2026/INV/000004", "BA-00043",
                CustomerRef.of(43).withBillingAccount("BA-00043"), "SUB-2026-000003",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                Money.ofMajorUnits(new BigDecimal("5503.94"), "HUF"),
                Money.ofMajorUnits(new BigDecimal("1486.06"), "HUF"),
                Money.ofMajorUnits(new BigDecimal("6990.00"), "HUF"),
                InvoiceStatus.SETTLEMENT_PENDING, "pi_test", "BATCH-20260929-001",
                Instant.parse("2026-09-09T00:00:00Z"));
    }
}

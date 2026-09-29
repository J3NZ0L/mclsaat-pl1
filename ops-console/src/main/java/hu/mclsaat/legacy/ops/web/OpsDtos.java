package hu.mclsaat.legacy.ops.web;

import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CanonicalBatch;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CanonicalInvoice;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CanonicalOrder;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CanonicalSubscription;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.StuckActivation;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.UnconfirmedSettlement;
import hu.mclsaat.legacy.clients.canonical.Money;
import hu.mclsaat.legacy.clients.protocol.BillingSoapClient;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/**
 * The ops console's wire shapes.
 *
 * <p>These are flattened views of the canonical model rather than the records themselves. Two reasons:
 * a human reading the output of {@code scripts/demo.sh} should not have to decode nested
 * {@code {"amount": …, "currency": …}} objects, and these shapes are the closest thing in the
 * repository to what a phase-2 MCP tool's response schema would look like — flat, self-describing,
 * and carrying the subsystem's raw value next to the canonical one wherever the two differ.
 */
public final class OpsDtos {

    private OpsDtos() {
    }

    private static String money(Money money) {
        return money == null ? null : money.toString();
    }

    // ------------------------------------------------------------- failure branch A

    /**
     * One stuck activation, with both halves of the picture side by side.
     *
     * <p>{@code activation} and {@code catalog} come from different systems over different protocols;
     * the point of the record is that you need both.
     */
    public record StuckActivationView(
            String orderNo,
            String category,
            boolean repairableByCallback,
            String diagnosis,
            ActivationSideView activation,
            CatalogSideView catalog,
            String suggestedRemedy) {

        public static StuckActivationView of(StuckActivation finding) {
            return new StuckActivationView(
                    finding.orderNo(),
                    finding.category().name(),
                    finding.repairableByCallback(),
                    finding.diagnosis(),
                    ActivationSideView.of(finding.order()),
                    CatalogSideView.of(finding.subscription()),
                    remedyFor(finding));
        }

        private static String remedyFor(StuckActivation finding) {
            return switch (finding.category()) {
                case STUCK_PROCESS -> finding.repairableByCallback()
                        ? "POST /ops/v1/remediation/activation/force-provision with this orderNo and "
                          + "an ICCID, which injects the callback the platform never sent"
                        : "POST /ops/v1/remediation/activation/cancel: the process is no longer "
                          + "waiting, so there is nothing to correlate into";
                case ORPHANED_STUCK_ORDER ->
                        "POST /ops/v1/remediation/activation/cancel: the process instance is gone";
                case ORPHANED_PENDING_SUBSCRIPTION ->
                        "no activation order refers to this subscription, so there is nothing to "
                        + "correlate into. Terminate it in the catalog and place a fresh order";
            };
        }
    }

    /** What activation knows, over REST. Null when no order refers to the catalog row at all. */
    public record ActivationSideView(
            String orderNo,
            String changeType,
            int customerRef,
            String productCode,
            String status,
            String subscriptionRef,
            String simIccid,
            String invoiceRef,
            String failureReason,
            boolean processRunning,
            boolean waitingForCallback,
            String currentActivity,
            String updatedAt) {

        static ActivationSideView of(CanonicalOrder order) {
            if (order == null) {
                return null;
            }
            return new ActivationSideView(order.orderNo(), order.changeType(),
                    order.customer().reference(), order.productCode(), order.status().name(),
                    order.subscriptionId(), order.simIccid(), order.invoiceNo(),
                    order.failureReason(), order.processRunning(), order.waitingForCallback(),
                    order.currentActivity(),
                    order.updatedAt() == null ? null : order.updatedAt().toString());
        }
    }

    /**
     * What the catalog knows, over direct JDBC. Null when the order never reserved anything.
     *
     * <p>{@code catalogStatusCode} is kept alongside {@code status} because an operator chasing a
     * mismatch needs to see the two-character code the database actually holds.
     */
    public record CatalogSideView(
            String subscriptionId,
            String custNo,
            int customerRef,
            String productCode,
            String status,
            String catalogStatusCode,
            String activatedOn,
            String msisdn,
            String simIccid,
            String dataAllowance,
            String parentSubscriptionId,
            String updatedAt) {

        static CatalogSideView of(CanonicalSubscription subscription) {
            if (subscription == null) {
                return null;
            }
            return new CatalogSideView(
                    subscription.subscriptionId(),
                    subscription.customer().catalogCustNo(),
                    subscription.customer().reference(),
                    subscription.productCode(),
                    subscription.status().name(),
                    subscription.catalogStatusCode(),
                    subscription.activatedOn() == null ? null : subscription.activatedOn().toString(),
                    subscription.phoneNumber() == null ? null : subscription.phoneNumber().withPlus(),
                    subscription.simIccid(),
                    subscription.dataAllowance() == null ? null : subscription.dataAllowance().toString(),
                    subscription.parentSubscriptionId(),
                    subscription.updatedAt() == null ? null : subscription.updatedAt().toString());
        }
    }

    // ------------------------------------------------------------- failure branch B

    public record UnconfirmedSettlementView(
            BatchView batch,
            String strandedAmount,
            List<InvoiceView> invoices,
            boolean settlementFilePresent,
            /** The fixed-width records as written. Printed verbatim so an operator can read them. */
            List<String> settlementFileLines,
            String diagnosis,
            String suggestedRemedy) {

        public static UnconfirmedSettlementView of(UnconfirmedSettlement finding) {
            return new UnconfirmedSettlementView(
                    BatchView.of(finding.batch()),
                    money(finding.strandedAmount()),
                    finding.invoices().stream().map(InvoiceView::of).toList(),
                    finding.settlementFilePresent(),
                    finding.settlementFileLines(),
                    finding.diagnosis(),
                    finding.settlementFilePresent()
                            ? "POST /ops/v1/remediation/billing/reconcile with mode "
                              + BillingSoapClient.RECONCILE_RE_DRIVE_ACK + " to rebuild the "
                              + "acknowledgement from the file that was sent, or "
                              + BillingSoapClient.RECONCILE_RESEND + " to hand it over again"
                            : "neither mode is safe without the settlement file; this needs a human "
                              + "and the clearing house's own statement");
        }
    }

    public record BatchView(
            String batchId,
            String fileName,
            String status,
            int itemCount,
            String totalAmount,
            String createdAt,
            String sentAt,
            String acknowledgedAt,
            String ackFileName,
            Long ageMinutes) {

        static BatchView of(CanonicalBatch batch) {
            if (batch == null) {
                return null;
            }
            return new BatchView(batch.batchId(), batch.fileName(), batch.status().name(),
                    batch.itemCount(), money(batch.totalAmount()),
                    batch.createdAt() == null ? null : batch.createdAt().toString(),
                    batch.sentAt() == null ? null : batch.sentAt().toString(),
                    batch.acknowledgedAt() == null ? null : batch.acknowledgedAt().toString(),
                    batch.ackFileName(), batch.ageMinutes());
        }
    }

    /** All three amounts are shown, because in this landscape net + VAT does not always equal gross. */
    public record InvoiceView(
            String invoiceNo,
            String billingAccountNo,
            int customerRef,
            String subscriptionRef,
            String periodStart,
            String periodEnd,
            String netAmount,
            String vatAmount,
            String grossAmount,
            String status,
            String paymentRef,
            String batchId) {

        static InvoiceView of(CanonicalInvoice invoice) {
            return new InvoiceView(invoice.invoiceNo(), invoice.billingAccountNo(),
                    invoice.customer().reference(), invoice.subscriptionId(),
                    invoice.periodStart() == null ? null : invoice.periodStart().toString(),
                    invoice.periodEnd() == null ? null : invoice.periodEnd().toString(),
                    money(invoice.netAmount()), money(invoice.vatAmount()), money(invoice.grossAmount()),
                    invoice.status().name(), invoice.paymentRef(), invoice.batchId());
        }
    }

    // ------------------------------------------------------------------- overview

    /** Everything an operator needs to answer "is anything wrong right now?". */
    public record OverviewView(
            Map<String, Boolean> subsystemsReachable,
            int stuckActivationCount,
            int unconfirmedSettlementCount,
            String strandedAmount,
            List<StuckActivationView> stuckActivations,
            List<UnconfirmedSettlementView> unconfirmedSettlements) {
    }

    // ---------------------------------------------------------------- remediation

    /** The order number is in the body because it contains slashes. */
    public record ForceProvisionRequest(
            @NotBlank String orderNo,
            String simIccid) {
    }

    public record CancelOrderRequest(
            @NotBlank String orderNo,
            String reason) {
    }

    /**
     * @param mode {@code RE_DRIVE_ACK} (rebuild the acknowledgement from the file we sent) or
     *             {@code RESEND} (hand the file over again). Defaults to {@code RE_DRIVE_ACK}.
     */
    public record ReconcileBatchRequest(
            @NotBlank String batchId,
            String mode) {
    }

    public record RemediationResult(
            String action,
            String target,
            boolean succeeded,
            String outcome,
            Object detail) {
    }

    public record ApiError(String code, String message) {
    }
}

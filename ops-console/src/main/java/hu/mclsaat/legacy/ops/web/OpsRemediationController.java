package hu.mclsaat.legacy.ops.web;

import hu.mclsaat.legacy.clients.protocol.ActivationRestClient;
import hu.mclsaat.legacy.clients.protocol.BillingSoapClient;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Service 6, repair half. Internal ops only.
 *
 * <p>Each operation crosses a subsystem boundary over that subsystem's own protocol, and each one
 * carries a consequence an operator has to have decided on first. There is no "fix it" button, because
 * the two branches each offer two remedies that mean different things:
 *
 * <ul>
 *   <li><b>force-provision</b> asserts that provisioning did in fact happen and the callback was lost.
 *       Reversible in the sense that it drives the normal path.
 *   <li><b>cancel</b> asserts that it did not, and gives up. <b>Not reversible</b>: it deletes the
 *       process instance and with it the message subscription.
 *   <li><b>RE_DRIVE_ACK</b> asserts that the money moved and the confirmation is never coming.
 *   <li><b>RESEND</b> asserts that the clearing house is simply back and will answer this time.
 * </ul>
 *
 * <p>That is why the diagnostics endpoints return a {@code suggestedRemedy} and a {@code diagnosis}
 * rather than acting on their own — and why, in phase 2, diagnosing and remediating should be separate
 * tools with separate permissions.
 */
@RestController
@RequestMapping("/ops/v1/remediation")
public class OpsRemediationController {

    private static final Logger log = LoggerFactory.getLogger(OpsRemediationController.class);

    private final ActivationRestClient activation;
    private final BillingSoapClient billing;

    public OpsRemediationController(ActivationRestClient activation, BillingSoapClient billing) {
        this.activation = activation;
        this.billing = billing;
    }

    /**
     * Failure branch A: injects the provisioning callback the platform never sent.
     *
     * <p>Indistinguishable from the real callback as far as the engine is concerned, so the order
     * finishes down the normal path — activating the catalog subscription and issuing the invoice.
     * Only works while the process is still waiting, which the diagnostics report as
     * {@code repairableByCallback}.
     */
    @PostMapping("/activation/force-provision")
    public OpsDtos.RemediationResult forceProvision(
            @Valid @RequestBody OpsDtos.ForceProvisionRequest request) {

        log.warn("ops is forcing provisioning of {} with ICCID {}", request.orderNo(),
                request.simIccid() == null ? "(none supplied)" : request.simIccid());
        var order = activation.forceProvision(request.orderNo(), request.simIccid());

        return new OpsDtos.RemediationResult(
                "force-provision", request.orderNo(), true,
                "the provisioningCompleted message was correlated into the waiting process instance; "
                        + "the order will complete on the job executor within a second or two",
                OpsDtos.ActivationSideView.of(order));
    }

    /**
     * Failure branch A: gives up on the order.
     *
     * <p>Not reversible. Deleting the process instance removes the message subscription, so a callback
     * that arrives afterwards has nowhere to land.
     */
    @PostMapping("/activation/cancel")
    public OpsDtos.RemediationResult cancelOrder(
            @Valid @RequestBody OpsDtos.CancelOrderRequest request) {

        String reason = request.reason() == null || request.reason().isBlank()
                ? "cancelled by ops" : request.reason();
        log.warn("ops is cancelling {}: {}", request.orderNo(), reason);
        var order = activation.cancelOrder(request.orderNo(), reason);

        return new OpsDtos.RemediationResult(
                "cancel", request.orderNo(), true,
                "the process instance and its message subscription were deleted; this cannot be undone",
                OpsDtos.ActivationSideView.of(order));
    }

    /**
     * Failure branch B: settles a batch the clearing house never acknowledged.
     *
     * @see BillingSoapClient#RECONCILE_RE_DRIVE_ACK
     * @see BillingSoapClient#RECONCILE_RESEND
     */
    @PostMapping("/billing/reconcile")
    public OpsDtos.RemediationResult reconcileBatch(
            @Valid @RequestBody OpsDtos.ReconcileBatchRequest request) {

        String mode = request.mode() == null || request.mode().isBlank()
                ? BillingSoapClient.RECONCILE_RE_DRIVE_ACK : request.mode().trim().toUpperCase();
        log.warn("ops is reconciling batch {} in mode {}", request.batchId(), mode);
        var result = billing.reconcileBatch(request.batchId(), mode);

        return new OpsDtos.RemediationResult(
                "reconcile:" + mode, request.batchId(), true, result.message(),
                Map.of("batch", OpsDtos.BatchView.of(result.batch()),
                        "invoicesSettled", result.invoicesSettled()));
    }

    /**
     * The mocked provisioning platform's global callback switch.
     *
     * <p>Fault injection, not remediation — it lives here because it is the same audience and the same
     * permission level, and because the demo needs a way to make failure branch A happen on purpose.
     */
    @PostMapping("/fault-injection/provisioning-callbacks")
    public OpsDtos.RemediationResult provisioningCallbacks(@RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.parseBoolean(
                String.valueOf(body.getOrDefault("callbacksEnabled", true)));
        boolean previous = activation.setProvisioningCallbacksEnabled(enabled);

        return new OpsDtos.RemediationResult(
                "fault-injection:provisioning-callbacks", "the provisioning platform", true,
                "provisioning callbacks are now " + (enabled ? "enabled" : "SUPPRESSED")
                        + " (were " + (previous ? "enabled" : "suppressed") + ")",
                Map.of("callbacksEnabled", enabled, "previousCallbacksEnabled", previous));
    }

    /**
     * Cuts a settlement batch now rather than waiting for the nightly export.
     *
     * <p>Ops needs this for the same reason the demo does: there is no point asking an operator to
     * diagnose a stranded batch if they cannot create one to look at.
     */
    @PostMapping("/billing/export-batch")
    public OpsDtos.RemediationResult exportBatch() {
        var batch = billing.exportPaymentBatch();
        return batch
                .map(exported -> new OpsDtos.RemediationResult(
                        "export-batch", exported.batchId(), true,
                        "batch " + exported.batchId() + " was written to the outbox as "
                                + exported.fileName() + " and handed to the clearing house",
                        OpsDtos.BatchView.of(exported)))
                .orElseGet(() -> new OpsDtos.RemediationResult(
                        "export-batch", null, false,
                        "nothing to settle: no invoice is waiting for settlement", null));
    }
}

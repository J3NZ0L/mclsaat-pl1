package hu.mclsaat.legacy.activation.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of {@code activation.activation_order}: this subsystem's own record of a change request.
 *
 * <p>Everything is in activation's dialect. {@code offerId} is {@code "mob.voice.0010"} where the
 * catalog says {@code "MOB-VOICE-0010"}; {@code dataAllowanceGb} is {@code 10.000} where the
 * catalog says {@code 10240}; {@code msisdn} carries a leading {@code +} where the catalog does
 * not; {@code requestedStartDate} is the eight-character string {@code "20260929"} where the
 * catalog uses a {@code DATE} and billing uses {@code "2026-09-29"}.
 */
public record ActivationOrder(
        String orderNo,
        String changeType,
        int customerRef,
        String offerId,
        String msisdn,
        String requestedStartDate,
        BigDecimal dataAllowanceGb,
        BigDecimal monthlyFeeHuf,
        String targetSubscriptionRef,
        String subscriptionRef,
        String status,
        String processInstanceId,
        String simIccid,
        String invoiceRef,
        String failureReason,
        boolean simulateStuck,
        Instant createdTs,
        Instant updatedTs) {

    public static final String CHANGE_NEW_SUBSCRIPTION = "NEW_SUBSCRIPTION";
    public static final String CHANGE_PLAN_CHANGE = "PLAN_CHANGE";
    public static final String CHANGE_ADDON = "ADDON";

    public static final String STATUS_RECEIVED = "RECEIVED";
    public static final String STATUS_VALIDATED = "VALIDATED";
    /** Waiting for the network provisioning platform to call back. */
    public static final String STATUS_AWAITING_PROVISIONING = "AWAITING_PROVISIONING";
    /** The boundary timer fired and nobody called back. Failure branch A. */
    public static final String STATUS_STUCK = "STUCK";
    public static final String STATUS_PROVISIONED = "PROVISIONED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public boolean isTerminal() {
        return STATUS_PROVISIONED.equals(status)
                || STATUS_FAILED.equals(status)
                || STATUS_CANCELLED.equals(status);
    }
}

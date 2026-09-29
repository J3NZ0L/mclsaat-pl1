package hu.mclsaat.legacy.activation.web;

import hu.mclsaat.legacy.activation.domain.ActivationOrder;
import hu.mclsaat.legacy.activation.service.ActivationOrderService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Wire shapes for subsystem 2.
 *
 * <p>These speak activation's dialect, not the catalog's: {@code customerRef} is a number,
 * {@code offerId} is dotted lowercase, {@code requestedStartDate} is {@code "20260929"},
 * {@code msisdn} carries its plus, allowances are gigabytes and money is HUF with two decimals. A
 * client that has just read the catalog's REST API has to convert every one of those.
 */
public final class ActivationDtos {

    private ActivationDtos() {
    }

    /**
     * @param changeType          {@code NEW_SUBSCRIPTION}, {@code PLAN_CHANGE} or {@code ADDON}
     * @param offerId             dotted lowercase, e.g. {@code mob.voice.0010}
     * @param msisdn              E.164 with a leading plus, e.g. {@code +36301234567}
     * @param requestedStartDate  {@code yyyyMMdd}; defaults to today
     * @param simulateStuck       ask the provisioning platform to ignore this order, which is how
     *                            failure branch A is triggered on purpose
     * @param provisioningTimeout ISO-8601 duration overriding the boundary timer, e.g. {@code PT10S}
     */
    public record StartOrderRequest(
            @NotBlank String changeType,
            @Positive int customerRef,
            @NotBlank String offerId,
            String msisdn,
            String requestedStartDate,
            String targetSubscriptionRef,
            boolean simulateStuck,
            String provisioningTimeout) {
    }

    public record OrderView(
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
            String createdTs,
            String updatedTs) {

        public static OrderView of(ActivationOrder order) {
            return new OrderView(order.orderNo(), order.changeType(), order.customerRef(),
                    order.offerId(), order.msisdn(), order.requestedStartDate(),
                    order.dataAllowanceGb(), order.monthlyFeeHuf(), order.targetSubscriptionRef(),
                    order.subscriptionRef(), order.status(), order.processInstanceId(),
                    order.simIccid(), order.invoiceRef(), order.failureReason(),
                    order.createdTs().toString(), order.updatedTs().toString());
        }
    }

    /**
     * What a poller needs: the order, plus enough about the process instance to know whether waiting
     * longer is going to help.
     */
    public record OrderStatusView(
            OrderView order,
            boolean processRunning,
            boolean processFinished,
            boolean waitingForCallback,
            String currentActivity,
            boolean terminal) {

        public static OrderStatusView of(ActivationOrderService.OrderStatus status) {
            return new OrderStatusView(OrderView.of(status.order()), status.processRunning(),
                    status.processFinished(), status.waitingForCallback(), status.currentActivity(),
                    status.order().isTerminal());
        }
    }

    /** The provisioning platform's callback. An external system's shape, not ours. */
    public record ProvisioningCallbackRequest(
            @NotBlank String orderNo,
            String outcome,
            String simIccid) {
    }

    public record StuckOrderView(
            OrderView order,
            boolean waitingForCallback,
            boolean processRunning,
            boolean repairableByCallback) {

        public static StuckOrderView of(ActivationOrderService.StuckOrder stuck) {
            return new StuckOrderView(OrderView.of(stuck.order()), stuck.waitingForCallback(),
                    stuck.processRunning(), stuck.repairableByCallback());
        }
    }

    /** The order number travels in the body because it contains slashes. */
    public record ForceProvisionRequest(
            @NotBlank String orderNo,
            String simIccid) {
    }

    public record CancelOrderRequest(
            @NotBlank String orderNo,
            String reason) {
    }

    public record ApiError(String code, String message) {
    }
}

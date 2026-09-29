package hu.mclsaat.legacy.activation.service;

import hu.mclsaat.legacy.activation.domain.ActivationOrder;
import hu.mclsaat.legacy.activation.process.ProcessVariables;
import hu.mclsaat.legacy.activation.repo.ActivationOrderRepository;
import hu.mclsaat.legacy.activation.semantics.ActivationSemantics;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Services 2, 3, 4 and the activation half of service 6.
 *
 * <p>Starting an order returns as soon as the process instance exists. The work happens on Flowable's
 * job executor afterwards, so the response can only say {@code RECEIVED} - which is why polling
 * (service 3) is a service in its own right rather than a convenience.
 */
@Service
public class ActivationOrderService {

    private static final Logger log = LoggerFactory.getLogger(ActivationOrderService.class);

    private final ActivationOrderRepository orders;
    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final ProvisioningPlatformSimulator platform;
    private final String defaultProvisioningTimeout;

    public ActivationOrderService(ActivationOrderRepository orders, RuntimeService runtimeService,
                                  HistoryService historyService,
                                  ProvisioningPlatformSimulator platform,
                                  @Value("${activation.provisioning.timeout:PT45S}")
                                  String defaultProvisioningTimeout) {
        this.orders = orders;
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.platform = platform;
        this.defaultProvisioningTimeout = defaultProvisioningTimeout;
    }

    /**
     * Services 2 and 4: start a new subscription, a plan change or an add-on. The same process
     * definition handles all three.
     *
     * @param provisioningTimeout ISO-8601 duration for the boundary timer, or {@code null} for the
     *                            configured default. The demo shortens it so a stuck order can be
     *                            shown in seconds.
     */
    @Transactional
    public ActivationOrder startOrder(String changeType, int customerRef, String offerId,
                                      String msisdn, String requestedStartDate,
                                      String targetSubscriptionRef, boolean simulateStuck,
                                      String provisioningTimeout) {
        String type = requireChangeType(changeType);
        if (customerRef <= 0) {
            throw new ActivationException.BadRequest("customerRef must be a positive integer");
        }
        if (offerId == null || offerId.isBlank()) {
            throw new ActivationException.BadRequest("offerId is required, e.g. mob.voice.0010");
        }
        if (!offerId.matches("^[a-z0-9.]+$")) {
            throw new ActivationException.BadRequest("offerId must be dotted lowercase, e.g. "
                    + "mob.voice.0010 (the catalog's MOB-VOICE-0010), was: " + offerId);
        }
        String normalisedMsisdn = ActivationSemantics.toActivationMsisdn(msisdn);
        if (normalisedMsisdn != null && !normalisedMsisdn.matches("^\\+[0-9]{8,15}$")) {
            throw new ActivationException.BadRequest(
                    "msisdn must be E.164 with a leading plus, was: " + msisdn);
        }
        String startDate = requestedStartDate == null || requestedStartDate.isBlank()
                ? ActivationSemantics.formatOrderDate(LocalDate.now())
                : requestedStartDate.trim();
        try {
            ActivationSemantics.parseOrderDate(startDate);
        } catch (IllegalArgumentException ex) {
            throw new ActivationException.BadRequest(ex.getMessage());
        }
        if (!ActivationOrder.CHANGE_NEW_SUBSCRIPTION.equals(type)
                && (targetSubscriptionRef == null || targetSubscriptionRef.isBlank())) {
            throw new ActivationException.BadRequest(
                    type + " needs targetSubscriptionRef (the catalog's SUB-yyyy-nnnnnn)");
        }

        ActivationOrder order = orders.insertReceived(type, customerRef, offerId.trim(),
                normalisedMsisdn, startDate, blankToNull(targetSubscriptionRef), simulateStuck);

        Map<String, Object> variables = new HashMap<>();
        variables.put(ProcessVariables.ORDER_NO, order.orderNo());
        variables.put(ProcessVariables.CHANGE_TYPE, type);
        variables.put(ProcessVariables.CUSTOMER_REF, customerRef);
        variables.put(ProcessVariables.OFFER_ID, order.offerId());
        variables.put(ProcessVariables.MSISDN, order.msisdn());
        variables.put(ProcessVariables.REQUESTED_START_DATE, order.requestedStartDate());
        variables.put(ProcessVariables.TARGET_SUBSCRIPTION_REF, order.targetSubscriptionRef());
        variables.put(ProcessVariables.SIMULATE_STUCK, simulateStuck);
        variables.put(ProcessVariables.PROVISIONING_TIMEOUT,
                provisioningTimeout == null || provisioningTimeout.isBlank()
                        ? defaultProvisioningTimeout : provisioningTimeout.trim());

        // The business key is the order number; that is what provisioningCompleted correlates on.
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                ProcessVariables.PROCESS_KEY, order.orderNo(), variables);
        orders.updateProcessInstanceId(order.orderNo(), instance.getId());

        log.info("order {} accepted ({} for customerRef {}, offer {}), process instance {}",
                order.orderNo(), type, customerRef, order.offerId(), instance.getId());
        return orders.findByOrderNo(order.orderNo()).orElseThrow();
    }

    /** Service 3: what is happening with this order? */
    public OrderStatus status(String orderNo) {
        ActivationOrder order = requireOrder(orderNo);
        Optional<ProcessInstance> instance = findInstance(orderNo);
        boolean waiting = findProvisioningSubscription(orderNo).isPresent();
        String activity = instance
                .map(pi -> String.join(",", runtimeService.getActiveActivityIds(pi.getId())))
                .filter(ids -> !ids.isBlank())
                .orElse(null);
        boolean finished = instance.isEmpty() && historyService
                .createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(orderNo)
                .finished()
                .count() > 0;
        return new OrderStatus(order, instance.isPresent(), finished, waiting, activity);
    }

    public List<ActivationOrder> ordersOf(int customerRef) {
        return orders.findByCustomerRef(customerRef);
    }

    /**
     * The activation half of service 6: orders that are waiting or have been reported stuck.
     *
     * <p>{@code waitingForCallback} says whether the message subscription is still there, which is
     * what decides whether ops can repair the order by injecting the callback or has to cancel it.
     */
    public List<StuckOrder> stuckOrders(int olderThanMinutes) {
        return orders.findStuck(olderThanMinutes).stream()
                .map(order -> new StuckOrder(order,
                        findProvisioningSubscription(order.orderNo()).isPresent(),
                        findInstance(order.orderNo()).isPresent()))
                .toList();
    }

    /**
     * Correlates the {@code provisioningCompleted} message into the waiting process instance.
     *
     * <p>Used by two callers with the same effect and very different intent: the provisioning
     * platform's own callback endpoint, and ops forcing a stuck order through by supplying an ICCID
     * by hand. The engine cannot tell them apart, which is the point - the repair path is the normal
     * path, not a special case.
     */
    @Transactional
    public ActivationOrder correlateProvisioningCompleted(String orderNo, String simIccid,
                                                         String source) {
        ActivationOrder order = requireOrder(orderNo);
        Execution waiting = findProvisioningSubscription(orderNo).orElseThrow(() ->
                new ActivationException.IllegalState("order " + orderNo + " is not waiting for a "
                        + "provisioning callback (status " + order.status() + ")"
                        + (order.isTerminal() ? "; it has already finished" : "")));

        Map<String, Object> payload = new HashMap<>();
        if (simIccid != null && !simIccid.isBlank()) {
            payload.put(ProcessVariables.SIM_ICCID, simIccid.trim());
        }
        runtimeService.messageEventReceived(ProcessVariables.MESSAGE_PROVISIONING_COMPLETED,
                waiting.getId(), payload);
        log.info("order {}: provisioningCompleted correlated from {}{}", orderNo, source,
                simIccid == null || simIccid.isBlank() ? "" : " with SIM " + simIccid);
        return orders.findByOrderNo(orderNo).orElseThrow();
    }

    /**
     * The other ops remediation: give up on the order and put the catalog back to a consistent
     * state. Deleting the process instance removes the message subscription, so this is not
     * reversible - which is why it is a separate, explicit decision.
     */
    @Transactional
    public ActivationOrder cancelOrder(String orderNo, String reason) {
        ActivationOrder order = requireOrder(orderNo);
        if (order.isTerminal()) {
            throw new ActivationException.IllegalState(
                    "order " + orderNo + " is already " + order.status());
        }
        findInstance(orderNo).ifPresent(instance ->
                runtimeService.deleteProcessInstance(instance.getId(),
                        reason == null ? "cancelled by ops" : reason));
        orders.updateFailure(orderNo, ActivationOrder.STATUS_CANCELLED,
                reason == null ? "cancelled by ops" : reason);
        log.warn("order {} cancelled: {}", orderNo, reason);
        return orders.findByOrderNo(orderNo).orElseThrow();
    }

    public ProvisioningPlatformSimulator platform() {
        return platform;
    }

    public ActivationOrder requireOrder(String orderNo) {
        return orders.findByOrderNo(orderNo)
                .orElseThrow(() -> new ActivationException.NotFound("activation order", orderNo));
    }

    private Optional<ProcessInstance> findInstance(String orderNo) {
        return Optional.ofNullable(runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(orderNo)
                .singleResult());
    }

    /**
     * The execution sitting on the {@code provisioningCompleted} catch event, if there is one.
     *
     * <p>The lookup goes via the process instance id rather than straight from the business key,
     * because the business key lives only on the process instance's own execution row while the
     * message subscription lives on a child execution inside the {@code waitForProvisioning}
     * sub-process. Querying executions by business key therefore finds the root and misses the one
     * that is actually waiting.
     */
    private Optional<Execution> findProvisioningSubscription(String orderNo) {
        return findInstance(orderNo).flatMap(instance -> runtimeService.createExecutionQuery()
                .processInstanceId(instance.getId())
                .messageEventSubscriptionName(ProcessVariables.MESSAGE_PROVISIONING_COMPLETED)
                .list()
                .stream()
                .findFirst());
    }

    private static String requireChangeType(String changeType) {
        String type = changeType == null ? "" : changeType.trim().toUpperCase();
        return switch (type) {
            case ActivationOrder.CHANGE_NEW_SUBSCRIPTION,
                 ActivationOrder.CHANGE_PLAN_CHANGE,
                 ActivationOrder.CHANGE_ADDON -> type;
            default -> throw new ActivationException.BadRequest("changeType must be one of "
                    + "NEW_SUBSCRIPTION, PLAN_CHANGE, ADDON, was: " + changeType);
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * @param processRunning     the instance still exists in the runtime tables
     * @param processFinished    the instance is in the history tables and gone from runtime
     * @param waitingForCallback there is a live {@code provisioningCompleted} subscription
     * @param currentActivity    the BPMN activity ids the instance is sitting on
     */
    public record OrderStatus(ActivationOrder order, boolean processRunning, boolean processFinished,
                              boolean waitingForCallback, String currentActivity) {
    }

    public record StuckOrder(ActivationOrder order, boolean waitingForCallback,
                             boolean processRunning) {
        /** A waiting instance can be pushed through; anything else has to be cancelled. */
        public boolean repairableByCallback() {
            return waitingForCallback;
        }
    }
}

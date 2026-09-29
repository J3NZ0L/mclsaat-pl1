package hu.mclsaat.legacy.activation.process;

import hu.mclsaat.legacy.activation.client.CatalogRestClient;
import hu.mclsaat.legacy.activation.domain.ActivationOrder;
import hu.mclsaat.legacy.activation.repo.ActivationOrderRepository;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Checks the order against the catalog and writes back what the catalog said, converted into
 * activation's units.
 *
 * <p>This is the first place the two subsystems' vocabularies meet, and the first place an order can
 * be rejected for good: a withdrawn plan, an unknown customer or an add-on requested as a base
 * subscription are all decided here, before anything has been reserved.
 */
@Component("validateOrderDelegate")
public class ValidateOrderDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(ValidateOrderDelegate.class);

    private final CatalogRestClient catalog;
    private final ActivationOrderRepository orders;

    public ValidateOrderDelegate(CatalogRestClient catalog, ActivationOrderRepository orders) {
        this.catalog = catalog;
        this.orders = orders;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String orderNo = (String) execution.getVariable(ProcessVariables.ORDER_NO);
        String changeType = (String) execution.getVariable(ProcessVariables.CHANGE_TYPE);
        String offerId = (String) execution.getVariable(ProcessVariables.OFFER_ID);
        int customerRef = ((Number) execution.getVariable(ProcessVariables.CUSTOMER_REF)).intValue();
        String msisdn = (String) execution.getVariable(ProcessVariables.MSISDN);
        String targetSubscriptionRef =
                (String) execution.getVariable(ProcessVariables.TARGET_SUBSCRIPTION_REF);

        var subscriber = catalog.fetchSubscriber(customerRef);
        var plan = catalog.fetchPlan(offerId);

        if (!plan.active()) {
            throw reject(execution, orderNo, "OFFER_WITHDRAWN",
                    "offer " + offerId + " is withdrawn in the catalog");
        }
        if (ActivationOrder.CHANGE_ADDON.equals(changeType) && !plan.isAddon()) {
            throw reject(execution, orderNo, "NOT_AN_ADDON", "offer " + offerId + " is not an add-on");
        }
        if (!ActivationOrder.CHANGE_ADDON.equals(changeType) && plan.isAddon()) {
            throw reject(execution, orderNo, "ADDON_AS_BASE",
                    "offer " + offerId + " is an add-on and cannot be a base subscription");
        }
        if (ActivationOrder.CHANGE_NEW_SUBSCRIPTION.equals(changeType)
                && plan.isMobile() && (msisdn == null || msisdn.isBlank())) {
            throw reject(execution, orderNo, "MSISDN_REQUIRED",
                    "mobile offer " + offerId + " needs an msisdn");
        }
        if (!ActivationOrder.CHANGE_NEW_SUBSCRIPTION.equals(changeType)
                && (targetSubscriptionRef == null || targetSubscriptionRef.isBlank())) {
            throw reject(execution, orderNo, "TARGET_REQUIRED",
                    changeType + " needs a targetSubscriptionRef");
        }
        if (targetSubscriptionRef != null && !targetSubscriptionRef.isBlank()) {
            var target = catalog.fetchSubscription(targetSubscriptionRef);
            if (target.customerRef() != customerRef) {
                throw reject(execution, orderNo, "TARGET_NOT_OWNED",
                        "subscription " + targetSubscriptionRef + " belongs to customer "
                                + target.customerRef() + ", not " + customerRef);
            }
        }

        execution.setVariable(ProcessVariables.MONTHLY_FEE_HUF, plan.monthlyFeeHufGross());
        execution.setVariable(ProcessVariables.DATA_ALLOWANCE_GB, plan.dataAllowanceGb());
        // carried for billing, which has no way to look a customer's name up itself
        execution.setVariable("customerName", subscriber.fullName());
        execution.setVariable("customerEmail", subscriber.email());

        orders.updateValidated(orderNo, plan.dataAllowanceGb(), plan.monthlyFeeHufGross());
        log.info("order {} validated: {} -> {}, {} HUF gross, {} GB", orderNo, offerId,
                plan.planCode(), plan.monthlyFeeHufGross(), plan.dataAllowanceGb());
    }

    /**
     * Rejects the order.
     *
     * <p>A {@link BpmnError} rather than a plain exception, so the async executor treats this as a
     * business outcome instead of retrying something that will never succeed. It is caught by the
     * error boundary event on this task, which routes to {@code markRejected}; the reason is written
     * there rather than here, because an unhandled BpmnError would roll this transaction back and
     * take the explanation with it.
     */
    private BpmnError reject(DelegateExecution execution, String orderNo, String code, String message) {
        execution.setVariable(ProcessVariables.REJECTION_CODE, code);
        execution.setVariable(ProcessVariables.REJECTION_MESSAGE, message);
        log.warn("order {} rejected at validation: {} ({})", orderNo, message, code);
        return new BpmnError(code, message);
    }
}

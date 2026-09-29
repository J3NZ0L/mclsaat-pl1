package hu.mclsaat.legacy.activation.process;

import hu.mclsaat.legacy.activation.client.CatalogRestClient;
import hu.mclsaat.legacy.activation.domain.ActivationOrder;
import hu.mclsaat.legacy.activation.repo.ActivationOrderRepository;
import hu.mclsaat.legacy.activation.semantics.ActivationSemantics;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The provisioning callback arrived. Takes the subscription live in the catalog.
 *
 * <p>A plan change has nothing to activate - that subscription was already {@code AC} - so this only
 * runs the activation call for the two variants that reserved something. The catalog's activate
 * endpoint is itself idempotent, which matters because this can be reached twice: once from the
 * callback that arrived normally, and once more if a retried job replays it.
 */
@Component("completeActivationDelegate")
public class CompleteActivationDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(CompleteActivationDelegate.class);

    private final CatalogRestClient catalog;
    private final ActivationOrderRepository orders;

    public CompleteActivationDelegate(CatalogRestClient catalog, ActivationOrderRepository orders) {
        this.catalog = catalog;
        this.orders = orders;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String orderNo = (String) execution.getVariable(ProcessVariables.ORDER_NO);
        String changeType = (String) execution.getVariable(ProcessVariables.CHANGE_TYPE);
        String subscriptionRef = (String) execution.getVariable(ProcessVariables.SUBSCRIPTION_REF);
        String simIccid = (String) execution.getVariable(ProcessVariables.SIM_ICCID);

        if (ActivationOrder.CHANGE_PLAN_CHANGE.equals(changeType)) {
            log.info("order {}: {} was already active, so the plan change needed no activation",
                    orderNo, subscriptionRef);
            return;
        }

        if (simIccid != null && !simIccid.isBlank()) {
            orders.updateSimIccid(orderNo, simIccid);
        }
        var activated = catalog.activateSubscription(subscriptionRef, simIccid,
                ActivationSemantics.parseOrderDate(
                        (String) execution.getVariable(ProcessVariables.REQUESTED_START_DATE)));
        log.info("order {}: catalog subscription {} is now {} ({}), allowance {} GB", orderNo,
                subscriptionRef, activated.catalogStatusCode(), activated.status(),
                activated.dataAllowanceGb());
    }
}

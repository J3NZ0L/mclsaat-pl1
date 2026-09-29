package hu.mclsaat.legacy.activation.process;

import hu.mclsaat.legacy.activation.client.CatalogRestClient;
import hu.mclsaat.legacy.activation.repo.ActivationOrderRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reserves an add-on (data pack or roaming pass) under an existing subscription.
 *
 * <p>The add-on gets its own catalog subscription row hanging off the base one, and the base
 * subscription's effective data allowance only grows once the add-on itself becomes active. That is
 * the catalog's rule, not this subsystem's, and it is why an add-on order goes through the same
 * reserve/provision/activate shape as a brand new subscription.
 */
@Component("reserveAddonDelegate")
public class ReserveAddonDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(ReserveAddonDelegate.class);

    private final CatalogRestClient catalog;
    private final ActivationOrderRepository orders;

    public ReserveAddonDelegate(CatalogRestClient catalog, ActivationOrderRepository orders) {
        this.catalog = catalog;
        this.orders = orders;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String orderNo = (String) execution.getVariable(ProcessVariables.ORDER_NO);
        String existing = (String) execution.getVariable(ProcessVariables.SUBSCRIPTION_REF);
        if (existing != null && !existing.isBlank()) {
            log.info("order {} already reserved add-on {}; nothing to do", orderNo, existing);
            return;
        }

        var reserved = catalog.reserveAddon(
                (String) execution.getVariable(ProcessVariables.TARGET_SUBSCRIPTION_REF),
                (String) execution.getVariable(ProcessVariables.OFFER_ID));

        execution.setVariable(ProcessVariables.SUBSCRIPTION_REF, reserved.subscriptionRef());
        orders.updateSubscriptionRef(orderNo, reserved.subscriptionRef());
        log.info("order {} reserved add-on subscription {} under {}", orderNo,
                reserved.subscriptionRef(), reserved.parentSubscriptionRef());
    }
}

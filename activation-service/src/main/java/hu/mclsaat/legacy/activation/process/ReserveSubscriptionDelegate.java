package hu.mclsaat.legacy.activation.process;

import hu.mclsaat.legacy.activation.client.CatalogRestClient;
import hu.mclsaat.legacy.activation.repo.ActivationOrderRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Creates the catalog subscription in {@code PA} (pending activation).
 *
 * <p>From this moment the two subsystems can disagree: the catalog has a subscription that is not
 * live, and only this process knows whether it ever will be. That is the inconsistency failure
 * branch A produces and the ops console has to find.
 *
 * <p>Idempotent by inspection rather than by an idempotency key, because the catalog has no such
 * concept: if the order already carries a subscription reference, a retried job reuses it.
 */
@Component("reserveSubscriptionDelegate")
public class ReserveSubscriptionDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(ReserveSubscriptionDelegate.class);

    private final CatalogRestClient catalog;
    private final ActivationOrderRepository orders;

    public ReserveSubscriptionDelegate(CatalogRestClient catalog, ActivationOrderRepository orders) {
        this.catalog = catalog;
        this.orders = orders;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String orderNo = (String) execution.getVariable(ProcessVariables.ORDER_NO);
        String existing = (String) execution.getVariable(ProcessVariables.SUBSCRIPTION_REF);
        if (existing != null && !existing.isBlank()) {
            log.info("order {} already reserved {}; nothing to do", orderNo, existing);
            return;
        }

        var reserved = catalog.reserveSubscription(
                ((Number) execution.getVariable(ProcessVariables.CUSTOMER_REF)).intValue(),
                (String) execution.getVariable(ProcessVariables.OFFER_ID),
                (String) execution.getVariable(ProcessVariables.MSISDN));

        execution.setVariable(ProcessVariables.SUBSCRIPTION_REF, reserved.subscriptionRef());
        orders.updateSubscriptionRef(orderNo, reserved.subscriptionRef());
        log.info("order {} reserved catalog subscription {} (catalog status {})",
                orderNo, reserved.subscriptionRef(), reserved.catalogStatusCode());
    }
}

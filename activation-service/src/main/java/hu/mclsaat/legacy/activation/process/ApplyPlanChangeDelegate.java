package hu.mclsaat.legacy.activation.process;

import hu.mclsaat.legacy.activation.client.CatalogRestClient;
import hu.mclsaat.legacy.activation.repo.ActivationOrderRepository;
import hu.mclsaat.legacy.activation.semantics.ActivationSemantics;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Swaps the base plan of a live subscription.
 *
 * <p>Unlike the other two variants this one changes a subscription that is already active, so there
 * is nothing to activate afterwards. The network profile still has to be reprovisioned, which is why
 * it goes through the same wait: a new tariff is not in force until the network agrees.
 *
 * <p>Idempotent by inspection: if the catalog already reports the target offer, a retried job is a
 * no-op instead of a rejected duplicate change.
 */
@Component("applyPlanChangeDelegate")
public class ApplyPlanChangeDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(ApplyPlanChangeDelegate.class);

    private final CatalogRestClient catalog;
    private final ActivationOrderRepository orders;

    public ApplyPlanChangeDelegate(CatalogRestClient catalog, ActivationOrderRepository orders) {
        this.catalog = catalog;
        this.orders = orders;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String orderNo = (String) execution.getVariable(ProcessVariables.ORDER_NO);
        String targetRef = (String) execution.getVariable(ProcessVariables.TARGET_SUBSCRIPTION_REF);
        String offerId = (String) execution.getVariable(ProcessVariables.OFFER_ID);

        var current = catalog.fetchSubscription(targetRef);
        if (offerId.equals(current.offerId())) {
            log.info("order {}: {} is already on {}; treating as done", orderNo, targetRef, offerId);
        } else {
            var changed = catalog.changePlan(targetRef, offerId);
            log.info("order {} moved {} to {} (effective allowance now {} GB)", orderNo, targetRef,
                    changed.offerId(), changed.dataAllowanceGb());
        }

        execution.setVariable(ProcessVariables.SUBSCRIPTION_REF, targetRef);
        orders.updateSubscriptionRef(orderNo, targetRef);
        // the catalog holds the number without a plus; activation wants it with one
        execution.setVariable(ProcessVariables.MSISDN,
                ActivationSemantics.toActivationMsisdn(current.msisdn()));
    }
}

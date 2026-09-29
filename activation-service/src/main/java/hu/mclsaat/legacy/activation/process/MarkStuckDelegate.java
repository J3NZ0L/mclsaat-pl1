package hu.mclsaat.legacy.activation.process;

import hu.mclsaat.legacy.activation.domain.ActivationOrder;
import hu.mclsaat.legacy.activation.repo.ActivationOrderRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The boundary timer fired: the provisioning SLA is blown and nobody has called back.
 *
 * <p>This does not abandon anything. The timer is non-interrupting, so the wait is still in place
 * when this runs; all it does is make the situation visible, by putting the order in {@code STUCK}
 * with a reason. The subscription is still sitting in {@code PA} over in the catalog, and neither
 * subsystem can see the other's half of the problem - which is precisely what the ops console is
 * for.
 */
@Component("markStuckDelegate")
public class MarkStuckDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(MarkStuckDelegate.class);

    private final ActivationOrderRepository orders;

    public MarkStuckDelegate(ActivationOrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String orderNo = (String) execution.getVariable(ProcessVariables.ORDER_NO);
        String subscriptionRef = (String) execution.getVariable(ProcessVariables.SUBSCRIPTION_REF);

        orders.updateFailure(orderNo, ActivationOrder.STATUS_STUCK,
                "the network platform did not confirm provisioning within "
                        + execution.getVariable(ProcessVariables.PROVISIONING_TIMEOUT)
                        + "; the process is still waiting for the provisioningCompleted message"
                        + (subscriptionRef == null ? ""
                           : " and catalog subscription " + subscriptionRef + " is left pending"));

        log.error("order {} is STUCK: no provisioning callback. The process instance is still "
                + "waiting, so correlating provisioningCompleted will finish it.", orderNo);
    }
}

package hu.mclsaat.legacy.activation.process;

import hu.mclsaat.legacy.activation.domain.ActivationOrder;
import hu.mclsaat.legacy.activation.repo.ActivationOrderRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The order is done. Clears any stuck reason it picked up on the way, because an order that was
 * reported stuck and then completed is no longer a problem and must stop being reported as one.
 */
@Component("markProvisionedDelegate")
public class MarkProvisionedDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(MarkProvisionedDelegate.class);

    private final ActivationOrderRepository orders;

    public MarkProvisionedDelegate(ActivationOrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String orderNo = (String) execution.getVariable(ProcessVariables.ORDER_NO);
        orders.updateFailure(orderNo, ActivationOrder.STATUS_PROVISIONED, null);
        log.info("order {} is PROVISIONED (subscription {}, invoice {})", orderNo,
                execution.getVariable(ProcessVariables.SUBSCRIPTION_REF),
                execution.getVariable(ProcessVariables.INVOICE_REF));
    }
}

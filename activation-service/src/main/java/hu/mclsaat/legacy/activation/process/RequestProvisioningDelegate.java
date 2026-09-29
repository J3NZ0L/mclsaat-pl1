package hu.mclsaat.legacy.activation.process;

import hu.mclsaat.legacy.activation.domain.ActivationOrder;
import hu.mclsaat.legacy.activation.repo.ActivationOrderRepository;
import hu.mclsaat.legacy.activation.service.ProvisioningPlatformSimulator;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Asks the external network platform to do its part, then stops caring.
 *
 * <p>Fire and forget: nothing is returned, nothing is waited for here. The answer arrives later as a
 * {@code provisioningCompleted} message correlated on the order number, or it never arrives and the
 * boundary timer notices.
 */
@Component("requestProvisioningDelegate")
public class RequestProvisioningDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(RequestProvisioningDelegate.class);

    private final ProvisioningPlatformSimulator platform;
    private final ActivationOrderRepository orders;

    public RequestProvisioningDelegate(ProvisioningPlatformSimulator platform,
                                       ActivationOrderRepository orders) {
        this.platform = platform;
        this.orders = orders;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String orderNo = (String) execution.getVariable(ProcessVariables.ORDER_NO);
        String changeType = (String) execution.getVariable(ProcessVariables.CHANGE_TYPE);
        String msisdn = (String) execution.getVariable(ProcessVariables.MSISDN);
        boolean simulateStuck =
                Boolean.TRUE.equals(execution.getVariable(ProcessVariables.SIMULATE_STUCK));

        // A new mobile subscription needs a SIM; the other two only need the profile reloaded.
        boolean needsSim = ActivationOrder.CHANGE_NEW_SUBSCRIPTION.equals(changeType)
                && msisdn != null && !msisdn.isBlank();

        orders.updateStatus(orderNo, ActivationOrder.STATUS_AWAITING_PROVISIONING);
        log.info("order {}: asked the network platform for {}{}", orderNo,
                needsSim ? "SIM provisioning" : "a network profile update",
                simulateStuck ? " (the platform has been told to stay silent)" : "");

        platform.requestProvisioning(orderNo, msisdn, needsSim, simulateStuck);
    }
}

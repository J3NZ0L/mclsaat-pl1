package hu.mclsaat.legacy.activation.process;

import hu.mclsaat.legacy.activation.domain.ActivationOrder;
import hu.mclsaat.legacy.activation.repo.ActivationOrderRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records why an order was rejected, on the error-handling branch of the process.
 *
 * <p>This runs after the error boundary event on {@code validateOrder} has caught the rejection, so
 * its write is in a transaction that commits. Doing it inside the validating delegate instead would
 * put the write in the transaction that the unhandled error rolls back, and the order would sit in
 * {@code RECEIVED} with no explanation at all.
 */
@Component("markRejectedDelegate")
public class MarkRejectedDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(MarkRejectedDelegate.class);

    private final ActivationOrderRepository orders;

    public MarkRejectedDelegate(ActivationOrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String orderNo = (String) execution.getVariable(ProcessVariables.ORDER_NO);
        String code = (String) execution.getVariable(ProcessVariables.REJECTION_CODE);
        String message = (String) execution.getVariable(ProcessVariables.REJECTION_MESSAGE);

        // Defensive: the variables are set immediately before the error is thrown, but an error
        // reaching this branch from anywhere else must still leave a usable reason behind.
        String reason = code == null
                ? "the order was rejected during validation"
                : code + ": " + (message == null ? "no further detail" : message);

        orders.updateFailure(orderNo, ActivationOrder.STATUS_FAILED, reason);
        log.warn("order {} is FAILED: {}", orderNo, reason);
    }
}

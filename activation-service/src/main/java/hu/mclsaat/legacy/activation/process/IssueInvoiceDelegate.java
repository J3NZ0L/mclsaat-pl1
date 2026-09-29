package hu.mclsaat.legacy.activation.process;

import hu.mclsaat.legacy.activation.client.BillingSoapClient;
import hu.mclsaat.legacy.activation.repo.ActivationOrderRepository;
import hu.mclsaat.legacy.activation.semantics.ActivationSemantics;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Issues the first invoice over SOAP.
 *
 * <p>Three translations happen in the space of one call. The date goes from {@code "20260929"} to
 * {@code "2026-09-29"}. The amount goes from the gross HUF the catalog quotes to the net amount
 * billing invoices on. And the customer, who is {@code 42} here and {@code "00000042"} in the
 * catalog, becomes billing account {@code "BA-00042"} on the far side.
 *
 * <p>The order number is the idempotency key, so a retried job - and this task is
 * {@code flowable:async}, so retries are real - gets the invoice that already exists rather than a
 * second one.
 */
@Component("issueInvoiceDelegate")
public class IssueInvoiceDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(IssueInvoiceDelegate.class);

    private final BillingSoapClient billing;
    private final ActivationOrderRepository orders;

    public IssueInvoiceDelegate(BillingSoapClient billing, ActivationOrderRepository orders) {
        this.billing = billing;
        this.orders = orders;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String orderNo = (String) execution.getVariable(ProcessVariables.ORDER_NO);
        String subscriptionRef = (String) execution.getVariable(ProcessVariables.SUBSCRIPTION_REF);
        int customerRef = ((Number) execution.getVariable(ProcessVariables.CUSTOMER_REF)).intValue();
        String startDate = (String) execution.getVariable(ProcessVariables.REQUESTED_START_DATE);
        BigDecimal grossHuf = toBigDecimal(execution.getVariable(ProcessVariables.MONTHLY_FEE_HUF));

        if (grossHuf == null) {
            throw new BpmnError("FEE_UNKNOWN",
                    "order " + orderNo + " has no monthly fee; validation should have resolved one");
        }

        // one calendar month from the requested start date, in activation's own date format
        LocalDate periodStart = ActivationSemantics.parseOrderDate(startDate);
        LocalDate periodEnd = periodStart.plusMonths(1).minusDays(1);

        var invoice = billing.createInvoice(customerRef, subscriptionRef,
                startDate, ActivationSemantics.formatOrderDate(periodEnd),
                grossHuf, orderNo,
                (String) execution.getVariable("customerName"),
                (String) execution.getVariable("customerEmail"));

        execution.setVariable(ProcessVariables.INVOICE_REF, invoice.invoiceNo());
        orders.updateInvoiceRef(orderNo, invoice.invoiceNo());
        log.info("order {}: invoice {} on {} for {} {} gross (catalog quoted {} gross)", orderNo,
                invoice.invoiceNo(), invoice.billingAccountNo(), invoice.grossAmount(),
                invoice.currency(), grossHuf);
    }

    /**
     * Flowable stores a {@code BigDecimal} variable as a string, so it can come back as either.
     */
    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : new BigDecimal(text);
    }
}

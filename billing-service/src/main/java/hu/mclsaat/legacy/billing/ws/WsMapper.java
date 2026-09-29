package hu.mclsaat.legacy.billing.ws;

import hu.mclsaat.legacy.billing.domain.InvoiceRow;
import hu.mclsaat.legacy.billing.domain.PaymentBatchRow;
import hu.mclsaat.legacy.billing.service.BillingException;
import hu.mclsaat.legacy.billing.ws.gen.InvoiceType;
import hu.mclsaat.legacy.billing.ws.gen.PaymentBatchType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Row to wire mapping.
 *
 * <p>Dates go out as {@code yyyy-MM-dd} and timestamps as ISO-8601 strings. The activation
 * subsystem uses bare {@code yyyyMMdd} for the same dates, so somebody has to reformat; that
 * somebody is whoever calls this service.
 */
@Component
public class WsMapper {

    /** Billing's date format on the wire. Not the activation subsystem's. */
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public InvoiceType toWs(InvoiceRow row, int customerRef) {
        InvoiceType out = new InvoiceType();
        out.setInvoiceNo(row.invoiceNo());
        out.setBillingAccountNo(row.baNo());
        out.setCustomerRef(customerRef);
        out.setSubscriptionRef(row.subscriptionRef());
        out.setPeriodStart(row.periodStart().format(DATE));
        out.setPeriodEnd(row.periodEnd().format(DATE));
        out.setNetAmount(row.netAmount());
        out.setVatRate(row.vatRate());
        out.setVatAmount(row.vatAmount());
        out.setGrossAmount(row.grossAmount());
        out.setCurrency(row.currency());
        out.setStatus(row.status());
        out.setPaymentRef(row.paymentRef());
        out.setBatchId(row.batchId());
        out.setIssuedTs(row.issuedTs().toString());
        return out;
    }

    public PaymentBatchType toWs(PaymentBatchRow row) {
        PaymentBatchType out = new PaymentBatchType();
        out.setBatchId(row.batchId());
        out.setFileName(row.fileName());
        out.setStatus(row.status());
        out.setItemCount(row.itemCount());
        out.setTotalAmount(row.totalAmount());
        out.setCurrency(row.currency());
        out.setCreatedTs(row.createdTs().toString());
        out.setSentTs(row.sentTs() == null ? null : row.sentTs().toString());
        out.setAckedTs(row.ackedTs() == null ? null : row.ackedTs().toString());
        out.setAckFileName(row.ackFileName());
        Instant reference = row.sentTs() == null ? row.createdTs() : row.sentTs();
        out.setAgeMinutes(Duration.between(reference, Instant.now()).toMinutes());
        return out;
    }

    /** Parses billing's {@code yyyy-MM-dd}, refusing anything else with a SOAP fault. */
    public LocalDate parseDate(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new BillingException.BadRequest(field + " is required (yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(value.trim(), DATE);
        } catch (DateTimeParseException ex) {
            throw new BillingException.BadRequest(
                    field + " must be formatted yyyy-MM-dd, was: " + value);
        }
    }
}

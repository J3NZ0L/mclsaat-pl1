package hu.mclsaat.legacy.billing.service;

import hu.mclsaat.legacy.billing.domain.BillingAccountRow;
import hu.mclsaat.legacy.billing.domain.InvoiceRow;
import hu.mclsaat.legacy.billing.repo.BillingAccountRepository;
import hu.mclsaat.legacy.billing.repo.InvoiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/** Invoice issuing and querying: the business core of subsystem 3. */
@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    private final BillingAccountRepository accounts;
    private final InvoiceRepository invoices;
    private final VatCalculator vat;

    public InvoiceService(BillingAccountRepository accounts, InvoiceRepository invoices, VatCalculator vat) {
        this.accounts = accounts;
        this.invoices = invoices;
        this.vat = vat;
    }

    /**
     * Issues an invoice for a net amount, adding VAT.
     *
     * <p>Idempotent on {@code requestRef}: the activation process is driven by Flowable's async
     * job executor, which retries, so the same invoice request can legitimately arrive twice.
     * A second call with the same {@code requestRef} returns the invoice that already exists
     * rather than issuing another one.
     */
    @Transactional
    public CreateResult createInvoice(int customerRef, String subscriptionRef, LocalDate periodStart,
                                     LocalDate periodEnd, BigDecimal netAmount, String currency,
                                     String requestRef, String accountNameIfNew, String billingEmailIfNew) {
        if (netAmount == null || netAmount.signum() < 0) {
            throw new BillingException.BadRequest("netAmount must be present and non-negative");
        }
        if (periodEnd.isBefore(periodStart)) {
            throw new BillingException.BadRequest("periodEnd must not be before periodStart");
        }
        if (requestRef != null && !requestRef.isBlank()) {
            var existing = invoices.findByRequestRef(requestRef);
            if (existing.isPresent()) {
                log.info("createInvoice: requestRef {} already produced invoice {}, returning it",
                        requestRef, existing.get().invoiceNo());
                return new CreateResult(existing.get(), true);
            }
        }

        BillingAccountRow account = accounts.findByCustomerRef(customerRef)
                .orElseGet(() -> openAccount(customerRef, accountNameIfNew, billingEmailIfNew, currency));

        BigDecimal net = netAmount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal vatAmount = vat.vatOf(net);
        BigDecimal gross = net.add(vatAmount);

        InvoiceRow invoice = invoices.insert(account.baNo(), subscriptionRef, periodStart, periodEnd,
                net, vat.vatRate(), vatAmount, gross, currency, blankToNull(requestRef));
        log.info("issued invoice {} for {} ({} net + {} VAT = {} {})", invoice.invoiceNo(), account.baNo(),
                net, vatAmount, gross, currency);
        return new CreateResult(invoice, false);
    }

    public List<InvoiceRow> findByBillingAccount(String baNo, String status, String subscriptionRef) {
        accounts.findByBaNo(baNo)
                .orElseThrow(() -> new BillingException.NotFound("billing account", baNo));
        return invoices.search(baNo, status, subscriptionRef);
    }

    public List<InvoiceRow> findByCustomerRef(int customerRef, String status, String subscriptionRef) {
        BillingAccountRow account = accounts.findByCustomerRef(customerRef)
                .orElseThrow(() -> new BillingException.NotFound("billing account for customerRef",
                        String.valueOf(customerRef)));
        return invoices.search(account.baNo(), status, subscriptionRef);
    }

    public InvoiceRow requireInvoice(String invoiceNo) {
        return invoices.findByInvoiceNo(invoiceNo)
                .orElseThrow(() -> new BillingException.NotFound("invoice", invoiceNo));
    }

    public BillingAccountRow requireAccount(String baNo) {
        return accounts.findByBaNo(baNo)
                .orElseThrow(() -> new BillingException.NotFound("billing account", baNo));
    }

    private BillingAccountRow openAccount(int customerRef, String accountName, String billingEmail,
                                          String currency) {
        if (accountName == null || accountName.isBlank()) {
            throw new BillingException.BadRequest("customerRef " + customerRef
                    + " has no billing account; supply accountName and billingEmail to open one");
        }
        String email = billingEmail == null || billingEmail.isBlank()
                ? "billing+" + customerRef + "@example.invalid"
                : billingEmail;
        BillingAccountRow opened = accounts.insert(customerRef, accountName, email,
                currency == null || currency.isBlank() ? "HUF" : currency);
        log.info("opened billing account {} for customerRef {}", opened.baNo(), customerRef);
        return opened;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** {@code alreadyExisted} tells the caller its retry was recognised rather than acted on. */
    public record CreateResult(InvoiceRow invoice, boolean alreadyExisted) {
    }
}

package hu.mclsaat.legacy.billing.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * VAT arithmetic, kept in one place because it is the exact spot where the semantic mismatch
 * between the catalog and billing turns into money.
 *
 * <p>The catalog quotes consumer prices, which in Hungary are gross. Billing invoices on net
 * amounts and adds VAT itself. So a caller that starts from a catalog price has to divide by
 * {@code 1 + rate} before it calls {@code CreateInvoice}, and both sides round to two decimals
 * independently. The result does not always come back to the price the customer was shown:
 * 5990.00 gross becomes a 4716.54 net, which grosses back up to 5990.01.
 *
 * <p>That one fillér is not a bug to be fixed here. It is the measurable cost of two subsystems
 * disagreeing about what "the price" means, and it is exactly the kind of thing the MCP layer
 * in phase 2 will have to decide who owns.
 */
@Component
public class VatCalculator {

    private final BigDecimal vatRate;

    public VatCalculator(@Value("${billing.vat-rate:0.27}") BigDecimal vatRate) {
        this.vatRate = vatRate.setScale(4, RoundingMode.UNNECESSARY);
    }

    public BigDecimal vatRate() {
        return vatRate;
    }

    /** VAT on a net amount, rounded half-up to two decimals. */
    public BigDecimal vatOf(BigDecimal netAmount) {
        return netAmount.multiply(vatRate).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal grossOf(BigDecimal netAmount) {
        return netAmount.setScale(2, RoundingMode.HALF_UP).add(vatOf(netAmount));
    }

    /**
     * The conversion a gross-priced caller has to perform before it can talk to billing.
     * Exposed here so the demo and the tests can show both directions of the same mismatch.
     */
    public BigDecimal netFromGross(BigDecimal grossAmount) {
        return grossAmount.divide(BigDecimal.ONE.add(vatRate), 2, RoundingMode.HALF_UP);
    }
}

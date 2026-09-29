package hu.mclsaat.legacy.billing;

import hu.mclsaat.legacy.billing.service.VatCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the arithmetic at the seam between a gross-priced catalog and a net-invoicing billing
 * system, including the one-fillér round trip error. The error is asserted, not fixed: it is
 * the measurable cost of the semantic mismatch and phase 2 has to decide who owns it.
 */
class VatCalculatorTest {

    private final VatCalculator vat = new VatCalculator(new BigDecimal("0.2700"));

    @Test
    void vatAndGrossAreRoundedHalfUpToTwoDecimals() {
        BigDecimal net = new BigDecimal("10228.35");
        assertThat(vat.vatOf(net)).isEqualByComparingTo("2761.65");
        assertThat(vat.grossOf(net)).isEqualByComparingTo("12990.00");
    }

    @Test
    void grossToNetIsTheConversionEveryCatalogPricedCallerHasToDo() {
        // 12 990 Ft consumer price, stored in the catalog as 1299000 fillér
        assertThat(vat.netFromGross(new BigDecimal("12990.00"))).isEqualByComparingTo("10228.35");
        // 5 990 Ft, stored as 599000 fillér
        assertThat(vat.netFromGross(new BigDecimal("5990.00"))).isEqualByComparingTo("4716.54");
    }

    @Test
    void oneCatalogPriceGainsAFillerOnTheWayThroughBilling() {
        BigDecimal catalogGross = new BigDecimal("5990.00");
        BigDecimal net = vat.netFromGross(catalogGross);
        BigDecimal grossAgain = vat.grossOf(net);

        // one fillér more than the price the customer was quoted
        assertThat(grossAgain).isEqualByComparingTo("5990.01");
        assertThat(grossAgain.subtract(catalogGross)).isEqualByComparingTo("0.01");
    }

    /**
     * The whole seeded catalog, priced in fillér, put through gross -> net -> gross.
     *
     * <p>Seven of the nine plans come back to the price the customer was quoted and two do not:
     * Mobil Alap 10GB gains a fillér and Fibernet 1000 loses one. This table is the executable
     * version of the money row in docs/semantic-mismatches.md, so if anyone ever "fixes" the
     * rounding the documentation stops lying the same day.
     */
    @Test
    void theRoundTripErrorAcrossTheWholeSeededCatalogIsExactlyThis() {
        assertRoundTripDelta("ADDON-DATA-0005",   149_000L, "0.00");
        assertRoundTripDelta("ADDON-DATA-0010",   249_000L, "0.00");
        assertRoundTripDelta("ADDON-ROAM-EU01",   399_000L, "0.00");
        assertRoundTripDelta("INET-ADSL-0030",    699_000L, "0.00");
        assertRoundTripDelta("INET-FIB-0500",   1_299_000L, "0.00");
        assertRoundTripDelta("INET-FIB-1000",   1_799_000L, "-0.01");
        assertRoundTripDelta("MOB-VOICE-0002",    349_000L, "0.00");
        assertRoundTripDelta("MOB-VOICE-0010",    599_000L, "0.01");
        assertRoundTripDelta("MOB-VOICE-0050",    999_000L, "0.00");
    }

    /**
     * @param monthlyFeeMinor the catalog's {@code monthly_fee_minor}, an integer count of fillér
     * @param expectedDelta   what the caller loses or gains by routing the price through billing
     */
    private void assertRoundTripDelta(String planCode, long monthlyFeeMinor, String expectedDelta) {
        BigDecimal catalogGross = BigDecimal.valueOf(monthlyFeeMinor, 2);
        BigDecimal grossAgain = vat.grossOf(vat.netFromGross(catalogGross));
        assertThat(grossAgain.subtract(catalogGross))
                .as("round-trip delta for %s (%d fillér)", planCode, monthlyFeeMinor)
                .isEqualByComparingTo(expectedDelta);
    }

    @Test
    void vatRateIsExposedAtFourDecimalsBecauseThatIsHowTheColumnStoresIt() {
        assertThat(vat.vatRate().scale()).isEqualTo(4);
        assertThat(vat.vatRate()).isEqualByComparingTo("0.27");
    }
}

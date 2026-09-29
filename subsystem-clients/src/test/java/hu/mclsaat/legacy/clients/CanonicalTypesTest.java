package hu.mclsaat.legacy.clients;

import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CustomerRef;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.PhoneNumber;
import hu.mclsaat.legacy.clients.canonical.DataVolume;
import hu.mclsaat.legacy.clients.canonical.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The canonical value types exist to make the landscape's disagreements impossible to express
 * accidentally. These tests are mostly about what they <em>refuse</em>.
 */
class CanonicalTypesTest {

    // ---------------------------------------------------------------------- Money

    @Test
    void moneyAcceptsAllFourRepresentationsOfTheSameFigure() {
        // the catalog's monthly_fee_minor
        Money fromCatalog = Money.ofMinorUnits(599_000L, "HUF");
        // activation's monthly_fee_huf, and billing's NUMERIC(12,2)
        Money fromDecimal = Money.ofMajorUnits(new BigDecimal("5990.00"), "HUF");

        assertThat(fromCatalog).isEqualTo(fromDecimal);
        // and back out again for Stripe and for the settlement file
        assertThat(fromCatalog.minorUnits()).isEqualTo(599_000L);
        assertThat(fromCatalog.toString()).isEqualTo("5990.00 HUF");
    }

    @Test
    void moneyIsAlwaysTwoDecimalsSoTheScaleCannotDriftBetweenSubsystems() {
        assertThat(Money.ofMajorUnits(new BigDecimal("5990"), "HUF").amount().scale()).isEqualTo(2);
        assertThat(Money.ofMajorUnits(new BigDecimal("5990.000"), "HUF").amount().scale()).isEqualTo(2);
        assertThat(Money.ofMinorUnits(0L, "HUF")).isEqualTo(Money.zero("HUF"));
    }

    @Test
    void mixingCurrenciesIsRefusedRatherThanQuietlyAdded() {
        Money huf = Money.ofMajorUnits(new BigDecimal("100.00"), "HUF");
        Money eur = Money.ofMajorUnits(new BigDecimal("100.00"), "EUR");

        assertThatThrownBy(() -> huf.plus(eur))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot combine HUF with EUR");
        assertThatThrownBy(() -> huf.compareTo(eur)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFractionOfAMinorUnitCannotSurviveIntoTheSettlementFile() {
        // the settlement file and Stripe both take integers; silently rounding here would move money
        assertThatThrownBy(() -> Money.ofMinorUnits(1L, "HUF")
                .plus(new Money(new BigDecimal("0.005"), "HUF")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void anInvalidCurrencyIsRefused() {
        assertThatThrownBy(() -> new Money(BigDecimal.ONE.setScale(2), "FORINT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO 4217");
    }

    // ----------------------------------------------------------------- DataVolume

    @Test
    void megabytesAndGigabytesAreTheSameVolumeAtAFactorOf1024() {
        assertThat(DataVolume.ofMegabytes(10_240).gigabytes()).isEqualByComparingTo("10.000");
        assertThat(DataVolume.ofGigabytes(new BigDecimal("10.000")).megabytes()).isEqualTo(10_240L);
        assertThat(DataVolume.ofMegabytes(51_200).gigabytes()).isEqualByComparingTo("50.000");
        assertThat(DataVolume.ofGigabytes(new BigDecimal("1.000")).megabytes()).isEqualTo(1_024L);
    }

    @Test
    void unmeteredIsAStateAndNotANumber() {
        DataVolume unmetered = DataVolume.unmetered();

        assertThat(unmetered.isUnmetered()).isTrue();
        assertThat(unmetered.gigabytes()).isNull();
        // the catalog's sentinel is produced on the way out, never held internally
        assertThat(unmetered.catalogMegabytes()).isEqualTo(-1);
        assertThat(unmetered.toString()).isEqualTo("unmetered");
        assertThat(DataVolume.ofGigabytes(null)).isEqualTo(unmetered);
    }

    @Test
    void theCatalogsMinusOneIsNeverTreatedAsAVolume() {
        assertThatThrownBy(() -> DataVolume.ofMegabytes(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unmetered()");
    }

    @Test
    void addingToAnUnmeteredAllowanceLeavesItUnmetered() {
        // the catalog's rule: an add-on cannot make an unlimited plan limited
        assertThat(DataVolume.unmetered().plus(DataVolume.ofMegabytes(5_120)).isUnmetered()).isTrue();
        assertThat(DataVolume.ofMegabytes(5_120).plus(DataVolume.unmetered()).isUnmetered()).isTrue();
        // 10 GB base + 5 GB add-on, as the catalog computes it
        assertThat(DataVolume.ofMegabytes(10_240).plus(DataVolume.ofMegabytes(5_120)).megabytes())
                .isEqualTo(15_360L);
    }

    // ---------------------------------------------------------------- CustomerRef

    @Test
    void oneCustomerReferenceProducesTheCatalogsSpelling() {
        assertThat(CustomerRef.of(42).catalogCustNo()).isEqualTo("00000042");
        assertThat(CustomerRef.of(101).catalogCustNo()).isEqualTo("00000101");
        assertThat(CustomerRef.of(42).billingAccountNo()).isNull();
        assertThat(CustomerRef.of(42).withBillingAccount("BA-00042").billingAccountNo())
                .isEqualTo("BA-00042");
    }

    @Test
    void aCustomerReferenceThatCannotBeSpeltInTheCatalogIsRefused() {
        assertThatThrownBy(() -> CustomerRef.of(100_000_000).catalogCustNo())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CHAR(8)");
        assertThatThrownBy(() -> CustomerRef.of(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theBillingAccountIsNotPartOfIdentityBecauseItIsALookup() {
        // two records for the same customer differ only by what billing happened to tell us
        assertThat(CustomerRef.of(42).reference())
                .isEqualTo(CustomerRef.of(42).withBillingAccount("BA-00042").reference());
    }

    // ---------------------------------------------------------------- PhoneNumber

    @Test
    void thePlusSignIsPresentationAndNotIdentity() {
        PhoneNumber fromCatalog = PhoneNumber.of("36301234567");
        PhoneNumber fromActivation = PhoneNumber.of("+36301234567");

        assertThat(fromCatalog).isEqualTo(fromActivation);
        assertThat(fromCatalog.withoutPlus()).isEqualTo("36301234567");   // the catalog's column
        assertThat(fromCatalog.withPlus()).isEqualTo("+36301234567");     // activation's column
    }

    @Test
    void aNumberNeitherSubsystemWouldAcceptIsRefused() {
        assertThatThrownBy(() -> PhoneNumber.of("+36-30/123-4567"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("E.164");
        assertThat(PhoneNumber.of(null)).isNull();
        assertThat(PhoneNumber.of("   ")).isNull();
    }
}

package hu.mclsaat.legacy.activation;

import hu.mclsaat.legacy.activation.semantics.ActivationSemantics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The executable version of the semantic-mismatch table.
 *
 * <p>Every assertion here is a rule that exists only because two subsystems disagree, and that
 * somebody - today this class, in phase 2 an MCP server - has to know. The count is the point.
 */
class ActivationSemanticsTest {

    // ------------------------------------------------------------------ identity

    @Test
    void customerNumbersGainAndLoseTheirZeroPadding() {
        assertThat(ActivationSemantics.toCatalogCustNo(42)).isEqualTo("00000042");
        assertThat(ActivationSemantics.toCatalogCustNo(101)).isEqualTo("00000101");
        assertThat(ActivationSemantics.toCatalogCustNo(99_999_999)).isEqualTo("99999999");

        assertThat(ActivationSemantics.fromCatalogCustNo("00000042")).isEqualTo(42);
        // PostgreSQL pads CHAR(8) on read, so the trailing spaces have to be tolerated
        assertThat(ActivationSemantics.fromCatalogCustNo("00000042  ")).isEqualTo(42);
    }

    @Test
    void aCustomerRefThatCannotFitTheCatalogsColumnIsRefused() {
        assertThatThrownBy(() -> ActivationSemantics.toCatalogCustNo(100_000_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CHAR(8)");
        assertThatThrownBy(() -> ActivationSemantics.toCatalogCustNo(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------- product

    @Test
    void offerIdsAndTariffCodesAreTheSameProductSpeltDifferently() {
        assertThat(ActivationSemantics.toCatalogPlanCode("mob.voice.0010")).isEqualTo("MOB-VOICE-0010");
        assertThat(ActivationSemantics.toCatalogPlanCode("inet.fib.0500")).isEqualTo("INET-FIB-0500");
        assertThat(ActivationSemantics.toCatalogPlanCode("addon.roam.eu01")).isEqualTo("ADDON-ROAM-EU01");

        assertThat(ActivationSemantics.toOfferId("MOB-VOICE-0010")).isEqualTo("mob.voice.0010");
        assertThat(ActivationSemantics.toOfferId("ADDON-DATA-0005")).isEqualTo("addon.data.0005");
    }

    @Test
    void theProductNamingRoundTripsBothWaysForEverySeededPlan() {
        for (String planCode : new String[]{"INET-FIB-0500", "INET-FIB-1000", "INET-ADSL-0030",
                "MOB-VOICE-0010", "MOB-VOICE-0050", "MOB-VOICE-0002",
                "ADDON-DATA-0005", "ADDON-DATA-0010", "ADDON-ROAM-EU01"}) {
            String offerId = ActivationSemantics.toOfferId(planCode);
            assertThat(ActivationSemantics.toCatalogPlanCode(offerId))
                    .as("round trip of %s via %s", planCode, offerId)
                    .isEqualTo(planCode);
        }
    }

    // ---------------------------------------------------------------------- data

    @Test
    void megabytesBecomeGigabytesAndTheUnmeteredSentinelBecomesNothing() {
        assertThat(ActivationSemantics.toGigabytes(10_240)).isEqualByComparingTo("10.000");
        assertThat(ActivationSemantics.toGigabytes(51_200)).isEqualByComparingTo("50.000");
        assertThat(ActivationSemantics.toGigabytes(5_120)).isEqualByComparingTo("5.000");
        assertThat(ActivationSemantics.toGigabytes(3_072)).isEqualByComparingTo("3.000");
        // the catalog says -1 for unmetered; activation says nothing at all
        assertThat(ActivationSemantics.toGigabytes(-1)).isNull();
        assertThat(ActivationSemantics.toGigabytes(0)).isEqualByComparingTo("0.000");
    }

    @Test
    void gigabytesGoBackToMegabytesIncludingTheSentinel() {
        assertThat(ActivationSemantics.toMegabytes(new BigDecimal("10.000"))).isEqualTo(10_240);
        assertThat(ActivationSemantics.toMegabytes(new BigDecimal("15.000"))).isEqualTo(15_360);
        assertThat(ActivationSemantics.toMegabytes(null)).isEqualTo(-1);
    }

    @Test
    void aThousandIsNotAThousandTwentyFour() {
        // The whole reason this conversion is worth a test: an agent guessing 1000 MB per GB would
        // sell a customer 24 MB less per gigabyte and nothing would ever fail loudly.
        assertThat(ActivationSemantics.toMegabytes(new BigDecimal("1.000"))).isEqualTo(1_024);
        assertThat(ActivationSemantics.toGigabytes(1_000)).isEqualByComparingTo("0.977");
    }

    // --------------------------------------------------------------------- money

    @Test
    void fillerIntegersBecomeHufDecimals() {
        assertThat(ActivationSemantics.toHuf(599_000L)).isEqualByComparingTo("5990.00");
        assertThat(ActivationSemantics.toHuf(1_299_000L)).isEqualByComparingTo("12990.00");
        assertThat(ActivationSemantics.toHuf(149_000L)).isEqualByComparingTo("1490.00");
    }

    @Test
    void grossBecomesTheNetAmountBillingWants() {
        assertThat(ActivationSemantics.toBillingNet(new BigDecimal("12990.00")))
                .isEqualByComparingTo("10228.35");
        assertThat(ActivationSemantics.toBillingNet(new BigDecimal("5990.00")))
                .isEqualByComparingTo("4716.54");
        assertThat(ActivationSemantics.toBillingNet(new BigDecimal("6990.00")))
                .isEqualByComparingTo("5503.94");
    }

    @Test
    void theCatalogPriceTravelsThroughThreeRepresentationsToReachBilling() {
        // MOB-VOICE-0010: 599000 fillér in the catalog, 5990.00 HUF here, 4716.54 net at billing,
        // which billing grosses back up to 5990.01 - one fillér more than the customer was quoted.
        long catalogMinor = 599_000L;
        BigDecimal activationGross = ActivationSemantics.toHuf(catalogMinor);
        BigDecimal billingNet = ActivationSemantics.toBillingNet(activationGross);

        assertThat(activationGross).isEqualByComparingTo("5990.00");
        assertThat(billingNet).isEqualByComparingTo("4716.54");
        assertThat(billingNet.multiply(new BigDecimal("1.27")).setScale(2, java.math.RoundingMode.HALF_UP))
                .isEqualByComparingTo("5990.01");
    }

    // ---------------------------------------------------------------------- date

    @Test
    void threeSubsystemsThreeDateFormats() {
        assertThat(ActivationSemantics.parseOrderDate("20260929")).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(ActivationSemantics.formatOrderDate(LocalDate.of(2026, 9, 29))).isEqualTo("20260929");
        // activation's yyyyMMdd straight to billing's yyyy-MM-dd
        assertThat(ActivationSemantics.toBillingDate("20260929")).isEqualTo("2026-09-29");
        assertThat(ActivationSemantics.toBillingDate("20261001")).isEqualTo("2026-10-01");
    }

    @Test
    void billingsOwnFormatIsNotAcceptedAsAnOrderDate() {
        assertThatThrownBy(() -> ActivationSemantics.parseOrderDate("2026-09-29"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyyMMdd");
    }

    // --------------------------------------------------------------------- phone

    @Test
    void thePlusSignIsAddedAndRemovedAtTheBoundary() {
        assertThat(ActivationSemantics.toCatalogMsisdn("+36301234567")).isEqualTo("36301234567");
        assertThat(ActivationSemantics.toActivationMsisdn("36301234567")).isEqualTo("+36301234567");
        // both directions are idempotent, because callers cannot be trusted to know which side
        // of the boundary they are on
        assertThat(ActivationSemantics.toCatalogMsisdn("36301234567")).isEqualTo("36301234567");
        assertThat(ActivationSemantics.toActivationMsisdn("+36301234567")).isEqualTo("+36301234567");
        assertThat(ActivationSemantics.toCatalogMsisdn(null)).isNull();
        assertThat(ActivationSemantics.toActivationMsisdn("  ")).isNull();
    }

    // -------------------------------------------------------------------- status

    @Test
    void theTwoStatusVocabulariesDoNotLineUpCleanly() {
        assertThat(ActivationSemantics.fromCatalogStatusCode("PA")).isEqualTo("AWAITING_PROVISIONING");
        assertThat(ActivationSemantics.fromCatalogStatusCode("AC")).isEqualTo("PROVISIONED");
        assertThat(ActivationSemantics.fromCatalogStatusCode("TE")).isEqualTo("CANCELLED");
        // the catalog has no word for STUCK, and activation has no word for SUSPENDED, so this
        // mapping leaks a catalog-only concept through under a made-up name
        assertThat(ActivationSemantics.fromCatalogStatusCode("SU")).isEqualTo("SUSPENDED_IN_CATALOG");
        assertThat(ActivationSemantics.fromCatalogStatusCode("ZZ")).isEqualTo("UNKNOWN(ZZ)");
        assertThat(ActivationSemantics.fromCatalogStatusCode(null)).isEqualTo("UNKNOWN(null)");
    }
}

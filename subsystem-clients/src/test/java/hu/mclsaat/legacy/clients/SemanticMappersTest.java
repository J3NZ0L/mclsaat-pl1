package hu.mclsaat.legacy.clients;

import hu.mclsaat.legacy.clients.canonical.CanonicalModel.BatchStatus;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.InvoiceStatus;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.OrderStatus;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.ProductKind;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.ServiceKind;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.SubscriptionStatus;
import hu.mclsaat.legacy.clients.mapping.SemanticMappers;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The canonical layer's translation rules.
 *
 * <p>Deliberately overlapping with {@code activation-service}'s {@code ActivationSemanticsTest}: the
 * same rules are implemented twice in this repository, once by the subsystem that grew its own
 * integration code and once by the canonical layer. See {@code docs/decision-log.md} DL-009 — the
 * duplication is the "before" picture for phase 2, and having both test suites is how we know the two
 * implementations actually agree today.
 */
class SemanticMappersTest {

    // ------------------------------------------------------------------ identity

    @Test
    void customerReferencesAndCatalogCustomerNumbersConvertBothWays() {
        assertThat(SemanticMappers.toCatalogCustNo(42)).isEqualTo("00000042");
        assertThat(SemanticMappers.fromCatalogCustNo("00000042")).isEqualTo(42);
        // PostgreSQL space-pads CHAR(8) on read
        assertThat(SemanticMappers.fromCatalogCustNo("00000042   ")).isEqualTo(42);
        assertThat(SemanticMappers.fromCatalogCustNo("101")).isEqualTo(101);
    }

    @Test
    void nonsenseCustomerNumbersAreRefusedRatherThanParsedLoosely() {
        assertThatThrownBy(() -> SemanticMappers.fromCatalogCustNo("BA-00042"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a catalog cust_no");
        assertThatThrownBy(() -> SemanticMappers.fromCatalogCustNo(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SemanticMappers.toCatalogCustNo(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theBillingAccountFormatIsAGuessAndIsNamedAsOne() {
        // the method name is the documentation: billing_account is a table, not a format
        assertThat(SemanticMappers.guessBillingAccountNo(42)).isEqualTo("BA-00042");
        assertThat(SemanticMappers.guessBillingAccountNo(101)).isEqualTo("BA-00101");
    }

    // ------------------------------------------------------------------- product

    @Test
    void tariffCodesAndOfferIdsAreTheSameProduct() {
        assertThat(SemanticMappers.toCatalogPlanCode("mob.voice.0010")).isEqualTo("MOB-VOICE-0010");
        assertThat(SemanticMappers.toOfferId("MOB-VOICE-0010")).isEqualTo("mob.voice.0010");

        for (String planCode : new String[]{"INET-FIB-0500", "INET-FIB-1000", "INET-ADSL-0030",
                "MOB-VOICE-0010", "MOB-VOICE-0050", "MOB-VOICE-0002",
                "ADDON-DATA-0005", "ADDON-DATA-0010", "ADDON-ROAM-EU01"}) {
            assertThat(SemanticMappers.toCatalogPlanCode(SemanticMappers.toOfferId(planCode)))
                    .as("round trip of %s", planCode)
                    .isEqualTo(planCode);
        }
    }

    // ---------------------------------------------------------------------- date

    @Test
    void threeSubsystemsThreeDateFormatsAndNoneToleratesAnother() {
        assertThat(SemanticMappers.fromActivationDate("20260929")).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(SemanticMappers.fromBillingDate("2026-09-29")).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(SemanticMappers.toActivationDate(LocalDate.of(2026, 9, 29))).isEqualTo("20260929");
        assertThat(SemanticMappers.toBillingDate(LocalDate.of(2026, 9, 29))).isEqualTo("2026-09-29");

        assertThatThrownBy(() -> SemanticMappers.fromActivationDate("2026-09-29"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyyMMdd");
        assertThatThrownBy(() -> SemanticMappers.fromBillingDate("20260929"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM-dd");
    }

    @Test
    void anAbsentDateIsAbsentRatherThanEpoch() {
        assertThat(SemanticMappers.fromActivationDate(null)).isNull();
        assertThat(SemanticMappers.fromBillingDate("")).isNull();
        assertThat(SemanticMappers.toActivationDate(null)).isNull();
    }

    // ------------------------------------------------------------------ subscription

    @Test
    void aCatalogSubscriptionKeepsItsRawStatusCodeAndShedsTheCharPadding() {
        var subscription = SemanticMappers.fromCatalogSubscription("SUB-2026-000001", "00000042  ",
                "MOB-VOICE-0050", "AC ", LocalDate.of(2026, 9, 29), "36209876543", "8936300000000000001",
                51200, null, java.time.Instant.parse("2026-10-06T08:15:30Z"));

        assertThat(subscription.customer().reference()).isEqualTo(42);
        assertThat(subscription.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription.catalogStatusCode()).isEqualTo("AC");
        assertThat(subscription.phoneNumber().withPlus()).isEqualTo("+36209876543");
        assertThat(subscription.dataAllowance().megabytes()).isEqualTo(51200L);
        assertThat(subscription.isAddon()).isFalse();
    }

    @Test
    void anUnrecognisedCatalogStatusOnASubscriptionIsUnknownNotTheNearestGuess() {
        var subscription = SemanticMappers.fromCatalogSubscription("SUB-2026-000002", "00000042",
                "MOB-VOICE-0050", "ZZ", null, null, null, 0, null, java.time.Instant.EPOCH);

        assertThat(subscription.status()).isEqualTo(SubscriptionStatus.UNKNOWN);
        assertThat(subscription.catalogStatusCode()).isEqualTo("ZZ");
    }

    @Test
    void theCatalogsMinusOneAllowanceIsUnmeteredAndNeverANegativeVolume() {
        assertThat(SemanticMappers.fromCatalogAllowanceMb(-1).isUnmetered()).isTrue();
        assertThat(SemanticMappers.fromCatalogAllowanceMb(0).megabytes()).isEqualTo(0L);
        assertThat(SemanticMappers.fromCatalogAllowanceMb(5120).megabytes()).isEqualTo(5120L);
        assertThatThrownBy(() -> SemanticMappers.fromCatalogAllowanceMb(-2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------- status

    @Test
    void theCatalogsTwoCharacterCodesMapOntoTheCanonicalLifecycle() {
        assertThat(SemanticMappers.fromCatalogStatusCode("NW")).isEqualTo(SubscriptionStatus.NEW);
        assertThat(SemanticMappers.fromCatalogStatusCode("PA"))
                .isEqualTo(SubscriptionStatus.PENDING_ACTIVATION);
        assertThat(SemanticMappers.fromCatalogStatusCode("AC")).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(SemanticMappers.fromCatalogStatusCode("SU")).isEqualTo(SubscriptionStatus.SUSPENDED);
        assertThat(SemanticMappers.fromCatalogStatusCode("TE")).isEqualTo(SubscriptionStatus.TERMINATED);
        // PostgreSQL pads CHAR(2) too
        assertThat(SemanticMappers.fromCatalogStatusCode("AC ")).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void everyCatalogStatusRoundTripsExceptTheOneThatMeansWeDoNotKnow() {
        for (SubscriptionStatus status : SubscriptionStatus.values()) {
            if (status == SubscriptionStatus.UNKNOWN) {
                continue;
            }
            assertThat(SemanticMappers.fromCatalogStatusCode(
                    SemanticMappers.toCatalogStatusCode(status))).isEqualTo(status);
        }
        assertThatThrownBy(() -> SemanticMappers.toCatalogStatusCode(SubscriptionStatus.UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnrecognisedStatusBecomesUnknownAndIsNeverGuessedAt() {
        // a plausible-looking wrong answer is worse than an obviously unhandled one
        assertThat(SemanticMappers.fromCatalogStatusCode("XX")).isEqualTo(SubscriptionStatus.UNKNOWN);
        assertThat(SemanticMappers.fromCatalogStatusCode(null)).isEqualTo(SubscriptionStatus.UNKNOWN);
        assertThat(SemanticMappers.fromActivationStatus("SOMETHING_NEW")).isEqualTo(OrderStatus.UNKNOWN);
        assertThat(SemanticMappers.fromBillingInvoiceStatus("WRITTEN_OFF"))
                .isEqualTo(InvoiceStatus.UNKNOWN);
        assertThat(SemanticMappers.fromBillingBatchStatus("PARTIAL")).isEqualTo(BatchStatus.UNKNOWN);
    }

    @Test
    void activationsOwnVocabularyIncludesAStateNoOtherSubsystemHas() {
        assertThat(SemanticMappers.fromActivationStatus("AWAITING_PROVISIONING"))
                .isEqualTo(OrderStatus.AWAITING_PROVISIONING);
        // STUCK exists only here: the catalog has no word for it, which is failure branch A
        assertThat(SemanticMappers.fromActivationStatus("STUCK")).isEqualTo(OrderStatus.STUCK);
        assertThat(SemanticMappers.fromActivationStatus("PROVISIONED"))
                .isEqualTo(OrderStatus.PROVISIONED);
    }

    @Test
    void billingsVocabularyIncludesAnotherStateNoOtherSubsystemHas() {
        // SETTLEMENT_PENDING exists only here, and is failure branch B
        assertThat(SemanticMappers.fromBillingInvoiceStatus("SETTLEMENT_PENDING"))
                .isEqualTo(InvoiceStatus.SETTLEMENT_PENDING);
        assertThat(SemanticMappers.fromBillingInvoiceStatus("PAID")).isEqualTo(InvoiceStatus.PAID);
        assertThat(SemanticMappers.toBillingInvoiceStatus(InvoiceStatus.SETTLEMENT_PENDING))
                .isEqualTo("SETTLEMENT_PENDING");
    }

    @Test
    void billingsAbbreviatedBatchStatusIsSpeltOutInTheCanonicalModel() {
        // billing says ACKED; the canonical name is ACKNOWLEDGED
        assertThat(SemanticMappers.fromBillingBatchStatus("ACKED"))
                .isEqualTo(BatchStatus.ACKNOWLEDGED);
        assertThat(SemanticMappers.fromBillingBatchStatus("SENT")).isEqualTo(BatchStatus.SENT);
    }

    // -------------------------------------------------------------- kinds, flags

    @Test
    void theCatalogsSingleCharacterServiceKindConvertsBothWays() {
        assertThat(SemanticMappers.fromCatalogServiceKind("I")).isEqualTo(ServiceKind.INTERNET);
        assertThat(SemanticMappers.fromCatalogServiceKind("M")).isEqualTo(ServiceKind.MOBILE);
        assertThat(SemanticMappers.fromCatalogServiceKind("X")).isEqualTo(ServiceKind.UNKNOWN);
        assertThat(SemanticMappers.toCatalogServiceKind(ServiceKind.MOBILE)).isEqualTo("M");
        assertThatThrownBy(() -> SemanticMappers.toCatalogServiceKind(ServiceKind.UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void planKindsConvert() {
        assertThat(SemanticMappers.fromCatalogPlanKind("BASE")).isEqualTo(ProductKind.BASE);
        assertThat(SemanticMappers.fromCatalogPlanKind("ADDON")).isEqualTo(ProductKind.ADDON);
        assertThat(SemanticMappers.fromCatalogPlanKind("BUNDLE")).isEqualTo(ProductKind.UNKNOWN);
    }

    @Test
    void onlyAnExactYIsTrueBecauseAnythingLooserWouldPutAWithdrawnPlanBackOnSale() {
        assertThat(SemanticMappers.fromCatalogFlag("Y")).isTrue();
        assertThat(SemanticMappers.fromCatalogFlag("N")).isFalse();
        assertThat(SemanticMappers.fromCatalogFlag("y")).isFalse();
        assertThat(SemanticMappers.fromCatalogFlag("true")).isFalse();
        assertThat(SemanticMappers.fromCatalogFlag("1")).isFalse();
        assertThat(SemanticMappers.fromCatalogFlag(null)).isFalse();
        assertThat(SemanticMappers.toCatalogFlag(true)).isEqualTo("Y");
        assertThat(SemanticMappers.toCatalogFlag(false)).isEqualTo("N");
    }
}

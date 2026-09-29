package hu.mclsaat.legacy.catalog;

import hu.mclsaat.legacy.catalog.domain.PlanRow;
import hu.mclsaat.legacy.catalog.domain.SubscriptionRow;
import hu.mclsaat.legacy.catalog.repo.PlanRepository;
import hu.mclsaat.legacy.catalog.repo.SubscriberRepository;
import hu.mclsaat.legacy.catalog.repo.SubscriptionRepository;
import hu.mclsaat.legacy.catalog.service.CatalogException;
import hu.mclsaat.legacy.catalog.service.SubscriptionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubscriptionRegistryIT extends PostgresIntegrationTest {

    @Autowired SubscriptionRegistry registry;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired SubscriberRepository subscribers;
    @Autowired PlanRepository plans;

    @Test
    void seedDataIsLoadedWithItsLegacyShape() {
        var plan = plans.findByCode("MOB-VOICE-0010").orElseThrow();
        // 5 990 Ft stored as 599000 fillér, 10 GB stored as 10240 MB
        assertThat(plan.monthlyFeeMinor()).isEqualTo(599_000L);
        assertThat(plan.dataAllowanceMb()).isEqualTo(10_240);
        assertThat(plan.serviceKind()).isEqualTo('M');

        var fibre = plans.findByCode("INET-FIB-0500").orElseThrow();
        assertThat(fibre.dataAllowanceMb()).isEqualTo(PlanRow.UNMETERED);
        assertThat(fibre.speedKbps()).isEqualTo(500_000);

        // CHAR(8) is space-padded by PostgreSQL on read; the repository has to trim it
        var subscriber = subscribers.findByCustNo("00000042").orElseThrow();
        assertThat(subscriber.custNo()).isEqualTo("00000042");
        assertThat(subscriber.msisdn()).isEqualTo("36301234567");
    }

    @Test
    void withdrawnPlansAreHiddenUnlessAskedFor() {
        assertThat(plans.search(null, null, null, false))
                .extracting(PlanRow::planCode)
                .doesNotContain("MOB-VOICE-0002");
        assertThat(plans.search(null, null, null, true))
                .extracting(PlanRow::planCode)
                .contains("MOB-VOICE-0002");
    }

    @Test
    void happyPathReserveThenActivate() {
        var reserved = registry.reserveBaseSubscription("00000043", "MOB-VOICE-0050", "36209876543");
        assertThat(reserved.subId()).matches("SUB-\\d{4}-\\d{6}");
        assertThat(reserved.statusCode()).isEqualTo(SubscriptionRow.STATUS_PENDING_ACTIVATION);
        assertThat(reserved.activatedOn()).isNull();
        assertThat(reserved.dataAllowanceMb()).isEqualTo(51_200);

        var activated = registry.activate(reserved.subId(), "8936010099999999901", LocalDate.of(2026, 9, 29));
        assertThat(activated.statusCode()).isEqualTo(SubscriptionRow.STATUS_ACTIVE);
        assertThat(activated.simIccid()).isEqualTo("8936010099999999901");
        assertThat(activated.activatedOn()).isEqualTo(LocalDate.of(2026, 9, 29));
    }

    @Test
    void activationIsIdempotentSoAReplayedJobCannotCorruptTheRow() {
        var reserved = registry.reserveBaseSubscription("00000043", "MOB-VOICE-0010", "36209876543");
        var first = registry.activate(reserved.subId(), "8936010011111111101", LocalDate.of(2026, 9, 1));
        var second = registry.activate(reserved.subId(), "8936010022222222202", LocalDate.of(2026, 9, 2));

        assertThat(second.statusCode()).isEqualTo(SubscriptionRow.STATUS_ACTIVE);
        assertThat(second.simIccid()).isEqualTo(first.simIccid());
        assertThat(second.activatedOn()).isEqualTo(first.activatedOn());
    }

    @Test
    void addonGrowsTheParentsEffectiveAllowanceOnlyOnceItIsActive() {
        var base = registry.reserveBaseSubscription("00000042", "MOB-VOICE-0010", "36301234567");
        registry.activate(base.subId(), "8936010033333333303", LocalDate.now());
        assertThat(subscriptions.findById(base.subId()).orElseThrow().dataAllowanceMb()).isEqualTo(10_240);

        var addon = registry.reserveAddon(base.subId(), "ADDON-DATA-0005");
        assertThat(addon.parentSubId()).isEqualTo(base.subId());
        // still pending: the parent must not have grown yet
        assertThat(subscriptions.findById(base.subId()).orElseThrow().dataAllowanceMb()).isEqualTo(10_240);

        registry.activate(addon.subId(), null, LocalDate.now());
        // 10 GB + 5 GB in megabytes
        assertThat(subscriptions.findById(base.subId()).orElseThrow().dataAllowanceMb()).isEqualTo(15_360);

        // terminating the add-on gives the allowance back
        registry.terminate(addon.subId());
        assertThat(subscriptions.findById(base.subId()).orElseThrow().dataAllowanceMb()).isEqualTo(10_240);
    }

    @Test
    void addonsDoNotMakeAnUnmeteredPlanMetered() {
        var base = registry.reserveBaseSubscription("00000042", "INET-FIB-1000", null);
        registry.activate(base.subId(), null, LocalDate.now());
        var addon = registry.reserveAddon(base.subId(), "ADDON-DATA-0010");
        registry.activate(addon.subId(), null, LocalDate.now());

        assertThat(subscriptions.findById(base.subId()).orElseThrow().dataAllowanceMb())
                .isEqualTo(PlanRow.UNMETERED);
    }

    @Test
    void planChangeKeepsActiveAddonsAndRecomputesTheAllowance() {
        var base = registry.reserveBaseSubscription("00000042", "MOB-VOICE-0010", "36301234567");
        registry.activate(base.subId(), "8936010044444444404", LocalDate.now());
        var addon = registry.reserveAddon(base.subId(), "ADDON-ROAM-EU01");
        registry.activate(addon.subId(), null, LocalDate.now());
        // 10 GB base + 3 GB roaming
        assertThat(subscriptions.findById(base.subId()).orElseThrow().dataAllowanceMb()).isEqualTo(13_312);

        var changed = registry.changePlan(base.subId(), "MOB-VOICE-0050");
        // 50 GB base + 3 GB roaming, the add-on survived the plan change
        assertThat(changed.planCode()).isEqualTo("MOB-VOICE-0050");
        assertThat(changed.dataAllowanceMb()).isEqualTo(54_272);
    }

    @Test
    void illegalTransitionsAndBadRequestsAreRejected() {
        assertThatThrownBy(() -> registry.reserveBaseSubscription("00000042", "ADDON-DATA-0005", "36301234567"))
                .isInstanceOf(CatalogException.BadRequest.class)
                .hasMessageContaining("is an add-on");

        assertThatThrownBy(() -> registry.reserveBaseSubscription("00000042", "MOB-VOICE-0002", "36301234567"))
                .isInstanceOf(CatalogException.BadRequest.class)
                .hasMessageContaining("withdrawn");

        assertThatThrownBy(() -> registry.reserveBaseSubscription("00000042", "MOB-VOICE-0010", null))
                .isInstanceOf(CatalogException.BadRequest.class)
                .hasMessageContaining("requires an msisdn");

        assertThatThrownBy(() -> registry.reserveBaseSubscription("99999999", "MOB-VOICE-0010", "36301234567"))
                .isInstanceOf(CatalogException.NotFound.class);

        // an add-on can only hang off an ACTIVE base subscription
        var pending = registry.reserveBaseSubscription("00000043", "MOB-VOICE-0010", "36209876543");
        assertThatThrownBy(() -> registry.reserveAddon(pending.subId(), "ADDON-DATA-0005"))
                .isInstanceOf(CatalogException.IllegalTransition.class)
                .hasMessageContaining("PA");

        // service kinds cannot be crossed
        var internet = registry.reserveBaseSubscription("00000043", "INET-ADSL-0030", null);
        assertThatThrownBy(() -> registry.changePlan(internet.subId(), "MOB-VOICE-0010"))
                .isInstanceOf(CatalogException.BadRequest.class)
                .hasMessageContaining("service kinds");
    }

    @Test
    void stalePendingActivationFindsTheSeededInconsistencyButNotFreshReservations() {
        var fresh = registry.reserveBaseSubscription("00000043", "MOB-VOICE-0050", "36209876543");

        List<SubscriptionRow> stale = registry.stalePendingActivation(60);
        assertThat(stale).extracting(SubscriptionRow::subId)
                .contains("SUB-2026-000009")
                .doesNotContain(fresh.subId());

        // back-dating the fresh one makes it show up, which is how the demo triggers branch A
        subscriptions.backdateUpdatedTs(fresh.subId(), 120);
        assertThat(registry.stalePendingActivation(60)).extracting(SubscriptionRow::subId)
                .contains(fresh.subId());
    }
}

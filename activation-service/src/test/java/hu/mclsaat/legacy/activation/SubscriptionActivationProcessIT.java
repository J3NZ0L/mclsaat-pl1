package hu.mclsaat.legacy.activation;

import hu.mclsaat.legacy.activation.client.BillingSoapClient;
import hu.mclsaat.legacy.activation.client.CatalogRestClient;
import hu.mclsaat.legacy.activation.domain.ActivationOrder;
import hu.mclsaat.legacy.activation.repo.ActivationOrderRepository;
import hu.mclsaat.legacy.activation.service.ActivationException;
import hu.mclsaat.legacy.activation.service.ActivationOrderService;
import hu.mclsaat.legacy.activation.service.ProvisioningPlatformSimulator;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The activation process against a real Flowable engine on a real PostgreSQL.
 *
 * <p>What is actually being tested: that starting an order returns before any work happens, that the
 * exclusive gateway routes the three variants differently, that the process really parks on a message
 * subscription, that the message correlates by business key, that the non-interrupting boundary timer
 * reports a stuck order without destroying the wait, and that the same correlation then finishes it.
 */
class SubscriptionActivationProcessIT extends ActivationIntegrationTest {

    @Autowired ActivationOrderService activation;
    @Autowired ActivationOrderRepository orders;
    @Autowired ProvisioningPlatformSimulator platform;
    @Autowired RuntimeService runtimeService;

    @MockitoBean CatalogRestClient catalog;
    @MockitoBean BillingSoapClient billing;

    @BeforeEach
    void stubTheNeighbouringSubsystems() {
        platform.setCallbacksEnabled(true);
        drainProcessInstancesLeftByEarlierTests();

        when(catalog.fetchSubscriber(anyInt())).thenReturn(new CatalogRestClient.CatalogSubscriber(
                "00000042", 42, "Kovács Anna", "anna.kovacs@example.hu", "+36301234567"));
        when(catalog.fetchPlan("mob.voice.0010")).thenReturn(mobilePlan("MOB-VOICE-0010",
                "mob.voice.0010", "BASE", null, "5990.00", "10.000"));
        when(catalog.fetchPlan("mob.voice.0050")).thenReturn(mobilePlan("MOB-VOICE-0050",
                "mob.voice.0050", "BASE", null, "9990.00", "50.000"));
        when(catalog.fetchPlan("addon.data.0005")).thenReturn(mobilePlan("ADDON-DATA-0005",
                "addon.data.0005", "ADDON", "DATA_PACK", "1490.00", "5.000"));
        when(catalog.fetchPlan("mob.voice.0002")).thenReturn(new CatalogRestClient.CatalogPlan(
                "MOB-VOICE-0002", "mob.voice.0002", "M", "Mobil Mini 2GB (kivezetve)", "BASE", null,
                new BigDecimal("3490.00"), new BigDecimal("2.000"), false));

        when(catalog.fetchSubscription(anyString())).thenAnswer(call ->
                subscription(call.getArgument(0), "AC", "mob.voice.0010", "10.000", null));
        when(catalog.reserveSubscription(anyInt(), anyString(), any())).thenReturn(
                subscription("SUB-2026-001000", "PA", "mob.voice.0010", "10.000", null));
        when(catalog.reserveAddon(anyString(), anyString())).thenAnswer(call ->
                subscription("SUB-2026-001001", "PA", "addon.data.0005", "5.000",
                        call.getArgument(0)));
        when(catalog.activateSubscription(anyString(), any(), any())).thenAnswer(call ->
                subscription(call.getArgument(0), "AC", "mob.voice.0010", "10.000", null));
        when(catalog.changePlan(anyString(), anyString())).thenAnswer(call ->
                subscription(call.getArgument(0), "AC", call.getArgument(1), "50.000", null));

        when(billing.createInvoice(anyInt(), anyString(), anyString(), anyString(), any(),
                anyString(), any(), any()))
                .thenReturn(new BillingSoapClient.CreatedInvoice("2026/INV/000100", "BA-00042",
                        new BigDecimal("4716.54"), new BigDecimal("1273.47"),
                        new BigDecimal("5990.01"), "HUF", "OPEN", false));
    }

    // ------------------------------------------------------------- the happy path

    @Test
    void startingAnOrderReturnsBeforeAnythingHasHappened() {
        var order = activation.startOrder(ActivationOrder.CHANGE_NEW_SUBSCRIPTION, 42,
                "mob.voice.0010", "+36301234567", "20260929", null, false, "PT30S");

        assertThat(order.orderNo()).matches("^ORD/\\d{4}/\\d{7}$");
        // RECEIVED, not PROVISIONED: validateOrder is flowable:async, so nothing has run yet
        assertThat(order.status()).isEqualTo(ActivationOrder.STATUS_RECEIVED);
        assertThat(order.processInstanceId()).isNotBlank();
        assertThat(order.monthlyFeeHuf()).isNull();
    }

    @Test
    void aNewSubscriptionRunsThroughToProvisionedOnItsOwn() {
        var order = activation.startOrder(ActivationOrder.CHANGE_NEW_SUBSCRIPTION, 42,
                "mob.voice.0010", "+36301234567", "20260929", null, false, "PT30S");

        awaitUntil("order " + order.orderNo() + " to reach PROVISIONED", () ->
                ActivationOrder.STATUS_PROVISIONED.equals(
                        orders.findByOrderNo(order.orderNo()).orElseThrow().status()));

        var done = orders.findByOrderNo(order.orderNo()).orElseThrow();
        assertThat(done.subscriptionRef()).isEqualTo("SUB-2026-001000");
        assertThat(done.invoiceRef()).isEqualTo("2026/INV/000100");
        assertThat(done.failureReason()).isNull();
        // validation wrote the catalog's numbers back in activation's own units
        assertThat(done.monthlyFeeHuf()).isEqualByComparingTo("5990.00");
        assertThat(done.dataAllowanceGb()).isEqualByComparingTo("10.000");
        // the SIM the provisioning platform allocated came in over the callback
        assertThat(done.simIccid()).matches("^89360100\\d{11}$");

        var status = activation.status(order.orderNo());
        assertThat(status.processRunning()).isFalse();
        assertThat(status.processFinished()).isTrue();
        assertThat(status.waitingForCallback()).isFalse();
    }

    @Test
    void theProcessReallyParksOnAMessageSubscriptionBeforeTheCallback() {
        platform.setCallbacksEnabled(false);
        var order = activation.startOrder(ActivationOrder.CHANGE_NEW_SUBSCRIPTION, 42,
                "mob.voice.0010", "+36301234567", "20260929", null, false, "PT30S");

        awaitUntil("order to be waiting for the callback", () ->
                activation.status(order.orderNo()).waitingForCallback());

        var status = activation.status(order.orderNo());
        assertThat(status.order().status()).isEqualTo(ActivationOrder.STATUS_AWAITING_PROVISIONING);
        assertThat(status.processRunning()).isTrue();
        assertThat(status.currentActivity()).contains("provisioningCallback");
        // the catalog subscription exists and is not live: the inconsistency is real from here on
        assertThat(status.order().subscriptionRef()).isEqualTo("SUB-2026-001000");
        verify(catalog, never()).activateSubscription(anyString(), any(), any());
    }

    // -------------------------------------------------------------- the variants

    @Test
    void theGatewayRoutesAnAddonThroughReserveAddonAndNotReserveSubscription() {
        var order = activation.startOrder(ActivationOrder.CHANGE_ADDON, 42, "addon.data.0005",
                null, "20260929", "SUB-2026-000002", false, "PT30S");

        awaitUntil("add-on order to reach PROVISIONED", () ->
                ActivationOrder.STATUS_PROVISIONED.equals(
                        orders.findByOrderNo(order.orderNo()).orElseThrow().status()));

        verify(catalog).reserveAddon("SUB-2026-000002", "addon.data.0005");
        verify(catalog, never()).reserveSubscription(anyInt(), anyString(), any());
        // the add-on's own subscription is what gets activated, not the parent's
        verify(catalog).activateSubscription(eq("SUB-2026-001001"), any(), any());
        assertThat(orders.findByOrderNo(order.orderNo()).orElseThrow().subscriptionRef())
                .isEqualTo("SUB-2026-001001");
    }

    @Test
    void aPlanChangeReprovisionsTheNetworkButActivatesNothing() {
        var order = activation.startOrder(ActivationOrder.CHANGE_PLAN_CHANGE, 42, "mob.voice.0050",
                null, "20260929", "SUB-2026-000002", false, "PT30S");

        awaitUntil("plan change to reach PROVISIONED", () ->
                ActivationOrder.STATUS_PROVISIONED.equals(
                        orders.findByOrderNo(order.orderNo()).orElseThrow().status()));

        verify(catalog).changePlan("SUB-2026-000002", "mob.voice.0050");
        // the subscription was already AC, so there is nothing to activate - but it still had to
        // wait for the network profile update
        verify(catalog, never()).activateSubscription(anyString(), any(), any());
        verify(catalog, never()).reserveSubscription(anyInt(), anyString(), any());
        assertThat(orders.findByOrderNo(order.orderNo()).orElseThrow().subscriptionRef())
                .isEqualTo("SUB-2026-000002");
    }

    // ------------------------------------------------- failure branch A, and repair

    @Test
    void anOrderThePlatformIgnoresGoesStuckWithoutLosingItsWait() {
        var order = activation.startOrder(ActivationOrder.CHANGE_NEW_SUBSCRIPTION, 42,
                "mob.voice.0010", "+36301234567", "20260929", null, true, "PT2S");

        awaitUntil("order " + order.orderNo() + " to be reported STUCK", () ->
                ActivationOrder.STATUS_STUCK.equals(
                        orders.findByOrderNo(order.orderNo()).orElseThrow().status()));

        var stuck = orders.findByOrderNo(order.orderNo()).orElseThrow();
        assertThat(stuck.failureReason()).contains("did not confirm provisioning");
        assertThat(stuck.failureReason()).contains(stuck.subscriptionRef());

        // the important part: the timer was non-interrupting, so the wait survived it
        var status = activation.status(order.orderNo());
        assertThat(status.processRunning()).isTrue();
        assertThat(status.waitingForCallback()).isTrue();
        assertThat(status.order().isTerminal()).isFalse();
    }

    @Test
    void opsRepairsAStuckOrderByInjectingTheCallbackThePlatformNeverSent() {
        var order = activation.startOrder(ActivationOrder.CHANGE_NEW_SUBSCRIPTION, 42,
                "mob.voice.0010", "+36301234567", "20260929", null, true, "PT2S");
        awaitUntil("order to be STUCK", () -> ActivationOrder.STATUS_STUCK.equals(
                orders.findByOrderNo(order.orderNo()).orElseThrow().status()));

        var stuckList = activation.stuckOrders(0);
        assertThat(stuckList).anySatisfy(entry -> {
            assertThat(entry.order().orderNo()).isEqualTo(order.orderNo());
            assertThat(entry.repairableByCallback()).isTrue();
        });

        activation.correlateProvisioningCompleted(order.orderNo(), "89360100999888777", "ops");

        awaitUntil("repaired order to reach PROVISIONED", () ->
                ActivationOrder.STATUS_PROVISIONED.equals(
                        orders.findByOrderNo(order.orderNo()).orElseThrow().status()));

        var repaired = orders.findByOrderNo(order.orderNo()).orElseThrow();
        assertThat(repaired.simIccid()).isEqualTo("89360100999888777");
        assertThat(repaired.invoiceRef()).isEqualTo("2026/INV/000100");
        // the stuck reason is cleared, so the order stops being reported as a problem
        assertThat(repaired.failureReason()).isNull();
        assertThat(activation.stuckOrders(0)).noneSatisfy(entry ->
                assertThat(entry.order().orderNo()).isEqualTo(order.orderNo()));
        // and the catalog subscription was taken live with the ICCID ops supplied
        verify(catalog).activateSubscription("SUB-2026-001000", "89360100999888777",
                LocalDate.of(2026, 9, 29));
    }

    @Test
    void opsCanInsteadCancelAStuckOrderWhichIsNotReversible() {
        var order = activation.startOrder(ActivationOrder.CHANGE_NEW_SUBSCRIPTION, 42,
                "mob.voice.0010", "+36301234567", "20260929", null, true, "PT2S");
        awaitUntil("order to be STUCK", () -> ActivationOrder.STATUS_STUCK.equals(
                orders.findByOrderNo(order.orderNo()).orElseThrow().status()));

        var cancelled = activation.cancelOrder(order.orderNo(), "SIM stock exhausted");

        assertThat(cancelled.status()).isEqualTo(ActivationOrder.STATUS_CANCELLED);
        assertThat(cancelled.failureReason()).isEqualTo("SIM stock exhausted");
        // the process instance and its message subscription are gone, so the callback no longer lands
        assertThat(activation.status(order.orderNo()).processRunning()).isFalse();
        assertThatThrownBy(() -> activation.correlateProvisioningCompleted(
                order.orderNo(), "89360100111222333", "ops"))
                .isInstanceOf(ActivationException.IllegalState.class)
                .hasMessageContaining("not waiting");
    }

    @Test
    void anOrderThatHasAlreadyFinishedCannotBeCorrelatedAgain() {
        var order = activation.startOrder(ActivationOrder.CHANGE_NEW_SUBSCRIPTION, 42,
                "mob.voice.0010", "+36301234567", "20260929", null, false, "PT30S");
        awaitUntil("order to finish", () -> ActivationOrder.STATUS_PROVISIONED.equals(
                orders.findByOrderNo(order.orderNo()).orElseThrow().status()));

        assertThatThrownBy(() -> activation.correlateProvisioningCompleted(
                order.orderNo(), "89360100111222333", "a duplicate platform callback"))
                .isInstanceOf(ActivationException.IllegalState.class)
                .hasMessageContaining("already finished");
    }

    // -------------------------------------------------------- validation and input

    @Test
    void aWithdrawnOfferIsRejectedBeforeAnythingIsReserved() {
        var order = activation.startOrder(ActivationOrder.CHANGE_NEW_SUBSCRIPTION, 42,
                "mob.voice.0002", "+36301234567", "20260929", null, false, "PT30S");

        awaitUntil("order to be rejected", () -> ActivationOrder.STATUS_FAILED.equals(
                orders.findByOrderNo(order.orderNo()).orElseThrow().status()));

        var failed = orders.findByOrderNo(order.orderNo()).orElseThrow();
        assertThat(failed.failureReason()).contains("OFFER_WITHDRAWN");
        assertThat(failed.subscriptionRef()).isNull();
        verify(catalog, never()).reserveSubscription(anyInt(), anyString(), any());
    }

    @Test
    void anAddonRequestedAsABaseSubscriptionIsRejected() {
        var order = activation.startOrder(ActivationOrder.CHANGE_NEW_SUBSCRIPTION, 42,
                "addon.data.0005", "+36301234567", "20260929", null, false, "PT30S");

        awaitUntil("order to be rejected", () -> ActivationOrder.STATUS_FAILED.equals(
                orders.findByOrderNo(order.orderNo()).orElseThrow().status()));
        assertThat(orders.findByOrderNo(order.orderNo()).orElseThrow().failureReason())
                .contains("ADDON_AS_BASE");
    }

    @Test
    void theCatalogsOwnTariffCodeIsNotAcceptedAsAnOfferId() {
        assertThatThrownBy(() -> activation.startOrder(ActivationOrder.CHANGE_NEW_SUBSCRIPTION, 42,
                "MOB-VOICE-0010", "+36301234567", "20260929", null, false, null))
                .isInstanceOf(ActivationException.BadRequest.class)
                .hasMessageContaining("dotted lowercase");
    }

    @Test
    void badInputIsRejectedSynchronouslyBeforeAnOrderExists() {
        assertThatThrownBy(() -> activation.startOrder("UPGRADE_EVERYTHING", 42, "mob.voice.0010",
                null, "20260929", null, false, null))
                .isInstanceOf(ActivationException.BadRequest.class)
                .hasMessageContaining("changeType");

        assertThatThrownBy(() -> activation.startOrder(ActivationOrder.CHANGE_ADDON, 42,
                "addon.data.0005", null, "20260929", null, false, null))
                .isInstanceOf(ActivationException.BadRequest.class)
                .hasMessageContaining("targetSubscriptionRef");

        // billing's date format, not activation's
        assertThatThrownBy(() -> activation.startOrder(ActivationOrder.CHANGE_NEW_SUBSCRIPTION, 42,
                "mob.voice.0010", "+36301234567", "2026-09-29", null, false, null))
                .isInstanceOf(ActivationException.BadRequest.class)
                .hasMessageContaining("yyyyMMdd");

        assertThatThrownBy(() -> activation.startOrder(ActivationOrder.CHANGE_NEW_SUBSCRIPTION, 42,
                "mob.voice.0010", "+36-30/123-4567", "20260929", null, false, null))
                .isInstanceOf(ActivationException.BadRequest.class)
                .hasMessageContaining("E.164");
    }

    @Test
    void anMsisdnWithoutItsPlusIsNormalisedRatherThanRejected() {
        var order = activation.startOrder(ActivationOrder.CHANGE_NEW_SUBSCRIPTION, 42,
                "mob.voice.0010", "36301234567", "20260929", null, false, "PT30S");

        assertThat(order.msisdn()).isEqualTo("+36301234567");
    }

    // --------------------------------------------------- what crosses the boundary

    @Test
    void theBoundaryTranslationsActuallyHappenOnTheWayOut() {
        var order = activation.startOrder(ActivationOrder.CHANGE_NEW_SUBSCRIPTION, 42,
                "mob.voice.0010", "+36301234567", "20261001", null, false, "PT30S");
        awaitUntil("order to finish", () -> ActivationOrder.STATUS_PROVISIONED.equals(
                orders.findByOrderNo(order.orderNo()).orElseThrow().status()));

        // to the catalog: the offer id, the customer as an int, the msisdn with its plus - the
        // client is what converts them, so it is called in activation's own terms
        verify(catalog).reserveSubscription(42, "mob.voice.0010", "+36301234567");

        // to billing: dates in ITS format, the gross fee, and the order number as idempotency key
        ArgumentCaptor<String> periodStart = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> periodEnd = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BigDecimal> gross = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> requestRef = ArgumentCaptor.forClass(String.class);
        verify(billing).createInvoice(eq(42), eq("SUB-2026-001000"), periodStart.capture(),
                periodEnd.capture(), gross.capture(), requestRef.capture(), any(), any());

        // the client is handed activation's yyyyMMdd and converts it itself
        assertThat(periodStart.getValue()).isEqualTo("20261001");
        assertThat(periodEnd.getValue()).isEqualTo("20261031");
        assertThat(gross.getValue()).isEqualByComparingTo("5990.00");
        assertThat(requestRef.getValue()).isEqualTo(order.orderNo());
    }

    @Test
    void anUnknownOrderIsNotFound() {
        assertThatThrownBy(() -> activation.status("ORD/2026/9999999"))
                .isInstanceOf(ActivationException.NotFound.class);
    }

    /**
     * The Flowable engine is shared by every test in this class and its job executor keeps running
     * between them, so an order left parked or mid-flight by an earlier test can call a delegate -
     * and therefore a mock - in the middle of the next one. That makes any {@code verify(never())}
     * meaningless. Deleting the leftovers first is what makes the interaction assertions below
     * trustworthy.
     *
     * <p>Deleting an instance the job executor is working on at that exact moment loses the race and
     * throws {@link FlowableOptimisticLockingException}, so this retries rather than treating the
     * collision as a test failure. That is also how a real caller has to behave against a live
     * engine.
     */
    private void drainProcessInstancesLeftByEarlierTests() {
        for (int attempt = 0; attempt < 10; attempt++) {
            var instances = runtimeService.createProcessInstanceQuery().list();
            if (instances.isEmpty()) {
                return;
            }
            for (var instance : instances) {
                try {
                    runtimeService.deleteProcessInstance(instance.getId(), "test isolation");
                } catch (FlowableOptimisticLockingException | FlowableObjectNotFoundException ex) {
                    // the executor got there first, or already finished it; try again next round
                }
            }
            sleep(150);
        }
        throw new IllegalStateException("could not drain the process instances left by earlier tests");
    }

    // ----------------------------------------------------------------- stub helpers

    private static CatalogRestClient.CatalogPlan mobilePlan(String planCode, String offerId,
                                                            String planKind, String addonCategory,
                                                            String grossHuf, String gb) {
        return new CatalogRestClient.CatalogPlan(planCode, offerId, "M", planCode, planKind,
                addonCategory, new BigDecimal(grossHuf), new BigDecimal(gb), true);
    }

    private static CatalogRestClient.CatalogSubscription subscription(String subId, String statusCode,
                                                                     String offerId, String gb,
                                                                     String parent) {
        return new CatalogRestClient.CatalogSubscription(subId, 42, offerId, statusCode,
                hu.mclsaat.legacy.activation.semantics.ActivationSemantics
                        .fromCatalogStatusCode(statusCode),
                "AC".equals(statusCode) ? LocalDate.of(2026, 1, 15) : null,
                "+36301234567", null, new BigDecimal(gb), parent);
    }
}

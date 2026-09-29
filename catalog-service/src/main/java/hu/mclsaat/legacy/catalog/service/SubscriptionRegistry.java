package hu.mclsaat.legacy.catalog.service;

import hu.mclsaat.legacy.catalog.domain.PlanRow;
import hu.mclsaat.legacy.catalog.domain.SubscriptionRow;
import hu.mclsaat.legacy.catalog.repo.PlanRepository;
import hu.mclsaat.legacy.catalog.repo.SubscriberRepository;
import hu.mclsaat.legacy.catalog.repo.SubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * The subscription lifecycle as subsystem 1 understands it.
 *
 * <p>Everything here speaks the catalog's own dialect: {@code CHAR(8)} customer numbers,
 * two-character status codes, megabytes. The activation process (subsystem 2) calls these
 * operations over REST while speaking its own dialect, and translates on its side.
 */
@Service
public class SubscriptionRegistry {

    private final PlanRepository plans;
    private final SubscriberRepository subscribers;
    private final SubscriptionRepository subscriptions;

    public SubscriptionRegistry(PlanRepository plans, SubscriberRepository subscribers,
                                SubscriptionRepository subscriptions) {
        this.plans = plans;
        this.subscribers = subscribers;
        this.subscriptions = subscriptions;
    }

    /**
     * Creates a base subscription in {@code PA} (pending activation).
     *
     * <p>Called by the activation process right after it validates an order. The subscription
     * exists from this moment on, which is what makes failure branch A visible at all: if the
     * process then gets stuck, this row is left behind in {@code PA}.
     */
    @Transactional
    public SubscriptionRow reserveBaseSubscription(String custNo, String planCode, String msisdn) {
        requireSubscriber(custNo);
        PlanRow plan = requireActivePlan(planCode);
        if (plan.isAddon()) {
            throw new CatalogException.BadRequest(
                    "plan " + planCode + " is an add-on and cannot be a base subscription");
        }
        if (plan.serviceKind() == 'M' && (msisdn == null || msisdn.isBlank())) {
            throw new CatalogException.BadRequest("mobile plan " + planCode + " requires an msisdn");
        }
        return subscriptions.insertPendingActivation(custNo, planCode, msisdn,
                plan.dataAllowanceMb(), null);
    }

    /**
     * Creates an add-on subscription in {@code PA}, hanging off an active base subscription.
     * The parent's effective allowance only grows once the add-on itself becomes active.
     */
    @Transactional
    public SubscriptionRow reserveAddon(String parentSubId, String addonPlanCode) {
        SubscriptionRow parent = requireSubscription(parentSubId);
        if (!SubscriptionRow.STATUS_ACTIVE.equals(parent.statusCode())) {
            throw new CatalogException.IllegalTransition(
                    "add-ons can only be attached to an active subscription, but " + parentSubId
                            + " is in status " + parent.statusCode());
        }
        if (parent.isAddon()) {
            throw new CatalogException.BadRequest("cannot attach an add-on to another add-on: " + parentSubId);
        }
        PlanRow addon = requireActivePlan(addonPlanCode);
        if (!addon.isAddon()) {
            throw new CatalogException.BadRequest("plan " + addonPlanCode + " is not an add-on");
        }
        return subscriptions.insertPendingActivation(parent.custNo(), addonPlanCode, parent.msisdn(),
                addon.dataAllowanceMb(), parentSubId);
    }

    /**
     * {@code PA} -> {@code AC}. Idempotent: activating an already active subscription is a no-op
     * so a replayed Flowable job cannot corrupt the row.
     */
    @Transactional
    public SubscriptionRow activate(String subId, String simIccid, LocalDate activatedOn) {
        SubscriptionRow current = requireSubscription(subId);
        if (SubscriptionRow.STATUS_ACTIVE.equals(current.statusCode())) {
            return current;
        }
        if (!SubscriptionRow.STATUS_PENDING_ACTIVATION.equals(current.statusCode())
                && !SubscriptionRow.STATUS_NEW.equals(current.statusCode())) {
            throw new CatalogException.IllegalTransition(
                    "cannot activate " + subId + " from status " + current.statusCode());
        }
        subscriptions.markActive(subId, simIccid, activatedOn == null ? LocalDate.now() : activatedOn);
        if (current.isAddon()) {
            recomputeEffectiveAllowance(current.parentSubId());
        }
        return requireSubscription(subId);
    }

    /**
     * Swaps the base plan of an existing subscription and recomputes the effective allowance
     * from the new base plus every active add-on.
     */
    @Transactional
    public SubscriptionRow changePlan(String subId, String newPlanCode) {
        SubscriptionRow current = requireSubscription(subId);
        if (current.isAddon()) {
            throw new CatalogException.BadRequest("cannot change the plan of an add-on: " + subId);
        }
        if (!SubscriptionRow.STATUS_ACTIVE.equals(current.statusCode())
                && !SubscriptionRow.STATUS_PENDING_ACTIVATION.equals(current.statusCode())) {
            throw new CatalogException.IllegalTransition(
                    "cannot change the plan of " + subId + " in status " + current.statusCode());
        }
        PlanRow newPlan = requireActivePlan(newPlanCode);
        if (newPlan.isAddon()) {
            throw new CatalogException.BadRequest("plan " + newPlanCode + " is an add-on, not a base plan");
        }
        if (newPlan.planCode().equals(current.planCode())) {
            throw new CatalogException.IllegalTransition(subId + " is already on plan " + newPlanCode);
        }
        PlanRow oldPlan = requireAnyPlan(current.planCode());
        if (oldPlan.serviceKind() != newPlan.serviceKind()) {
            throw new CatalogException.BadRequest("cannot move a subscription between service kinds ("
                    + oldPlan.serviceKind() + " -> " + newPlan.serviceKind() + ")");
        }
        subscriptions.updatePlan(subId, newPlanCode, newPlan.dataAllowanceMb());
        recomputeEffectiveAllowance(subId);
        return requireSubscription(subId);
    }

    @Transactional
    public SubscriptionRow terminate(String subId) {
        SubscriptionRow current = requireSubscription(subId);
        if (SubscriptionRow.STATUS_TERMINATED.equals(current.statusCode())) {
            return current;
        }
        subscriptions.updateStatus(subId, SubscriptionRow.STATUS_TERMINATED);
        if (current.isAddon()) {
            recomputeEffectiveAllowance(current.parentSubId());
        }
        return requireSubscription(subId);
    }

    /**
     * Effective allowance = the base plan's allowance plus every active add-on's allowance.
     * An unmetered base plan stays unmetered no matter what is bolted onto it.
     */
    @Transactional
    public int recomputeEffectiveAllowance(String subId) {
        SubscriptionRow sub = requireSubscription(subId);
        PlanRow basePlan = requireAnyPlan(sub.planCode());
        int effective;
        if (basePlan.dataAllowanceMb() == PlanRow.UNMETERED) {
            effective = PlanRow.UNMETERED;
        } else {
            effective = basePlan.dataAllowanceMb();
            for (SubscriptionRow addon : subscriptions.findActiveAddonsOf(subId)) {
                PlanRow addonPlan = requireAnyPlan(addon.planCode());
                if (addonPlan.dataAllowanceMb() > 0) {
                    effective += addonPlan.dataAllowanceMb();
                }
            }
        }
        subscriptions.updateEffectiveAllowance(subId, effective);
        return effective;
    }

    public List<SubscriptionRow> stalePendingActivation(int olderThanMinutes) {
        return subscriptions.findStalePendingActivation(olderThanMinutes);
    }

    private void requireSubscriber(String custNo) {
        subscribers.findByCustNo(custNo)
                .orElseThrow(() -> new CatalogException.NotFound("subscriber", custNo));
    }

    private SubscriptionRow requireSubscription(String subId) {
        return subscriptions.findById(subId)
                .orElseThrow(() -> new CatalogException.NotFound("subscription", subId));
    }

    private PlanRow requireAnyPlan(String planCode) {
        return plans.findByCode(planCode)
                .orElseThrow(() -> new CatalogException.NotFound("plan", planCode));
    }

    private PlanRow requireActivePlan(String planCode) {
        PlanRow plan = requireAnyPlan(planCode);
        if (!plan.active()) {
            throw new CatalogException.BadRequest("plan " + planCode + " is withdrawn (active_flag = 'N')");
        }
        return plan;
    }
}

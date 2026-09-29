package hu.mclsaat.legacy.catalog.web;

import hu.mclsaat.legacy.catalog.repo.SubscriberRepository;
import hu.mclsaat.legacy.catalog.repo.SubscriptionRepository;
import hu.mclsaat.legacy.catalog.service.CatalogException;
import hu.mclsaat.legacy.catalog.service.SubscriptionRegistry;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Subscriber and subscription endpoints.
 *
 * <p>The read endpoints are customer-facing. The write endpoints under
 * {@code /api/v1/subscriptions} are what the activation process (subsystem 2) drives; they are
 * not meant for customers, but in true legacy fashion nothing enforces that beyond convention.
 */
@RestController
@RequestMapping("/api/v1")
public class SubscriptionController {

    private final SubscriberRepository subscribers;
    private final SubscriptionRepository subscriptions;
    private final SubscriptionRegistry registry;

    public SubscriptionController(SubscriberRepository subscribers, SubscriptionRepository subscriptions,
                                  SubscriptionRegistry registry) {
        this.subscribers = subscribers;
        this.subscriptions = subscriptions;
        this.registry = registry;
    }

    @GetMapping("/subscribers/{custNo}")
    public CatalogDtos.SubscriberView subscriber(@PathVariable String custNo) {
        return subscribers.findByCustNo(custNo)
                .map(CatalogDtos.SubscriberView::of)
                .orElseThrow(() -> new CatalogException.NotFound("subscriber", custNo));
    }

    @PostMapping("/subscribers")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogDtos.SubscriberView createSubscriber(
            @Valid @RequestBody CatalogDtos.CreateSubscriberRequest request) {
        String msisdn = request.msisdn() == null || request.msisdn().isBlank() ? null : request.msisdn();
        return CatalogDtos.SubscriberView.of(
                subscribers.insert(request.fullName(), request.email(), msisdn));
    }

    @GetMapping("/subscribers/{custNo}/subscriptions")
    public List<CatalogDtos.SubscriptionView> subscriptionsOf(@PathVariable String custNo) {
        subscriber(custNo); // 404 if the customer does not exist at all
        return subscriptions.findByCustNo(custNo).stream()
                .map(CatalogDtos.SubscriptionView::of)
                .toList();
    }

    @GetMapping("/subscriptions/{subId}")
    public CatalogDtos.SubscriptionView subscription(@PathVariable String subId) {
        return subscriptions.findById(subId)
                .map(CatalogDtos.SubscriptionView::of)
                .orElseThrow(() -> new CatalogException.NotFound("subscription", subId));
    }

    /** Reserve a base subscription in {@code PA}. Called by the activation process. */
    @PostMapping("/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogDtos.SubscriptionView reserve(
            @Valid @RequestBody CatalogDtos.ReserveSubscriptionRequest request) {
        String msisdn = request.msisdn() == null || request.msisdn().isBlank() ? null : request.msisdn();
        return CatalogDtos.SubscriptionView.of(
                registry.reserveBaseSubscription(request.custNo(), request.planCode(), msisdn));
    }

    /** Reserve an add-on subscription in {@code PA} under an active base subscription. */
    @PostMapping("/subscriptions/{subId}/addons")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogDtos.SubscriptionView reserveAddon(@PathVariable String subId,
                                                    @Valid @RequestBody CatalogDtos.ReserveAddonRequest request) {
        return CatalogDtos.SubscriptionView.of(registry.reserveAddon(subId, request.addonPlanCode()));
    }

    /** {@code PA} -> {@code AC}. Idempotent. */
    @PostMapping("/subscriptions/{subId}/activate")
    public CatalogDtos.SubscriptionView activate(@PathVariable String subId,
                                                @RequestBody(required = false) CatalogDtos.ActivateRequest request) {
        CatalogDtos.ActivateRequest body =
                request == null ? new CatalogDtos.ActivateRequest(null, null) : request;
        return CatalogDtos.SubscriptionView.of(
                registry.activate(subId, body.simIccid(), body.activatedOn()));
    }

    @PostMapping("/subscriptions/{subId}/change-plan")
    public CatalogDtos.SubscriptionView changePlan(@PathVariable String subId,
                                                  @Valid @RequestBody CatalogDtos.ChangePlanRequest request) {
        return CatalogDtos.SubscriptionView.of(registry.changePlan(subId, request.newPlanCode()));
    }

    @PostMapping("/subscriptions/{subId}/terminate")
    public CatalogDtos.SubscriptionView terminate(@PathVariable String subId) {
        return CatalogDtos.SubscriptionView.of(registry.terminate(subId));
    }

    /**
     * Subscriptions stuck in {@code PA}. The ops console normally reads this over direct JDBC,
     * but the same view is exposed over REST so the difference between the two access paths can
     * be demonstrated side by side.
     */
    @GetMapping("/subscriptions/stale-pending")
    public List<CatalogDtos.SubscriptionView> stalePending(
            @RequestParam(defaultValue = "5") int olderThanMinutes) {
        return registry.stalePendingActivation(olderThanMinutes).stream()
                .map(CatalogDtos.SubscriptionView::of)
                .toList();
    }
}

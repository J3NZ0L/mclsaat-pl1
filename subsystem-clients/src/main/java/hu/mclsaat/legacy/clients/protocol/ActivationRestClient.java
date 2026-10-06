package hu.mclsaat.legacy.clients.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CanonicalOrder;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CustomerRef;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.PhoneNumber;
import hu.mclsaat.legacy.clients.mapping.SemanticMappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Subsystem 2 over REST, translated into the canonical model.
 *
 * <p>Note what this client has to work around. Order numbers contain slashes, so the order number
 * travels as a query parameter or in a body rather than in a path. Activation reports a customer as a
 * bare integer, a product as a dotted lowercase offer id, and an allowance in gigabytes — none of which
 * matches the catalog this module also talks to.
 */
public class ActivationRestClient {

    private static final Logger log = LoggerFactory.getLogger(ActivationRestClient.class);

    public static final String CHANGE_NEW_SUBSCRIPTION = "NEW_SUBSCRIPTION";
    public static final String CHANGE_PLAN_CHANGE = "PLAN_CHANGE";
    public static final String CHANGE_ADDON = "ADDON";

    private final RestClient rest;
    private final String baseUrl;

    public ActivationRestClient(RestClient.Builder builder, String baseUrl) {
        this.baseUrl = baseUrl;
        this.rest = builder.baseUrl(baseUrl).build();
        log.info("activation client talking REST to {}", baseUrl);
    }

    /** One order and the state of its process instance. */
    public Optional<CanonicalOrder> findOrder(String orderNo) {
        JsonNode body = get("/activation/v1/orders?orderNo={orderNo}", "order " + orderNo, orderNo);
        return body == null ? Optional.empty() : Optional.of(toOrder(body));
    }

    public List<CanonicalOrder> ordersOf(int customerReference) {
        JsonNode body = get("/activation/v1/orders?customerRef={customerRef}",
                "orders of customer " + customerReference, customerReference);
        List<CanonicalOrder> orders = new ArrayList<>();
        if (body != null) {
            body.forEach(order -> orders.add(toOrderFromListEntry(order)));
        }
        return orders;
    }

    /** Services 2 and 4 through one endpoint: submit a new order to activation. */
    public CanonicalOrder startOrder(StartOrderRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("changeType", request.changeType());
        payload.put("customerRef", request.customerRef());
        payload.put("offerId", SemanticMappers.toOfferId(request.productCode()));
        payload.put("msisdn", request.phoneNumber() == null ? null : request.phoneNumber().withPlus());
        payload.put("requestedStartDate", SemanticMappers.toActivationDate(request.requestedStartDate()));
        payload.put("targetSubscriptionRef", request.targetSubscriptionRef());
        payload.put("simulateStuck", request.simulateStuck());
        payload.put("provisioningTimeout", request.provisioningTimeout());
        JsonNode body = post("/activation/v1/orders", payload,
                "start order " + request.changeType() + " for customer " + request.customerRef());
        return toOrderFromListEntry(body);
    }

    public CanonicalOrder startNewSubscription(int customerReference, String productCode,
                                               PhoneNumber phoneNumber, LocalDate requestedStartDate) {
        return startOrder(new StartOrderRequest(CHANGE_NEW_SUBSCRIPTION, customerReference, productCode,
                phoneNumber, requestedStartDate, null, false, null));
    }

    public CanonicalOrder startPlanChange(int customerReference, String productCode,
                                          String targetSubscriptionRef, LocalDate requestedStartDate) {
        return startOrder(new StartOrderRequest(CHANGE_PLAN_CHANGE, customerReference, productCode,
                null, requestedStartDate, targetSubscriptionRef, false, null));
    }

    public CanonicalOrder startAddon(int customerReference, String productCode,
                                     String targetSubscriptionRef, LocalDate requestedStartDate) {
        return startOrder(new StartOrderRequest(CHANGE_ADDON, customerReference, productCode,
                null, requestedStartDate, targetSubscriptionRef, false, null));
    }

    /**
     * The activation half of failure branch A: orders waiting on a provisioning callback, or already
     * reported stuck.
     *
     * <p>{@code repairableByCallback} on each entry is the field that matters — it says whether the
     * message subscription still exists, and therefore whether injecting the missing callback can
     * still finish the order or whether only cancellation is left.
     */
    public List<StuckOrderReport> stuckOrders(int olderThanMinutes) {
        JsonNode body = get("/activation/v1/ops/stuck-orders?olderThanMinutes={minutes}",
                "stuck orders", olderThanMinutes);
        List<StuckOrderReport> reports = new ArrayList<>();
        if (body != null) {
            body.forEach(entry -> {
                boolean waiting = entry.path("waitingForCallback").asBoolean();
                boolean running = entry.path("processRunning").asBoolean();
                // the process-state flags live on the wrapper, not on the order, so they have to be
                // pushed down - otherwise the order says "not waiting" while the report next to it
                // says the order is repairable by a callback, which is a contradiction
                reports.add(new StuckOrderReport(
                        toOrderFromListEntry(entry.path("order"), running, waiting),
                        waiting, running,
                        entry.path("repairableByCallback").asBoolean()));
            });
        }
        return reports;
    }

    /**
     * Injects the provisioning callback the platform never sent.
     *
     * <p>Indistinguishable, from the engine's point of view, from the real callback — which is why the
     * repaired order follows the normal path to completion rather than a special recovery route.
     */
    public CanonicalOrder forceProvision(String orderNo, String simIccid) {
        Map<String, Object> request = new HashMap<>();
        request.put("orderNo", orderNo);
        request.put("simIccid", simIccid);
        JsonNode body = post("/activation/v1/ops/orders/force-provision", request,
                "force provisioning of " + orderNo);
        return toOrderFromListEntry(body);
    }

    /** Gives up on the order. Not reversible. */
    public CanonicalOrder cancelOrder(String orderNo, String reason) {
        Map<String, Object> request = new HashMap<>();
        request.put("orderNo", orderNo);
        request.put("reason", reason);
        JsonNode body = post("/activation/v1/ops/orders/cancel", request, "cancellation of " + orderNo);
        return toOrderFromListEntry(body);
    }

    /** The mocked provisioning platform's global callback switch. Fault injection for branch A. */
    public boolean setProvisioningCallbacksEnabled(boolean enabled) {
        JsonNode body = post("/activation/v1/ops/provisioning-platform",
                Map.of("callbacksEnabled", enabled), "provisioning platform configuration");
        return body.path("previousCallbacksEnabled").asBoolean();
    }

    public boolean reachable() {
        try {
            JsonNode health = rest.get().uri("/actuator/health").retrieve().body(JsonNode.class);
            return health != null && "UP".equals(health.path("status").asText());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    // ------------------------------------------------------------------ mapping

    /** The polling response: an order plus the process-instance fields wrapped around it. */
    private CanonicalOrder toOrder(JsonNode statusResponse) {
        JsonNode order = statusResponse.path("order");
        return new CanonicalOrder(
                text(order, "orderNo"),
                text(order, "changeType"),
                CustomerRef.of(order.path("customerRef").asInt()),
                SemanticMappers.toCatalogPlanCode(text(order, "offerId")),
                SemanticMappers.fromActivationStatus(text(order, "status")),
                text(order, "subscriptionRef"),
                text(order, "simIccid"),
                text(order, "invoiceRef"),
                text(order, "failureReason"),
                statusResponse.path("processRunning").asBoolean(),
                statusResponse.path("waitingForCallback").asBoolean(),
                text(statusResponse, "currentActivity"),
                instant(order, "updatedTs"));
    }

    /** A bare order, with no process-instance information available. */
    private CanonicalOrder toOrderFromListEntry(JsonNode order) {
        return toOrderFromListEntry(order, false, false);
    }

    private CanonicalOrder toOrderFromListEntry(JsonNode order, boolean processRunning,
                                               boolean waitingForCallback) {
        return new CanonicalOrder(
                text(order, "orderNo"),
                text(order, "changeType"),
                CustomerRef.of(order.path("customerRef").asInt()),
                SemanticMappers.toCatalogPlanCode(text(order, "offerId")),
                SemanticMappers.fromActivationStatus(text(order, "status")),
                text(order, "subscriptionRef"),
                text(order, "simIccid"),
                text(order, "invoiceRef"),
                text(order, "failureReason"),
                processRunning, waitingForCallback, null,
                instant(order, "updatedTs"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : Instant.parse(value);
    }

    // ------------------------------------------------------------------- plumbing

    /** @return {@code null} on 404, so a missing order is an empty answer rather than an exception */
    private JsonNode get(String uriTemplate, String what, Object... uriVariables) {
        try {
            return rest.get().uri(uriTemplate, uriVariables).retrieve()
                    .onStatus(status -> status.value() == 404, (request, response) -> {
                        throw new NotFound();
                    })
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw failure(what, response.getStatusCode().value(), body(response));
                    })
                    .body(JsonNode.class);
        } catch (NotFound ex) {
            return null;
        } catch (ResourceAccessException ex) {
            throw new ActivationClientException(
                    "activation at " + baseUrl + " is unreachable while fetching " + what, ex);
        }
    }

    private JsonNode post(String uri, Object request, String what) {
        try {
            return rest.post().uri(uri).body(request).retrieve()
                    .onStatus(HttpStatusCode::isError, (req, response) -> {
                        throw failure(what, response.getStatusCode().value(), body(response));
                    })
                    .body(JsonNode.class);
        } catch (ResourceAccessException ex) {
            throw new ActivationClientException(
                    "activation at " + baseUrl + " is unreachable while requesting " + what, ex);
        }
    }

    private static String body(org.springframework.http.client.ClientHttpResponse response) {
        try {
            return new String(response.getBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "<unreadable>";
        }
    }

    private static ActivationClientException failure(String what, int status, String body) {
        return new ActivationClientException(
                "activation refused " + what + " with HTTP " + status + ": " + body, null);
    }

    /** Signals a 404 internally; never escapes this class. */
    private static class NotFound extends RuntimeException {
    }

    public static class ActivationClientException extends RuntimeException {
        public ActivationClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record StuckOrderReport(CanonicalOrder order, boolean waitingForCallback,
                                  boolean processRunning, boolean repairableByCallback) {
    }

    public record StartOrderRequest(
            String changeType,
            int customerRef,
            String productCode,
            PhoneNumber phoneNumber,
            LocalDate requestedStartDate,
            String targetSubscriptionRef,
            boolean simulateStuck,
            String provisioningTimeout) {
    }
}

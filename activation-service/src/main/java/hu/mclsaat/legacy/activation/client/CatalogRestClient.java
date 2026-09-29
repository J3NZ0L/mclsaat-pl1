package hu.mclsaat.legacy.activation.client;

import com.fasterxml.jackson.databind.JsonNode;
import hu.mclsaat.legacy.activation.semantics.ActivationSemantics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Activation's own hand-rolled client for subsystem 1.
 *
 * <p>It is deliberately not shared with the ops console's {@code subsystem-clients} layer. Each
 * legacy subsystem grew its own integration code, with its own idea of how to translate the other
 * side's vocabulary, and the duplication is the problem phase 2 is supposed to solve: an MCP server
 * replaces N ad-hoc translations with one. Removing the duplication now would delete the evidence.
 *
 * <p>Everything crossing this boundary is translated by {@link ActivationSemantics}: the customer
 * number gains its zero padding, the offer id becomes a tariff code, the phone number loses its
 * plus sign, and megabytes come back as gigabytes.
 */
@Component
public class CatalogRestClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogRestClient.class);

    private final RestClient rest;

    public CatalogRestClient(RestClient.Builder builder,
                             @Value("${activation.catalog.base-url}") String baseUrl) {
        this.rest = builder.baseUrl(baseUrl).build();
        log.info("catalog client talking REST to {}", baseUrl);
    }

    /** @return the plan, translated into activation's units */
    public CatalogPlan fetchPlan(String offerId) {
        String planCode = ActivationSemantics.toCatalogPlanCode(offerId);
        JsonNode body = get("/api/v1/plans/" + planCode, "plan " + planCode);
        return new CatalogPlan(
                body.path("planCode").asText(),
                ActivationSemantics.toOfferId(body.path("planCode").asText()),
                body.path("serviceKind").asText(),
                body.path("displayName").asText(),
                body.path("planKind").asText(),
                body.path("addonCategory").isNull() ? null : body.path("addonCategory").asText(),
                // fillér integer -> HUF decimal, and megabytes -> gigabytes
                ActivationSemantics.toHuf(body.path("monthlyFeeMinor").asLong()),
                ActivationSemantics.toGigabytes(body.path("dataAllowanceMb").asInt()),
                "Y".equals(body.path("activeFlag").asText()));
    }

    /** @return the subscriber, or throws if the catalog does not know this customer */
    public CatalogSubscriber fetchSubscriber(int customerRef) {
        String custNo = ActivationSemantics.toCatalogCustNo(customerRef);
        JsonNode body = get("/api/v1/subscribers/" + custNo, "subscriber " + custNo);
        return new CatalogSubscriber(
                body.path("custNo").asText(),
                ActivationSemantics.fromCatalogCustNo(body.path("custNo").asText()),
                body.path("fullName").asText(),
                body.path("email").asText(),
                ActivationSemantics.toActivationMsisdn(
                        body.path("msisdn").isNull() ? null : body.path("msisdn").asText()));
    }

    public CatalogSubscription fetchSubscription(String subscriptionRef) {
        return toSubscription(get("/api/v1/subscriptions/" + subscriptionRef,
                "subscription " + subscriptionRef));
    }

    /** Reserves a base subscription. The catalog creates it in {@code PA}. */
    public CatalogSubscription reserveSubscription(int customerRef, String offerId, String msisdn) {
        Map<String, Object> request = new HashMap<>();
        request.put("custNo", ActivationSemantics.toCatalogCustNo(customerRef));
        request.put("planCode", ActivationSemantics.toCatalogPlanCode(offerId));
        request.put("msisdn", ActivationSemantics.toCatalogMsisdn(msisdn));
        return toSubscription(post("/api/v1/subscriptions", request, "reserve subscription"));
    }

    public CatalogSubscription reserveAddon(String parentSubscriptionRef, String addonOfferId) {
        Map<String, Object> request = Map.of("addonPlanCode",
                ActivationSemantics.toCatalogPlanCode(addonOfferId));
        return toSubscription(post("/api/v1/subscriptions/" + parentSubscriptionRef + "/addons",
                request, "reserve add-on on " + parentSubscriptionRef));
    }

    /** {@code PA} -> {@code AC}. Idempotent on the catalog side. */
    public CatalogSubscription activateSubscription(String subscriptionRef, String simIccid,
                                                   LocalDate activatedOn) {
        Map<String, Object> request = new HashMap<>();
        request.put("simIccid", simIccid);
        request.put("activatedOn", activatedOn == null ? null : activatedOn.toString());
        return toSubscription(post("/api/v1/subscriptions/" + subscriptionRef + "/activate",
                request, "activate " + subscriptionRef));
    }

    public CatalogSubscription changePlan(String subscriptionRef, String newOfferId) {
        Map<String, Object> request = Map.of("newPlanCode",
                ActivationSemantics.toCatalogPlanCode(newOfferId));
        return toSubscription(post("/api/v1/subscriptions/" + subscriptionRef + "/change-plan",
                request, "change plan of " + subscriptionRef));
    }

    public CatalogSubscription terminate(String subscriptionRef) {
        return toSubscription(post("/api/v1/subscriptions/" + subscriptionRef + "/terminate",
                Map.of(), "terminate " + subscriptionRef));
    }

    private CatalogSubscription toSubscription(JsonNode body) {
        return new CatalogSubscription(
                body.path("subId").asText(),
                ActivationSemantics.fromCatalogCustNo(body.path("custNo").asText()),
                ActivationSemantics.toOfferId(body.path("planCode").asText()),
                body.path("statusCode").asText(),
                ActivationSemantics.fromCatalogStatusCode(body.path("statusCode").asText()),
                body.path("activatedOn").isNull() ? null
                        : LocalDate.parse(body.path("activatedOn").asText()),
                ActivationSemantics.toActivationMsisdn(
                        body.path("msisdn").isNull() ? null : body.path("msisdn").asText()),
                body.path("simIccid").isNull() ? null : body.path("simIccid").asText(),
                ActivationSemantics.toGigabytes(body.path("dataAllowanceMb").asInt()),
                body.path("parentSubId").isNull() ? null : body.path("parentSubId").asText());
    }

    private JsonNode get(String path, String what) {
        try {
            return rest.get().uri(path).retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw describe(what, response.getStatusCode(), readBody(response));
                    })
                    .body(JsonNode.class);
        } catch (ResourceAccessException ex) {
            throw new CatalogClientException("the catalog is unreachable while fetching " + what,
                    true, ex);
        }
    }

    private JsonNode post(String path, Object request, String what) {
        try {
            return rest.post().uri(path).body(request).retrieve()
                    .onStatus(HttpStatusCode::isError, (req, response) -> {
                        throw describe(what, response.getStatusCode(), readBody(response));
                    })
                    .body(JsonNode.class);
        } catch (ResourceAccessException ex) {
            throw new CatalogClientException("the catalog is unreachable while trying to " + what,
                    true, ex);
        }
    }

    private static String readBody(org.springframework.http.client.ClientHttpResponse response) {
        try {
            return new String(response.getBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "<unreadable>";
        }
    }

    /** A 4xx is the order's fault and must not be retried; a 5xx is worth another go. */
    private static CatalogClientException describe(String what, HttpStatusCode status, String body) {
        boolean retryable = status.is5xxServerError();
        return new CatalogClientException(
                "the catalog refused to " + what + " with " + status.value() + ": " + body,
                retryable, null);
    }

    // ---------------------------------------------------------------- responses

    /** A catalog plan, already converted into activation's units. */
    public record CatalogPlan(String planCode, String offerId, String serviceKind, String displayName,
                              String planKind, String addonCategory, BigDecimal monthlyFeeHufGross,
                              BigDecimal dataAllowanceGb, boolean active) {
        public boolean isMobile() {
            return "M".equals(serviceKind);
        }

        public boolean isAddon() {
            return "ADDON".equals(planKind);
        }
    }

    public record CatalogSubscriber(String custNo, int customerRef, String fullName, String email,
                                    String msisdn) {
    }

    /** {@code catalogStatusCode} is kept alongside the translation so ops can see both. */
    public record CatalogSubscription(String subscriptionRef, int customerRef, String offerId,
                                      String catalogStatusCode, String status, LocalDate activatedOn,
                                      String msisdn, String simIccid, BigDecimal dataAllowanceGb,
                                      String parentSubscriptionRef) {
    }
}

package hu.mclsaat.legacy.clients.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CanonicalSubscriber;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CanonicalSubscription;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CustomerRef;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.PhoneNumber;
import hu.mclsaat.legacy.clients.mapping.SemanticMappers;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * The catalog's write side over REST: subscriber onboarding and subscription termination.
 *
 * <p>The name predates {@link #terminateSubscription}. It is kept because this is the one REST client
 * for subsystem 1 (the shared layer has one client per protocol), and a second class would repeat the
 * HTTP plumbing and the failure type for a single method. {@link CatalogJdbcClient} stays read-only: a
 * direct UPDATE would skip the catalog's own rules, such as recomputing a parent's effective allowance
 * when an add-on ends.
 */
public class CatalogSubscriberRestClient {

    private final RestClient rest;
    private final String baseUrl;

    public CatalogSubscriberRestClient(RestClient.Builder builder, String baseUrl) {
        this.baseUrl = baseUrl;
        this.rest = builder.baseUrl(baseUrl).build();
    }

    /** Create one subscriber in the catalog, then translate it into the canonical model. */
    public CanonicalSubscriber createSubscriber(CreateSubscriberRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("fullName", request.fullName());
        payload.put("email", request.email());
        payload.put("msisdn", request.phoneNumber() == null ? null : request.phoneNumber().withoutPlus());
        JsonNode body = post("/api/v1/subscribers", payload,
                "subscriber onboarding for " + request.email());
        return new CanonicalSubscriber(
                CustomerRef.of(SemanticMappers.fromCatalogCustNo(text(body, "custNo"))),
                text(body, "fullName"),
                text(body, "email"),
                PhoneNumber.of(text(body, "msisdn")));
    }

    /**
     * Terminate a catalog subscription ({@code TE}) and return it as the catalog now holds it.
     *
     * <p>The catalog makes this idempotent, so a repeat returns the already-terminated subscription
     * unchanged. That is what makes it safe to re-issue after a timeout without knowing whether the
     * first call landed. Terminating an add-on also lowers its parent's effective allowance, on the
     * catalog's side. An unknown subscription is a {@link CatalogClientException} naming HTTP 404.
     */
    public CanonicalSubscription terminateSubscription(String subscriptionId) {
        String what = "termination of subscription " + subscriptionId;
        JsonNode body = post("/api/v1/subscriptions/{subId}/terminate", null, what, subscriptionId);
        if (body == null) {
            throw new CatalogClientException("catalog answered " + what + " with an empty body", null);
        }
        String activatedOn = text(body, "activatedOn");
        return SemanticMappers.fromCatalogSubscription(
                text(body, "subId"),
                text(body, "custNo"),
                text(body, "planCode"),
                text(body, "statusCode"),
                activatedOn == null ? null : LocalDate.parse(activatedOn),
                text(body, "msisdn"),
                text(body, "simIccid"),
                body.required("dataAllowanceMb").asInt(),
                text(body, "parentSubId"),
                OffsetDateTime.parse(body.required("updatedTs").asText()).toInstant());
    }

    private JsonNode post(String uri, Object request, String what, Object... uriVariables) {
        try {
            RestClient.RequestBodySpec spec = rest.post().uri(uri, uriVariables);
            if (request != null) {
                spec.body(request);
            }
            return spec.retrieve()
                    .onStatus(HttpStatusCode::isError, (req, response) -> {
                        throw failure(what, response.getStatusCode().value(), body(response));
                    })
                    .body(JsonNode.class);
        } catch (ResourceAccessException ex) {
            throw new CatalogClientException(
                    "catalog at " + baseUrl + " is unreachable while requesting " + what, ex);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String body(org.springframework.http.client.ClientHttpResponse response) {
        try {
            return new String(response.getBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "<unreadable>";
        }
    }

    private static CatalogClientException failure(String what, int status, String body) {
        return new CatalogClientException(
                "catalog refused " + what + " with HTTP " + status + ": " + body, null);
    }

    public static class CatalogClientException extends RuntimeException {
        public CatalogClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record CreateSubscriberRequest(String fullName, String email, PhoneNumber phoneNumber) {
    }
}

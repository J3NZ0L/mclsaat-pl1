package hu.mclsaat.legacy.clients.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CanonicalSubscriber;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CustomerRef;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.PhoneNumber;
import hu.mclsaat.legacy.clients.mapping.SemanticMappers;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Subscriber onboarding in subsystem 1 over REST.
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

    private JsonNode post(String uri, Object request, String what) {
        try {
            return rest.post().uri(uri).body(request).retrieve()
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

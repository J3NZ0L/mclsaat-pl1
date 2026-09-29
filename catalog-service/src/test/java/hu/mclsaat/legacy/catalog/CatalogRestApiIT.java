package hu.mclsaat.legacy.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import hu.mclsaat.legacy.catalog.web.CatalogDtos;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the REST surface of subsystem 1 the way subsystem 2 and the chat client will. */
class CatalogRestApiIT extends PostgresIntegrationTest {

    @Autowired TestRestTemplate rest;

    @Test
    void browsePlansLeaksTheLegacyVocabularyOnTheWire() {
        JsonNode plans = rest.getForObject("/api/v1/plans?serviceKind=M&planKind=BASE", JsonNode.class);
        assertThat(plans.isArray()).isTrue();

        JsonNode alap = null;
        for (JsonNode plan : plans) {
            if ("MOB-VOICE-0010".equals(plan.get("planCode").asText())) {
                alap = plan;
            }
        }
        assertThat(alap).as("MOB-VOICE-0010 must be in the mobile base plan list").isNotNull();
        assertThat(alap.get("serviceKind").asText()).isEqualTo("M");
        assertThat(alap.get("monthlyFeeMinor").asLong()).isEqualTo(599_000L);
        assertThat(alap.get("currency").asText()).isEqualTo("HUF");
        assertThat(alap.get("dataAllowanceMb").asInt()).isEqualTo(10_240);
        assertThat(alap.get("activeFlag").asText()).isEqualTo("Y");
    }

    @Test
    void addonCategoryFilterWorks() {
        JsonNode roaming = rest.getForObject(
                "/api/v1/plans?planKind=ADDON&addonCategory=ROAMING", JsonNode.class);
        assertThat(roaming).hasSize(1);
        assertThat(roaming.get(0).get("planCode").asText()).isEqualTo("ADDON-ROAM-EU01");
    }

    @Test
    void unknownPlanIs404WithAStructuredError() {
        var response = rest.getForEntity("/api/v1/plans/NOPE", CatalogDtos.ApiError.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void badServiceKindIs400() {
        var response = rest.getForEntity("/api/v1/plans?serviceKind=X", CatalogDtos.ApiError.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void reserveAndActivateOverRest() {
        var reserved = rest.postForEntity("/api/v1/subscriptions",
                new CatalogDtos.ReserveSubscriptionRequest("00000042", "MOB-VOICE-0010", "36301234567"),
                CatalogDtos.SubscriptionView.class);
        assertThat(reserved.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String subId = reserved.getBody().subId();
        assertThat(reserved.getBody().statusCode()).isEqualTo("PA");

        var activated = rest.postForEntity("/api/v1/subscriptions/" + subId + "/activate",
                new CatalogDtos.ActivateRequest("8936010055555555505", null),
                CatalogDtos.SubscriptionView.class);
        assertThat(activated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(activated.getBody().statusCode()).isEqualTo("AC");
        assertThat(activated.getBody().activatedOn()).isNotNull();

        var addon = rest.postForEntity("/api/v1/subscriptions/" + subId + "/addons",
                new CatalogDtos.ReserveAddonRequest("ADDON-DATA-0010"),
                CatalogDtos.SubscriptionView.class);
        assertThat(addon.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        rest.postForEntity("/api/v1/subscriptions/" + addon.getBody().subId() + "/activate",
                new CatalogDtos.ActivateRequest(null, null), CatalogDtos.SubscriptionView.class);

        var afterAddon = rest.getForObject("/api/v1/subscriptions/" + subId,
                CatalogDtos.SubscriptionView.class);
        assertThat(afterAddon.dataAllowanceMb()).isEqualTo(20_480);
    }

    @Test
    void malformedCustomerNumberIsRejectedByValidation() {
        var response = rest.postForEntity("/api/v1/subscriptions",
                new CatalogDtos.ReserveSubscriptionRequest("42", "MOB-VOICE-0010", "36301234567"),
                CatalogDtos.ApiError.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().message()).contains("custNo");
    }

    @Test
    void createSubscriberAllocatesAZeroPaddedCustomerNumber() {
        var created = rest.postForEntity("/api/v1/subscribers",
                new CatalogDtos.CreateSubscriberRequest("Teszt Elek", "teszt.elek@example.hu", "36301112222"),
                CatalogDtos.SubscriberView.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().custNo()).matches("^[0-9]{8}$");

        var fetched = rest.getForObject("/api/v1/subscribers/" + created.getBody().custNo(),
                CatalogDtos.SubscriberView.class);
        assertThat(fetched.fullName()).isEqualTo("Teszt Elek");
    }

    @Test
    void subscriptionsOfSeededCustomerAreListed() {
        var subs = rest.getForObject("/api/v1/subscribers/00000042/subscriptions", JsonNode.class);
        boolean hasFibre = false;
        for (JsonNode sub : subs) {
            if ("SUB-2026-000001".equals(sub.get("subId").asText())) {
                hasFibre = true;
                assertThat(sub.get("statusCode").asText()).isEqualTo("AC");
                assertThat(sub.get("dataAllowanceMb").asInt()).isEqualTo(-1);
            }
        }
        assertThat(hasFibre).isTrue();
    }
}

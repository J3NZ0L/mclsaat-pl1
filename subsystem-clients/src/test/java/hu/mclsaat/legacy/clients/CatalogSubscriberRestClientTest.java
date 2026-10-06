package hu.mclsaat.legacy.clients;

import hu.mclsaat.legacy.clients.canonical.CanonicalModel.PhoneNumber;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.SubscriptionStatus;
import hu.mclsaat.legacy.clients.protocol.CatalogSubscriberRestClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CatalogSubscriberRestClientTest {

    @Test
    void createSubscriberUsesCatalogsWireShapeAndReturnsCanonicalSubscriber() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CatalogSubscriberRestClient client = new CatalogSubscriberRestClient(builder, "http://catalog.test");

        server.expect(requestTo("http://catalog.test/api/v1/subscribers"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "fullName":"Test User",
                          "email":"test.user@example.com",
                          "msisdn":"36301234567"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "custNo":"00000123",
                          "fullName":"Test User",
                          "email":"test.user@example.com",
                          "msisdn":"36301234567",
                          "createdTs":"2026-10-05T10:02:00Z"
                        }
                        """, MediaType.APPLICATION_JSON));

        var subscriber = client.createSubscriber(new CatalogSubscriberRestClient.CreateSubscriberRequest(
                "Test User", "test.user@example.com", PhoneNumber.of("+36301234567")));

        assertThat(subscriber.customer().reference()).isEqualTo(123);
        assertThat(subscriber.fullName()).isEqualTo("Test User");
        assertThat(subscriber.email()).isEqualTo("test.user@example.com");
        assertThat(subscriber.phoneNumber().withPlus()).isEqualTo("+36301234567");
        server.verify();
    }

    private static final String TERMINATED_ORPHAN = """
            {
              "subId":"SUB-2026-000009",
              "custNo":"00000042",
              "planCode":"MOB-VOICE-0050",
              "statusCode":"TE",
              "activatedOn":null,
              "msisdn":null,
              "simIccid":null,
              "dataAllowanceMb":51200,
              "parentSubId":null,
              "createdTs":"2026-10-05T09:00:00Z",
              "updatedTs":"2026-10-06T08:15:30Z"
            }
            """;

    @Test
    void terminatingASubscriptionPostsToTheCatalogAndReturnsTheCanonicalSubscription() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CatalogSubscriberRestClient client = new CatalogSubscriberRestClient(builder, "http://catalog.test");

        server.expect(requestTo("http://catalog.test/api/v1/subscriptions/SUB-2026-000009/terminate"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(TERMINATED_ORPHAN, MediaType.APPLICATION_JSON));

        var subscription = client.terminateSubscription("SUB-2026-000009");

        assertThat(subscription.subscriptionId()).isEqualTo("SUB-2026-000009");
        assertThat(subscription.status()).isEqualTo(SubscriptionStatus.TERMINATED);
        assertThat(subscription.catalogStatusCode()).isEqualTo("TE");
        assertThat(subscription.customer().reference()).isEqualTo(42);
        assertThat(subscription.activatedOn()).isNull();
        assertThat(subscription.phoneNumber()).isNull();
        assertThat(subscription.dataAllowance().megabytes()).isEqualTo(51200L);
        assertThat(subscription.updatedAt()).hasToString("2026-10-06T08:15:30Z");
        server.verify();
    }

    @Test
    void terminatingAnAlreadyTerminatedSubscriptionIsHarmlessAndReturnsTheSameThing() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CatalogSubscriberRestClient client = new CatalogSubscriberRestClient(builder, "http://catalog.test");

        // the catalog returns the unchanged row, updatedTs included, on a repeat
        server.expect(org.springframework.test.web.client.ExpectedCount.twice(),
                        requestTo("http://catalog.test/api/v1/subscriptions/SUB-2026-000009/terminate"))
                .andRespond(withSuccess(TERMINATED_ORPHAN, MediaType.APPLICATION_JSON));

        var first = client.terminateSubscription("SUB-2026-000009");
        var second = client.terminateSubscription("SUB-2026-000009");

        assertThat(second).isEqualTo(first);
        assertThat(second.status()).isEqualTo(SubscriptionStatus.TERMINATED);
        server.verify();
    }

    @Test
    void terminatingAnAddonCarriesItsParentAndTheUnmeteredSentinelIsTranslated() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CatalogSubscriberRestClient client = new CatalogSubscriberRestClient(builder, "http://catalog.test");

        server.expect(requestTo("http://catalog.test/api/v1/subscriptions/SUB-2026-000011/terminate"))
                .andRespond(withSuccess("""
                        {
                          "subId":"SUB-2026-000011",
                          "custNo":"00000043",
                          "planCode":"MOB-ADDON-5GB",
                          "statusCode":"TE",
                          "activatedOn":"2026-09-29",
                          "msisdn":"36209876543",
                          "simIccid":"8936300000000000011",
                          "dataAllowanceMb":-1,
                          "parentSubId":"SUB-2026-000010",
                          "createdTs":"2026-09-29T10:00:00Z",
                          "updatedTs":"2026-10-06T08:15:30+00:00"
                        }
                        """, MediaType.APPLICATION_JSON));

        var addon = client.terminateSubscription("SUB-2026-000011");

        assertThat(addon.isAddon()).isTrue();
        assertThat(addon.parentSubscriptionId()).isEqualTo("SUB-2026-000010");
        assertThat(addon.dataAllowance().isUnmetered()).isTrue();
        assertThat(addon.activatedOn()).hasToString("2026-09-29");
        assertThat(addon.phoneNumber().withPlus()).isEqualTo("+36209876543");
        server.verify();
    }

    @Test
    void terminatingAnUnknownSubscriptionIsAClearFailureThatNamesTheStatusAndTheCatalogsReason() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CatalogSubscriberRestClient client = new CatalogSubscriberRestClient(builder, "http://catalog.test");

        server.expect(requestTo("http://catalog.test/api/v1/subscriptions/SUB-NOPE/terminate"))
                .andRespond(withResourceNotFound().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"NOT_FOUND\",\"message\":\"subscription SUB-NOPE not found\"}"));

        assertThatThrownBy(() -> client.terminateSubscription("SUB-NOPE"))
                .isInstanceOf(CatalogSubscriberRestClient.CatalogClientException.class)
                .hasMessageContaining("termination of subscription SUB-NOPE")
                .hasMessageContaining("HTTP 404")
                .hasMessageContaining("not found");
        server.verify();
    }

    @Test
    void anUnreachableCatalogIsReportedAsSuchWhenTerminating() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CatalogSubscriberRestClient client = new CatalogSubscriberRestClient(builder, "http://catalog.test");

        server.expect(requestTo("http://catalog.test/api/v1/subscriptions/SUB-2026-000009/terminate"))
                .andRespond(request -> {
                    throw new java.io.IOException("Connection refused");
                });

        assertThatThrownBy(() -> client.terminateSubscription("SUB-2026-000009"))
                .isInstanceOf(CatalogSubscriberRestClient.CatalogClientException.class)
                .hasMessageContaining("http://catalog.test is unreachable");
    }
}

package hu.mclsaat.legacy.clients;

import hu.mclsaat.legacy.clients.canonical.CanonicalModel.OrderStatus;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.PhoneNumber;
import hu.mclsaat.legacy.clients.protocol.ActivationRestClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ActivationRestClientTest {

    @Test
    void startOrderTranslatesCanonicalFieldsToActivationsDialect() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ActivationRestClient client = new ActivationRestClient(builder, "http://activation.test");

        server.expect(requestTo("http://activation.test/activation/v1/orders"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "changeType":"PLAN_CHANGE",
                          "customerRef":42,
                          "offerId":"mob.voice.0050",
                          "msisdn":null,
                          "requestedStartDate":"20261005",
                          "targetSubscriptionRef":"SUB-2026-000777",
                          "simulateStuck":false,
                          "provisioningTimeout":null
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "orderNo":"ORD/2026/0000123",
                          "changeType":"PLAN_CHANGE",
                          "customerRef":42,
                          "offerId":"mob.voice.0050",
                          "status":"RECEIVED",
                          "subscriptionRef":"SUB-2026-000777",
                          "updatedTs":"2026-10-05T10:00:00Z"
                        }
                        """, MediaType.APPLICATION_JSON));

        var order = client.startOrder(new ActivationRestClient.StartOrderRequest(
                ActivationRestClient.CHANGE_PLAN_CHANGE,
                42,
                "MOB-VOICE-0050",
                null,
                LocalDate.of(2026, 10, 5),
                "SUB-2026-000777",
                false,
                null));

        assertThat(order.orderNo()).isEqualTo("ORD/2026/0000123");
        assertThat(order.productCode()).isEqualTo("MOB-VOICE-0050");
        assertThat(order.status()).isEqualTo(OrderStatus.RECEIVED);
        assertThat(order.customer().reference()).isEqualTo(42);
        server.verify();
    }

    @Test
    void startNewSubscriptionSendsTheMsisdnWithPlusPrefixForActivation() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ActivationRestClient client = new ActivationRestClient(builder, "http://activation.test");

        server.expect(requestTo("http://activation.test/activation/v1/orders"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "changeType":"NEW_SUBSCRIPTION",
                          "customerRef":43,
                          "offerId":"mob.voice.0010",
                          "msisdn":"+36301234567",
                          "requestedStartDate":"20261006",
                          "targetSubscriptionRef":null,
                          "simulateStuck":false,
                          "provisioningTimeout":null
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "orderNo":"ORD/2026/0000124",
                          "changeType":"NEW_SUBSCRIPTION",
                          "customerRef":43,
                          "offerId":"mob.voice.0010",
                          "status":"AWAITING_PROVISIONING",
                          "updatedTs":"2026-10-05T10:01:00Z"
                        }
                        """, MediaType.APPLICATION_JSON));

        var order = client.startNewSubscription(43, "MOB-VOICE-0010", PhoneNumber.of("36301234567"),
                LocalDate.of(2026, 10, 6));

        assertThat(order.status()).isEqualTo(OrderStatus.AWAITING_PROVISIONING);
        assertThat(order.productCode()).isEqualTo("MOB-VOICE-0010");
        assertThat(order.customer().reference()).isEqualTo(43);
        server.verify();
    }
}

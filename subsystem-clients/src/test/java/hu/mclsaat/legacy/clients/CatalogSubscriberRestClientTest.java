package hu.mclsaat.legacy.clients;

import hu.mclsaat.legacy.clients.canonical.CanonicalModel.PhoneNumber;
import hu.mclsaat.legacy.clients.protocol.CatalogSubscriberRestClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
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
}

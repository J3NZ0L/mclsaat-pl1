package hu.mclsaat.legacy.billing;

import com.fasterxml.jackson.databind.JsonNode;
import hu.mclsaat.legacy.billing.repo.InvoiceRepository;
import hu.mclsaat.legacy.billing.ws.gen.CreateInvoiceRequest;
import hu.mclsaat.legacy.billing.ws.gen.CreateInvoiceResponse;
import hu.mclsaat.legacy.billing.ws.gen.StartPaymentRequest;
import hu.mclsaat.legacy.billing.ws.gen.StartPaymentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.SoapFaultClientException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Start a payment over SOAP, hear about its success over a REST webhook: two protocols, one
 * invoice. The Stripe leg goes through the real SDK to a real stripe-mock container.
 */
class PaymentFlowIT extends BillingIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired InvoiceRepository invoices;

    private WebServiceTemplate soap;

    @BeforeEach
    void setUpSoapClient() {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath("hu.mclsaat.legacy.billing.ws.gen");
        soap = new WebServiceTemplate(marshaller);
        soap.setDefaultUri("http://localhost:" + port + "/ws");
    }

    @Test
    void startPaymentCreatesAStripeIntentAndRecordsItOnTheInvoice() {
        String invoiceNo = issueInvoice(new BigDecimal("10228.35"));

        StartPaymentResponse response = startPayment(invoiceNo);

        assertThat(response.getPaymentRef()).startsWith("pi_");
        assertThat(response.getClientSecret()).isNotBlank();
        // 12990.00 gross, handed to Stripe as 1299000 minor units
        assertThat(response.getAmountMinor()).isEqualTo(1_299_000L);
        assertThat(response.getCurrency()).isEqualTo("huf");

        var stored = invoices.findByInvoiceNo(invoiceNo).orElseThrow();
        assertThat(stored.paymentRef()).isEqualTo(response.getPaymentRef());
        // starting a payment must not move the invoice: Stripe has not taken anything yet
        assertThat(stored.status()).isEqualTo("OPEN");
    }

    @Test
    void theWebhookMovesTheInvoiceToSettlementPendingAndNotStraightToPaid() {
        String invoiceNo = issueInvoice(new BigDecimal("4716.54"));
        StartPaymentResponse payment = startPayment(invoiceNo);

        var webhook = postPaymentSucceeded(payment.getPaymentRef(), invoiceNo);

        assertThat(webhook.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(webhook.getBody().get("handled").asBoolean()).isTrue();
        assertThat(webhook.getBody().get("changed").asBoolean()).isTrue();
        // the money is at Stripe but the clearing house has not confirmed the settlement batch
        assertThat(invoices.findByInvoiceNo(invoiceNo).orElseThrow().status())
                .isEqualTo("SETTLEMENT_PENDING");
    }

    @Test
    void theWebhookIsIdempotentBecauseStripeRetries() {
        String invoiceNo = issueInvoice(new BigDecimal("1000.00"));
        StartPaymentResponse payment = startPayment(invoiceNo);

        postPaymentSucceeded(payment.getPaymentRef(), invoiceNo);
        var second = postPaymentSucceeded(payment.getPaymentRef(), invoiceNo);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("changed").asBoolean()).isFalse();
        assertThat(second.getBody().get("status").asText()).isEqualTo("SETTLEMENT_PENDING");
    }

    @Test
    void anInvoiceWithoutMetadataIsStillFoundByItsPaymentIntentId() {
        String invoiceNo = issueInvoice(new BigDecimal("2000.00"));
        StartPaymentResponse payment = startPayment(invoiceNo);

        var webhook = postPaymentSucceeded(payment.getPaymentRef(), null);

        assertThat(webhook.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(webhook.getBody().get("invoiceNo").asText()).isEqualTo(invoiceNo);
    }

    @Test
    void unrelatedStripeEventsAreAcknowledgedAndIgnored() {
        var response = rest.postForEntity("/webhook/stripe",
                Map.of("type", "charge.refunded", "data", Map.of("object", Map.of("id", "ch_x"))),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("handled").asBoolean()).isFalse();
    }

    @Test
    void anUnknownPaymentIntentIs404RatherThanASilentSuccess() {
        var response = postPaymentSucceeded("pi_does_not_exist", null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void payingAnInvoiceTwiceIsRefused() {
        String invoiceNo = issueInvoice(new BigDecimal("3000.00"));
        StartPaymentResponse payment = startPayment(invoiceNo);
        postPaymentSucceeded(payment.getPaymentRef(), invoiceNo);

        assertThatThrownBy(() -> startPayment(invoiceNo))
                .isInstanceOf(SoapFaultClientException.class)
                .hasMessageContaining("ILLEGAL_STATE");
    }

    @Test
    void payingAnInvoiceThatDoesNotExistIsAFault() {
        assertThatThrownBy(() -> startPayment("2026/INV/999999"))
                .isInstanceOf(SoapFaultClientException.class)
                .hasMessageContaining("NOT_FOUND");
    }

    private String issueInvoice(BigDecimal netAmount) {
        CreateInvoiceRequest request = new CreateInvoiceRequest();
        request.setCustomerRef(42);
        request.setSubscriptionRef("SUB-2026-000001");
        request.setPeriodStart("2026-11-01");
        request.setPeriodEnd("2026-11-30");
        request.setNetAmount(netAmount);
        request.setCurrency("HUF");
        request.setRequestRef("payment-it-" + UUID.randomUUID());
        return ((CreateInvoiceResponse) soap.marshalSendAndReceive(request)).getInvoice().getInvoiceNo();
    }

    private StartPaymentResponse startPayment(String invoiceNo) {
        StartPaymentRequest request = new StartPaymentRequest();
        request.setInvoiceNo(invoiceNo);
        return (StartPaymentResponse) soap.marshalSendAndReceive(request);
    }

    /** The JSON Stripe itself would POST once the customer's card clears. */
    private org.springframework.http.ResponseEntity<JsonNode> postPaymentSucceeded(
            String paymentIntentId, String invoiceNo) {
        Map<String, Object> object = invoiceNo == null
                ? Map.of("id", paymentIntentId, "object", "payment_intent")
                : Map.of("id", paymentIntentId, "object", "payment_intent",
                         "metadata", Map.of("invoice_no", invoiceNo));
        return rest.postForEntity("/webhook/stripe",
                Map.of("id", "evt_" + UUID.randomUUID(),
                       "type", "payment_intent.succeeded",
                       "data", Map.of("object", object)),
                JsonNode.class);
    }
}

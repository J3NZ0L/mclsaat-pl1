package hu.mclsaat.legacy.billing.web;

import com.fasterxml.jackson.databind.JsonNode;
import hu.mclsaat.legacy.billing.payment.PaymentService;
import hu.mclsaat.legacy.billing.service.BillingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Stripe's side of the conversation: a REST/JSON webhook, inside a subsystem that otherwise only
 * speaks SOAP and fixed-width files.
 *
 * <p>Signature verification is deliberately absent. There is no real Stripe account and therefore
 * no webhook signing secret, and pretending to verify one would be theatre. With a real test key
 * this is where {@code Webhook.constructEvent(payload, sigHeader, endpointSecret)} goes, and the
 * endpoint must not be reachable from outside the compose network until it does.
 */
@RestController
@RequestMapping("/webhook/stripe")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final PaymentService payments;

    public StripeWebhookController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> receive(@RequestBody JsonNode event) {
        String type = event.path("type").asText("");
        if (!"payment_intent.succeeded".equals(type)) {
            log.info("ignoring Stripe event of type '{}'", type);
            return ResponseEntity.ok(Map.of("received", true, "handled", false, "type", type));
        }

        JsonNode object = event.path("data").path("object");
        String paymentIntentId = object.path("id").asText(null);
        String invoiceNo = object.path("metadata").path("invoice_no").asText(null);
        if ((paymentIntentId == null || paymentIntentId.isBlank())
                && (invoiceNo == null || invoiceNo.isBlank())) {
            throw new BillingException.BadRequest(
                    "payment_intent.succeeded needs data.object.id or data.object.metadata.invoice_no");
        }

        var result = payments.paymentSucceeded(paymentIntentId, invoiceNo);
        return ResponseEntity.ok(Map.of(
                "received", true,
                "handled", true,
                "invoiceNo", result.invoiceNo(),
                "status", result.status(),
                "changed", result.changed()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(BillingException.class)
    public ResponseEntity<Map<String, Object>> onBillingException(BillingException ex) {
        HttpStatus status = ex instanceof BillingException.NotFound
                ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(Map.of("received", true, "handled", false, "error", ex.getMessage()));
    }
}

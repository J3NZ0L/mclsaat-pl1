package hu.mclsaat.legacy.stripesim;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The three PaymentIntent endpoints this system calls, answering in Stripe's response shape.
 *
 * <p>Unlike {@code stripe/stripe-mock}, which replies with canned data and forgets everything,
 * this simulator keeps the intents it created, so a retrieve after a confirm actually reports
 * {@code succeeded}. That is the only behavioural difference, and it only makes the local path
 * more useful - both are spoken to through the real Stripe Java SDK, and swapping in a real
 * test key means changing two properties and nothing else.
 *
 * <p>The API key is accepted but not checked. This process must never be exposed outside a
 * developer machine or a compose network.
 */
@RestController
public class PaymentIntentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentIntentController.class);

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private final Map<String, Map<String, Object>> intents = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1);

    @PostMapping(value = "/v1/payment_intents", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> create(
            @RequestParam("amount") long amount,
            @RequestParam("currency") String currency,
            @RequestParam Map<String, String> allParams,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        // The random suffix matters: without it a restart resets the counter and hands out an id
        // that an earlier invoice is already carrying, so billing's lookup-by-payment-intent stops
        // being unambiguous. Real Stripe ids never repeat, and neither should these.
        long n = sequence.getAndIncrement();
        String id = "pi_sim_%06d%s".formatted(n, randomSuffix());

        Map<String, Object> metadata = new LinkedHashMap<>();
        allParams.forEach((key, value) -> {
            if (key.startsWith("metadata[") && key.endsWith("]")) {
                metadata.put(key.substring("metadata[".length(), key.length() - 1), value);
            }
        });

        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("id", id);
        intent.put("object", "payment_intent");
        intent.put("amount", amount);
        intent.put("amount_received", 0L);
        intent.put("currency", currency.toLowerCase());
        intent.put("status", "requires_payment_method");
        intent.put("client_secret", id + "_secret_sim" + n);
        intent.put("created", Instant.now().getEpochSecond());
        intent.put("livemode", false);
        intent.put("metadata", metadata);
        intent.put("description", allParams.get("description"));
        intents.put(id, intent);

        log.info("created {} for {} {} (metadata {})", id, amount, currency, metadata);
        return intent;
    }

    @GetMapping(value = "/v1/payment_intents/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> retrieve(@PathVariable String id) {
        Map<String, Object> intent = intents.get(id);
        return intent == null ? notFound(id) : ResponseEntity.ok(intent);
    }

    /**
     * Marks the intent {@code succeeded}. In production the customer's browser does this through
     * Stripe.js; here the demo script calls it so the payment leg can complete without a browser.
     */
    @PostMapping(value = "/v1/payment_intents/{id}/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> confirm(@PathVariable String id) {
        Map<String, Object> intent = intents.get(id);
        if (intent == null) {
            return notFound(id);
        }
        intent.put("status", "succeeded");
        intent.put("amount_received", intent.get("amount"));
        log.info("confirmed {}", id);
        return ResponseEntity.ok(intent);
    }

    private static String randomSuffix() {
        char[] alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
        StringBuilder suffix = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            suffix.append(alphabet[RANDOM.nextInt(alphabet.length)]);
        }
        return suffix.toString();
    }

    /** Stripe's own error envelope, so SDK clients see a normal Stripe error. */
    private ResponseEntity<Map<String, Object>> notFound(String id) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", Map.of(
                "type", "invalid_request_error",
                "code", "resource_missing",
                "message", "No such payment_intent: '" + id + "'",
                "param", "intent")));
    }
}

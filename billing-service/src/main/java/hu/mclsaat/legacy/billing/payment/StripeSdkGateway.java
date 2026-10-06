package hu.mclsaat.legacy.billing.payment;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentConfirmParams;
import com.stripe.param.PaymentIntentCreateParams;
import hu.mclsaat.legacy.billing.service.BillingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StripeSdkGateway implements StripeGateway {

    private static final Logger log = LoggerFactory.getLogger(StripeSdkGateway.class);

    private final String apiBase;
    private final String serverSidePaymentMethod;
    /**
     * Per-request rather than the SDK's static {@code Stripe.apiKey} / {@code overrideApiBase}: a
     * process-wide global would let a second Spring context in the same JVM silently repoint this
     * one, and the integration tests do run several against different Stripe stand-ins.
     */
    private final RequestOptions options;

    public StripeSdkGateway(@Value("${billing.stripe.api-key}") String apiKey,
                            @Value("${billing.stripe.api-base}") String apiBase,
                            @Value("${billing.stripe.server-side-payment-method:pm_card_visa}")
                            String serverSidePaymentMethod) {
        this.apiBase = apiBase;
        this.serverSidePaymentMethod = serverSidePaymentMethod;
        // Points the SDK at stripe-mock / stripe-sim. Set it to https://api.stripe.com together
        // with a real sk_test_... key to talk to Stripe itself.
        this.options = RequestOptions.builder().setApiKey(apiKey).setBaseUrl(apiBase).build();
        log.info("Stripe gateway talking to {}", apiBase);
    }

    @Override
    public PaymentIntentView createPaymentIntent(String invoiceNo, long amountMinor, String currency) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amountMinor)
                .setCurrency(currency.toLowerCase())
                .setDescription("Invoice " + invoiceNo)
                .putMetadata("invoice_no", invoiceNo)
                // Not payment_method_types=card: the current Stripe API (and so the current
                // stripe-mock) has replaced it, and redirects are off because a server-side
                // confirm has no browser to send the customer to.
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                        .build())
                .build();
        try {
            PaymentIntent intent = PaymentIntent.create(params, options);
            log.info("created Stripe PaymentIntent {} for invoice {} ({} {} minor units)",
                    intent.getId(), invoiceNo, amountMinor, currency);
            return new PaymentIntentView(intent.getId(), intent.getClientSecret(),
                    intent.getAmount() == null ? amountMinor : intent.getAmount(),
                    intent.getCurrency() == null ? currency.toLowerCase() : intent.getCurrency(),
                    intent.getStatus());
        } catch (StripeException ex) {
            // The SOAP fault only carries "DOWNSTREAM_FAILURE", so the detail has to be logged
            // here or it is lost.
            log.error("Stripe call failed for invoice {} against {}", invoiceNo, apiBase, ex);
            throw new BillingException.DownstreamFailure(
                    "Stripe at " + apiBase + " rejected the PaymentIntent for invoice " + invoiceNo
                            + ": " + ex.getMessage(), ex);
        }
    }

    @Override
    public PaymentIntentView confirmPaymentIntent(String paymentIntentId) {
        try {
            PaymentIntent intent = PaymentIntent.retrieve(paymentIntentId, options);
            if (!"succeeded".equals(intent.getStatus())) {
                // pm_card_visa is Stripe's test-mode card token; with a live key a stored customer
                // payment method would go here instead, and that is a phase-3 question
                intent = intent.confirm(PaymentIntentConfirmParams.builder()
                        .setPaymentMethod(serverSidePaymentMethod)
                        .build(), options);
            }
            log.info("Stripe PaymentIntent {} is {}", paymentIntentId, intent.getStatus());
            return new PaymentIntentView(intent.getId(), intent.getClientSecret(),
                    intent.getAmount() == null ? 0L : intent.getAmount(),
                    intent.getCurrency(), intent.getStatus());
        } catch (StripeException ex) {
            log.error("Stripe could not confirm {} against {}", paymentIntentId, apiBase, ex);
            throw new BillingException.DownstreamFailure(
                    "Stripe at " + apiBase + " could not confirm PaymentIntent " + paymentIntentId
                            + ": " + ex.getMessage(), ex);
        }
    }
}

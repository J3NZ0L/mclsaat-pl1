package hu.mclsaat.legacy.billing.payment;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
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

    public StripeSdkGateway(@Value("${billing.stripe.api-key}") String apiKey,
                            @Value("${billing.stripe.api-base}") String apiBase) {
        this.apiBase = apiBase;
        Stripe.apiKey = apiKey;
        // Points the SDK at stripe-mock / stripe-sim. Leave unset (or set to
        // https://api.stripe.com) together with a real sk_test_... key to talk to Stripe itself.
        Stripe.overrideApiBase(apiBase);
        log.info("Stripe gateway talking to {}", apiBase);
    }

    @Override
    public PaymentIntentView createPaymentIntent(String invoiceNo, long amountMinor, String currency) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amountMinor)
                .setCurrency(currency.toLowerCase())
                .setDescription("Invoice " + invoiceNo)
                .putMetadata("invoice_no", invoiceNo)
                .addPaymentMethodType("card")
                .build();
        try {
            PaymentIntent intent = PaymentIntent.create(params);
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
}

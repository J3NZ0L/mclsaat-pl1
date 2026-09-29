package hu.mclsaat.legacy.billing.payment;

/**
 * The card-payment side of subsystem 3.
 *
 * <p>There is exactly one implementation, {@link StripeSdkGateway}, and it talks to whatever is
 * at {@code billing.stripe.api-base} through the official Stripe Java SDK: the
 * {@code stripe/stripe-mock} container under Docker compose, the {@code stripe-sim} module on the
 * local path, or {@code https://api.stripe.com} with a real test key. Swapping in a real key is
 * a two-property change, which is the whole reason this is an SDK call and not hand-rolled JSON.
 */
public interface StripeGateway {

    /**
     * @param amountMinor the gross amount as an integer count of minor units, which is Stripe's
     *                    convention and the third money representation the same figure takes on
     *                    its way through this system
     */
    PaymentIntentView createPaymentIntent(String invoiceNo, long amountMinor, String currency);

    /** The subset of Stripe's PaymentIntent this system cares about. */
    record PaymentIntentView(String id, String clientSecret, long amountMinor, String currency,
                             String status) {
    }
}

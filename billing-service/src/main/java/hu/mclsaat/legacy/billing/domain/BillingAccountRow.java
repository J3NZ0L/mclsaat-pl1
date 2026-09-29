package hu.mclsaat.legacy.billing.domain;

import java.time.Instant;

/**
 * One row of {@code billing.billing_account}.
 *
 * <p>{@code customerRef} is a plain integer. The catalog calls the same customer
 * {@code "00000042"}. Nothing enforces the correspondence; it is a convention.
 */
public record BillingAccountRow(
        String baNo,
        int customerRef,
        String accountName,
        String billingEmail,
        String currency,
        Instant createdTs) {
}

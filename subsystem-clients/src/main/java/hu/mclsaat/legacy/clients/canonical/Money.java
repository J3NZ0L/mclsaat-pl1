package hu.mclsaat.legacy.clients.canonical;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An amount of money, once.
 *
 * <p>The landscape writes the same figure four ways: the catalog as an integer count of HUF fillér,
 * activation as a HUF decimal, billing as {@code NUMERIC(12,2)}, and Stripe and the settlement file
 * as integer minor units again. This type is major units plus a currency, and it knows how to hand
 * out the minor-unit form when something downstream insists on it.
 *
 * <p>It does not know about VAT. Whether a figure is gross or net is a property of the field it came
 * out of, not of the number, and conflating them is how the one-fillér discrepancy documented in
 * {@code docs/semantic-mismatches.md} gets lost.
 */
public record Money(BigDecimal amount, String currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (currency.length() != 3) {
            throw new IllegalArgumentException("currency must be an ISO 4217 code, was: " + currency);
        }
        currency = currency.toUpperCase();
        amount = amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** From the catalog's {@code monthly_fee_minor}, or from Stripe's {@code amount}. */
    public static Money ofMinorUnits(long minorUnits, String currency) {
        return new Money(BigDecimal.valueOf(minorUnits, 2), currency);
    }

    /** From activation's HUF decimal or billing's {@code NUMERIC(12,2)}. */
    public static Money ofMajorUnits(BigDecimal majorUnits, String currency) {
        return new Money(majorUnits.setScale(2, RoundingMode.HALF_UP), currency);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    /** The integer form Stripe and the settlement file want. */
    public long minorUnits() {
        return amount.movePointRight(2).longValueExact();
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "cannot combine " + currency + " with " + other.currency);
        }
    }
}

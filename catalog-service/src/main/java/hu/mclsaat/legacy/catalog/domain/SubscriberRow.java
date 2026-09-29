package hu.mclsaat.legacy.catalog.domain;

import java.time.Instant;

/**
 * One row of {@code catalog.subscriber}.
 *
 * <p>{@code custNo} is a zero-padded eight-character numeric string ({@code "00000042"}),
 * not an integer. Subsystems 2 and 3 both refer to the same customer by the plain integer
 * {@code 42}; see {@code docs/semantic-mismatches.md}.
 */
public record SubscriberRow(
        String custNo,
        String fullName,
        String email,
        String msisdn,
        Instant createdTs) {
}

package hu.mclsaat.legacy.catalog.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One row of {@code catalog.subscription}.
 *
 * <p>{@code statusCode} is a two-character legacy code, not a word: {@code NW} new,
 * {@code PA} pending activation, {@code AC} active, {@code SU} suspended, {@code TE}
 * terminated. Subsystem 2 uses an entirely different, spelled-out vocabulary for what is
 * conceptually the same lifecycle.
 */
public record SubscriptionRow(
        String subId,
        String custNo,
        String planCode,
        String statusCode,
        LocalDate activatedOn,
        String msisdn,
        String simIccid,
        int dataAllowanceMb,
        String parentSubId,
        Instant createdTs,
        Instant updatedTs) {

    public static final String STATUS_NEW = "NW";
    public static final String STATUS_PENDING_ACTIVATION = "PA";
    public static final String STATUS_ACTIVE = "AC";
    public static final String STATUS_SUSPENDED = "SU";
    public static final String STATUS_TERMINATED = "TE";

    public boolean isAddon() {
        return parentSubId != null;
    }
}

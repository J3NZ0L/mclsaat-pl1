package hu.mclsaat.legacy.catalog.domain;

/**
 * One row of {@code catalog.plan}, field-for-field.
 *
 * <p>The legacy names and units are preserved on purpose: {@code serviceKind} is a single
 * character, {@code monthlyFeeMinor} counts HUF fillér and {@code dataAllowanceMb} counts
 * megabytes with {@code -1} meaning unmetered. Nothing in this subsystem normalises any of
 * it - translation is the caller's problem, which is exactly the property the modernization
 * experiment is measuring.
 */
public record PlanRow(
        String planCode,
        char serviceKind,
        String displayName,
        String planKind,
        String addonCategory,
        long monthlyFeeMinor,
        int dataAllowanceMb,
        Integer speedKbps,
        boolean active) {

    public static final int UNMETERED = -1;

    public boolean isAddon() {
        return "ADDON".equals(planKind);
    }
}

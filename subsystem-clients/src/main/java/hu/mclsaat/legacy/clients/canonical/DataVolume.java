package hu.mclsaat.legacy.clients.canonical;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A data allowance, or the absence of a limit.
 *
 * <p>The catalog counts megabytes and says {@code -1} for unmetered; activation counts gigabytes to
 * three decimals and says nothing at all. Both of those are ways of writing this type wrong. Storing
 * megabytes internally keeps the catalog's precision exactly, and {@code unmetered} is a state rather
 * than a magic number, so no arithmetic can accidentally be done on it.
 *
 * <p>The conversion factor is 1024, not 1000. See {@code docs/semantic-mismatches.md}.
 */
public record DataVolume(Long megabytes) {

    private static final BigDecimal MB_PER_GB = new BigDecimal("1024");
    private static final DataVolume UNMETERED = new DataVolume(null);

    /** The catalog's {@code -1} and activation's {@code null}, as a state. */
    public static DataVolume unmetered() {
        return UNMETERED;
    }

    public static DataVolume ofMegabytes(long megabytes) {
        if (megabytes < 0) {
            throw new IllegalArgumentException(
                    "a negative allowance is not a volume; use unmetered() for the catalog's -1");
        }
        return new DataVolume(megabytes);
    }

    public static DataVolume ofGigabytes(BigDecimal gigabytes) {
        if (gigabytes == null) {
            return unmetered();
        }
        return ofMegabytes(gigabytes.multiply(MB_PER_GB).setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    public boolean isUnmetered() {
        return megabytes == null;
    }

    /** @return {@code null} when unmetered, which is activation's convention */
    public BigDecimal gigabytes() {
        return isUnmetered() ? null
                : BigDecimal.valueOf(megabytes).divide(MB_PER_GB, 3, RoundingMode.HALF_UP);
    }

    /** @return the catalog's column value, {@code -1} included */
    public int catalogMegabytes() {
        return isUnmetered() ? -1 : Math.toIntExact(megabytes);
    }

    public DataVolume plus(DataVolume other) {
        if (isUnmetered() || other.isUnmetered()) {
            return unmetered();
        }
        return ofMegabytes(megabytes + other.megabytes);
    }

    @Override
    public String toString() {
        return isUnmetered() ? "unmetered" : gigabytes().toPlainString() + " GB";
    }
}

package hu.mclsaat.legacy.activation.semantics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Every translation between this subsystem's dialect and the ones spoken by the catalog and by
 * billing, in one place.
 *
 * <p>This class exists because the mismatches are real and there are a lot of them. It is also the
 * single most interesting file in the repository for the modernization experiment: phase 2's MCP
 * server will have to do exactly this work, and the tokenomics comparison is between paying a
 * model to infer these rules from schemas in a prompt and having them written down once.
 *
 * <table>
 *   <caption>The mismatch table</caption>
 *   <tr><th>concept</th><th>catalog</th><th>activation</th><th>billing</th></tr>
 *   <tr><td>customer</td><td>{@code "00000042"} CHAR(8)</td><td>{@code 42} int</td>
 *       <td>{@code 42} int / {@code "BA-00042"}</td></tr>
 *   <tr><td>product</td><td>{@code "MOB-VOICE-0010"}</td><td>{@code "mob.voice.0010"}</td>
 *       <td>not modelled</td></tr>
 *   <tr><td>data</td><td>{@code 10240} MB int, {@code -1} unmetered</td>
 *       <td>{@code 10.000} GB decimal, {@code null} unmetered</td><td>not modelled</td></tr>
 *   <tr><td>money</td><td>{@code 599000} fillér BIGINT, gross</td>
 *       <td>{@code 5990.00} HUF decimal, gross</td>
 *       <td>{@code 4716.54} NUMERIC(12,2), net</td></tr>
 *   <tr><td>date</td><td>{@code DATE}</td><td>{@code "20260929"}</td><td>{@code "2026-09-29"}</td></tr>
 *   <tr><td>phone</td><td>{@code "36301234567"}</td><td>{@code "+36301234567"}</td>
 *       <td>not modelled</td></tr>
 *   <tr><td>status</td><td>{@code "PA"}, {@code "AC"}</td>
 *       <td>{@code "AWAITING_PROVISIONING"}, {@code "PROVISIONED"}</td>
 *       <td>{@code "SETTLEMENT_PENDING"}, {@code "PAID"}</td></tr>
 * </table>
 */
public final class ActivationSemantics {

    /** Activation's date format. Not the catalog's DATE and not billing's {@code yyyy-MM-dd}. */
    public static final DateTimeFormatter ORDER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** Billing's date format on the SOAP wire. */
    public static final DateTimeFormatter BILLING_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final BigDecimal MB_PER_GB = new BigDecimal("1024");
    private static final BigDecimal FILLER_PER_HUF = new BigDecimal("100");
    private static final BigDecimal VAT_MULTIPLIER = new BigDecimal("1.27");

    /** The catalog's sentinel for an unmetered plan. Activation uses {@code null} instead. */
    public static final int CATALOG_UNMETERED = -1;

    private ActivationSemantics() {
    }

    // ---------------------------------------------------------------- identity

    /** {@code 42} to the catalog's {@code "00000042"}. */
    public static String toCatalogCustNo(int customerRef) {
        if (customerRef <= 0 || customerRef > 99_999_999) {
            throw new IllegalArgumentException(
                    "customerRef " + customerRef + " does not fit the catalog's CHAR(8) cust_no");
        }
        return "%08d".formatted(customerRef);
    }

    /** The catalog's {@code "00000042"} back to {@code 42}. Tolerates the CHAR padding. */
    public static int fromCatalogCustNo(String custNo) {
        return Integer.parseInt(custNo.trim());
    }

    // ----------------------------------------------------------------- product

    /** {@code "mob.voice.0010"} to the catalog's {@code "MOB-VOICE-0010"}. */
    public static String toCatalogPlanCode(String offerId) {
        if (offerId == null || offerId.isBlank()) {
            throw new IllegalArgumentException("offerId is required");
        }
        return offerId.trim().toUpperCase().replace('.', '-');
    }

    /** The catalog's {@code "MOB-VOICE-0010"} to {@code "mob.voice.0010"}. */
    public static String toOfferId(String planCode) {
        if (planCode == null || planCode.isBlank()) {
            throw new IllegalArgumentException("planCode is required");
        }
        return planCode.trim().toLowerCase().replace('-', '.');
    }

    // -------------------------------------------------------------------- data

    /**
     * The catalog's megabyte integer to activation's GB decimal.
     *
     * @return {@code null} for the catalog's {@code -1} sentinel, because activation says
     *         "unmetered" by saying nothing
     */
    public static BigDecimal toGigabytes(int megabytes) {
        if (megabytes == CATALOG_UNMETERED) {
            return null;
        }
        return BigDecimal.valueOf(megabytes).divide(MB_PER_GB, 3, RoundingMode.HALF_UP);
    }

    /** Activation's GB decimal back to the catalog's megabyte integer. */
    public static int toMegabytes(BigDecimal gigabytes) {
        if (gigabytes == null) {
            return CATALOG_UNMETERED;
        }
        return gigabytes.multiply(MB_PER_GB).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    // ------------------------------------------------------------------- money

    /** The catalog's gross fillér integer to activation's gross HUF decimal. */
    public static BigDecimal toHuf(long minorUnits) {
        return BigDecimal.valueOf(minorUnits).divide(FILLER_PER_HUF, 2, RoundingMode.UNNECESSARY);
    }

    /**
     * Activation's gross HUF to the net amount billing wants.
     *
     * <p>The catalog quotes gross consumer prices; billing invoices on net and adds VAT back.
     * Both sides round to two decimals, so this does not always round-trip - see
     * {@code docs/semantic-mismatches.md} and {@code VatCalculatorTest} on the billing side.
     */
    public static BigDecimal toBillingNet(BigDecimal grossHuf) {
        return grossHuf.divide(VAT_MULTIPLIER, 2, RoundingMode.HALF_UP);
    }

    // -------------------------------------------------------------------- date

    /** Activation's {@code "20260929"} to a real date. */
    public static LocalDate parseOrderDate(String yyyyMMdd) {
        if (yyyyMMdd == null || yyyyMMdd.isBlank()) {
            throw new IllegalArgumentException("requestedStartDate is required (yyyyMMdd)");
        }
        try {
            return LocalDate.parse(yyyyMMdd.trim(), ORDER_DATE);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "requestedStartDate must be formatted yyyyMMdd, was: " + yyyyMMdd);
        }
    }

    public static String formatOrderDate(LocalDate date) {
        return date.format(ORDER_DATE);
    }

    /** Activation's {@code "20260929"} straight to billing's {@code "2026-09-29"}. */
    public static String toBillingDate(String yyyyMMdd) {
        return parseOrderDate(yyyyMMdd).format(BILLING_DATE);
    }

    // ------------------------------------------------------------------- phone

    /** Activation's {@code "+36301234567"} to the catalog's {@code "36301234567"}. */
    public static String toCatalogMsisdn(String msisdn) {
        if (msisdn == null || msisdn.isBlank()) {
            return null;
        }
        String trimmed = msisdn.trim();
        return trimmed.startsWith("+") ? trimmed.substring(1) : trimmed;
    }

    /** The catalog's {@code "36301234567"} to activation's {@code "+36301234567"}. */
    public static String toActivationMsisdn(String msisdn) {
        if (msisdn == null || msisdn.isBlank()) {
            return null;
        }
        String trimmed = msisdn.trim();
        return trimmed.startsWith("+") ? trimmed : "+" + trimmed;
    }

    // ------------------------------------------------------------------ status

    /**
     * The catalog's two-character status code to activation's spelled-out vocabulary. The two
     * lifecycles are not the same shape, so this is lossy by nature: the catalog has no word for
     * {@code STUCK} and activation has no word for {@code SUSPENDED}.
     */
    public static String fromCatalogStatusCode(String statusCode) {
        return switch (statusCode == null ? "" : statusCode.trim()) {
            case "NW" -> "RECEIVED";
            case "PA" -> "AWAITING_PROVISIONING";
            case "AC" -> "PROVISIONED";
            case "SU" -> "SUSPENDED_IN_CATALOG";
            case "TE" -> "CANCELLED";
            default -> "UNKNOWN(" + statusCode + ")";
        };
    }
}

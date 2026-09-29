package hu.mclsaat.legacy.clients.mapping;

import hu.mclsaat.legacy.clients.canonical.CanonicalModel.BatchStatus;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.InvoiceStatus;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.OrderStatus;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.ProductKind;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.ServiceKind;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.SubscriptionStatus;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Every translation between a subsystem's dialect and the canonical model, in one place.
 *
 * <p>This is the file phase 2 is really about. The same rules exist today in
 * {@code activation-service}'s {@code ActivationSemantics}, written separately by that subsystem's
 * own integration code — see {@code docs/decision-log.md} DL-009 for why that duplication is
 * deliberate. An MCP server should depend on *this* one and let the duplication stand as the "before"
 * picture.
 *
 * <p>Two rules run through all of it:
 *
 * <ul>
 *   <li><b>Unknown values are never coerced.</b> An unrecognised status becomes
 *       {@code UNKNOWN}, not the nearest guess. A wrong status that looks plausible is worse than one
 *       that is obviously unhandled.
 *   <li><b>Nothing is inferred that can be carried.</b> Where a raw value is not recoverable from the
 *       canonical one — the catalog's two-character code, billing's account number — the canonical
 *       record keeps it alongside.
 * </ul>
 */
public final class SemanticMappers {

    /** Activation's date format: bare {@code yyyyMMdd}, in a {@code CHAR(8)} column. */
    public static final DateTimeFormatter ACTIVATION_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** Billing's date format on the SOAP wire. */
    public static final DateTimeFormatter BILLING_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** The catalog's sentinel for an unmetered plan. */
    public static final int CATALOG_UNMETERED_MB = -1;

    private SemanticMappers() {
    }

    // ------------------------------------------------------------------ identity

    /** {@code 42} to the catalog's {@code "00000042"}. */
    public static String toCatalogCustNo(int customerReference) {
        if (customerReference <= 0 || customerReference > 99_999_999) {
            throw new IllegalArgumentException("customer reference " + customerReference
                    + " does not fit the catalog's CHAR(8) cust_no");
        }
        return "%08d".formatted(customerReference);
    }

    /**
     * The catalog's {@code "00000042"} back to {@code 42}.
     *
     * <p>Trims first: PostgreSQL space-pads {@code CHAR(8)} on read, so the value arriving from a JDBC
     * query is {@code "00000042"} from the REST API and {@code "00000042"} with trailing spaces from a
     * direct column read, depending on the driver's mood.
     */
    public static int fromCatalogCustNo(String custNo) {
        if (custNo == null || custNo.isBlank()) {
            throw new IllegalArgumentException("cust_no is required");
        }
        String trimmed = custNo.trim();
        if (!trimmed.matches("^[0-9]{1,8}$")) {
            throw new IllegalArgumentException("not a catalog cust_no: " + custNo);
        }
        return Integer.parseInt(trimmed);
    }

    /**
     * Billing's account number for a customer reference, **as a formatting convention only**.
     *
     * <p>Use this to *guess*, never to resolve. In billing the mapping is a row in
     * {@code billing.billing_account}, and nothing guarantees that customer 42 is {@code BA-00042} —
     * the seed data merely happens to line up. Anything that needs the real answer has to ask billing.
     */
    public static String guessBillingAccountNo(int customerReference) {
        return "BA-%05d".formatted(customerReference);
    }

    // ------------------------------------------------------------------- product

    /** Activation's {@code "mob.voice.0010"} to the catalog's {@code "MOB-VOICE-0010"}. */
    public static String toCatalogPlanCode(String offerId) {
        if (offerId == null || offerId.isBlank()) {
            throw new IllegalArgumentException("offerId is required");
        }
        return offerId.trim().toUpperCase().replace('.', '-');
    }

    /** The catalog's {@code "MOB-VOICE-0010"} to activation's {@code "mob.voice.0010"}. */
    public static String toOfferId(String planCode) {
        if (planCode == null || planCode.isBlank()) {
            throw new IllegalArgumentException("planCode is required");
        }
        return planCode.trim().toLowerCase().replace('-', '.');
    }

    // ---------------------------------------------------------------------- date

    /** Activation's {@code "20260929"} to a date. */
    public static LocalDate fromActivationDate(String yyyyMMdd) {
        return parse("activation date", yyyyMMdd, ACTIVATION_DATE, "yyyyMMdd");
    }

    public static String toActivationDate(LocalDate date) {
        return date == null ? null : date.format(ACTIVATION_DATE);
    }

    /** Billing's {@code "2026-09-29"} to a date. */
    public static LocalDate fromBillingDate(String yyyyDashMmDashDd) {
        return parse("billing date", yyyyDashMmDashDd, BILLING_DATE, "yyyy-MM-dd");
    }

    public static String toBillingDate(LocalDate date) {
        return date == null ? null : date.format(BILLING_DATE);
    }

    private static LocalDate parse(String what, String value, DateTimeFormatter format,
                                   String pattern) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), format);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    what + " must be formatted " + pattern + ", was: " + value);
        }
    }

    // -------------------------------------------------------------------- status

    /** The catalog's two-character {@code status_code}. */
    public static SubscriptionStatus fromCatalogStatusCode(String statusCode) {
        return switch (trim(statusCode)) {
            case "NW" -> SubscriptionStatus.NEW;
            case "PA" -> SubscriptionStatus.PENDING_ACTIVATION;
            case "AC" -> SubscriptionStatus.ACTIVE;
            case "SU" -> SubscriptionStatus.SUSPENDED;
            case "TE" -> SubscriptionStatus.TERMINATED;
            default -> SubscriptionStatus.UNKNOWN;
        };
    }

    /** Back to the catalog's spelling. {@code UNKNOWN} has none, so it is refused. */
    public static String toCatalogStatusCode(SubscriptionStatus status) {
        return switch (status) {
            case NEW -> "NW";
            case PENDING_ACTIVATION -> "PA";
            case ACTIVE -> "AC";
            case SUSPENDED -> "SU";
            case TERMINATED -> "TE";
            case UNKNOWN -> throw new IllegalArgumentException(
                    "UNKNOWN has no catalog status code; it means the catalog said something "
                            + "this model does not handle");
        };
    }

    public static OrderStatus fromActivationStatus(String status) {
        return switch (trim(status)) {
            case "RECEIVED" -> OrderStatus.RECEIVED;
            case "VALIDATED" -> OrderStatus.VALIDATED;
            case "AWAITING_PROVISIONING" -> OrderStatus.AWAITING_PROVISIONING;
            case "STUCK" -> OrderStatus.STUCK;
            case "PROVISIONED" -> OrderStatus.PROVISIONED;
            case "FAILED" -> OrderStatus.FAILED;
            case "CANCELLED" -> OrderStatus.CANCELLED;
            default -> OrderStatus.UNKNOWN;
        };
    }

    public static InvoiceStatus fromBillingInvoiceStatus(String status) {
        return switch (trim(status)) {
            case "OPEN" -> InvoiceStatus.OPEN;
            case "SETTLEMENT_PENDING" -> InvoiceStatus.SETTLEMENT_PENDING;
            case "PAID" -> InvoiceStatus.PAID;
            case "CANCELLED" -> InvoiceStatus.CANCELLED;
            default -> InvoiceStatus.UNKNOWN;
        };
    }

    public static String toBillingInvoiceStatus(InvoiceStatus status) {
        return switch (status) {
            case OPEN -> "OPEN";
            case SETTLEMENT_PENDING -> "SETTLEMENT_PENDING";
            case PAID -> "PAID";
            case CANCELLED -> "CANCELLED";
            case UNKNOWN -> throw new IllegalArgumentException(
                    "UNKNOWN has no billing invoice status");
        };
    }

    /** Note the rename: billing says {@code ACKED}, the canonical model spells it out. */
    public static BatchStatus fromBillingBatchStatus(String status) {
        return switch (trim(status)) {
            case "OPEN" -> BatchStatus.OPEN;
            case "SENT" -> BatchStatus.SENT;
            case "ACKED" -> BatchStatus.ACKNOWLEDGED;
            case "FAILED" -> BatchStatus.FAILED;
            default -> BatchStatus.UNKNOWN;
        };
    }

    // -------------------------------------------------------- kinds and flags

    /** The catalog's single-character {@code service_kind}. */
    public static ServiceKind fromCatalogServiceKind(String serviceKind) {
        return switch (trim(serviceKind)) {
            case "I" -> ServiceKind.INTERNET;
            case "M" -> ServiceKind.MOBILE;
            default -> ServiceKind.UNKNOWN;
        };
    }

    public static String toCatalogServiceKind(ServiceKind serviceKind) {
        return switch (serviceKind) {
            case INTERNET -> "I";
            case MOBILE -> "M";
            case UNKNOWN -> throw new IllegalArgumentException("UNKNOWN has no catalog service kind");
        };
    }

    public static ProductKind fromCatalogPlanKind(String planKind) {
        return switch (trim(planKind)) {
            case "BASE" -> ProductKind.BASE;
            case "ADDON" -> ProductKind.ADDON;
            default -> ProductKind.UNKNOWN;
        };
    }

    /**
     * The catalog's {@code CHAR(1)} {@code 'Y'}/{@code 'N'} flags.
     *
     * <p>Anything that is not exactly {@code Y} is false. Being strict here matters: treating an
     * unexpected value as true would put a withdrawn plan back on sale.
     */
    public static boolean fromCatalogFlag(String flag) {
        return "Y".equals(trim(flag));
    }

    public static String toCatalogFlag(boolean value) {
        return value ? "Y" : "N";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}

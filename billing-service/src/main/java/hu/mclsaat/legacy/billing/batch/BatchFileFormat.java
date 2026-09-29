package hu.mclsaat.legacy.billing.batch;

import hu.mclsaat.legacy.billing.service.BillingException;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The fixed-width settlement file format the clearing house speaks.
 *
 * <p>This is the third kind of API in the system, after REST and SOAP: no envelope, no schema, no
 * field names, just column offsets that both sides have to agree on out of band. Money is an
 * integer count of minor units with an implied two decimals and no separator, which is a fourth
 * way of writing the same figure that started life as {@code monthly_fee_minor} in the catalog.
 *
 * <pre>
 * outbound settlement file  PMT-&lt;batchId&gt;.txt
 *   off len field                        off len field
 *   HDR   0   3 "HDR"                    DTL   0   3 "DTL"
 *         3  20 batchId  (left, space)         3  20 invoiceNo (left, space)
 *        23   8 created yyyyMMdd               23  12 billingAccountNo (left, space)
 *        31   6 created HHmmss                 35  15 amountMinor (right, zero)
 *        37   6 itemCount   (right, zero)      50  32 paymentRef (left, space)
 *        43  15 totalMinor  (right, zero)      82   3 currency
 *        58   3 currency                      = 85 bytes
 *       = 61 bytes
 *   TRL   0   3 "TRL"
 *         3   6 itemCount  (right, zero)
 *         9  15 totalMinor (right, zero)
 *       = 24 bytes
 *
 * inbound acknowledgement    ACK-&lt;batchId&gt;.txt
 *   ACK   0   3 "ACK"                    RES   0   3 "RES"
 *         3  20 batchId                        3  20 invoiceNo
 *        23   8 ACCEPTED|REJECTED             23   8 ACCEPTED|REJECTED
 *        31   8 acked yyyyMMdd                31   4 reasonCode
 *        39   6 acked HHmmss                 = 35 bytes
 *        45   6 acceptedCount
 *        51   6 rejectedCount
 *       = 57 bytes
 * </pre>
 */
public final class BatchFileFormat {

    /** No accented characters ever appear in these records, so the narrowest charset will do. */
    public static final Charset CHARSET = StandardCharsets.US_ASCII;

    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_REJECTED = "REJECTED";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

    private BatchFileFormat() {
    }

    public static String settlementFileName(String batchId) {
        return "PMT-" + batchId + ".txt";
    }

    public static String ackFileName(String batchId) {
        return "ACK-" + batchId + ".txt";
    }

    // ----------------------------------------------------------------- writing

    public static String header(String batchId, LocalDateTime created, int itemCount,
                               long totalMinor, String currency) {
        return "HDR"
                + left(batchId, 20)
                + created.format(DAY)
                + created.format(TIME)
                + zeros(itemCount, 6)
                + zeros(totalMinor, 15)
                + left(currency, 3);
    }

    public static String detail(String invoiceNo, String billingAccountNo, long amountMinor,
                                String paymentRef, String currency) {
        return "DTL"
                + left(invoiceNo, 20)
                + left(billingAccountNo, 12)
                + zeros(amountMinor, 15)
                + left(paymentRef == null ? "" : paymentRef, 32)
                + left(currency, 3);
    }

    public static String trailer(int itemCount, long totalMinor) {
        return "TRL" + zeros(itemCount, 6) + zeros(totalMinor, 15);
    }

    public static String ackHeader(String batchId, String status, LocalDateTime acked,
                                   int acceptedCount, int rejectedCount) {
        return "ACK"
                + left(batchId, 20)
                + left(status, 8)
                + acked.format(DAY)
                + acked.format(TIME)
                + zeros(acceptedCount, 6)
                + zeros(rejectedCount, 6);
    }

    public static String ackResult(String invoiceNo, String status, String reasonCode) {
        return "RES" + left(invoiceNo, 20) + left(status, 8) + left(reasonCode, 4);
    }

    // ----------------------------------------------------------------- reading

    /** Parses an outbound settlement file back into its records, checking the trailer totals. */
    public static SettlementFile parseSettlementFile(List<String> lines) {
        String batchId = null;
        String currency = "HUF";
        List<Detail> details = new ArrayList<>();
        Integer trailerCount = null;
        Long trailerTotal = null;

        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String tag = slice(line, 0, 3);
            switch (tag) {
                case "HDR" -> {
                    batchId = slice(line, 3, 20).trim();
                    currency = slice(line, 58, 3).trim();
                }
                case "DTL" -> details.add(new Detail(
                        slice(line, 3, 20).trim(),
                        slice(line, 23, 12).trim(),
                        parseLong(slice(line, 35, 15)),
                        slice(line, 50, 32).trim(),
                        slice(line, 82, 3).trim()));
                case "TRL" -> {
                    trailerCount = (int) parseLong(slice(line, 3, 6));
                    trailerTotal = parseLong(slice(line, 9, 15));
                }
                default -> throw new BillingException.BadRequest(
                        "unknown record tag '" + tag + "' in settlement file");
            }
        }

        if (batchId == null) {
            throw new BillingException.BadRequest("settlement file has no HDR record");
        }
        if (trailerCount == null) {
            throw new BillingException.BadRequest("settlement file has no TRL record");
        }
        if (trailerCount != details.size()) {
            throw new BillingException.BadRequest("settlement file trailer claims " + trailerCount
                    + " items but carries " + details.size());
        }
        long actualTotal = details.stream().mapToLong(Detail::amountMinor).sum();
        if (trailerTotal != actualTotal) {
            throw new BillingException.BadRequest("settlement file trailer total " + trailerTotal
                    + " does not match the sum of its details " + actualTotal);
        }
        return new SettlementFile(batchId, currency, details);
    }

    /** Parses an inbound acknowledgement file. */
    public static AckFile parseAckFile(List<String> lines) {
        String batchId = null;
        String status = null;
        List<AckResult> results = new ArrayList<>();

        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String tag = slice(line, 0, 3);
            switch (tag) {
                case "ACK" -> {
                    batchId = slice(line, 3, 20).trim();
                    status = slice(line, 23, 8).trim();
                }
                case "RES" -> results.add(new AckResult(
                        slice(line, 3, 20).trim(),
                        slice(line, 23, 8).trim(),
                        slice(line, 31, 4).trim()));
                default -> throw new BillingException.BadRequest(
                        "unknown record tag '" + tag + "' in acknowledgement file");
            }
        }
        if (batchId == null || status == null) {
            throw new BillingException.BadRequest("acknowledgement file has no ACK record");
        }
        return new AckFile(batchId, status, results);
    }

    // ----------------------------------------------------------------- helpers

    /** HUF major units with two decimals to the file's implied-decimal integer. */
    public static long toMinor(BigDecimal majorUnits) {
        return majorUnits.movePointRight(2).longValueExact();
    }

    /** The file's implied-decimal integer back to HUF major units with two decimals. */
    public static BigDecimal toMajor(long minorUnits) {
        return BigDecimal.valueOf(minorUnits, 2);
    }

    private static String left(String value, int width) {
        String text = value == null ? "" : value;
        if (text.length() > width) {
            throw new BillingException.BadRequest(
                    "'" + text + "' does not fit the " + width + "-character field");
        }
        return text + " ".repeat(width - text.length());
    }

    private static String zeros(long value, int width) {
        String text = Long.toString(value);
        if (text.length() > width) {
            throw new BillingException.BadRequest(
                    value + " does not fit the " + width + "-digit field");
        }
        return "0".repeat(width - text.length()) + text;
    }

    private static String slice(String line, int offset, int length) {
        if (line.length() < offset + length) {
            throw new BillingException.BadRequest("record is too short at offset " + offset
                    + " (length " + line.length() + "): '" + line + "'");
        }
        return line.substring(offset, offset + length);
    }

    private static long parseLong(String digits) {
        try {
            return Long.parseLong(digits.trim());
        } catch (NumberFormatException ex) {
            throw new BillingException.BadRequest("'" + digits + "' is not a number");
        }
    }

    public record Detail(String invoiceNo, String billingAccountNo, long amountMinor,
                         String paymentRef, String currency) {
    }

    public record SettlementFile(String batchId, String currency, List<Detail> details) {
        public long totalMinor() {
            return details.stream().mapToLong(Detail::amountMinor).sum();
        }
    }

    public record AckResult(String invoiceNo, String status, String reasonCode) {
    }

    public record AckFile(String batchId, String status, List<AckResult> results) {
        public boolean accepted() {
            return STATUS_ACCEPTED.equals(status);
        }
    }
}

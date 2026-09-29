package hu.mclsaat.legacy.billing;

import hu.mclsaat.legacy.billing.batch.BatchFileFormat;
import hu.mclsaat.legacy.billing.service.BillingException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fixed-width format has no schema to validate against, so the column offsets are pinned here
 * instead. Getting a width wrong in a format like this is silent and corrupting, which is exactly
 * why it belongs in the heterogeneity story.
 */
class BatchFileFormatTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 9, 29, 23, 45, 7);

    @Test
    void recordsHaveTheirDocumentedLengthsAndOffsets() {
        String header = BatchFileFormat.header("BATCH-20260929-001", AT, 2, 1_899_001L, "HUF");
        assertThat(header).hasSize(61);
        assertThat(header).startsWith("HDR");
        assertThat(header.substring(3, 23)).isEqualTo("BATCH-20260929-001  ");
        assertThat(header.substring(23, 31)).isEqualTo("20260929");
        assertThat(header.substring(31, 37)).isEqualTo("234507");
        assertThat(header.substring(37, 43)).isEqualTo("000002");
        assertThat(header.substring(43, 58)).isEqualTo("000000001899001");
        assertThat(header.substring(58, 61)).isEqualTo("HUF");

        String detail = BatchFileFormat.detail("2026/INV/000002", "BA-00042", 1_299_000L,
                "pi_3PgafyB7WZ01zgkW1abcdefg", "HUF");
        assertThat(detail).hasSize(85);
        assertThat(detail.substring(3, 23)).isEqualTo("2026/INV/000002     ");
        assertThat(detail.substring(23, 35)).isEqualTo("BA-00042    ");
        assertThat(detail.substring(35, 50)).isEqualTo("000000001299000");
        assertThat(detail.substring(82, 85)).isEqualTo("HUF");

        String trailer = BatchFileFormat.trailer(2, 1_899_001L);
        assertThat(trailer).hasSize(24);
        assertThat(trailer).isEqualTo("TRL000002000000001899001");
    }

    @Test
    void aSettlementFileRoundTripsThroughWritingAndParsing() {
        List<String> lines = List.of(
                BatchFileFormat.header("BATCH-20260929-001", AT, 2, 1_899_001L, "HUF"),
                BatchFileFormat.detail("2026/INV/000002", "BA-00042", 1_299_000L, "pi_a", "HUF"),
                BatchFileFormat.detail("2026/INV/000003", "BA-00042", 600_001L, "pi_b", "HUF"),
                BatchFileFormat.trailer(2, 1_899_001L));

        var parsed = BatchFileFormat.parseSettlementFile(lines);

        assertThat(parsed.batchId()).isEqualTo("BATCH-20260929-001");
        assertThat(parsed.currency()).isEqualTo("HUF");
        assertThat(parsed.details()).hasSize(2);
        assertThat(parsed.details().get(0).invoiceNo()).isEqualTo("2026/INV/000002");
        assertThat(parsed.details().get(0).amountMinor()).isEqualTo(1_299_000L);
        assertThat(parsed.details().get(1).paymentRef()).isEqualTo("pi_b");
        assertThat(parsed.totalMinor()).isEqualTo(1_899_001L);
    }

    @Test
    void aTrailerThatDisagreesWithTheDetailsIsRejected() {
        List<String> wrongCount = List.of(
                BatchFileFormat.header("BATCH-X", AT, 2, 1_299_000L, "HUF"),
                BatchFileFormat.detail("2026/INV/000002", "BA-00042", 1_299_000L, "pi_a", "HUF"),
                BatchFileFormat.trailer(2, 1_299_000L));
        assertThatThrownBy(() -> BatchFileFormat.parseSettlementFile(wrongCount))
                .isInstanceOf(BillingException.BadRequest.class)
                .hasMessageContaining("claims 2 items but carries 1");

        List<String> wrongTotal = List.of(
                BatchFileFormat.header("BATCH-X", AT, 1, 999L, "HUF"),
                BatchFileFormat.detail("2026/INV/000002", "BA-00042", 1_299_000L, "pi_a", "HUF"),
                BatchFileFormat.trailer(1, 999L));
        assertThatThrownBy(() -> BatchFileFormat.parseSettlementFile(wrongTotal))
                .isInstanceOf(BillingException.BadRequest.class)
                .hasMessageContaining("does not match the sum of its details");
    }

    @Test
    void anAcknowledgementFileRoundTrips() {
        List<String> lines = List.of(
                BatchFileFormat.ackHeader("BATCH-20260929-001", BatchFileFormat.STATUS_ACCEPTED, AT, 1, 0),
                BatchFileFormat.ackResult("2026/INV/000002", BatchFileFormat.STATUS_ACCEPTED, "0000"));

        var ack = BatchFileFormat.parseAckFile(lines);

        assertThat(ack.batchId()).isEqualTo("BATCH-20260929-001");
        assertThat(ack.accepted()).isTrue();
        assertThat(ack.results()).hasSize(1);
        assertThat(ack.results().get(0).invoiceNo()).isEqualTo("2026/INV/000002");
        assertThat(ack.results().get(0).reasonCode()).isEqualTo("0000");

        assertThat(BatchFileFormat.ackHeader("B", BatchFileFormat.STATUS_REJECTED, AT, 0, 3)).hasSize(57);
        assertThat(BatchFileFormat.ackResult("2026/INV/000002", "REJECTED", "0912")).hasSize(35);
    }

    @Test
    void valuesThatDoNotFitTheirColumnAreRefusedRatherThanTruncated() {
        assertThatThrownBy(() -> BatchFileFormat.detail(
                "2026/INV/000002-this-is-far-too-long", "BA-00042", 1L, "pi_a", "HUF"))
                .isInstanceOf(BillingException.BadRequest.class)
                .hasMessageContaining("does not fit the 20-character field");

        assertThatThrownBy(() -> BatchFileFormat.trailer(1, 1_000_000_000_000_000_0L))
                .isInstanceOf(BillingException.BadRequest.class)
                .hasMessageContaining("does not fit the 15-digit field");
    }

    @Test
    void shortAndUnknownRecordsAreRejected() {
        assertThatThrownBy(() -> BatchFileFormat.parseSettlementFile(List.of("HDR too short")))
                .isInstanceOf(BillingException.BadRequest.class)
                .hasMessageContaining("too short");

        assertThatThrownBy(() -> BatchFileFormat.parseSettlementFile(
                List.of("XXX" + " ".repeat(58))))
                .isInstanceOf(BillingException.BadRequest.class)
                .hasMessageContaining("unknown record tag 'XXX'");
    }

    @Test
    void theFileWritesMoneyWithAnImpliedTwoDecimalsAndNoSeparator() {
        assertThat(BatchFileFormat.toMinor(new BigDecimal("12990.00"))).isEqualTo(1_299_000L);
        assertThat(BatchFileFormat.toMinor(new BigDecimal("5990.01"))).isEqualTo(599_001L);
        assertThat(BatchFileFormat.toMajor(1_299_000L)).isEqualByComparingTo("12990.00");
        assertThat(BatchFileFormat.toMajor(599_001L)).isEqualByComparingTo("5990.01");
    }
}

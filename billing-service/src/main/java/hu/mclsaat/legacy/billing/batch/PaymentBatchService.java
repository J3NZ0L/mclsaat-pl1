package hu.mclsaat.legacy.billing.batch;

import hu.mclsaat.legacy.billing.domain.InvoiceRow;
import hu.mclsaat.legacy.billing.domain.PaymentBatchRow;
import hu.mclsaat.legacy.billing.repo.InvoiceRepository;
import hu.mclsaat.legacy.billing.repo.PaymentBatchRepository;
import hu.mclsaat.legacy.billing.service.BillingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The settlement batch lifecycle: cut a batch, write it out, wait for the acknowledgement, post
 * the money. Nothing here is synchronous end to end, which is why ops needs a way in.
 */
@Service
public class PaymentBatchService {

    private static final Logger log = LoggerFactory.getLogger(PaymentBatchService.class);

    /** {@code reconcileBatch} modes, as spelled in the SOAP contract. */
    public static final String MODE_RESEND = "RESEND";
    public static final String MODE_RE_DRIVE_ACK = "RE_DRIVE_ACK";

    private final PaymentBatchRepository batches;
    private final InvoiceRepository invoices;
    private final ClearingHouseSimulator clearingHouse;
    private final BatchExchangeProperties properties;

    public PaymentBatchService(PaymentBatchRepository batches, InvoiceRepository invoices,
                              ClearingHouseSimulator clearingHouse,
                              BatchExchangeProperties properties) {
        this.batches = batches;
        this.invoices = invoices;
        this.clearingHouse = clearingHouse;
        this.properties = properties;
    }

    /**
     * Cuts a batch from every invoice Stripe has paid for that no batch has claimed yet, writes
     * the fixed-width file to the outbox and hands it to the clearing house.
     *
     * @return empty when there was nothing to settle
     */
    @Transactional
    public Optional<PaymentBatchRow> exportBatch() {
        List<InvoiceRow> pending = invoices.findUnbatchedSettlementPending();
        if (pending.isEmpty()) {
            log.info("nothing to settle: no unbatched SETTLEMENT_PENDING invoices");
            return Optional.empty();
        }

        String currency = pending.get(0).currency();
        PaymentBatchRow batch = batches.insertOpen(currency);

        List<String> lines = new ArrayList<>();
        long totalMinor = 0;
        List<String> claimed = new ArrayList<>();
        for (InvoiceRow invoice : pending) {
            if (!currency.equals(invoice.currency())) {
                // one file, one currency: the clearing house has no way to express a mixed batch
                log.info("invoice {} in {} left for the next batch ({} batch)",
                        invoice.invoiceNo(), invoice.currency(), currency);
                continue;
            }
            if (invoices.assignToBatch(invoice.invoiceNo(), batch.batchId()) == 0) {
                continue; // another export claimed it first
            }
            long amountMinor = BatchFileFormat.toMinor(invoice.grossAmount());
            totalMinor += amountMinor;
            claimed.add(invoice.invoiceNo());
            lines.add(BatchFileFormat.detail(invoice.invoiceNo(), invoice.baNo(), amountMinor,
                    invoice.paymentRef(), invoice.currency()));
        }

        if (claimed.isEmpty()) {
            batches.markFailed(batch.batchId());
            log.info("batch {} claimed nothing and was marked FAILED", batch.batchId());
            return Optional.empty();
        }

        lines.add(0, BatchFileFormat.header(batch.batchId(), LocalDateTime.now(), claimed.size(),
                totalMinor, currency));
        lines.add(BatchFileFormat.trailer(claimed.size(), totalMinor));

        String fileName = BatchFileFormat.settlementFileName(batch.batchId());
        Path written = writeToOutbox(fileName, lines);
        batches.markSent(batch.batchId(), fileName, claimed.size(), BatchFileFormat.toMajor(totalMinor));
        log.info("batch {} sent: {} items, {} {} -> {}", batch.batchId(), claimed.size(),
                BatchFileFormat.toMajor(totalMinor), currency, fileName);

        // hand it over; whether an acknowledgement ever comes back is the clearing house's call
        clearingHouse.receive(written);

        return batches.findById(batch.batchId());
    }

    /**
     * Applies one acknowledgement file: marks the batch {@code ACKED} and its invoices
     * {@code PAID}, then archives the file. Idempotent, because a file can be re-dropped.
     *
     * @return how many invoices moved to {@code PAID}
     */
    @Transactional
    public int applyAcknowledgement(Path ackFile) {
        BatchFileFormat.AckFile ack;
        try {
            ack = BatchFileFormat.parseAckFile(Files.readAllLines(ackFile, BatchFileFormat.CHARSET));
        } catch (IOException ex) {
            throw new UncheckedIOException("could not read " + ackFile, ex);
        }

        PaymentBatchRow batch = batches.findById(ack.batchId())
                .orElseThrow(() -> new BillingException.NotFound("payment batch", ack.batchId()));

        int settled = 0;
        if (!ack.accepted()) {
            batches.markFailed(batch.batchId());
            log.error("clearing house REJECTED batch {}; its invoices stay in SETTLEMENT_PENDING",
                    batch.batchId());
        } else if (PaymentBatchRow.STATUS_ACKED.equals(batch.status())) {
            log.info("batch {} is already ACKED; acknowledgement file ignored", batch.batchId());
        } else {
            batches.markAcked(batch.batchId(), ackFile.getFileName().toString());
            settled = invoices.markPaidByBatch(batch.batchId());
            log.info("batch {} acknowledged: {} invoices posted as PAID", batch.batchId(), settled);
        }

        archive(ackFile);
        return settled;
    }

    public List<PaymentBatchRow> listUnconfirmed(Integer olderThanMinutes) {
        int threshold = olderThanMinutes == null || olderThanMinutes < 0
                ? properties.unconfirmedAfterMinutes() : olderThanMinutes;
        return batches.findUnconfirmed(threshold);
    }

    public PaymentBatchRow requireBatch(String batchId) {
        return batches.findById(batchId)
                .orElseThrow(() -> new BillingException.NotFound("payment batch", batchId));
    }

    public List<InvoiceRow> invoicesOf(String batchId) {
        return invoices.findByBatchId(batchId);
    }

    /**
     * The ops remediation for failure branch B.
     *
     * <p>{@code RESEND} hands the file we already wrote to the clearing house again and hopes for a
     * real acknowledgement - the right move when the clearing house is simply back up.
     * {@code RE_DRIVE_ACK} reconstructs the acknowledgement from that same file and applies it
     * immediately - the right move when the money demonstrably moved and the confirmation is never
     * coming. Both are safe to repeat.
     */
    @Transactional
    public ReconcileResult reconcile(String batchId, String mode) {
        PaymentBatchRow batch = requireBatch(batchId);
        if (PaymentBatchRow.STATUS_ACKED.equals(batch.status())) {
            return new ReconcileResult(batch, 0,
                    "batch " + batchId + " was already acknowledged; nothing to do");
        }
        if (batch.fileName() == null) {
            throw new BillingException.IllegalState("batch " + batchId
                    + " has no settlement file, so there is nothing to reconcile from");
        }
        Path settlementFile = properties.outboxDir().resolve(batch.fileName());
        if (!Files.isReadable(settlementFile)) {
            throw new BillingException.IllegalState("settlement file " + settlementFile
                    + " for batch " + batchId + " is missing from the outbox");
        }

        String effectiveMode = mode == null || mode.isBlank() ? MODE_RE_DRIVE_ACK : mode.trim();
        switch (effectiveMode) {
            case MODE_RESEND -> {
                String ackFile = clearingHouse.receive(settlementFile);
                String message = ackFile == null
                        ? "batch " + batchId + " was handed to the clearing house again, but "
                          + "acknowledgement is still suppressed there; nothing will come back"
                        : "batch " + batchId + " was handed over again; " + ackFile
                          + " is waiting in the inbox for the poller";
                return new ReconcileResult(requireBatch(batchId), 0, message);
            }
            case MODE_RE_DRIVE_ACK -> {
                String ackFile = clearingHouse.acknowledge(settlementFile);
                int settled = applyAcknowledgement(properties.inboxDir().resolve(ackFile));
                return new ReconcileResult(requireBatch(batchId), settled,
                        "acknowledgement for batch " + batchId + " was rebuilt from " + batch.fileName()
                                + " and applied; " + settled + " invoice(s) posted as PAID");
            }
            default -> throw new BillingException.BadRequest(
                    "mode must be " + MODE_RESEND + " or " + MODE_RE_DRIVE_ACK + ", was: " + mode);
        }
    }

    /** Reads a settlement or acknowledgement file back, for ops and for the demo output. */
    public List<String> readExchangeFile(String directory, String fileName) {
        Path dir = switch (directory) {
            case "outbox" -> properties.outboxDir();
            case "inbox" -> properties.inboxDir();
            case "archive" -> properties.archiveDir();
            default -> throw new BillingException.BadRequest(
                    "directory must be outbox, inbox or archive, was: " + directory);
        };
        Path file = dir.resolve(fileName).normalize();
        if (!file.startsWith(dir.normalize())) {
            throw new BillingException.BadRequest("fileName must not escape " + directory);
        }
        try {
            return Files.readAllLines(file, BatchFileFormat.CHARSET);
        } catch (IOException ex) {
            throw new BillingException.NotFound("exchange file", directory + "/" + fileName);
        }
    }

    private Path writeToOutbox(String fileName, List<String> lines) {
        try {
            Files.createDirectories(properties.outboxDir());
            Path target = properties.outboxDir().resolve(fileName);
            Path staging = properties.outboxDir().resolve(fileName + ".part");
            Files.write(staging, lines, BatchFileFormat.CHARSET);
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException ex) {
            throw new UncheckedIOException("could not write settlement file " + fileName, ex);
        }
    }

    private void archive(Path file) {
        try {
            Files.createDirectories(properties.archiveDir());
            Files.move(file, properties.archiveDir().resolve(file.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            log.warn("could not archive {}: {}", file, ex.getMessage());
        }
    }

    public record ReconcileResult(PaymentBatchRow batch, int invoicesSettled, String message) {
    }

    /** Exposed for the ops console, which reports amounts in major units. */
    public static BigDecimal majorUnits(long minorUnits) {
        return BatchFileFormat.toMajor(minorUnits);
    }
}

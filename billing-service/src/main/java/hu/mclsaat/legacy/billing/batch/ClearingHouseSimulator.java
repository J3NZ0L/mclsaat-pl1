package hu.mclsaat.legacy.billing.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The mocked external clearing house.
 *
 * <p>It does one thing: when a settlement file appears in the outbox and is handed to it, it reads
 * the file and drops a matching acknowledgement into the inbox. It has no memory and no schedule
 * of its own - the asynchrony comes from the inbox poller on the other side.
 *
 * <p>Acknowledgement can be switched off. That is the fault injection point for failure branch B:
 * with acknowledgement off, the settlement file leaves, the batch goes to {@code SENT}, its
 * invoices sit in {@code SETTLEMENT_PENDING}, and nothing ever comes back. Stripe already has the
 * customer's money at that point, which is what makes it worth an ops escalation rather than a
 * shrug.
 */
@Component
public class ClearingHouseSimulator {

    private static final Logger log = LoggerFactory.getLogger(ClearingHouseSimulator.class);

    private final BatchExchangeProperties properties;
    private final AtomicBoolean ackEnabled = new AtomicBoolean(true);

    public ClearingHouseSimulator(BatchExchangeProperties properties) {
        this.properties = properties;
    }

    public boolean isAckEnabled() {
        return ackEnabled.get();
    }

    /** @return the previous setting */
    public boolean setAckEnabled(boolean enabled) {
        boolean previous = ackEnabled.getAndSet(enabled);
        log.warn("clearing-house acknowledgement {} (was {})",
                enabled ? "ENABLED" : "SUPPRESSED", previous ? "enabled" : "suppressed");
        return previous;
    }

    /**
     * Receives a settlement file. With acknowledgement enabled, writes {@code ACK-<batchId>.txt}
     * into the inbox for the poller to find.
     *
     * @return the acknowledgement file name, or {@code null} if acknowledgement is suppressed
     */
    public String receive(Path settlementFile) {
        if (!ackEnabled.get()) {
            log.warn("clearing house received {} but acknowledgement is suppressed; "
                    + "this batch will strand in SENT", settlementFile.getFileName());
            return null;
        }
        return acknowledge(settlementFile);
    }

    /**
     * Builds the acknowledgement for a settlement file that is already in the outbox and writes it
     * to the inbox, regardless of the suppression flag.
     *
     * <p>This is the back-office fix behind {@code reconcileBatch(mode = RE_DRIVE_ACK)}: the money
     * did move, the file we sent is the record of it, so the acknowledgement is reconstructed from
     * that file rather than waited for.
     */
    public String acknowledge(Path settlementFile) {
        try {
            var parsed = BatchFileFormat.parseSettlementFile(
                    Files.readAllLines(settlementFile, BatchFileFormat.CHARSET));

            List<String> ack = new java.util.ArrayList<>();
            ack.add(BatchFileFormat.ackHeader(parsed.batchId(), BatchFileFormat.STATUS_ACCEPTED,
                    LocalDateTime.now(), parsed.details().size(), 0));
            for (var detail : parsed.details()) {
                ack.add(BatchFileFormat.ackResult(detail.invoiceNo(),
                        BatchFileFormat.STATUS_ACCEPTED, "0000"));
            }

            Files.createDirectories(properties.inboxDir());
            String fileName = BatchFileFormat.ackFileName(parsed.batchId());
            // write to a temporary name first so the poller cannot read a half-written file
            Path target = properties.inboxDir().resolve(fileName);
            Path staging = properties.inboxDir().resolve(fileName + ".part");
            Files.write(staging, ack, BatchFileFormat.CHARSET);
            Files.move(staging, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            log.info("clearing house acknowledged {} with {} accepted items -> {}",
                    parsed.batchId(), parsed.details().size(), fileName);
            return fileName;
        } catch (IOException ex) {
            throw new UncheckedIOException("could not acknowledge " + settlementFile, ex);
        }
    }
}

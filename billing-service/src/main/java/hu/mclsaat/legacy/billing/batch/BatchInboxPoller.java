package hu.mclsaat.legacy.billing.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Sweeps the inbox for acknowledgement files.
 *
 * <p>This is what makes the settlement leg genuinely asynchronous: billing writes a file and then
 * has no idea when, or whether, an answer will appear. Nothing in the request that started the
 * payment is still waiting for it.
 */
@Component
public class BatchInboxPoller {

    private static final Logger log = LoggerFactory.getLogger(BatchInboxPoller.class);

    private final PaymentBatchService batches;
    private final BatchExchangeProperties properties;

    public BatchInboxPoller(PaymentBatchService batches, BatchExchangeProperties properties) {
        this.batches = batches;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${billing.batch.poll-interval-ms:2000}")
    public void pollInbox() {
        List<Path> files = listAckFiles();
        for (Path file : files) {
            try {
                batches.applyAcknowledgement(file);
            } catch (RuntimeException ex) {
                // A bad file must not stop the sweep, and must not be retried forever either.
                log.error("could not apply acknowledgement {}: {}", file.getFileName(), ex.getMessage());
                quarantine(file);
            }
        }
    }

    /** The nightly-style export. Off unless {@code billing.batch.auto-export-enabled} is true. */
    @Scheduled(fixedDelayString = "${billing.batch.auto-export-interval-ms:60000}")
    public void autoExport() {
        if (!properties.autoExportEnabled()) {
            return;
        }
        batches.exportBatch();
    }

    private List<Path> listAckFiles() {
        Path inbox = properties.inboxDir();
        if (!Files.isDirectory(inbox)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(inbox)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("ACK-"))
                    .filter(path -> path.getFileName().toString().endsWith(".txt"))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            log.warn("could not list {}: {}", inbox, ex.getMessage());
            return List.of();
        }
    }

    private void quarantine(Path file) {
        try {
            Path rejected = properties.archiveDir().resolve("rejected");
            Files.createDirectories(rejected);
            Files.move(file, rejected.resolve(file.getFileName()),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            log.error("could not quarantine {}: {}", file, ex.getMessage());
        }
    }
}

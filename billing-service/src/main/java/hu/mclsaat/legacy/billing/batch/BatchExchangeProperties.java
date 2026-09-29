package hu.mclsaat.legacy.billing.batch;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Where the settlement files live and how often they are looked at.
 *
 * <p>Under docker compose the three directories are a volume shared with nothing else: the
 * clearing house is simulated inside this process, so the "remote" party writes into the same
 * filesystem. That is the one thing about this subsystem that is mocked rather than real, and it
 * is mocked because the alternative is an SFTP server nobody learns anything from.
 */
@ConfigurationProperties(prefix = "billing.batch")
public record BatchExchangeProperties(
        Path outboxDir,
        Path inboxDir,
        Path archiveDir,
        /** How often the inbox is swept for acknowledgement files, in milliseconds. */
        long pollIntervalMs,
        /**
         * Whether the nightly-style automatic export runs. Off by default so the demo and the
         * tests decide when a batch is cut; a real deployment would have this on a cron.
         */
        boolean autoExportEnabled,
        /** A batch sent longer ago than this and still unacknowledged is reported by ops. */
        int unconfirmedAfterMinutes) {

    public BatchExchangeProperties {
        outboxDir = outboxDir == null ? Path.of("batch-exchange", "outbox") : outboxDir;
        inboxDir = inboxDir == null ? Path.of("batch-exchange", "inbox") : inboxDir;
        archiveDir = archiveDir == null ? Path.of("batch-exchange", "archive") : archiveDir;
        pollIntervalMs = pollIntervalMs <= 0 ? 2_000L : pollIntervalMs;
        unconfirmedAfterMinutes = unconfirmedAfterMinutes <= 0 ? 2 : unconfirmedAfterMinutes;
    }
}

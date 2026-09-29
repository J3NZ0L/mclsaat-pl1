package hu.mclsaat.legacy.clients.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The settlement file exchange, read as a filesystem.
 *
 * <p>Read-only, and deliberately so: billing owns these files and reaches the clearing house through
 * them, so anything that writes here is impersonating one of the two parties. The ops console needs to
 * *see* the file a stranded batch was built from — that is what tells an operator whether the money
 * demonstrably left, and therefore whether rebuilding the acknowledgement is honest or a guess.
 *
 * <p>There is no schema and no envelope. The record layout is documented in
 * {@code BatchFileFormat} on the billing side and in {@code docs/api-reference.md}; this client does
 * not parse it, because the ops console's job is to show an operator what was sent, not to
 * reinterpret it.
 */
public class BatchFileClient {

    private static final Logger log = LoggerFactory.getLogger(BatchFileClient.class);

    private final Path outboxDir;

    public BatchFileClient(Path outboxDir) {
        this.outboxDir = outboxDir;
    }

    public Path outboxDir() {
        return outboxDir;
    }

    /** Whether the settlement file a batch names is actually on disk. */
    public boolean settlementFileExists(String fileName) {
        return fileName != null && Files.isReadable(resolve(fileName));
    }

    /**
     * The settlement file's records, as written.
     *
     * @return empty when the file is absent — which is itself a finding: a batch that names a file
     *         nobody can read cannot be reconciled from it
     */
    public Optional<List<String>> readSettlementFile(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return Optional.empty();
        }
        Path file = resolve(fileName);
        if (!Files.isReadable(file)) {
            log.warn("settlement file {} is not readable at {}", fileName, file);
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllLines(file, StandardCharsets.US_ASCII));
        } catch (IOException ex) {
            log.warn("could not read settlement file {}: {}", file, ex.getMessage());
            return Optional.empty();
        }
    }

    /** Every settlement file currently in the outbox, newest name last. */
    public List<String> listSettlementFiles() {
        if (!Files.isDirectory(outboxDir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(outboxDir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("PMT-") && name.endsWith(".txt"))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            log.warn("could not list {}: {}", outboxDir, ex.getMessage());
            return List.of();
        }
    }

    public boolean reachable() {
        return Files.isDirectory(outboxDir);
    }

    /** Refuses a file name that would climb out of the outbox. */
    private Path resolve(String fileName) {
        Path resolved = outboxDir.resolve(fileName).normalize();
        if (!resolved.startsWith(outboxDir.normalize())) {
            throw new IllegalArgumentException("file name must not escape the outbox: " + fileName);
        }
        return resolved;
    }
}

package com.network.monitoring.alarm.storage;

import com.network.monitoring.alarm.exception.AlarmConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import static com.network.monitoring.alarm.exception.AlarmConfigurationException.invalid;
import static com.network.monitoring.alarm.support.AlarmValues.digest;

/** Owns staged source files. Database transactions and tenant authorization stay in services. */
@Component
public class AlarmSourceStorage {
    public static final int MAX_SOURCE_BYTES = 10 * 1024 * 1024;
    private final Path root;

    public record StagedSource(String key, String checksum, String filename) {}

    public AlarmSourceStorage(@Value("${app.alarm-workflow.storage-path:uploads/alarm-workflow}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public StagedSource stage(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_SOURCE_BYTES)
            throw invalid("A non-empty file of at most 10 MiB is required.");
        String filename = Optional.ofNullable(file.getOriginalFilename()).orElse("").replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        if (filename.length() > 255 || !filename.toLowerCase(Locale.ROOT).matches(".+\\.(csv|xlsx)"))
            throw invalid("Supply a CSV or XLSX filename of at most 255 characters.");
        final byte[] bytes;
        try (InputStream stream = file.getInputStream()) { bytes = stream.readNBytes(MAX_SOURCE_BYTES + 1); }
        catch (IOException error) { throw invalid("Cannot read the supplied source file."); }
        if (bytes.length > MAX_SOURCE_BYTES) throw invalid("File exceeds the 10 MiB ingestion budget.");
        String key = UUID.randomUUID().toString();
        try {
            Files.createDirectories(root);
            Files.write(path(key), bytes, StandardOpenOption.CREATE_NEW);
        } catch (IOException error) {
            discard(key);
            throw new AlarmConfigurationException(503, "ALARM_STORAGE_UNAVAILABLE", "Alarm source staging is unavailable.");
        }
        return new StagedSource(key, digest(bytes), filename);
    }

    public byte[] readVerified(String key, String checksum) throws IOException {
        Path source = path(key);
        if (Files.isSymbolicLink(source) || Files.size(source) > MAX_SOURCE_BYTES)
            throw invalid("Staged source is invalid.");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(source)) { bytes = input.readNBytes(MAX_SOURCE_BYTES + 1); }
        if (bytes.length > MAX_SOURCE_BYTES) throw invalid("Staged source is invalid.");
        if (!digest(bytes).equals(checksum)) throw invalid("Staged source checksum changed; import refused.");
        return bytes;
    }

    public Path path(String key) {
        if (key == null || !key.matches("[0-9a-f-]{36}")) throw invalid("Invalid staged source identifier.");
        Path source = root.resolve(key + ".source").normalize();
        if (!source.getParent().equals(root) || Files.isSymbolicLink(root)) throw invalid("Invalid storage path.");
        return source;
    }

    public void discard(String key) {
        try { Files.deleteIfExists(path(key)); }
        catch (IOException ignored) { /* Only an unreferenced staged file remains; no run can use it. */ }
    }
}

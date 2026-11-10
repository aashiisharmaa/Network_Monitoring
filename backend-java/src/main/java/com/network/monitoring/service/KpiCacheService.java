package com.network.monitoring.service;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.IntStream;

@Service
public class KpiCacheService {

    private final Path kpiCacheDir;
    private final KpiSnapshotStore snapshots;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Bounded stripes coordinate readers/replacements on Windows without a per-file lock leak.
    private static final ReentrantReadWriteLock[] LOCKS = IntStream.range(0, 64)
            .mapToObj(index -> new ReentrantReadWriteLock(true)).toArray(ReentrantReadWriteLock[]::new);

    private ReentrantReadWriteLock lockFor(long fileId) {
        return LOCKS[Math.floorMod(Long.hashCode(fileId), LOCKS.length)];
    }

    public KpiCacheService(String kpiCachePath) {
        this(kpiCachePath, null);
    }

    @Autowired
    public KpiCacheService(@Value("${app.kpi-cache-path}") String kpiCachePath, KpiSnapshotStore snapshots) {
        this.snapshots = snapshots;
        Path configured = Paths.get(kpiCachePath == null || kpiCachePath.isBlank() ? "uploads/kpi-cache" : kpiCachePath.trim());
        this.kpiCacheDir = configured.isAbsolute()
                ? configured.toAbsolutePath().normalize()
                : resolveWorkspaceRoot().resolve(configured).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.kpiCacheDir);
            if (snapshots != null) Files.createDirectories(this.kpiCacheDir.resolve("versions"));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to create KPI cache directory", ex);
        }
    }

    public Path getKpiCachePath(long fileId) {
        String active = snapshots == null ? null : snapshots.activeName(fileId);
        if (active != null) {
            if (!active.matches(fileId + "-[0-9a-f-]+\\.json")) {
                throw new IllegalStateException("Invalid KPI snapshot reference.");
            }
            return kpiCacheDir.resolve("versions").resolve(active);
        }
        return kpiCacheDir.resolve(fileId + ".json");
    }

    public void writeKpiCache(long fileId, Object payload) {
        if (snapshots != null) {
            writeVersion(fileId, payload);
            return;
        }
        Path staged = null;
        try {
            staged = Files.createTempFile(kpiCacheDir, fileId + "-", ".tmp");
            MAPPER.writeValue(staged.toFile(), payload);
            var lock = lockFor(fileId).writeLock();
            lock.lock();
            try {
                Files.move(staged, getKpiCachePath(fileId),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                lock.unlock();
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to write KPI cache", ex);
        } finally {
            if (staged != null) {
                try { Files.deleteIfExists(staged); } catch (IOException ignored) { }
            }
        }
    }

    private void writeVersion(long fileId, Object payload) {
        String revision = UUID.randomUUID().toString();
        Path path = kpiCacheDir.resolve("versions").resolve(fileId + "-" + revision + ".json");
        try {
            MAPPER.writeValue(path.toFile(), payload);
            try (var channel = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            String checksum = RecommendationCacheKey.fileDigest(path);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) deleteVersion(path);
                    }
                });
            }
            snapshots.publish(fileId, path.getFileName().toString(), revision, checksum);
        } catch (IOException | RuntimeException ex) {
            deleteVersion(path);
            throw new IllegalStateException("Unable to publish KPI snapshot.", ex);
        }
    }

    private void deleteVersion(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    public Map<String, Object> readKpiCache(long fileId) {
        Snapshot snapshot = readSnapshot(fileId);
        return snapshot == null ? null : snapshot.payload();
    }

    public record Snapshot(Map<String, Object> payload, String fingerprint) { }

    public Snapshot readSnapshotVersion(long fileId, String name) {
        Path path;
        if (name.equals(fileId + ".json")) path = kpiCacheDir.resolve(name);
        else if (name.matches(fileId + "-[0-9a-f-]+\\.json")) path = kpiCacheDir.resolve("versions").resolve(name);
        else throw new IllegalArgumentException("Invalid analysis source snapshot.");
        var digest = RecommendationCacheKey.digest();
        try (var stream = new DigestInputStream(Files.newInputStream(path), digest);
             var parser = MAPPER.getFactory().createParser(stream)) {
            Map<String, Object> payload = MAPPER.readValue(parser, new TypeReference<Map<String, Object>>() {});
            if (payload == null || parser.nextToken() != null) throw new IOException("Invalid snapshot JSON");
            return new Snapshot(payload, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException ex) {
            throw new IllegalStateException("Analysis source snapshot is unavailable or invalid.", ex);
        }
    }

    public Snapshot readSnapshot(long fileId) {
        var lock = lockFor(fileId).readLock();
        lock.lock();
        try {
            return readSnapshotUnlocked(fileId);
        } finally {
            lock.unlock();
        }
    }

    private Snapshot readSnapshotUnlocked(long fileId) {
        var digest = RecommendationCacheKey.digest();
        try (var stream = new DigestInputStream(Files.newInputStream(getKpiCachePath(fileId)), digest);
             var parser = MAPPER.getFactory().createParser(stream)) {
            Map<String, Object> payload = MAPPER.readValue(parser, new TypeReference<Map<String, Object>>() {});
            if (payload == null || parser.nextToken() != null) return null;
            return new Snapshot(payload, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException ex) {
            if (snapshots != null && snapshots.activeName(fileId) != null) {
                throw new IllegalStateException("Committed KPI snapshot is unavailable or invalid; restore it before continuing.", ex);
            }
            return null;
        }
    }

    /** Validate and hash a usable primary RCA source without materializing its rows. */
    public Optional<String> fingerprintKpiCache(long fileId) {
        var lock = lockFor(fileId).readLock();
        lock.lock();
        try {
            return fingerprintKpiCacheUnlocked(fileId);
        } finally {
            lock.unlock();
        }
    }

    private Optional<String> fingerprintKpiCacheUnlocked(long fileId) {
        var digest = RecommendationCacheKey.digest();
        try (var stream = new DigestInputStream(Files.newInputStream(getKpiCachePath(fileId)), digest);
             var parser = MAPPER.getFactory().createParser(stream)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return Optional.empty();
            boolean hasRecords = false;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return Optional.empty();
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("records".equals(field)) {
                    hasRecords = false;
                    if (value == JsonToken.START_ARRAY) {
                        while (parser.nextToken() != JsonToken.END_ARRAY) {
                            if (parser.currentToken() == null) return Optional.empty();
                            if (parser.currentToken() == JsonToken.START_OBJECT) hasRecords = true;
                            parser.skipChildren();
                        }
                    } else {
                        parser.skipChildren();
                    }
                } else {
                    parser.skipChildren();
                }
            }
            if (parser.nextToken() != null || !hasRecords) return Optional.empty();
            return Optional.of(HexFormat.of().formatHex(digest.digest()));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    public void deleteKpiCache(long fileId) {
        var lock = lockFor(fileId).writeLock();
        lock.lock();
        try {
            if (snapshots != null) snapshots.delete(fileId);
            Files.deleteIfExists(kpiCacheDir.resolve(fileId + ".json"));
        } catch (IOException ignored) {
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> updateKpiCache(long fileId, java.util.function.UnaryOperator<Map<String, Object>> updater) {
        if (snapshots != null) {
            return snapshots.locked(fileId, () -> {
                Map<String, Object> current = readKpiCache(fileId);
                if (current == null) return null;
                Map<String, Object> updated = updater.apply(current);
                writeKpiCache(fileId, updated);
                return updated;
            });
        }
        var lock = lockFor(fileId).writeLock();
        lock.lock();
        try {
            Map<String, Object> current = readKpiCache(fileId);
            if (current == null) {
                return null;
            }
            Map<String, Object> updated = updater.apply(current);
            writeKpiCache(fileId, updated);
            return updated;
        } finally {
            lock.unlock();
        }
    }

    private Path resolveWorkspaceRoot() {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path name = cwd.getFileName();
        String current = name == null ? "" : name.toString().toLowerCase();
        if ("backend-java".equals(current) || "backend".equals(current)) {
            Path parent = cwd.getParent();
            if (parent != null) {
                return parent;
            }
        }
        return cwd;
    }
}

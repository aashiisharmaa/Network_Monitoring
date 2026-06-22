package com.network.monitoring.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@Service
public class KpiCacheService {

    private final Path kpiCacheDir;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public KpiCacheService(@Value("${app.kpi-cache-path}") String kpiCachePath) {
        Path configured = Paths.get(kpiCachePath == null || kpiCachePath.isBlank() ? "uploads/kpi-cache" : kpiCachePath.trim());
        this.kpiCacheDir = configured.isAbsolute()
                ? configured.toAbsolutePath().normalize()
                : resolveWorkspaceRoot().resolve(configured).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.kpiCacheDir);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to create KPI cache directory", ex);
        }
    }

    public Path getKpiCachePath(long fileId) {
        return kpiCacheDir.resolve(fileId + ".json");
    }

    public void writeKpiCache(long fileId, Object payload) {
        try {
            Files.writeString(getKpiCachePath(fileId), MAPPER.writeValueAsString(payload));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to write KPI cache", ex);
        }
    }

    public Map<String, Object> readKpiCache(long fileId) {
        Path path = getKpiCachePath(fileId);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return MAPPER.readValue(Files.readString(path), new TypeReference<Map<String, Object>>() {});
        } catch (IOException ex) {
            return null;
        }
    }

    public void deleteKpiCache(long fileId) {
        try {
            Files.deleteIfExists(getKpiCachePath(fileId));
        } catch (IOException ignored) {
        }
    }

    public Map<String, Object> updateKpiCache(long fileId, java.util.function.UnaryOperator<Map<String, Object>> updater) {
        Map<String, Object> current = readKpiCache(fileId);
        if (current == null) {
            return null;
        }
        Map<String, Object> updated = updater.apply(current);
        writeKpiCache(fileId, updated);
        return updated;
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

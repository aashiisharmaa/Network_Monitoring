package com.network.monitoring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

final class RecommendationCacheKey {
    // Bump when Java RCA input preparation or response semantics change.
    private static final String PIPELINE_VERSION = "rca-cache-v2";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    static String build(Map<String, Object> request, String dataset, Map<String, String> engine) {
        try {
            return HexFormat.of().formatHex(digest().digest(MAPPER.writeValueAsBytes(Map.of(
                    "pipeline", PIPELINE_VERSION, "dataset", dataset,
                    "engine", engine, "request", request == null ? Map.of() : request))));
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to serialize recommendation settings.", ex);
        }
    }

    static String fileDigest(Path path) throws IOException {
        MessageDigest digest = digest();
        try (var stream = new DigestInputStream(Files.newInputStream(path), digest)) {
            byte[] buffer = new byte[64 * 1024];
            while (stream.read(buffer) != -1) { /* digest bytes without retaining the file */ }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }
}

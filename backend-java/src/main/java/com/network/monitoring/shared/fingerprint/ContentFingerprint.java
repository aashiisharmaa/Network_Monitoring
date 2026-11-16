package com.network.monitoring.shared.fingerprint;

import com.network.monitoring.shared.json.CanonicalJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Stateless content hashing shared by ingestion, caches, reports and delivery. */
public final class ContentFingerprint {
    private ContentFingerprint() {}

    public static String key(String namespace, Map<String, Object> request,
                             String dataset, Map<String, String> engine) {
        try {
            return HexFormat.of().formatHex(sha256().digest(CanonicalJson.bytes(Map.of(
                    "pipeline", namespace, "dataset", dataset, "engine", engine,
                    "request", request == null ? Map.of() : request))));
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to serialize recommendation settings.", ex);
        }
    }

    public static String fileSha256(Path path) throws IOException {
        MessageDigest digest = sha256();
        try (var stream = new DigestInputStream(Files.newInputStream(path), digest)) {
            byte[] buffer = new byte[64 * 1024];
            while (stream.read(buffer) != -1) { /* bounded streaming */ }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }
}

package com.network.monitoring.rca.service;

import com.network.monitoring.shared.fingerprint.ContentFingerprint;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;

/** RCA owns its cache namespace; byte hashing belongs to shared infrastructure. */
final class RecommendationCacheKey {
    private static final String PIPELINE_VERSION = "rca-cache-v2";

    private RecommendationCacheKey() {}

    static String build(Map<String, Object> request, String dataset, Map<String, String> engine) {
        return ContentFingerprint.key(PIPELINE_VERSION, request, dataset, engine);
    }

    static String fileDigest(Path path) throws IOException {
        return ContentFingerprint.fileSha256(path);
    }

    static MessageDigest digest() {
        return ContentFingerprint.sha256();
    }
}

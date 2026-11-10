package com.network.monitoring.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class KpiCacheServiceTest {
    @TempDir Path directory;

    @Test
    void fingerprintMatchesTheExactLoadedSnapshotAndDetectsSameLengthChanges() throws Exception {
        var cache = new KpiCacheService(directory.toString());
        cache.writeKpiCache(1, Map.of("records", List.of(Map.of("value", 10))));
        String before = cache.fingerprintKpiCache(1).orElseThrow();
        assertEquals(before, cache.readSnapshot(1).fingerprint());
        long size = Files.size(cache.getKpiCachePath(1));
        cache.writeKpiCache(1, Map.of("records", List.of(Map.of("value", 11))));
        assertEquals(size, Files.size(cache.getKpiCachePath(1)));
        assertNotEquals(before, cache.fingerprintKpiCache(1).orElseThrow());
        assertEquals(cache.fingerprintKpiCache(1).orElseThrow(), cache.readSnapshot(1).fingerprint());
        cache.deleteKpiCache(1);
        assertTrue(cache.fingerprintKpiCache(1).isEmpty());
    }

    @Test
    void missingEmptyCorruptOrFallbackSourcesCannotProduceReusableIdentity() throws Exception {
        var cache = new KpiCacheService(directory.toString());
        assertTrue(cache.fingerprintKpiCache(1).isEmpty());
        for (String payload : List.of("{}", "null", "[]", "{\"records\":[]}",
                "{\"records\":[1,null]}", "{\"records\":[{}]", "{\"records\":[{}]}{}",
                "{\"records\":[{}],\"records\":null}")) {
            Files.writeString(cache.getKpiCachePath(1), payload);
            assertTrue(cache.fingerprintKpiCache(1).isEmpty(), payload);
        }
    }

    @Test
    void failedSerializationPreservesOldCacheAndCleansTemporaryFile() throws Exception {
        var cache = new KpiCacheService(directory.toString());
        var original = Map.of("records", List.of(Map.of("value", 1)));
        cache.writeKpiCache(1, original);
        assertThrows(IllegalStateException.class, () -> cache.writeKpiCache(1, new BrokenPayload()));
        assertEquals(original, cache.readKpiCache(1));
        try (var files = Files.list(directory)) {
            assertEquals(List.of("1.json"), files.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void readersSeeCompleteOldOrNewSnapshotsDuringReplacement() throws Exception {
        var cache = new KpiCacheService(directory.toString());
        cache.writeKpiCache(1, Map.of("records", List.of(Map.of("generation", 0))));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var writer = executor.submit(() -> {
                for (int i = 1; i <= 30; i++) {
                    cache.writeKpiCache(1, Map.of("records", List.of(Map.of("generation", i))));
                }
            });
            for (int i = 0; i < 60; i++) {
                assertNotNull(cache.readSnapshot(1));
                assertTrue(cache.fingerprintKpiCache(1).isPresent());
            }
            writer.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    public static class BrokenPayload {
        public String getValue() { throw new IllegalStateException("Simulated serialization failure"); }
    }
}

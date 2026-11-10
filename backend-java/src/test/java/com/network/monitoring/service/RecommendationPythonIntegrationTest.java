package com.network.monitoring.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecommendationPythonIntegrationTest {
    @TempDir Path directory;

    @Test
    @EnabledIfEnvironmentVariable(named = "KPI_RCA_PYTHON", matches = ".+")
    void realPythonOutputCarriesIdentityOfActualLoadedInput() throws Exception {
        var cache = new KpiCacheService(directory.resolve("cache").toString());
        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            records.add(Map.of("date", "2026-03-%02d".formatted(i / 2 + 1),
                    "cellName", "testcell_" + i % 2, "site", "testsite", "tech", "LTE",
                    "metrics", Map.of("mvpuschsinr", i < 10 ? 12 : -2,
                            "mveutranaveragecqi", i < 10 ? 10 : 2)));
        }
        cache.writeKpiCache(1, Map.of("records", records));
        var service = new RecommendationService(cache, null, null);
        Path workspace = Path.of(System.getProperty("user.dir"));
        if (workspace.getFileName().toString().equals("backend-java")) workspace = workspace.getParent();
        Path engine = workspace.resolve("python service");
        ReflectionTestUtils.setField(service, "pythonServicePath", engine.toString());
        ReflectionTestUtils.setField(service, "pythonBin", System.getenv("KPI_RCA_PYTHON"));
        Map<String, Object> request = Map.of(
                "vendor", "nokia",
                "columnMapping", Map.of("Short name", "cellname", "Date", "date",
                        "Cell I'd", "cellname", "Site I'd", "site", "Tech", "tech"),
                "kpiMapping", Map.of("MV_PUSCH SINR", "mvpuschsinr",
                        "MV_E-UTRAN Average CQI", "mveutranaveragecqi"),
                "selectedKpis", List.of("MV_PUSCH SINR", "MV_E-UTRAN Average CQI"));
        String expected = service.currentCacheKey(1, request).orElseThrow();
        var result = service.runRecommendation(1, request, false);
        try {
            assertEquals(expected, result.inputCacheKey);
            assertEquals(expected, service.currentCacheKey(1, request).orElseThrow());
            assertTrue(result.payload.containsKey("summary"));
            assertTrue(result.payload.containsKey("anomaly_details"));
        } finally {
            service.cleanup(result.workDir);
        }
        assertFalse(Files.exists(result.workDir));
        // Reports must analyze their captured source even when the live cache changes.
        var pinned = cache.readSnapshot(1);
        cache.writeKpiCache(1, Map.of("records", List.of(Map.of("date", "2026-03-07", "cellName", "new-live-cell", "metrics", Map.of("mvpuschsinr", 99)))));
        var captured = service.runSnapshot(1, pinned, request);
        try { assertEquals(expected, captured.inputCacheKey); assertTrue(captured.payload.containsKey("anomaly_details")); }
        finally { service.cleanup(captured.workDir); }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KPI_BENCHMARK_CACHE_FILE", matches = ".+")
    void measureValidatedFingerprintOnRealCacheWithoutChangingData() throws Exception {
        Path path = Path.of(System.getenv("KPI_BENCHMARK_CACHE_FILE"));
        var cache = new KpiCacheService(path.getParent().toString());
        long fileId = Long.parseLong(path.getFileName().toString().replace(".json", ""));
        long started = System.nanoTime();
        String fingerprint = cache.fingerprintKpiCache(fileId).orElseThrow();
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        assertEquals(RecommendationCacheKey.fileDigest(path), fingerprint);
        System.out.printf("[RCA-FINGERPRINT] bytes=%d validatedHashMs=%d%n", Files.size(path), elapsed);
    }
}

package com.network.monitoring.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecommendationCacheKeyTest {
    @TempDir Path directory;

    @Test
    void equivalentNestedMapOrderReusesKeyButArrayOrderAndSettingsChangesDoNot() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("vendor", "nokia");
        first.put("kpis", new LinkedHashMap<>(Map.of("a", 1, "b", 2)));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("kpis", Map.of("b", 2, "a", 1));
        second.put("vendor", "nokia");
        String key = RecommendationCacheKey.build(first, "data", Map.of("engine", "1"));
        assertEquals(64, key.length());
        assertEquals(key, RecommendationCacheKey.build(second, "data", Map.of("engine", "1")));
        second.put("vendor", "ericsson");
        assertNotEquals(key, RecommendationCacheKey.build(second, "data", Map.of("engine", "1")));
        assertNotEquals(key, RecommendationCacheKey.build(first, "updated-data", Map.of("engine", "1")));
        assertNotEquals(RecommendationCacheKey.build(Map.of("selectedKpis", List.of("a", "b")), "data", Map.of()),
                RecommendationCacheKey.build(Map.of("selectedKpis", List.of("b", "a")), "data", Map.of()));
    }

    @Test
    void dataEnginePresetsAndExplicitRuntimeRevisionInvalidateButUnchangedInputsReuse() throws Exception {
        Path engine = Files.createDirectory(directory.resolve("engine"));
        for (String name : List.of("main.py", "analyser.py", "lb_service.py")) Files.writeString(engine.resolve(name), "# initial");
        var cache = new KpiCacheService(directory.resolve("cache").toString());
        cache.writeKpiCache(1, Map.of("records", List.of(Map.of("value", 1))));
        var service = new RecommendationService(cache, null, null);
        ReflectionTestUtils.setField(service, "pythonServicePath", engine.toString());
        String initial = service.currentCacheKey(1, Map.of()).orElseThrow();
        assertEquals(initial, service.currentCacheKey(1, Map.of()).orElseThrow());
        cache.writeKpiCache(1, Map.of("records", List.of(Map.of("value", 2))));
        String appended = service.currentCacheKey(1, Map.of()).orElseThrow();
        assertNotEquals(initial, appended);
        Files.writeString(engine.resolve("analyser.py"), "# changed");
        String changedEngine = service.currentCacheKey(1, Map.of()).orElseThrow();
        assertNotEquals(appended, changedEngine);
        Files.writeString(engine.resolve("rca_presets.json"), "{\"same-name\":{\"threshold\":5}}");
        String preset = service.currentCacheKey(1, Map.of()).orElseThrow();
        assertNotEquals(changedEngine, preset);
        Files.writeString(engine.resolve("rca_presets.json"), "{\"same-name\":{\"threshold\":6}}");
        assertNotEquals(preset, service.currentCacheKey(1, Map.of()).orElseThrow());
        String beforeRevision = service.currentCacheKey(1, Map.of()).orElseThrow();
        ReflectionTestUtils.setField(service, "cacheRevision", "new-runtime");
        assertNotEquals(beforeRevision, service.currentCacheKey(1, Map.of()).orElseThrow());
        Files.delete(engine.resolve("main.py"));
        assertTrue(service.currentCacheKey(1, Map.of()).isEmpty());
        cache.deleteKpiCache(1);
        assertTrue(service.currentCacheKey(1, Map.of()).isEmpty());
    }
}

package com.network.monitoring.service;

import com.network.monitoring.entity.KpiThresholdRule;
import com.network.monitoring.repository.KpiThresholdRuleRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommonReportSnapshotAdaptersTest {
    @Test void thresholdAndAlarmEvaluationAreReadOnlyAndInvalidNumbersAreNotHealthy() {
        var jdbc = mock(JdbcTemplate.class); var data = mock(KpiDynamicDataService.class); var rules = mock(KpiThresholdRuleRepository.class);
        var thresholds = new KpiThresholdRuleService(rules, data, jdbc);
        var alarms = new KpiAlarmService(jdbc, data, thresholds);
        var rule = new KpiThresholdRule(); rule.setMetricName("prb"); rule.setDirection("HIGHER_IS_BAD"); rule.setCriticalValue(90.0);
        var rows = List.<Map<String, Object>>of(Map.of("date", "2026-11-05", "cellName", "A", "tech", "4G", "metrics", Map.of("prb", 95.0, "unknown", 1.0, "invalid", Double.NaN)));
        var result = thresholds.evaluateSnapshot(500L, rows, List.of(rule));
        assertEquals(1, result.get("valuesWithoutRule")); assertEquals(1, result.get("invalidValues"));
        assertEquals(1, CommonReportRenderer.map(result.get("severityCounts")).get("CRITICAL"));
        var candidates = alarms.evaluateSnapshot(500L, rows, List.of(rule), List.of(), 10);
        assertEquals(1, candidates.get("candidateCount")); assertEquals(1, candidates.get("invalidValues"));
        verifyNoInteractions(jdbc, data, rules);
    }
    @Test void optionsRejectUnknownOrDuplicateModules() {
        assertThrows(IllegalArgumentException.class, () -> new CommonReportModules.Options(List.of("rca", "rca"), null).validated());
        assertThrows(IllegalArgumentException.class, () -> new CommonReportModules.Options(List.of("typo"), null).validated());
        assertEquals(5, new CommonReportModules.Options(null, null).validated().modules().size());
    }
    @Test void capturedRulesAndEngineAreDetachedAndKpiPredictionsDoNotReadLiveContext() {
        var jdbc = mock(JdbcTemplate.class); var data = mock(KpiDynamicDataService.class);
        var rules = mock(KpiThresholdRuleRepository.class); when(rules.findAllByEnabledTrue()).thenReturn(List.of());
        var thresholds = new KpiThresholdRuleService(rules, data, jdbc);
        var alarms = new KpiAlarmService(jdbc, data, thresholds);
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
        var prediction = new SitePredictionService(data, null, null, null, jdbc, runnable -> { });
        var modules = new CommonReportModules(rules, thresholds, alarms, mock(RecommendationService.class), prediction);
        var input = CommonReportRendererTest.input(); var capture = modules.capture(input, new CommonReportModules.Options(null, null));
        clearInvocations(jdbc, rules);
        var source = new KpiCacheService.Snapshot(Map.of("records", List.of(Map.of("date", "2026-11-05", "cellName", "A", "site", "S", "metrics", Map.of("prb", 95.0)))), input.sourceFingerprint());
        var result = modules.run(input, source, capture, 100, 10, 100);
        assertEquals("SKIPPED", CommonReportRenderer.map(result.get("thresholds")).get("status"));
        assertEquals("LIMITED", CommonReportRenderer.map(result.get("prediction")).get("status"));
        assertEquals("SKIPPED", CommonReportRenderer.map(result.get("rca")).get("status"));
        verifyNoInteractions(jdbc, rules, data);
        capture.put("engineFingerprint", "changed");
        assertThrows(IllegalStateException.class, () -> modules.run(input, source, capture, 100, 10, 100));
    }
}

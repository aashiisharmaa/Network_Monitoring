package com.network.monitoring.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecommendationCacheMysqlTest {
    @TempDir Path directory;

    @Test
    @EnabledIfEnvironmentVariable(named = "KPI_TEST_DB_URL", matches = ".+")
    @SuppressWarnings("unchecked")
    void persistedCompletedJobsReuseOnlyTheCurrentDatasetAndSettingsAcrossServiceRecreation() throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv("KPI_TEST_DB_URL"),
                System.getenv("KPI_TEST_DB_USER"), System.getenv("KPI_TEST_DB_PASSWORD"))) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            // Only a session-local TEMPORARY table; real job history is never changed by this test.
            jdbc.execute("""
                    CREATE TEMPORARY TABLE recommendation_jobs (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY, fileId BIGINT NOT NULL,
                      configHash VARCHAR(64), status VARCHAR(32), progressPercent INT,
                      message VARCHAR(512), requestJson LONGTEXT, resultJson LONGTEXT,
                      createdAt DATETIME, updatedAt DATETIME
                    )
                    """);
            Path engine = Files.createDirectory(directory.resolve("engine"));
            for (String name : List.of("main.py", "analyser.py", "lb_service.py")) Files.writeString(engine.resolve(name), "# test engine");
            var cache = new KpiCacheService(directory.resolve("cache").toString());
            cache.writeKpiCache(1, Map.of("records", List.of(Map.of("value", 1))));
            var recommendation = spy(new RecommendationService(cache, null, null));
            ReflectionTestUtils.setField(recommendation, "pythonServicePath", engine.toString());
            doAnswer(invocation -> new RecommendationService.RecommendationResult(
                    Map.of("summary", Map.of("anomaly_count", 1)), null, directory.resolve("work"),
                    recommendation.currentCacheKey(invocation.getArgument(0), invocation.getArgument(1)).orElse(null)))
                    .when(recommendation).runRecommendation(anyLong(), anyMap(), eq(false), any());
            doNothing().when(recommendation).cleanup(any());
            ObjectProvider<RecommendationJobService> provider = mock(ObjectProvider.class);
            var jobs = new RecommendationJobService(jdbc, recommendation, provider);
            when(provider.getObject()).thenReturn(jobs); // Synchronous worker for this integration fixture.
            var request = Map.<String, Object>of("vendor", "nokia");
            Map<String, Object> first = (Map<String, Object>) jobs.startJob(1L, request).get("data");
            assertEquals("COMPLETED", first.get("status"));
            // Emulate restart: same persisted history, fresh service object.
            jobs = new RecommendationJobService(jdbc, recommendation, provider);
            when(provider.getObject()).thenReturn(jobs);
            Map<String, Object> reused = (Map<String, Object>) jobs.startJob(1L, request).get("data");
            assertEquals(first.get("jobId"), reused.get("jobId"));
            cache.writeKpiCache(1, Map.of("records", List.of(Map.of("value", 1), Map.of("value", 2))));
            Map<String, Object> appended = (Map<String, Object>) jobs.startJob(1L, request).get("data");
            assertNotEquals(first.get("jobId"), appended.get("jobId"));
            assertNotEquals(first.get("configHash"), appended.get("configHash"));
            Map<String, Object> changedSettings = (Map<String, Object>) jobs.startJob(1L, Map.of("vendor", "ericsson")).get("data");
            assertNotEquals(appended.get("jobId"), changedSettings.get("jobId"));
            Files.writeString(engine.resolve("analyser.py"), "# changed engine");
            Map<String, Object> changedEngine = (Map<String, Object>) jobs.startJob(1L, request).get("data");
            assertNotEquals(appended.get("jobId"), changedEngine.get("jobId"));
            cache.deleteKpiCache(1);
            Map<String, Object> fallback = (Map<String, Object>) jobs.startJob(1L, request).get("data");
            Map<String, Object> fallbackAgain = (Map<String, Object>) jobs.startJob(1L, request).get("data");
            assertNotEquals(fallback.get("jobId"), fallbackAgain.get("jobId"));
            assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_jobs", Integer.class));
            verify(recommendation, times(6)).runRecommendation(anyLong(), anyMap(), eq(false), any());
        }
    }
}

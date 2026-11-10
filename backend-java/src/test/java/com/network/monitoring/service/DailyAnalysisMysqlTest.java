package com.network.monitoring.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;

import static com.network.monitoring.service.DailyKpiCalculator.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DailyAnalysisMysqlTest {
    @TempDir Path directory;

    @Test
    @EnabledIfEnvironmentVariable(named = "KPI_TEST_DB_URL", matches = ".+")
    @SuppressWarnings("unchecked")
    void persistedComparisonsAreVersionedPaginatedRecoverableAndAutomaticallyAttachedToImport() throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv("KPI_TEST_DB_URL"),
                System.getenv("KPI_TEST_DB_USER"), System.getenv("KPI_TEST_DB_PASSWORD"))) {
            var ds = new SingleConnectionDataSource(connection, true);
            var jdbc = spy(new JdbcTemplate(ds));
            for (String table : List.of("uploadhistory", "kpi_dataset_snapshots", "kpi_ingestion_runs", "kpi_analysis_profiles", "kpi_analysis_snapshots", "kpi_daily_groups")) {
                String ddl = jdbc.queryForMap("SHOW CREATE TABLE `" + table + "`").get("Create Table").toString();
                ddl = ddl.replaceFirst("CREATE TABLE", "CREATE TEMPORARY TABLE");
                ddl = ddl.lines().filter(line -> !line.stripLeading().startsWith("CONSTRAINT "))
                        .collect(java.util.stream.Collectors.joining("\n")).replace(",\n)", "\n)");
                jdbc.execute(ddl);
            }
            jdbc.update("INSERT INTO uploadhistory (id,remarks) VALUES (500,'[kpi] fixture'),(501,'[kpi] legacy')");
            var store = new KpiSnapshotStore(jdbc, new DataSourceTransactionManager(ds));
            var cache = new KpiCacheService(directory.resolve("cache").toString(), store);
            ObjectProvider<DailyAnalysisService> provider = mock(ObjectProvider.class);
            var service = new DailyAnalysisService(jdbc, store, cache, provider);
            when(provider.getObject()).thenReturn(service);
            LocalDate day = LocalDate.of(2026, 11, 8);
            List<Map<String, Object>> records = new ArrayList<>();
            for (int date = 1; date <= 8; date++) {
                for (String cell : List.of("A", "B")) records.add(Map.of("date", LocalDate.of(2026, 11, date).toString(), "cellName", cell, "metrics", Map.of("prb", date)));
            }
            cache.writeKpiCache(500, Map.of("records", records));
            var profile = new Profile(List.of("cellName"), List.of(new Metric("prb", Aggregation.MEAN, Direction.LOWER_IS_BETTER, "PERCENT", null, null, null, true)), 7, 1, true);
            assertEquals("CONFIGURATION_REQUIRED", service.profile(500).get("status"));
            service.saveProfile(500, profile);
            Map<String, Object> first = service.request(500, null);
            assertEquals("COMPLETED", first.get("status"), first.toString());
            long id = ((Number) first.get("id")).longValue();
            assertEquals("CURRENT", first.get("freshness"));
            assertEquals(id, ((Number) service.request(500, null).get("id")).longValue());
            var missingDay = service.request(500, day.plusDays(30));
            assertEquals("NO_DATA_IN_WINDOW", ((Map<?, ?>) missingDay.get("summary")).get("dataStatus"));
            Map<String, Object> page = service.comparisons(id, 1, 1);
            assertEquals(2L, page.get("totalGroups"));
            List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
            assertEquals(1, items.size());
            assertEquals(4.0, ((Map<?, ?>) items.get(0).get("previousSevenDays")).get("baselineValue"));
            assertEquals("DEGRADED", ((Map<?, ?>) items.get(0).get("previousDay")).get("trend"));
            assertEquals(8, ((List<?>) items.get(0).get("trend")).size());

            // Fresh service reads the same persisted data; no in-memory result cache is needed.
            var restored = new DailyAnalysisService(jdbc, store, cache, provider);
            assertEquals(items, restored.comparisons(id, 1, 1).get("items"));
            records.add(Map.of("date", "2026-11-09", "cellName", "A", "metrics", Map.of("prb", 9)));
            cache.writeKpiCache(500, Map.of("records", records));
            Map<String, Object> newer = service.request(500, null);
            assertNotEquals(first.get("id"), newer.get("id"));
            assertEquals("HISTORICAL", service.get(id).get("freshness"));
            assertEquals(items, service.comparisons(id, 1, 1).get("items"));

            // A queued immutable source remains reproducible even after another version is published.
            var heldWorker = mock(DailyAnalysisService.class);
            when(provider.getObject()).thenReturn(heldWorker);
            long queued = ((Number) service.request(500, day.minusDays(1)).get("id")).longValue();
            records.add(Map.of("date", "2026-11-10", "cellName", "A", "metrics", Map.of("prb", 10)));
            cache.writeKpiCache(500, Map.of("records", records));
            service.process(queued);
            assertEquals("COMPLETED", service.get(queued).get("status"));
            assertEquals("HISTORICAL", service.get(queued).get("freshness"));
            when(provider.getObject()).thenReturn(service);

            // A failure after row batches cannot publish a partial analysis snapshot.
            doThrow(new IllegalStateException("injected publish failure")).doCallRealMethod()
                    .when(jdbc).update(contains("SET status='COMPLETED',dataDate"), any(Object[].class));
            long failed = ((Number) service.request(500, day.minusDays(2)).get("id")).longValue();
            assertEquals("FAILED", service.get(failed).get("status"));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM kpi_daily_groups WHERE snapshotId=?", Integer.class, failed));
            assertThrows(IllegalArgumentException.class, () -> service.comparisons(failed, 1, 10));
            assertEquals("COMPLETED", service.retry(failed).get("status"));

            // Queued legacy files are mutable: refuse changed input instead of analyzing another version.
            var legacy = new KpiCacheService(directory.resolve("legacy").toString());
            legacy.writeKpiCache(501, Map.of("records", records));
            var legacyService = new DailyAnalysisService(jdbc, store, legacy, provider);
            legacyService.saveProfile(501, profile);
            when(provider.getObject()).thenReturn(heldWorker);
            long legacyId = ((Number) legacyService.request(501, null).get("id")).longValue();
            legacy.writeKpiCache(501, Map.of("records", List.of(Map.of("date", "2026-11-11", "cellName", "A", "metrics", Map.of("prb", 11)))));
            legacyService.process(legacyId);
            assertEquals("FAILED", legacyService.get(legacyId).get("status"));

            doThrow(new RejectedExecutionException("full")).when(heldWorker).process(anyLong());
            var rejected = assertThrows(JobCapacityException.class, () -> service.request(500, day.minusDays(3)));
            assertEquals("FAILED", service.get(rejected.getJobId()).get("status"));
            when(provider.getObject()).thenReturn(service);

            // Imported-data state and analysis state are independent; missing config does not fail import.
            jdbc.update("""
                    INSERT INTO kpi_ingestion_runs (id,importKey,fileId,sourceName,fileName,sourcePath,checksum,configJson,status,resultJson,createdAt,updatedAt)
                    VALUES (1,REPEAT('a',64),500,'common','fixture','not-read',REPEAT('b',64),'{}','COMPLETED','{}',NOW(),NOW()),
                           (2,REPEAT('c',64),501,'common','fixture','not-read',REPEAT('d',64),'{}','COMPLETED','{}',NOW(),NOW())
                    """);
            jdbc.update("DELETE FROM kpi_analysis_profiles WHERE fileId=501");
            service.afterImport(501, 2);
            assertEquals("CONFIGURATION_REQUIRED", jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(resultJson,'$.analysisStatus')) FROM kpi_ingestion_runs WHERE id=2", String.class));
            service.afterImport(500, 1);
            assertEquals("COMPLETED", jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(resultJson,'$.analysisStatus')) FROM kpi_ingestion_runs WHERE id=1", String.class));
            assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM kpi_ingestion_runs WHERE id=1", String.class));
            assertEquals(1, service.list(500, 1).size());
            jdbc.update("UPDATE kpi_ingestion_runs SET resultJson=JSON_SET(resultJson,'$.datasetRevision',?) WHERE id=1", store.revision(500));
            String capturedProfile = service.profile(500).get("profileHash").toString();
            var restoredLink = service.requestImported(1, day, capturedProfile);
            assertEquals("COMPLETED", restoredLink.get("status")); assertEquals(day.toString(), restoredLink.get("dataDate").toString());
            assertThrows(IllegalStateException.class, () -> service.requestImported(1, day, "changed-profile"));
            jdbc.update("UPDATE kpi_ingestion_runs SET resultJson=JSON_SET(resultJson,'$.datasetRevision','another-version') WHERE id=1");
            assertThrows(IllegalStateException.class, () -> service.requestImported(1, day, capturedProfile));

            service.saveProfile(500, new Profile(List.of("cellName"), List.of(new Metric("prb", Aggregation.MEAN, Direction.HIGHER_IS_BETTER, "PERCENT", null, null, null, true)), 7, 1, true));
            assertEquals(false, service.get(id).get("profileCurrent"));
            assertEquals(items, service.comparisons(id, 1, 1).get("items"));

            // Recovery affects unfinished work only.
            when(provider.getObject()).thenReturn(mock(DailyAnalysisService.class));
            long interrupted = ((Number) service.request(500, day).get("id")).longValue();
            service.initialize();
            assertEquals("FAILED", service.get(interrupted).get("status"));
            assertEquals("COMPLETED", service.get(id).get("status"));
        }
    }
}

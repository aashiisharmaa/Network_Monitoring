package com.network.monitoring.service;

import com.network.monitoring.entity.UploadHistory;
import com.network.monitoring.repository.UploadHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KpiIngestionMysqlTest {
    @TempDir Path directory;

    @Test
    @EnabledIfEnvironmentVariable(named = "KPI_TEST_DB_URL", matches = ".+")
    @SuppressWarnings("unchecked")
    void realCsvImportDeduplicatesPersistsAndRollsBackRowsPointerAndRunTogether() throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv("KPI_TEST_DB_URL"),
                System.getenv("KPI_TEST_DB_USER"), System.getenv("KPI_TEST_DB_PASSWORD"))) {
            var dataSource = new SingleConnectionDataSource(connection, true);
            var jdbc = spy(new JdbcTemplate(dataSource));
            // Clones shadow all writable production tables on this connection only.
            for (String table : List.of("uploadhistory", "uploaddata", "kpidynamicdata", "kpi_metric_values",
                    "kpi_dataset_snapshots", "kpi_ingestion_runs", "site_prediction_results")) {
                String ddl = jdbc.queryForMap("SHOW CREATE TABLE `" + table + "`").get("Create Table").toString();
                ddl = ddl.replaceFirst("CREATE TABLE", "CREATE TEMPORARY TABLE");
                ddl = ddl.lines().filter(line -> !line.stripLeading().startsWith("CONSTRAINT "))
                        .collect(java.util.stream.Collectors.joining("\n")).replace(",\n)", "\n)");
                jdbc.execute(ddl);
            }
            jdbc.update("INSERT INTO uploadhistory (id,fileName,remarks,companyId) VALUES (500,'daily.csv','[kpi] test',99)");
            var store = new KpiSnapshotStore(jdbc, new DataSourceTransactionManager(dataSource));
            var cache = new KpiCacheService(directory.resolve("cache").toString(), store);
            var dynamic = new KpiDynamicDataService(jdbc);
            var history = mock(UploadHistoryRepository.class);
            var target = new UploadHistory();
            target.setId(500L); target.setCompanyId(99L); target.setRemarks("[kpi] test");
            when(history.findById(500L)).thenReturn(Optional.of(target));
            var role = mock(RoleAuthorizationService.class);
            when(role.currentUserCompanyIdOrNull()).thenReturn(99L);
            var uploads = spy(new UploadService(new ParserService(), null, history, null, null, null, null,
                    cache, dynamic, null, null, null, role, jdbc, null, store));
            doNothing().when(uploads).afterDailyCommit(anyLong());
            ObjectProvider<KpiIngestionService> provider = mock(ObjectProvider.class);
            var analysis = mock(DailyAnalysisService.class);
            var service = new KpiIngestionService(jdbc, store, cache, dynamic, uploads, history, provider, directory.toString(), analysis);
            when(provider.getObject()).thenReturn(service); // Synchronous fixture; production uses the bounded upload pool.
            List<String> fields = List.of("date", "cellName");

            var first = service.start(file("daily.csv", "2026-11-01,CELL_A,SITE_A,LTE,10\n"), 500L, "common", fields, Map.of());
            Map<String, Object> firstRun = (Map<String, Object>) first.get("data");
            assertEquals("COMPLETED", firstRun.get("status"), firstRun.toString());
            verify(analysis).afterImport(eq(500L), eq(((Number) firstRun.get("id")).longValue()));
            assertCounts(jdbc, 1);
            assertEquals(1, ((List<?>) cache.readKpiCache(500).get("records")).size());
            var repeat = service.start(file("renamed.csv", "2026-11-01,CELL_A,SITE_A,LTE,10\n"), 500L, "another-label", fields, Map.of());
            assertEquals(true, repeat.get("duplicateFile"));
            assertEquals(firstRun.get("id"), ((Map<?, ?>) repeat.get("data")).get("id"));

            var second = service.start(file("day2.csv", "2026-11-01,CELL_A,SITE_A,LTE,10\n2026-11-02,CELL_A,SITE_A,LTE,20\n"),
                    500L, "common", fields, Map.of());
            assertEquals("COMPLETED", ((Map<?, ?>) second.get("data")).get("status"));
            assertCounts(jdbc, 2);
            assertEquals(2, ((List<?>) cache.readKpiCache(500).get("records")).size());
            String activeBefore = store.activeName(500);
            long versionsBefore;
            try (var files = Files.list(directory.resolve("cache/versions"))) { versionsBefore = files.count(); }

            var conflict = service.start(file("correction.csv", "2026-11-01,CELL_A,SITE_A,LTE,99\n"), 500L, "common", fields, Map.of());
            assertEquals("FAILED", ((Map<?, ?>) conflict.get("data")).get("status"));
            assertCounts(jdbc, 2);
            assertEquals(activeBefore, store.activeName(500));

            // Fail after publishing a NEW pointer and after actual metric/legacy writes.
            doThrow(new IllegalStateException("injected commit-stage failure")).doCallRealMethod()
                    .when(jdbc).update(contains("SET status='COMPLETED'"), any(Object[].class));
            var failed = service.start(file("day3.csv", "2026-11-03,CELL_A,SITE_A,LTE,30\n"), 500L, "common", fields, Map.of());
            assertEquals("FAILED", ((Map<?, ?>) failed.get("data")).get("status"));
            assertCounts(jdbc, 2);
            assertEquals(activeBefore, store.activeName(500));
            try (var files = Files.list(directory.resolve("cache/versions"))) { assertEquals(versionsBefore, files.count()); }
            long failedId = ((Number) ((Map<?, ?>) failed.get("data")).get("id")).longValue();
            assertEquals("COMPLETED", service.retry(failedId).get("status"));
            assertEquals(2, ((Number) service.get(failedId).get("attempts")).intValue());
            assertCounts(jdbc, 3);

            // Direct transaction rollback proves even a published pointer and immutable file roll back.
            String committed = store.activeName(500);
            long beforeRollback;
            try (var files = Files.list(directory.resolve("cache/versions"))) { beforeRollback = files.count(); }
            assertThrows(IllegalStateException.class, () -> store.locked(500, () -> {
                cache.writeKpiCache(500, Map.of("records", List.of(Map.of("fake", 1))));
                throw new IllegalStateException("rollback");
            }));
            assertEquals(committed, store.activeName(500));
            try (var files = Files.list(directory.resolve("cache/versions"))) { assertEquals(beforeRollback, files.count()); }
            assertThrows(IllegalArgumentException.class, () -> uploads.uploadKpiData(
                    file("bypass.csv", "2026-11-04,CELL_A,SITE_A,LTE,40\n"), null, null, "append", 500L, null, false));

            // Recover only unfinished ledger state; committed imports remain completed.
            Map<String, Object> committedRun = jdbc.queryForMap("SELECT * FROM kpi_ingestion_runs WHERE id=?", failedId);
            jdbc.update("""
                    INSERT INTO kpi_ingestion_runs
                      (importKey,fileId,companyId,sourceName,fileName,sourcePath,checksum,configJson,status,createdAt,updatedAt)
                    VALUES (REPEAT('a',64),?,?,?,?,?,?,?,'IMPORTING',NOW(),NOW())
                    """, committedRun.get("fileId"), committedRun.get("companyId"), committedRun.get("sourceName"),
                    committedRun.get("fileName"), committedRun.get("sourcePath"), committedRun.get("checksum"), committedRun.get("configJson"));
            long interrupted = jdbc.queryForObject("SELECT MAX(id) FROM kpi_ingestion_runs", Long.class);
            service.recoverInterruptedRuns();
            assertEquals("FAILED", service.get(interrupted).get("status"));
            assertEquals("COMPLETED", service.get(failedId).get("status"));
            assertEquals("COMPLETED", service.get(((Number) firstRun.get("id")).longValue()).get("status"));
            assertTrue(versionsBefore >= 2);

            // Queued source tampering is detected before writes; paths are not exposed by API data.
            KpiIngestionService worker = mock(KpiIngestionService.class);
            when(provider.getObject()).thenReturn(worker);
            var queued = service.start(file("day4.csv", "2026-11-04,CELL_A,SITE_A,LTE,40\n"), 500L, "common", fields, Map.of());
            long queuedId = ((Number) ((Map<?, ?>) queued.get("data")).get("id")).longValue();
            assertFalse(service.get(queuedId).containsKey("sourcePath"));
            String stored = jdbc.queryForObject("SELECT sourcePath FROM kpi_ingestion_runs WHERE id=?", String.class, queuedId);
            Files.writeString(Path.of(stored), "changed");
            service.process(queuedId);
            assertEquals("FAILED", service.get(queuedId).get("status"));
            assertCounts(jdbc, 3);

            doThrow(new java.util.concurrent.RejectedExecutionException("full")).when(worker).process(anyLong());
            var rejected = assertThrows(JobCapacityException.class, () -> service.start(
                    file("day5.csv", "2026-11-05,CELL_A,SITE_A,LTE,50\n"), 500L, "common", fields, Map.of()));
            assertEquals("FAILED", service.get(rejected.getJobId()).get("status"));
            when(provider.getObject()).thenReturn(service);
            assertEquals("COMPLETED", service.retry(rejected.getJobId()).get("status"));
            assertCounts(jdbc, 4);
            assertEquals(1, service.list(1).size());
            assertFalse(service.list(1).get(0).containsKey("sourcePath"));

            var changedGrain = service.start(file("day6.csv", "2026-11-06,CELL_A,SITE_A,LTE,60\n"),
                    500L, "common", List.of("date", "cellName", "tech"), Map.of());
            assertEquals("FAILED", ((Map<?, ?>) changedGrain.get("data")).get("status"));
            assertCounts(jdbc, 4);
            Path daily = directory.resolve("nightly.csv"); Files.write(daily, file("nightly.csv", "2026-11-06,CELL_A,SITE_A,LTE,60\n").getBytes());
            var nightly = new NightlyProfile(false, directory.toString(), "kpi-{date}.csv", fields, Map.of(), "REPORT_ONLY", "01:30", null).validated();
            var wrongDay = service.startPath(daily, 500, nightly, java.time.LocalDate.of(2026,11,7), RecommendationCacheKey.fileDigest(daily), 200000000);
            assertEquals("FAILED", ((Map<?, ?>) wrongDay.get("data")).get("status")); assertCounts(jdbc, 4);
            assertThrows(IllegalArgumentException.class, () -> service.startPath(daily, 500, nightly, java.time.LocalDate.of(2026,11,6), "changed-checksum", 200000000));
            var correctDay = service.startPath(daily, 500, nightly, java.time.LocalDate.of(2026,11,6), RecommendationCacheKey.fileDigest(daily), 200000000);
            assertEquals("COMPLETED", ((Map<?, ?>) correctDay.get("data")).get("status")); assertCounts(jdbc, 5);
            verify(analysis).afterImport(eq(500L), anyLong(), eq(java.time.LocalDate.of(2026,11,6)));
        }
    }

    private static MockMultipartFile file(String name, String rows) {
        return new MockMultipartFile("file", name, "text/csv",
                ("date,cellname,site,tech,dlprbutilizationrate\n" + rows).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void assertCounts(JdbcTemplate jdbc, int expected) {
        assertEquals(expected, jdbc.queryForObject("SELECT COUNT(*) FROM uploaddata WHERE fileId=500", Integer.class));
        assertEquals(expected, jdbc.queryForObject("SELECT COUNT(*) FROM kpidynamicdata WHERE fileId=500", Integer.class));
        assertEquals(expected, jdbc.queryForObject("SELECT COUNT(*) FROM kpi_metric_values WHERE fileId=500", Integer.class));
    }
}

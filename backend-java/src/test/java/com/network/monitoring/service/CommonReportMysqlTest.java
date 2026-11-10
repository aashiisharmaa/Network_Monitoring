package com.network.monitoring.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommonReportMysqlTest {
    @TempDir Path directory;
    @Test @EnabledIfEnvironmentVariable(named = "KPI_TEST_DB_URL", matches = ".+")
    @SuppressWarnings("unchecked") void durableReportsAreIdempotentPinnedRecoverableAndIntegrityChecked() throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv("KPI_TEST_DB_URL"), System.getenv("KPI_TEST_DB_USER"), System.getenv("KPI_TEST_DB_PASSWORD"))) {
            var ds = new SingleConnectionDataSource(connection, true); var jdbc = new JdbcTemplate(ds);
            for (String table : List.of("uploadhistory", "kpi_dataset_snapshots", "kpi_common_reports")) {
                String ddl = jdbc.queryForMap("SHOW CREATE TABLE `" + table + "`").get("Create Table").toString().replaceFirst("CREATE TABLE", "CREATE TEMPORARY TABLE");
                ddl = ddl.lines().filter(line -> !line.stripLeading().startsWith("CONSTRAINT ")).collect(java.util.stream.Collectors.joining("\n")).replace(",\n)", "\n)"); jdbc.execute(ddl);
            }
            jdbc.update("INSERT INTO uploadhistory (id,remarks) VALUES (500,'[kpi] report fixture')");
            var store = new KpiSnapshotStore(jdbc, new DataSourceTransactionManager(ds));
            var cache = new KpiCacheService(directory.resolve("cache").toString(), store);
            cache.writeKpiCache(500, Map.of("records", List.of(Map.of("date", "2026-11-05", "cellName", "old", "metrics", Map.of("prb", 80)))));
            var source = Objects.requireNonNull(cache.readSnapshot(500));
            var fixture = CommonReportRendererTest.input();
            var input = new DailyAnalysisService.ReportInput(1, 500, cache.getKpiCachePath(500L).getFileName().toString(), source.fingerprint(), "fixture", "profile", fixture.date(), fixture.profile(), fixture.summary());
            var analysis = mock(DailyAnalysisService.class); when(analysis.reportInput(anyLong())).thenReturn(input);
            when(analysis.comparisons(1, 1, 100)).thenReturn(Map.of("totalGroups", 1, "items", List.of(CommonReportRendererTest.comparison("old", 80, false))));
            var modules = mock(CommonReportModules.class); when(modules.capture(any(), any())).thenReturn(Map.of("engineFingerprint", "fixture"));
            when(modules.run(any(), any(), any(), anyInt(), anyInt(), anyInt())).thenAnswer(call -> { assertEquals(source.fingerprint(), ((KpiCacheService.Snapshot) call.getArgument(1)).fingerprint()); return new LinkedHashMap<>(Map.of("rca", CommonReportModules.outcome("SKIPPED", "No mapping", Map.of()))); });
            ObjectProvider<CommonReportService> provider = mock(ObjectProvider.class); var held = mock(CommonReportService.class); when(provider.getObject()).thenReturn(held);
            var service = new CommonReportService(jdbc, analysis, cache, store, modules, new CommonReportRenderer(), provider, directory.resolve("reports").toString()); service.initialize();
            var options = new CommonReportModules.Options(null, null);
            long id = ((Number) service.request(1, options).get("id")).longValue();
            assertEquals(id, ((Number) service.request(1, options).get("id")).longValue()); verify(held, times(1)).process(id);
            cache.writeKpiCache(500, Map.of("records", List.of(Map.of("date", "2026-11-06", "cellName", "new", "metrics", Map.of("prb", 10)))));
            service.process(id); assertEquals("PARTIAL", service.get(id).get("status"));
            assertFalse(service.get(id).containsKey("artifactName"));
            Path pdf = service.download(id, "pdf"); assertTrue(Files.size(pdf) > 100);
            assertTrue(java.time.Duration.between(service.publishedAt(id), java.time.Instant.now()).abs().toSeconds() < 30);
            service.process(id); verify(modules, times(1)).run(any(), any(), any(), anyInt(), anyInt(), anyInt());
            assertThrows(IllegalArgumentException.class, () -> service.retry(id));
            Files.writeString(pdf, "tampered"); assertThrows(java.io.IOException.class, () -> service.download(id, "pdf"));
            assertThrows(IllegalArgumentException.class, () -> service.download(id, "../pdf"));
            long pending = ((Number) service.request(2, options).get("id")).longValue(); service.initialize(); assertEquals("FAILED", service.get(pending).get("status"));
            when(provider.getObject()).thenReturn(service); assertEquals("PARTIAL", service.retry(pending).get("status"));
            when(provider.getObject()).thenReturn(held); doThrow(new RejectedExecutionException()).when(held).process(anyLong());
            var full = assertThrows(JobCapacityException.class, () -> service.request(3, options)); assertEquals("FAILED", service.get(full.getJobId()).get("status"));
            when(provider.getObject()).thenReturn(service);
            doThrow(new IllegalStateException("injected module failure")).when(modules).run(any(), any(), any(), anyInt(), anyInt(), anyInt());
            assertEquals("FAILED", service.request(4, options).get("status"));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM kpi_common_reports WHERE artifactName IS NOT NULL", Integer.class));
        }
    }
}

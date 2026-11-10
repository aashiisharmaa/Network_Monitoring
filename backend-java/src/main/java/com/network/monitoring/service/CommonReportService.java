package com.network.monitoring.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;

@Service
public class CommonReportService {
    private final JdbcTemplate jdbc;
    private final DailyAnalysisService analysis;
    private final KpiCacheService cache;
    private final KpiSnapshotStore snapshots;
    private final CommonReportModules modules;
    private final CommonReportRenderer renderer;
    private final ObjectProvider<CommonReportService> self;
    private final Path root;
    @Value("${app.jobs.recover-on-startup:true}") private boolean recover = true;
    @Value("${app.jobs.report.max-comparison-rows:100000}") private int maxRows = 100000;
    @Value("${app.jobs.report.max-module-records:50000}") private int maxRecords = 50000;
    @Value("${app.jobs.report.detail-limit:200}") private int detailLimit = 200;
    @Value("${app.jobs.report.max-prediction-cells:500}") private int maxPredictionCells = 500;

    public CommonReportService(JdbcTemplate jdbc, DailyAnalysisService analysis, KpiCacheService cache,
                               KpiSnapshotStore snapshots, CommonReportModules modules, CommonReportRenderer renderer,
                               ObjectProvider<CommonReportService> self,
                               @Value("${app.report-base-path:uploads/common-reports}") String root) {
        this.jdbc = jdbc; this.analysis = analysis; this.cache = cache; this.snapshots = snapshots;
        this.modules = modules; this.renderer = renderer; this.self = self; this.root = Path.of(root).toAbsolutePath().normalize();
    }
    @PostConstruct public void initialize() {
        try { jdbc.queryForList("SELECT id FROM kpi_common_reports LIMIT 0"); }
        catch (org.springframework.dao.DataAccessException ex) { throw new IllegalStateException("Apply backend-java/db/migrations/004_common_reports.sql before starting.", ex); }
        if (maxRows < 1 || maxRows > 1000000 || maxRecords < 1 || detailLimit < 1 || detailLimit > 1000 || maxPredictionCells < 1 || maxPredictionCells > 2000) throw new IllegalArgumentException("Invalid common report limits.");
        if (recover) jdbc.update("UPDATE kpi_common_reports SET status='FAILED',message='Backend restarted before report publication. Retry the report.',updatedAt=NOW() WHERE status IN ('QUEUED','BUILDING')");
    }
    private record Submission(long id, boolean created) { }
    public Map<String, Object> request(long snapshotId, CommonReportModules.Options options) {
        var input = analysis.reportInput(snapshotId);
        Map<String, Object> config = new LinkedHashMap<>(modules.capture(input, options));
        config.put("limits", Map.of("maxComparisonRows", maxRows, "maxModuleRecords", maxRecords, "detailLimit", detailLimit, "maxPredictionCells", maxPredictionCells));
        String json = write(config);
        String key = RecommendationCacheKey.build(Map.of("snapshotId", snapshotId, "config", config), input.sourceFingerprint(), Map.of("report", "common-v1"));
        Submission submitted = snapshots.locked(input.fileId(), () -> {
            var keys = new GeneratedKeyHolder();
            try {
                jdbc.update(connection -> {
                    var statement = connection.prepareStatement("INSERT INTO kpi_common_reports (requestKey,snapshotId,fileId,configJson,status,createdAt,updatedAt) VALUES (?,?,?,?,'QUEUED',NOW(),NOW())", Statement.RETURN_GENERATED_KEYS);
                    statement.setString(1, key); statement.setLong(2, snapshotId); statement.setLong(3, input.fileId()); statement.setString(4, json); return statement;
                }, keys);
                return new Submission(Objects.requireNonNull(keys.getKey()).longValue(), true);
            } catch (DuplicateKeyException ex) { return new Submission(jdbc.queryForObject("SELECT id FROM kpi_common_reports WHERE requestKey=?", Long.class, key), false); }
        });
        if (submitted.created()) dispatch(submitted.id());
        return get(submitted.id());
    }
    private void dispatch(long id) {
        try { self.getObject().process(id); }
        catch (RejectedExecutionException ex) { fail(id, "Report workers and queue are full. Retry this report."); throw new JobCapacityException(id, "Report queue is full. Retry shortly.", ex); }
        catch (RuntimeException ex) { fail(id, "Report could not be scheduled."); throw ex; }
    }
    public Map<String, Object> retry(long id) {
        if (jdbc.update("UPDATE kpi_common_reports SET status='QUEUED',message=NULL,updatedAt=NOW() WHERE id=? AND status='FAILED'", id) == 0) throw new IllegalArgumentException("Only a failed report can be retried.");
        dispatch(id); return get(id);
    }
    @Async("commonReportExecutor")
    public void process(long id) {
        if (jdbc.update("UPDATE kpi_common_reports SET status='BUILDING',updatedAt=NOW() WHERE id=? AND status='QUEUED'", id) == 0) return;
        Path directory = null;
        try {
            var row = row(id);
            var input = analysis.reportInput(((Number) row.get("snapshotId")).longValue());
            var config = read(row.get("configJson").toString());
            var limits = CommonReportRenderer.map(config.get("limits"));
            var source = cache.readSnapshotVersion(input.fileId(), input.sourceName());
            if (!source.fingerprint().equals(input.sourceFingerprint())) throw new IllegalStateException("Report source fingerprint changed. Create a new analysis snapshot.");
            var results = modules.run(input, source, config, number(limits, "maxModuleRecords"), number(limits, "detailLimit"), number(limits, "maxPredictionCells"));
            Files.createDirectories(root);
            directory = Files.createDirectory(root.resolve("report-" + id + "-" + UUID.randomUUID()));
            Map<String, Object> summary = renderer.render(directory, input, results, consumer -> {
                for (int page = 1; ; page++) {
                    var batch = analysis.comparisons(input.snapshotId(), page, 100);
                    for (Object item : (List<?>) batch.get("items")) consumer.accept(CommonReportRenderer.map(item));
                    if (page * 100L >= ((Number) batch.get("totalGroups")).longValue()) break;
                }
            }, number(limits, "maxComparisonRows"));
            // Store the exact rule/configuration capture alongside the report, not a link to mutable live rules.
            Files.writeString(directory.resolve("configuration.json"), write(config));
            Map<String, String> checksums = new TreeMap<>();
            for (String file : List.of("report.pdf", "report.xlsx", "report.json", "configuration.json")) checksums.put(file, RecommendationCacheKey.fileDigest(directory.resolve(file)));
            String artifact = directory.getFileName().toString();
            snapshots.locked(input.fileId(), () -> {
                if (jdbc.update("UPDATE kpi_common_reports SET status=?,summaryJson=?,artifactName=?,checksumsJson=?,message=NULL,updatedAt=NOW() WHERE id=? AND status='BUILDING'",
                        "COMPLETE".equals(summary.get("coverage")) ? "READY" : "PARTIAL", write(summary), artifact, write(checksums), id) != 1) throw new IllegalStateException("Report no longer owns this build.");
                return null;
            });
            directory = null; // Publication owns all four artifacts now.
        } catch (Exception ex) { fail(id, ex.getMessage() == null ? "Report generation failed." : ex.getMessage()); }
        finally { if (directory != null) deleteUnpublished(directory); }
    }
    private static int number(Map<String, Object> map, String key) { return ((Number) map.get(key)).intValue(); }
    private void deleteUnpublished(Path directory) {
        Path target = directory.toAbsolutePath().normalize();
        if (!root.equals(target.getParent()) || !target.getFileName().toString().matches("report-[0-9]+-[a-f0-9-]{36}")) return;
        try (var paths = Files.walk(target)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        catch (IOException ignored) { /* Unpublished directories cannot be downloaded. */ }
    }
    private void fail(long id, String message) { jdbc.update("UPDATE kpi_common_reports SET status='FAILED',message=?,updatedAt=NOW() WHERE id=? AND status IN ('QUEUED','BUILDING')", message.substring(0, Math.min(message.length(), 2000)), id); }
    private Map<String, Object> row(long id) {
        var rows = jdbc.queryForList("SELECT * FROM kpi_common_reports WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Report was not found."); return rows.get(0);
    }
    public long fileId(long id) { return ((Number) row(id).get("fileId")).longValue(); }
    public java.time.Instant publishedAt(long id) {
        var epoch = jdbc.queryForObject("SELECT UNIX_TIMESTAMP(updatedAt) FROM kpi_common_reports WHERE id=? AND status IN ('READY','PARTIAL')", java.math.BigDecimal.class, id);
        if (epoch == null) throw new IllegalStateException("Report publication time is unavailable.");
        return java.time.Instant.ofEpochSecond(epoch.longValue());
    }
    public Map<String, Object> get(long id) { return view(row(id)); }
    public List<Map<String, Object>> list(long fileId, int limit) {
        return jdbc.queryForList("SELECT * FROM kpi_common_reports WHERE fileId=? ORDER BY id DESC LIMIT ?", fileId, Math.max(1, Math.min(limit, 100))).stream().map(this::view).toList();
    }
    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> value = new LinkedHashMap<>(row);
        value.remove("requestKey"); value.remove("configJson"); value.remove("artifactName"); value.remove("checksumsJson");
        Object summary = value.remove("summaryJson"); value.put("summary", summary == null ? null : read(summary.toString()));
        value.put("formats", List.of("READY", "PARTIAL").contains(value.get("status")) ? List.of("pdf", "xlsx", "json", "configuration") : List.of()); return value;
    }
    public Path download(long id, String format) throws IOException {
        String name = switch (format) { case "pdf", "xlsx", "json" -> "report." + format; case "configuration" -> "configuration.json"; default -> throw new IllegalArgumentException("Choose pdf, xlsx, json or configuration."); };
        var row = row(id);
        if (!List.of("READY", "PARTIAL").contains(row.get("status"))) throw new IllegalArgumentException("Report artifacts are not ready.");
        String artifact = String.valueOf(row.get("artifactName"));
        if (!artifact.matches("report-" + id + "-[a-f0-9-]{36}")) throw new IOException("Invalid report artifact reference.");
        Path file = root.resolve(artifact).resolve(name).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().startsWith(root.toRealPath())) throw new IOException("Report artifact is unavailable.");
        Object expected = read(row.get("checksumsJson").toString()).get(name);
        if (!Objects.equals(expected, RecommendationCacheKey.fileDigest(file))) throw new IOException("Report artifact integrity check failed.");
        return file;
    }
    private static String write(Object value) { try { return CommonReportModules.JSON.writeValueAsString(value); } catch (Exception ex) { throw new IllegalArgumentException("Invalid report configuration.", ex); } }
    private static Map<String, Object> read(String value) { try { return CommonReportModules.JSON.readValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() { }); } catch (Exception ex) { throw new IllegalStateException("Invalid stored report configuration.", ex); } }
}

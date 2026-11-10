package com.network.monitoring.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.monitoring.entity.UploadHistory;
import com.network.monitoring.repository.UploadHistoryRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

@Service
public class KpiIngestionService {
    private static final Logger LOG = LoggerFactory.getLogger(KpiIngestionService.class);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final JdbcTemplate jdbc;
    private final KpiSnapshotStore snapshots;
    private final KpiCacheService cache;
    private final KpiDynamicDataService dynamic;
    private final UploadService uploads;
    private final UploadHistoryRepository history;
    private final ObjectProvider<KpiIngestionService> self;
    private final Path sources;
    private final DailyAnalysisService analysis;

    @Value("${app.jobs.recover-on-startup:true}")
    private boolean recoverOnStartup = true;

    public KpiIngestionService(JdbcTemplate jdbc, KpiSnapshotStore snapshots, KpiCacheService cache,
                               KpiDynamicDataService dynamic, UploadService uploads, UploadHistoryRepository history,
                               ObjectProvider<KpiIngestionService> self,
                               @Value("${app.upload-base-path:uploads}") String base,
                               DailyAnalysisService analysis) throws IOException {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
        this.cache = cache;
        this.dynamic = dynamic;
        this.uploads = uploads;
        this.history = history;
        this.self = self;
        this.analysis = analysis;
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (root.getFileName().toString().equals("backend-java")) root = root.getParent();
        this.sources = root.resolve(base).resolve("ingestion-sources").normalize();
        Files.createDirectories(sources);
    }

    @PostConstruct
    public void recoverInterruptedRuns() {
        if (recoverOnStartup) jdbc.update("""
                UPDATE kpi_ingestion_runs SET status='FAILED',message=?,resultJson=?,updatedAt=NOW()
                WHERE status IN ('QUEUED','IMPORTING')
                """, "Backend restarted before import completed. Database changes were not published; retry is available.",
                KpiObservationMerge.json(Map.of("success", false, "retrySafe", true, "code", "BACKEND_RESTARTED")));
    }

    public Map<String, Object> start(MultipartFile file, long fileId, String sourceName,
                                     List<String> identityFields, Map<String, String> mapping) throws IOException {
        return start(file, fileId, sourceName, identityFields, mapping, null, null);
    }

    private Map<String, Object> start(MultipartFile file, long fileId, String sourceName,
                                     List<String> identityFields, Map<String, String> mapping,
                                     java.time.LocalDate expectedDate, String expectedChecksum) throws IOException {
        List<String> fields = KpiObservationMerge.validateFields(identityFields);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Daily KPI file is empty.");
        UploadHistory target = target(fileId);
        String name = file.getOriginalFilename() == null ? "daily.csv" : file.getOriginalFilename();
        if (name.length() > 512) throw new IllegalArgumentException("File name is too long.");
        String suffix = name.substring(Math.max(0, name.lastIndexOf('.'))).toLowerCase(java.util.Locale.ROOT);
        if (!List.of(".csv", ".xlsx", ".xls").contains(suffix)) throw new IllegalArgumentException("Use a CSV or Excel KPI file.");
        String source = sourceName == null || sourceName.isBlank() ? "common" : sourceName.trim();
        if (source.length() > 128) throw new IllegalArgumentException("Source name is too long.");
        Path path = Files.createTempFile(sources, "source-", suffix);
        boolean recorded = false;
        try {
            try (var stream = file.getInputStream()) {
                Files.copy(stream, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            String checksum = RecommendationCacheKey.fileDigest(path);
            if (expectedChecksum != null && !checksum.equals(expectedChecksum)) throw new IllegalArgumentException("Daily file changed during staging. No import was submitted.");
            Map<String, Object> config = new LinkedHashMap<>(Map.of("identityFields", fields, "columnMapping", mapping == null ? Map.of() : mapping));
            if (expectedDate != null) config.put("expectedDataDate", expectedDate.toString());
            String key = RecommendationCacheKey.build(config, checksum,
                    Map.of("scope", fileId + ":" + target.getCompanyId(), "ingestion", "strict-append-v1"));
            var holder = new GeneratedKeyHolder();
            try {
                jdbc.update((PreparedStatementCreator) connection -> {
                    var statement = connection.prepareStatement("""
                            INSERT INTO kpi_ingestion_runs
                              (importKey,fileId,companyId,sourceName,fileName,sourcePath,checksum,configJson,status,createdAt,updatedAt)
                            VALUES (?,?,?,?,?,?,?,?,'QUEUED',NOW(),NOW())
                            """, Statement.RETURN_GENERATED_KEYS);
                    statement.setString(1, key);
                    statement.setLong(2, fileId);
                    statement.setObject(3, target.getCompanyId());
                    statement.setString(4, source);
                    statement.setString(5, name);
                    statement.setString(6, path.toString());
                    statement.setString(7, checksum);
                    statement.setString(8, KpiObservationMerge.json(config));
                    return statement;
                }, holder);
            } catch (DuplicateKeyException ex) {
                Long id = jdbc.queryForObject("SELECT id FROM kpi_ingestion_runs WHERE importKey=?", Long.class, key);
                return Map.of("success", true, "duplicateFile", true, "data", get(id));
            }
            recorded = true;
            long id = Objects.requireNonNull(holder.getKey(), "Ingestion ID was not generated").longValue();
            dispatch(id);
            return Map.of("success", true, "duplicateFile", false, "data", get(id));
        } finally {
            if (!recorded) Files.deleteIfExists(path);
        }
    }

    public Map<String, Object> retry(long id) {
        Map<String, Object> run = run(id);
        sourcePath(run); // Never accept an arbitrary path from a caller or corrupted ledger entry.
        if (jdbc.update("UPDATE kpi_ingestion_runs SET status='QUEUED',resultJson=NULL,message=NULL,updatedAt=NOW() WHERE id=? AND status='FAILED'", id) == 0) {
            throw new IllegalArgumentException("Only a failed import can be retried.");
        }
        dispatch(id);
        return get(id);
    }

    private void dispatch(long id) {
        try {
            self.getObject().process(id);
        } catch (RejectedExecutionException ex) {
            fail(id, "Import queue is full. Retry this ingestion run shortly.", "JOB_CAPACITY");
            throw new JobCapacityException(id, "Import queue is full. Please retry shortly.", ex);
        } catch (RuntimeException ex) {
            fail(id, "Import could not be scheduled.", "SUBMISSION_FAILED");
            throw ex;
        }
    }

    @Async("uploadJobExecutor")
    public void process(long id) {
        if (jdbc.update("UPDATE kpi_ingestion_runs SET status='IMPORTING',attempts=attempts+1,updatedAt=NOW() WHERE id=? AND status='QUEUED'", id) == 0) return;
        try {
            Map<String, Object> run = run(id);
            Path source = sourcePath(run);
            if (!RecommendationCacheKey.fileDigest(source).equals(run.get("checksum"))) throw new IllegalArgumentException("Stored source checksum changed; upload the correct file as a new import.");
            Map<String, Object> config = JSON.readValue(run.get("configJson").toString(), new TypeReference<>() { });
            List<String> fields = JSON.convertValue(config.get("identityFields"), new TypeReference<>() { });
            Map<String, String> mapping = JSON.convertValue(config.get("columnMapping"), new TypeReference<>() { });
            var prepared = uploads.prepareDaily(source, mapping);
            java.time.LocalDate expectedDate = config.get("expectedDataDate") == null ? null : java.time.LocalDate.parse(config.get("expectedDataDate").toString());
            if (expectedDate != null) {
                var incoming = dynamic.getRecordsFromCachePayload(prepared.payload());
                if (incoming.isEmpty() || incoming.stream().anyMatch(record -> !expectedDate.toString().equals(String.valueOf(record.get("date"))))) throw new IllegalArgumentException("Daily file must contain observations only for " + expectedDate + ". Nothing was appended.");
            }
            if (!RecommendationCacheKey.fileDigest(source).equals(run.get("checksum"))) throw new IllegalArgumentException("Source changed while it was being parsed.");
            long fileId = ((Number) run.get("fileId")).longValue();
            snapshots.locked(fileId, () -> {
                if (!"IMPORTING".equals(jdbc.queryForObject("SELECT status FROM kpi_ingestion_runs WHERE id=? FOR UPDATE", String.class, id))) {
                    throw new IllegalStateException("Import no longer owns this run.");
                }
                UploadHistory target = target(fileId);
                if (!Objects.equals(target.getCompanyId(), run.get("companyId"))) throw new IllegalArgumentException("Dataset company changed after import submission.");
                String identity = KpiObservationMerge.json(fields);
                List<String> policies = jdbc.query("SELECT identityJson FROM kpi_dataset_snapshots WHERE fileId=?",
                        (rs, row) -> rs.getString(1), fileId);
                if (!policies.isEmpty() && policies.get(0) != null && !identity.equals(policies.get(0))) {
                    throw new IllegalArgumentException("This dataset already has another identityFields policy. Review its grain before changing it.");
                }
                Map<String, Object> existing = cache.readKpiCache(fileId);
                if (existing == null) {
                    List<Map<String, Object>> rows = dynamic.findRecordsByFileId(fileId);
                    Long legacy = jdbc.queryForObject("SELECT COUNT(*) FROM uploaddata WHERE fileId=?", Long.class, fileId);
                    if (rows.isEmpty() && legacy != null && legacy > 0) throw new IllegalStateException("Existing KPI snapshot is missing. Rebuild it before daily ingestion.");
                    existing = new LinkedHashMap<>(Map.of("records", rows));
                }
                var merge = KpiObservationMerge.merge(dynamic.getRecordsFromCachePayload(existing),
                        dynamic.getRecordsFromCachePayload(prepared.payload()), fields);
                List<Map<String, Object>> accepted = merge.acceptedIndexes().stream().map(prepared.rawRows()::get).toList();
                int legacy = uploads.appendDailyLegacy(fileId, target.getCompanyId(), accepted);
                dynamic.appendRecords(fileId, target.getCompanyId(), merge.added());
                jdbc.update("DELETE FROM site_prediction_results WHERE fileId=?", fileId);
                if (!merge.added().isEmpty() || !snapshots.managed(fileId)) {
                    cache.writeKpiCache(fileId, uploads.dailyPayload(existing, prepared.payload(), merge.all()));
                }
                jdbc.update("UPDATE kpi_dataset_snapshots SET identityJson=? WHERE fileId=?", identity, fileId);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("sourceRows", prepared.rawRows().size());
                result.put("addedRows", merge.added().size());
                result.put("duplicateRows", merge.duplicates());
                result.put("totalRows", merge.all().size());
                result.put("legacyRowsAdded", legacy);
                result.put("dataStartDate", merge.firstDate());
                result.put("dataEndDate", merge.lastDate());
                result.put("analysisStatus", "PENDING");
                result.put("datasetRevision", snapshots.revision(fileId));
                jdbc.update("UPDATE kpi_ingestion_runs SET status='COMPLETED',resultJson=?,message=?,updatedAt=NOW() WHERE id=?",
                        KpiObservationMerge.json(result), "KPI import committed. Daily analysis/reporting is a separate pipeline stage.", id);
                return null;
            });
            try { uploads.afterDailyCommit(fileId); }
            catch (RuntimeException ex) { LOG.warn("Import {} committed; derived-cache refresh needs retry", id, ex); }
            if (expectedDate == null) analysis.afterImport(fileId, id);
            else analysis.afterImport(fileId, id, expectedDate);
        } catch (Exception ex) {
            LOG.warn("Daily KPI import {} failed; data transaction was not published", id);
            fail(id, ex.getMessage() == null ? "Import failed." : ex.getMessage(), "IMPORT_FAILED");
        }
    }

    private UploadHistory target(long fileId) {
        UploadHistory target = history.findById(fileId).orElseThrow(() -> new IllegalArgumentException("Target KPI upload was not found."));
        if (target.getRemarks() == null || !target.getRemarks().startsWith("[kpi]")) {
            throw new IllegalArgumentException("Choose a KPI upload as the target dataset.");
        }
        return target;
    }

    public Map<String, Object> startPath(Path path, long fileId, NightlyProfile profile, java.time.LocalDate date, String checksum, long maxBytes) throws IOException {
        if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.size(path) > maxBytes) throw new IllegalArgumentException("Daily source is unavailable or exceeds its size limit.");
        MultipartFile file = new MultipartFile() {
            public String getName() { return "file"; }
            public String getOriginalFilename() { return path.getFileName().toString(); }
            public String getContentType() { return "application/octet-stream"; }
            public boolean isEmpty() { return getSize() == 0; }
            public long getSize() { try { return Files.size(path); } catch (IOException ex) { return 0; } }
            public byte[] getBytes() throws IOException { throw new IOException("Stream daily files instead of loading a byte array."); }
            public java.io.InputStream getInputStream() throws IOException {
                return new java.io.FilterInputStream(Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    long count;
                    public int read() throws IOException { int value = super.read(); if (value >= 0 && ++count > maxBytes) throw new IOException("Daily source exceeded its size limit."); return value; }
                    public int read(byte[] bytes, int offset, int length) throws IOException { int n = in.read(bytes, offset, length); if (n > 0 && (count += n) > maxBytes) throw new IOException("Daily source exceeded its size limit."); return n; }
                };
            }
            public void transferTo(java.io.File destination) throws IOException { try (var stream = getInputStream()) { Files.copy(stream, destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); } }
        };
        return start(file, fileId, "nightly", profile.identityFields(), profile.columnMapping(), date, checksum);
    }

    private Path sourcePath(Map<String, Object> run) {
        Path path = Path.of(run.get("sourcePath").toString()).toAbsolutePath().normalize();
        if (!path.getParent().equals(sources) || !Files.isRegularFile(path)) throw new IllegalArgumentException("Stored ingestion source is unavailable.");
        return path;
    }

    private void fail(long id, String message, String code) {
        jdbc.update("UPDATE kpi_ingestion_runs SET status='FAILED',message=?,resultJson=?,updatedAt=NOW() WHERE id=? AND status IN ('QUEUED','IMPORTING')",
                message, KpiObservationMerge.json(Map.of("success", false, "retrySafe", true, "code", code)), id);
    }

    private Map<String, Object> run(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM kpi_ingestion_runs WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Ingestion run was not found.");
        return rows.get(0);
    }

    public Map<String, Object> get(long id) {
        return publicView(run(id));
    }

    private Map<String, Object> publicView(Map<String, Object> record) {
        Map<String, Object> run = new LinkedHashMap<>(record);
        run.remove("sourcePath");
        run.remove("configJson");
        Object result = run.remove("resultJson");
        try { run.put("result", result == null ? null : JSON.readValue(result.toString(), Map.class)); }
        catch (IOException ex) { throw new IllegalStateException("Stored ingestion result is invalid.", ex); }
        return run;
    }

    public List<Map<String, Object>> list(int limit) {
        return jdbc.queryForList("""
                SELECT id,fileId,companyId,sourceName,fileName,checksum,status,attempts,message,resultJson,createdAt,updatedAt
                FROM kpi_ingestion_runs ORDER BY id DESC LIMIT ?
                """, Math.max(1, Math.min(limit, 100))).stream().map(this::publicView).toList();
    }
}

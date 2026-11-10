package com.network.monitoring.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.sql.Statement;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;

@Service
public class DailyAnalysisService {
    private static final String VERSION = "daily-analysis-v1";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final JdbcTemplate jdbc;
    private final KpiSnapshotStore snapshots;
    private final KpiCacheService cache;
    private final ObjectProvider<DailyAnalysisService> self;
    @Value("${app.jobs.recover-on-startup:true}") private boolean recoverOnStartup = true;
    @Value("${app.jobs.analysis.max-group-days:25000}") private int maxGroupDays = 25000;

    public DailyAnalysisService(JdbcTemplate jdbc, KpiSnapshotStore snapshots, KpiCacheService cache,
                                 ObjectProvider<DailyAnalysisService> self) {
        this.jdbc = jdbc; this.snapshots = snapshots; this.cache = cache; this.self = self;
    }

    @PostConstruct public void initialize() {
        try {
            jdbc.queryForList("SELECT fileId FROM kpi_analysis_profiles LIMIT 0");
            jdbc.queryForList("SELECT id FROM kpi_analysis_snapshots LIMIT 0");
            jdbc.queryForList("SELECT snapshotId FROM kpi_daily_groups LIMIT 0");
        } catch (org.springframework.dao.DataAccessException ex) {
            throw new IllegalStateException("Apply backend-java/db/migrations/003_daily_analysis_snapshots.sql before starting.", ex);
        }
        if (maxGroupDays < 1) throw new IllegalArgumentException("Analysis group-day limit must be positive.");
        if (recoverOnStartup) {
            jdbc.update("UPDATE kpi_analysis_snapshots SET status='FAILED',message='Backend restarted before analysis completed. Retry this snapshot.',updatedAt=NOW() WHERE status IN ('QUEUED','BUILDING')");
            jdbc.update("""
                    UPDATE kpi_ingestion_runs r JOIN kpi_analysis_snapshots a
                      ON JSON_UNQUOTE(JSON_EXTRACT(r.resultJson,'$.analysisSnapshotId'))=CAST(a.id AS CHAR)
                    SET r.resultJson=JSON_SET(r.resultJson,'$.analysisStatus',a.status)
                    WHERE r.status='COMPLETED'
                    """);
        }
    }

    public Map<String, Object> saveProfile(long fileId, DailyKpiCalculator.Profile raw) {
        var profile = raw.validated();
        String config = write(profile);
        String hash = RecommendationCacheKey.build(Map.of("profile", readMap(config)), VERSION, Map.of());
        snapshots.locked(fileId, () -> {
            jdbc.update("""
                    INSERT INTO kpi_analysis_profiles (fileId,profileHash,configJson,updatedAt) VALUES (?,?,?,NOW())
                    ON DUPLICATE KEY UPDATE profileHash=VALUES(profileHash),configJson=VALUES(configJson),updatedAt=NOW()
                    """, fileId, hash, config);
            return null;
        });
        return Map.of("fileId", fileId, "profileHash", hash, "profile", profile);
    }

    public Map<String, Object> profile(long fileId) {
        var rows = jdbc.queryForList("SELECT profileHash,configJson FROM kpi_analysis_profiles WHERE fileId=?", fileId);
        if (rows.isEmpty()) return Map.of("fileId", fileId, "status", "CONFIGURATION_REQUIRED");
        return Map.of("fileId", fileId, "profileHash", rows.get(0).get("profileHash"),
                "profile", readMap(rows.get(0).get("configJson").toString()));
    }

    private record Submission(long id, boolean created) { }

    public Map<String, Object> request(long fileId, LocalDate requestedDate) {
        return request(fileId, requestedDate, null);
    }

    private Map<String, Object> request(long fileId, LocalDate requestedDate, Long ingestionId) {
        Submission submitted = snapshots.locked(fileId, () -> {
            var profiles = jdbc.queryForList("SELECT profileHash,configJson FROM kpi_analysis_profiles WHERE fileId=?", fileId);
            if (profiles.isEmpty()) throw new IllegalArgumentException("Configure KPI aggregation, units and directions before building daily analysis.");
            String fingerprint = cache.fingerprintKpiCache(fileId).orElseThrow(() -> new IllegalArgumentException("A valid KPI source snapshot is required."));
            String source = cache.getKpiCachePath(fileId).getFileName().toString();
            String version = snapshots.activeName(fileId) == null ? "legacy:" + fingerprint : snapshots.revision(fileId);
            String profileHash = profiles.get(0).get("profileHash").toString();
            String requestKey = RecommendationCacheKey.build(Map.of("fileId", fileId, "profile", profileHash,
                    "date", requestedDate == null ? "LATEST_DATA_DATE" : requestedDate.toString()), fingerprint, Map.of("engine", VERSION));
            var keys = new GeneratedKeyHolder();
            try {
                jdbc.update((PreparedStatementCreator) connection -> {
                    var statement = connection.prepareStatement("""
                            INSERT INTO kpi_analysis_snapshots
                              (requestKey,fileId,sourceName,sourceFingerprint,datasetVersion,profileHash,configJson,requestedDate,status,createdAt,updatedAt)
                            VALUES (?,?,?,?,?,?,?,?,'QUEUED',NOW(),NOW())
                            """, Statement.RETURN_GENERATED_KEYS);
                    statement.setString(1, requestKey); statement.setLong(2, fileId); statement.setString(3, source);
                    statement.setString(4, fingerprint); statement.setString(5, version); statement.setString(6, profileHash);
                    statement.setString(7, profiles.get(0).get("configJson").toString());
                    statement.setObject(8, requestedDate);
                    return statement;
                }, keys);
                return new Submission(Objects.requireNonNull(keys.getKey()).longValue(), true);
            } catch (DuplicateKeyException ex) {
                return new Submission(jdbc.queryForObject("SELECT id FROM kpi_analysis_snapshots WHERE requestKey=?", Long.class, requestKey), false);
            }
        });
        if (ingestionId != null) attach(ingestionId, submitted.id());
        if (submitted.created()) dispatch(submitted.id());
        return get(submitted.id());
    }

    public void afterImport(long fileId, long ingestionId) {
        afterImport(fileId, ingestionId, null);
    }
    public void afterImport(long fileId, long ingestionId, LocalDate date) {
        try {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM kpi_analysis_profiles WHERE fileId=?", Integer.class, fileId) == 0) {
                jdbc.update("UPDATE kpi_ingestion_runs SET resultJson=JSON_SET(resultJson,'$.analysisStatus','CONFIGURATION_REQUIRED') WHERE id=? AND status='COMPLETED'", ingestionId);
            } else request(fileId, date, ingestionId);
        } catch (RuntimeException ex) {
            // Import is already committed; analysis failure must never relabel it as a failed append.
            jdbc.update("UPDATE kpi_ingestion_runs SET resultJson=JSON_SET(resultJson,'$.analysisStatus','FAILED'),message=? WHERE id=? AND status='COMPLETED'",
                    "Import committed; daily analysis could not be scheduled. Review analysis history/configuration.", ingestionId);
        }
    }

    public Map<String, Object> requestImported(long ingestionId, LocalDate date, String expectedProfileHash) {
        var rows = jdbc.queryForList("SELECT fileId,resultJson,status FROM kpi_ingestion_runs WHERE id=?", ingestionId);
        if (rows.isEmpty() || !"COMPLETED".equals(rows.get(0).get("status"))) throw new IllegalArgumentException("A committed import is required.");
        var run = rows.get(0); long fileId = ((Number) run.get("fileId")).longValue();
        return snapshots.locked(fileId, () -> {
            var result = readMap(run.get("resultJson").toString());
            if (!Objects.equals(result.get("datasetRevision"), snapshots.revision(fileId))) throw new IllegalStateException("Dataset changed before missing analysis could be attached. Review this run manually.");
            if (!Objects.equals(expectedProfileHash, currentProfileHash(fileId))) throw new IllegalStateException("Comparison settings changed before analysis submission.");
            return request(fileId, date, ingestionId);
        });
    }

    private void attach(long ingestionId, long snapshotId) {
        jdbc.update("""
                UPDATE kpi_ingestion_runs r JOIN kpi_analysis_snapshots a ON a.id=?
                SET r.resultJson=JSON_SET(r.resultJson,'$.analysisStatus',a.status,'$.analysisSnapshotId',a.id)
                WHERE r.id=? AND r.status='COMPLETED'
                """, snapshotId, ingestionId);
    }

    private void refreshImportStatus(long id) {
        jdbc.update("""
                UPDATE kpi_ingestion_runs r JOIN kpi_analysis_snapshots a ON a.id=?
                SET r.resultJson=JSON_SET(r.resultJson,'$.analysisStatus',a.status)
                WHERE r.status='COMPLETED' AND JSON_UNQUOTE(JSON_EXTRACT(r.resultJson,'$.analysisSnapshotId'))=CAST(a.id AS CHAR)
                """, id);
    }

    private void dispatch(long id) {
        try { self.getObject().process(id); }
        catch (RejectedExecutionException ex) {
            fail(id, "Daily analysis workers and queue are full. Retry this snapshot.");
            throw new JobCapacityException(id, "Daily analysis queue is full. Please retry shortly.", ex);
        } catch (RuntimeException ex) {
            fail(id, "Daily analysis could not be scheduled."); throw ex;
        }
    }

    public Map<String, Object> retry(long id) {
        if (jdbc.update("UPDATE kpi_analysis_snapshots SET status='QUEUED',message=NULL,updatedAt=NOW() WHERE id=? AND status='FAILED'", id) == 0) {
            throw new IllegalArgumentException("Only a failed analysis can be retried.");
        }
        refreshImportStatus(id);
        dispatch(id);
        return get(id);
    }

    @Async("dailyAnalysisExecutor")
    public void process(long id) {
        if (jdbc.update("UPDATE kpi_analysis_snapshots SET status='BUILDING',updatedAt=NOW() WHERE id=? AND status='QUEUED'", id) == 0) return;
        try {
            Map<String, Object> snapshot = row(id);
            long fileId = ((Number) snapshot.get("fileId")).longValue();
            var source = cache.readSnapshotVersion(fileId, snapshot.get("sourceName").toString());
            if (!source.fingerprint().equals(snapshot.get("sourceFingerprint"))) throw new IllegalStateException("Analysis input changed. Request a new snapshot for the current data.");
            var profile = readProfile(snapshot.get("configJson").toString());
            Object rawRecords = source.payload().get("records");
            if (!(rawRecords instanceof List<?> rows) || rows.stream().anyMatch(value -> !(value instanceof Map<?, ?>))) {
                throw new IllegalArgumentException("Analysis snapshot must contain observation objects.");
            }
            // Jackson already parsed string-keyed maps; avoid a second copy of the whole history.
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> records = (List<Map<String, Object>>) (List<?>) rows;
            Object requested = snapshot.get("requestedDate");
            var build = DailyKpiCalculator.build(records, profile, requested == null ? null : LocalDate.parse(requested.toString()), maxGroupDays);
            snapshots.locked(fileId, () -> {
                if (!"BUILDING".equals(jdbc.queryForObject("SELECT status FROM kpi_analysis_snapshots WHERE id=? FOR UPDATE", String.class, id))) {
                    throw new IllegalStateException("Analysis no longer owns this snapshot.");
                }
                jdbc.update("DELETE FROM kpi_daily_groups WHERE snapshotId=?", id);
                List<Object[]> batch = new ArrayList<>();
                for (var group : build.days()) {
                    batch.add(new Object[]{id, java.sql.Date.valueOf(group.date()), group.groupHash(),
                            write(group.dimensions()), write(group.metrics()), group.records()});
                    if (batch.size() == 500) { insert(batch); batch.clear(); }
                }
                if (!batch.isEmpty()) insert(batch);
                long currentGroups = build.days().stream().filter(group -> group.date().equals(build.date())).count();
                long validCurrentMetrics = build.days().stream().filter(group -> group.date().equals(build.date()))
                        .flatMap(group -> group.metrics().values().stream()).filter(value -> "OK".equals(value.status())).count();
                Map<String, Object> summary = new LinkedHashMap<>(Map.of("windowStart", build.date().minusDays(7).toString(),
                        "windowEnd", build.date().toString(), "groups", build.groups(), "groupDays", build.days().size(),
                        "inputRecords", build.inputRecords(), "metrics", profile.metrics().size(), "engineVersion", VERSION));
                summary.put("currentGroups", currentGroups);
                summary.put("validCurrentMetricValues", validCurrentMetrics);
                summary.put("dataStatus", build.days().isEmpty() ? "NO_DATA_IN_WINDOW" : currentGroups == 0 ? "MISSING_CURRENT_DAY"
                        : validCurrentMetrics == 0 ? "NO_VALID_CURRENT_METRICS" : "AVAILABLE");
                summary.put("availableDates", build.days().stream().map(group -> group.date().toString()).distinct().toList());
                jdbc.update("UPDATE kpi_analysis_snapshots SET status='COMPLETED',dataDate=?,summaryJson=?,message=NULL,updatedAt=NOW() WHERE id=?",
                        java.sql.Date.valueOf(build.date()), write(summary), id);
                return null;
            });
            refreshImportStatus(id);
        } catch (Exception ex) {
            fail(id, ex.getMessage() == null ? "Daily analysis failed." : ex.getMessage());
        }
    }

    private void insert(List<Object[]> rows) {
        jdbc.batchUpdate("INSERT INTO kpi_daily_groups (snapshotId,dataDate,groupHash,dimensionsJson,metricsJson,recordCount) VALUES (?,?,?,?,?,?)", rows);
    }

    private void fail(long id, String message) {
        jdbc.update("UPDATE kpi_analysis_snapshots SET status='FAILED',message=?,updatedAt=NOW() WHERE id=? AND status IN ('QUEUED','BUILDING')", message, id);
        refreshImportStatus(id);
    }

    private Map<String, Object> row(long id) {
        var rows = jdbc.queryForList("SELECT * FROM kpi_analysis_snapshots WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Analysis snapshot was not found.");
        return rows.get(0);
    }

    public long fileId(long id) { return ((Number) row(id).get("fileId")).longValue(); }

    public record ReportInput(long snapshotId, long fileId, String sourceName, String sourceFingerprint,
                              String datasetVersion, String profileHash, LocalDate date,
                              DailyKpiCalculator.Profile profile, Map<String, Object> summary) { }

    public ReportInput reportInput(long id) {
        var value = row(id);
        if (!"COMPLETED".equals(value.get("status"))) throw new IllegalArgumentException("A completed daily analysis snapshot is required.");
        return new ReportInput(id, ((Number) value.get("fileId")).longValue(), value.get("sourceName").toString(),
                value.get("sourceFingerprint").toString(), value.get("datasetVersion").toString(), value.get("profileHash").toString(),
                LocalDate.parse(value.get("dataDate").toString()), readProfile(value.get("configJson").toString()),
                readMap(value.get("summaryJson").toString()));
    }

    public Map<String, Object> get(long id) {
        Map<String, Object> row = row(id);
        long fileId = ((Number) row.get("fileId")).longValue();
        return publicView(row, cache.getKpiCachePath(fileId).getFileName().toString(), currentProfileHash(fileId));
    }

    private String currentProfileHash(long fileId) {
        List<String> hashes = jdbc.query("SELECT profileHash FROM kpi_analysis_profiles WHERE fileId=?", (rs, number) -> rs.getString(1), fileId);
        return hashes.isEmpty() ? null : hashes.get(0);
    }

    private Map<String, Object> publicView(Map<String, Object> row, String current, String profileHash) {
        Map<String, Object> snapshot = new LinkedHashMap<>(row);
        snapshot.remove("requestKey");
        snapshot.remove("configJson");
        Object summary = snapshot.remove("summaryJson");
        snapshot.put("summary", summary == null ? null : readMap(summary.toString()));
        String source = snapshot.remove("sourceName").toString();
        snapshot.put("freshness", source.endsWith("-" + snapshot.get("datasetVersion") + ".json")
                ? (source.equals(current) ? "CURRENT" : "HISTORICAL") : "UNVERIFIED_LEGACY");
        snapshot.put("profileCurrent", Objects.equals(snapshot.get("profileHash"), profileHash));
        return snapshot;
    }

    public List<Map<String, Object>> list(long fileId, int limit) {
        String current = cache.getKpiCachePath(fileId).getFileName().toString();
        String profileHash = currentProfileHash(fileId);
        return jdbc.queryForList("""
                SELECT id,fileId,sourceName,sourceFingerprint,datasetVersion,profileHash,requestedDate,dataDate,status,summaryJson,message,createdAt,updatedAt
                FROM kpi_analysis_snapshots WHERE fileId=? ORDER BY id DESC LIMIT ?
                """, fileId, Math.max(1, Math.min(limit, 100))).stream().map(record -> publicView(record, current, profileHash)).toList();
    }

    public Map<String, Object> comparisons(long id, int page, int limit) {
        Map<String, Object> snapshot = row(id);
        if (!"COMPLETED".equals(snapshot.get("status"))) throw new IllegalArgumentException("Analysis is not completed yet.");
        if (page < 1 || page > 1000000) throw new IllegalArgumentException("Invalid comparison page.");
        int size = Math.max(1, Math.min(limit, 100));
        var groups = jdbc.queryForList("SELECT groupHash,MAX(dimensionsJson) dimensionsJson FROM kpi_daily_groups WHERE snapshotId=? GROUP BY groupHash ORDER BY groupHash LIMIT ? OFFSET ?",
                id, size, (page - 1) * size);
        var profile = readProfile(snapshot.get("configJson").toString());
        LocalDate date = LocalDate.parse(snapshot.get("dataDate").toString());
        List<Map<String, Object>> items = new ArrayList<>();
        if (!groups.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(groups.size(), "?"));
            List<Object> args = new ArrayList<>(); args.add(id); groups.forEach(group -> args.add(group.get("groupHash")));
            var rows = jdbc.queryForList("SELECT dataDate,groupHash,metricsJson FROM kpi_daily_groups WHERE snapshotId=? AND groupHash IN (" + placeholders + ") ORDER BY dataDate", args.toArray());
            Map<String, Map<LocalDate, Map<String, DailyKpiCalculator.Value>>> days = new HashMap<>();
            for (var value : rows) {
                Map<String, DailyKpiCalculator.Value> metrics = readValues(value.get("metricsJson").toString());
                days.computeIfAbsent(value.get("groupHash").toString(), key -> new HashMap<>()).put(LocalDate.parse(value.get("dataDate").toString()), metrics);
            }
            for (var group : groups) {
                Map<String, String> dimensions = JSON.convertValue(readMap(group.get("dimensionsJson").toString()), new TypeReference<>() { });
                for (var metric : profile.metrics()) {
                    Map<LocalDate, DailyKpiCalculator.Value> values = new HashMap<>();
                    days.get(group.get("groupHash").toString()).forEach((day, metrics) -> values.put(day, metrics.get(metric.key())));
                    items.add(DailyKpiCalculator.compare(dimensions, metric, values, date, profile));
                }
            }
        }
        Long total = jdbc.queryForObject("SELECT COUNT(DISTINCT groupHash) FROM kpi_daily_groups WHERE snapshotId=?", Long.class, id);
        return Map.of("snapshot", get(id), "items", items, "page", page, "groupsPerPage", size, "totalGroups", total == null ? 0 : total);
    }

    private static String write(Object value) { try { return JSON.writeValueAsString(value); } catch (Exception ex) { throw new IllegalArgumentException("Invalid analysis configuration/result.", ex); } }
    private static Map<String, Object> readMap(String value) { try { return JSON.readValue(value, new TypeReference<>() { }); } catch (Exception ex) { throw new IllegalStateException("Stored analysis JSON is invalid.", ex); } }
    private static DailyKpiCalculator.Profile readProfile(String value) { try { return JSON.readValue(value, DailyKpiCalculator.Profile.class).validated(); } catch (Exception ex) { throw new IllegalArgumentException("Invalid analysis profile.", ex); } }
    private static Map<String, DailyKpiCalculator.Value> readValues(String value) { try { return JSON.readValue(value, new TypeReference<>() { }); } catch (Exception ex) { throw new IllegalStateException("Invalid daily values.", ex); } }
}

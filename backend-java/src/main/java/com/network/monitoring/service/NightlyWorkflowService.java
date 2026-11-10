package com.network.monitoring.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.sql.Statement;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-instance coordinator. Child jobs own their queues; no worker waits for another worker. */
@Service
public class NightlyWorkflowService {
    private static final Logger LOG = LoggerFactory.getLogger(NightlyWorkflowService.class);
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final String SELECT = "SELECT r.*,DATE_FORMAT(dataDate,'%Y-%m-%d') dayText,DATE_FORMAT(cutoffAt,'%Y-%m-%dT%H:%i:%s') cutoffUtc,DATE_FORMAT(deadlineAt,'%Y-%m-%dT%H:%i:%s') deadlineUtc,DATE_FORMAT(observedAt,'%Y-%m-%dT%H:%i:%s') observedUtc,DATE_FORMAT(createdAt,'%Y-%m-%dT%H:%i:%s') createdUtc,DATE_FORMAT(updatedAt,'%Y-%m-%dT%H:%i:%s') updatedUtc,DATE_FORMAT(finishedAt,'%Y-%m-%dT%H:%i:%s') finishedUtc,DATE_FORMAT(deadlineMissedAt,'%Y-%m-%dT%H:%i:%s') missedUtc FROM kpi_nightly_runs r";
    private final JdbcTemplate jdbc; private final KpiSnapshotStore transactions;
    private final KpiIngestionService ingestion; private final DailyAnalysisService analysis;
    private final CommonReportService reports; private final ReportMailOutboxService mail;
    private final ObjectProvider<NightlyWorkflowService> self; private final Clock clock;
    private final AtomicBoolean pending = new AtomicBoolean();
    @Value("${app.nightly.enabled:false}") private boolean schedulerEnabled;
    @Value("${app.nightly.stable-seconds:60}") private int stableSeconds = 60;
    @Value("${app.nightly.max-source-bytes:200000000}") private long maxSourceBytes = 200000000;
    @Value("${app.nightly.max-active-runs:20}") private int maxActive = 20;
    @Value("${app.nightly.max-attempts:3}") private int maxAttempts = 3;

    public NightlyWorkflowService(JdbcTemplate jdbc, KpiSnapshotStore transactions, KpiIngestionService ingestion,
                                  DailyAnalysisService analysis, CommonReportService reports, ReportMailOutboxService mail,
                                  ObjectProvider<NightlyWorkflowService> self, @Qualifier("nightlyClock") Clock clock) {
        this.jdbc = jdbc; this.transactions = transactions; this.ingestion = ingestion; this.analysis = analysis;
        this.reports = reports; this.mail = mail; this.self = self; this.clock = clock;
    }
    @PostConstruct public void initialize() {
        try { for (String table : List.of("kpi_nightly_profiles", "kpi_nightly_runs", "kpi_nightly_events")) jdbc.queryForList("SELECT * FROM " + table + " LIMIT 0"); }
        catch (org.springframework.dao.DataAccessException ex) { throw new IllegalStateException("Apply backend-java/db/migrations/006_nightly_workflow.sql before starting.", ex); }
        if (stableSeconds < 1 || stableSeconds > 3600 || maxSourceBytes < 1 || maxSourceBytes > 200000000 || maxActive < 1 || maxActive > 100 || maxAttempts < 1 || maxAttempts > 10) throw new IllegalArgumentException("Invalid nightly workflow limits.");
        // Active coordinators retain their child IDs. Child services recover interrupted work independently.
    }
    public Map<String, Object> configure(long fileId, NightlyProfile raw, Long actor) {
        var profile = raw.validated();
        if (profile.enabled()) validatePrerequisites(fileId, profile);
        transactions.locked(fileId, () -> {
            if (profile.enabled() && jdbc.queryForObject("SELECT COUNT(*) FROM kpi_nightly_profiles WHERE enabled=TRUE AND fileId<>?", Integer.class, fileId) >= maxActive) throw new IllegalArgumentException("Enabled nightly dataset capacity is full.");
            jdbc.update("INSERT INTO kpi_nightly_profiles (fileId,enabled,configJson,updatedBy,updatedAt) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),configJson=VALUES(configJson),updatedBy=VALUES(updatedBy),updatedAt=VALUES(updatedAt)", fileId, profile.enabled(), write(profile), actor, now()); return null;
        }); return profile(fileId);
    }
    private Map<String, Object> validatePrerequisites(long fileId, NightlyProfile profile) {
        var daily = analysis.profile(fileId);
        if (daily.get("profileHash") == null) throw new IllegalArgumentException("Save daily comparison settings before enabling nightly automation.");
        Map<String, Object> email = Map.of();
        if (!profile.deliveryMode().equals("REPORT_ONLY")) {
            email = mail.profile(fileId); var configured = map(email.get("profile"));
            if (!Boolean.TRUE.equals(configured.get("enabled")) || !profile.deliveryMode().equals(configured.get("mode")) || email.get("profileHash") == null) throw new IllegalArgumentException("Enable a matching CAPTURE/SMTP report mail profile first.");
            if (profile.deliveryMode().equals("SMTP") && !Boolean.TRUE.equals(email.get("outboundEnabled"))) throw new IllegalArgumentException("Outbound SMTP is disabled.");
        }
        return Map.of("analysisProfileHash", daily.get("profileHash"), "mailProfileHash", email.getOrDefault("profileHash", "REPORT_ONLY"));
    }
    public Map<String, Object> profile(long fileId) {
        var rows = jdbc.queryForList("SELECT configJson FROM kpi_nightly_profiles WHERE fileId=?", fileId);
        Map<String, Object> result = new LinkedHashMap<>(Map.of("fileId", fileId, "schedulerEnabled", schedulerEnabled, "timezone", NightlyProfile.ZONE.toString(), "startTime", "01:00", "deadlineTime", "02:00", "stableSeconds", stableSeconds));
        result.put("status", rows.isEmpty() ? "CONFIGURATION_REQUIRED" : "CONFIGURED");
        if (!rows.isEmpty()) result.put("profile", read(rows.get(0).get("configJson").toString())); return result;
    }
    public Map<String, Object> request(long fileId, LocalDate date, boolean allowLate, Long actor) {
        long id = create(fileId, date, allowLate, actor); dispatch(); return get(id);
    }
    private long create(long fileId, LocalDate date, boolean allowLate, Long actor) {
        if (date == null || date.isAfter(LocalDate.now(clock.withZone(NightlyProfile.ZONE)).minusDays(1))) throw new IllegalArgumentException("Choose a completed data day (yesterday or earlier in IST).");
        return transactions.locked(fileId, () -> {
            var existing = jdbc.queryForList("SELECT id FROM kpi_nightly_runs WHERE fileId=? AND dataDate=?", fileId, date.toString());
            if (!existing.isEmpty()) return ((Number) existing.get(0).get("id")).longValue();
            var rows = jdbc.queryForList("SELECT configJson FROM kpi_nightly_profiles WHERE fileId=?", fileId);
            if (rows.isEmpty()) throw new IllegalArgumentException("Configure the nightly source first.");
            var profile = CommonReportModules.JSON.convertValue(read(rows.get(0).get("configJson").toString()), NightlyProfile.class);
            if (profile.folder().isBlank() || profile.identityFields().isEmpty()) throw new IllegalArgumentException("A source folder and explicit observation identity are required.");
            Map<String, Object> config = new LinkedHashMap<>(); config.put("profile", profile);
            // Capture a configuration error as a durable failed run, rather than silently skipping a day.
            try { config.putAll(validatePrerequisites(fileId, profile)); }
            catch (IllegalArgumentException ex) { config.put("configurationError", ex.getMessage()); }
            if (jdbc.queryForObject("SELECT COUNT(*) FROM kpi_nightly_runs WHERE status='ACTIVE'", Integer.class) >= maxActive) throw new IllegalArgumentException("Nightly active-run capacity is full.");
            var keys = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("INSERT INTO kpi_nightly_runs (fileId,dataDate,configJson,status,stage,allowLate,cutoffAt,deadlineAt,createdAt,updatedAt) VALUES (?,?,?,'ACTIVE','WAITING_INPUT',?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, fileId); statement.setString(2, date.toString()); statement.setString(3, write(config)); statement.setBoolean(4, allowLate);
                statement.setString(5, utc(profile.cutoff(date).atZone(NightlyProfile.ZONE).toInstant())); statement.setString(6, utc(NightlyProfile.deadline(date).atZone(NightlyProfile.ZONE).toInstant())); statement.setString(7, now()); statement.setString(8, now()); return statement;
            }, keys);
            long created = Objects.requireNonNull(keys.getKey()).longValue(); event(created, "CREATED", actor, allowLate ? "Explicit late/replay run; deadline will still be assessed against 02:00 IST." : "Run created for the daily input window."); return created;
        });
    }
    @Scheduled(cron="0 0 1 * * *", zone="Asia/Kolkata") public void startWindow() { dispatch(); }
    @Scheduled(fixedDelayString="${app.nightly.tick-millis:30000}", initialDelayString="${app.nightly.tick-millis:30000}") public void tick() { dispatch(); }
    @Scheduled(fixedDelayString="${app.nightly.tick-millis:30000}", initialDelayString="${app.nightly.tick-millis:30000}")
    public void deadlineTick() {
        // Keep the deadline check independent of the worker doing file hashing/staging.
        try { assessDeadlines(); } catch (RuntimeException ex) { LOG.error("Nightly deadline check failed; inspect database availability.", ex); }
    }
    private void dispatch() {
        if (!pending.compareAndSet(false, true)) return;
        try { self.getObject().sweep(); } catch (RejectedExecutionException ex) { pending.set(false); LOG.warn("Nightly coordinator is busy; durable runs will be checked on the next tick."); }
        catch (RuntimeException ex) { pending.set(false); throw ex; }
    }
    @Async("nightlyExecutor") public void sweep() {
        try {
            if (schedulerEnabled) ensureDueRuns();
            var active = new ArrayList<>(jdbc.queryForList("SELECT id FROM kpi_nightly_runs WHERE status='ACTIVE' ORDER BY updatedAt,id LIMIT ?", maxActive));
            active.addAll(jdbc.queryForList("SELECT id FROM kpi_nightly_runs WHERE status='BLOCKED' ORDER BY updatedAt,id LIMIT ?", maxActive));
            for (var run : active) { long id = ((Number) run.get("id")).longValue(); try { advance(id); } catch (Exception ex) { fail(id, "Stored nightly configuration could not be read. Review this run."); } }
            assessDeadlines();
        } catch (Exception ex) { LOG.error("Nightly coordinator check failed; durable runs remain available for the next tick.", ex); }
        finally { pending.set(false); }
    }
    private void ensureDueRuns() {
        ZonedDateTime time = clock.instant().atZone(NightlyProfile.ZONE);
        if (time.toLocalTime().isBefore(LocalTime.of(1, 0))) return;
        var profiles = jdbc.queryForList("SELECT fileId FROM kpi_nightly_profiles WHERE enabled=TRUE ORDER BY fileId LIMIT ?", maxActive + 1);
        if (profiles.size() > maxActive) { LOG.error("Enabled nightly profiles exceed active-run capacity. Existing runs will continue; review configuration before new runs can be created."); return; }
        for (var profile : profiles) {
            long fileId = ((Number) profile.get("fileId")).longValue();
            try { create(fileId, time.toLocalDate().minusDays(1), false, null); }
            catch (RuntimeException ex) { LOG.error("Nightly run for dataset {} could not be created: {}", fileId, ex.getMessage()); }
        }
    }
    private void advance(long id) {
        var run = row(id); var config = read(run.get("configJson").toString());
        var profile = CommonReportModules.JSON.convertValue(config.get("profile"), NightlyProfile.class);
        long fileId = ((Number) run.get("fileId")).longValue(); LocalDate date = LocalDate.parse(run.get("dataDate").toString());
        try {
            if (config.containsKey("configurationError")) throw new IllegalArgumentException(config.get("configurationError").toString());
            switch (run.get("stage").toString()) {
                case "WAITING_INPUT" -> discover(run, profile, date);
                case "IMPORT" -> {
                    if (run.get("ingestionId") == null) {
                        Path source = safeInput(profile, date);
                        try {
                            var submitted = ingestion.startPath(source, fileId, profile, date, run.get("sourceChecksum").toString(), maxSourceBytes);
                            link(id, "ingestionId", ((Number) map(submitted.get("data")).get("id")).longValue());
                        } catch (JobCapacityException ex) { link(id, "ingestionId", ex.getJobId()); throw ex; }
                    } else {
                        long childId = ((Number) run.get("ingestionId")).longValue(); var child = ingestion.get(childId);
                        if ("FAILED".equals(child.get("status"))) { retryOrFail(run, () -> ingestion.retry(childId), "Import failed. Review the ingestion error before retrying."); return; }
                        if ("COMPLETED".equals(child.get("status"))) stage(id, "ANALYSIS");
                    }
                }
                case "ANALYSIS" -> {
                    var imported = ingestion.get(((Number) run.get("ingestionId")).longValue()); var result = map(imported.get("result"));
                    if (run.get("analysisId") == null) {
                        Long attached = result.get("analysisSnapshotId") instanceof Number n ? n.longValue() : null;
                        try { var snapshot = attached == null ? analysis.requestImported(((Number) run.get("ingestionId")).longValue(), date, config.get("analysisProfileHash").toString()) : analysis.get(attached); link(id, "analysisId", ((Number) snapshot.get("id")).longValue()); }
                        catch (JobCapacityException ex) { link(id, "analysisId", ex.getJobId()); throw ex; }
                    } else {
                        long childId = ((Number) run.get("analysisId")).longValue(); var child = analysis.get(childId);
                        if (!Objects.equals(child.get("profileHash"), config.get("analysisProfileHash")) || !Objects.equals(child.get("datasetVersion"), result.get("datasetRevision"))) throw new IllegalStateException("Analysis does not match the captured profile/import version.");
                        if ("FAILED".equals(child.get("status"))) { retryOrFail(run, () -> analysis.retry(childId), "Analysis failed. Review its history before retrying."); return; }
                        if ("COMPLETED".equals(child.get("status"))) {
                            if (!date.toString().equals(String.valueOf(child.get("dataDate")))) throw new IllegalStateException("Analysis data date does not match the nightly input day.");
                            if (!"AVAILABLE".equals(map(child.get("summary")).get("dataStatus"))) throw new IllegalStateException("Daily analysis has no valid current-day observations.");
                            stage(id, "REPORT");
                        }
                    }
                }
                case "REPORT" -> {
                    if (run.get("reportId") == null) {
                        try { var report = reports.request(((Number) run.get("analysisId")).longValue(), profile.reportOptions()); link(id, "reportId", ((Number) report.get("id")).longValue()); }
                        catch (JobCapacityException ex) { link(id, "reportId", ex.getJobId()); throw ex; }
                    } else {
                        long childId = ((Number) run.get("reportId")).longValue(); var child = reports.get(childId);
                        if ("FAILED".equals(child.get("status"))) { retryOrFail(run, () -> reports.retry(childId), "Report generation failed. Review its history before retrying."); return; }
                        if (List.of("READY", "PARTIAL").contains(child.get("status"))) {
                            if (profile.deliveryMode().equals("REPORT_ONLY")) complete(id, "REPORT_READY", reports.publishedAt(childId)); else stage(id, "DELIVERY");
                        }
                    }
                }
                case "DELIVERY" -> deliver(run, profile, config);
                default -> throw new IllegalStateException("Unknown nightly workflow stage.");
            }
        } catch (JobCapacityException ex) {
            if (((Number) run.get("attempts")).intValue() < maxAttempts && clock.instant().isBefore(instant(run, "deadlineUtc"))) event(id, "QUEUE_BACKPRESSURE", null, "Child ID retained; a bounded safe retry will be checked on the next tick.");
            else fail(id, "Queue capacity prevented completion within the retry/deadline budget.");
        } catch (Exception ex) { fail(id, ex.getMessage() == null ? "Nightly stage failed." : ex.getMessage()); }
    }
    private Path safeInput(NightlyProfile profile, LocalDate date) throws java.io.IOException {
        Path folder = Path.of(profile.folder()).toAbsolutePath().normalize(), file = profile.input(date);
        if (!file.getParent().equals(folder) || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().getParent().equals(folder.toRealPath())) throw new java.io.IOException("Expected daily file is unavailable; symbolic links and nested paths are not accepted.");
        return file;
    }
    private void discover(Map<String, Object> run, NightlyProfile profile, LocalDate date) throws Exception {
        long id = ((Number) run.get("id")).longValue();
        if (!Boolean.TRUE.equals(run.get("allowLate")) && !clock.instant().isBefore(instant(run, "cutoffUtc"))) { fail(id, "Input cutoff reached before a stable daily file was accepted."); return; }
        Path file;
        try { file = safeInput(profile, date); } catch (java.io.IOException ex) { return; }
        var attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.size() < 1 || attributes.size() > maxSourceBytes) throw new IllegalArgumentException("Daily input is empty or exceeds its size limit.");
        long modified = attributes.lastModifiedTime().toMillis();
        if (!Objects.equals(run.get("observedSize"), attributes.size()) || !Objects.equals(run.get("observedModified"), modified)) {
            jdbc.update("UPDATE kpi_nightly_runs SET observedSize=?,observedModified=?,observedAt=?,updatedAt=? WHERE id=?", attributes.size(), modified, now(), now(), id); return;
        }
        if (clock.instant().isBefore(instant(run, "observedUtc").plusSeconds(stableSeconds)) || clock.instant().isBefore(attributes.lastModifiedTime().toInstant().plusSeconds(stableSeconds))) return;
        String checksum = RecommendationCacheKey.fileDigest(file);
        jdbc.update("UPDATE kpi_nightly_runs SET sourceChecksum=?,stage='IMPORT',updatedAt=? WHERE id=? AND stage='WAITING_INPUT'", checksum, now(), id); event(id, "INPUT_STABLE", null, file.getFileName() + "; SHA-256 " + checksum);
    }
    private void deliver(Map<String, Object> run, NightlyProfile profile, Map<String, Object> config) throws Exception {
        long id = ((Number) run.get("id")).longValue();
        if (run.get("deliveryIdsJson") == null) {
            var submitted = mail.request(((Number) run.get("reportId")).longValue(), null, config.get("mailProfileHash").toString());
            List<Long> ids = ((List<?>) submitted.get("deliveries")).stream().map(value -> ((Number) map(value).get("id")).longValue()).toList();
            if (ids.isEmpty()) throw new IllegalStateException("Mail request did not produce recipient deliveries.");
            jdbc.update("UPDATE kpi_nightly_runs SET deliveryIdsJson=?,retryPending=FALSE,updatedAt=? WHERE id=?", write(ids), now(), id); event(id, "DELIVERIES_LINKED", null, write(ids));
            if (Boolean.TRUE.equals(submitted.get("capacityLimited"))) event(id, "QUEUE_BACKPRESSURE", null, "Mail queue was full; recipient delivery IDs were retained for bounded safe retry.");
            return;
        }
        List<Long> ids = ids(run.get("deliveryIdsJson").toString());
        List<Map<String, Object>> recipients = ids.stream().map(mail::get).toList();
        if (recipients.stream().anyMatch(value -> "UNKNOWN".equals(value.get("status")))) {
            if (!"BLOCKED".equals(run.get("status"))) { jdbc.update("UPDATE kpi_nightly_runs SET status='BLOCKED',message='Delivery outcome unknown. Reconcile in Email delivery; no resend will be attempted.',updatedAt=? WHERE id=?", now(), id); event(id, "DELIVERY_UNKNOWN", null, "Provider reconciliation required; no automatic resend."); }
            else jdbc.update("UPDATE kpi_nightly_runs SET updatedAt=? WHERE id=? AND status='BLOCKED'", now(), id);
            return;
        }
        if ("BLOCKED".equals(run.get("status"))) { jdbc.update("UPDATE kpi_nightly_runs SET status='ACTIVE',message=NULL,updatedAt=? WHERE id=?", now(), id); event(id, "RECONCILIATION_OBSERVED", null, "Recipient UNKNOWN status has been resolved."); }
        if (recipients.stream().anyMatch(value -> "CANCELLED".equals(value.get("status")))) throw new IllegalStateException("A recipient delivery was cancelled. It will not be resent by nightly retry.");
        if (recipients.stream().anyMatch(value -> List.of("FAILED", "CANCELLED").contains(value.get("status")))) {
            retryOrFail(run, () -> recipients.stream().filter(value -> "FAILED".equals(value.get("status"))).forEach(value -> mail.retry(((Number) value.get("id")).longValue(), null)), "One or more recipient deliveries failed or were cancelled. Review delivery history."); return;
        }
        String expected = profile.deliveryMode().equals("SMTP") ? "ACCEPTED" : "CAPTURED";
        if (recipients.stream().allMatch(value -> expected.equals(value.get("status")))) {
            Instant finished = ids.stream().map(mail::completedAt).max(Comparator.naturalOrder()).orElseThrow(); complete(id, expected.equals("ACCEPTED") ? "SMTP_ACCEPTED" : "MAIL_CAPTURED", finished);
        }
    }
    private void retryOrFail(Map<String, Object> run, Runnable retry, String message) {
        long id = ((Number) run.get("id")).longValue();
        if (Boolean.TRUE.equals(run.get("retryPending"))) { jdbc.update("UPDATE kpi_nightly_runs SET retryPending=FALSE,updatedAt=? WHERE id=?", now(), id); retry.run(); }
        else if (((Number) run.get("attempts")).intValue() < maxAttempts && clock.instant().isBefore(instant(run, "deadlineUtc")) && safelyRecoverable(run)) {
            jdbc.update("UPDATE kpi_nightly_runs SET attempts=attempts+1,updatedAt=? WHERE id=?", now(), id);
            event(id, "AUTOMATIC_SAFE_RETRY", null, "Known queue rejection or interrupted unsent work. Same child IDs and the run attempt cap are retained.");
            retry.run();
        }
        else fail(id, message);
    }
    private boolean safelyRecoverable(Map<String, Object> run) {
        List<Map<String, Object>> failed = switch (run.get("stage").toString()) {
            case "IMPORT" -> List.of(ingestion.get(((Number) run.get("ingestionId")).longValue()));
            case "ANALYSIS" -> List.of(analysis.get(((Number) run.get("analysisId")).longValue()));
            case "REPORT" -> List.of(reports.get(((Number) run.get("reportId")).longValue()));
            case "DELIVERY" -> ids(run.get("deliveryIdsJson").toString()).stream().map(mail::get).filter(value -> "FAILED".equals(value.get("status"))).toList();
            default -> List.of();
        };
        return !failed.isEmpty() && failed.stream().allMatch(value -> {
            String code = String.valueOf(map(value.get("result")).get("code"));
            String reason = String.valueOf(value.get("message")).toLowerCase(Locale.ROOT);
            return Set.of("JOB_CAPACITY", "BACKEND_RESTARTED").contains(code) || reason.contains("queue is full") || reason.contains("queue full") || reason.contains("workers and queue are full") || reason.contains("backend restarted");
        });
    }
    public Map<String, Object> retry(long id, boolean rediscoverInput, boolean refreshMailProfile, Long actor) {
        var current = row(id); long fileId = ((Number) current.get("fileId")).longValue();
        transactions.locked(fileId, () -> {
            var run = row(id); var config = read(run.get("configJson").toString());
            if (!"FAILED".equals(run.get("status")) || ((Number) run.get("attempts")).intValue() >= maxAttempts) throw new IllegalArgumentException("Only FAILED nightly runs below the attempt cap can be retried. Reconcile UNKNOWN mail separately.");
            if (rediscoverInput) {
                if (run.get("ingestionId") != null && !"FAILED".equals(ingestion.get(((Number) run.get("ingestionId")).longValue()).get("status"))) throw new IllegalArgumentException("Cannot rediscover after an import committed or while it is active.");
                if (run.get("analysisId") != null || run.get("reportId") != null) throw new IllegalArgumentException("Cannot replace input after analysis/report creation.");
                var saved = jdbc.queryForList("SELECT configJson FROM kpi_nightly_profiles WHERE fileId=?", fileId);
                if (saved.isEmpty()) throw new IllegalArgumentException("Nightly source settings are missing.");
                var updated = CommonReportModules.JSON.convertValue(read(saved.get(0).get("configJson").toString()), NightlyProfile.class).validated();
                if (updated.folder().isBlank() || updated.identityFields().isEmpty()) throw new IllegalArgumentException("Configure the corrected source and observation grain first.");
                config.put("profile", updated); config.putAll(validatePrerequisites(fileId, updated)); config.remove("configurationError");
                event(id, "INPUT_REDISCOVERY", actor, "Explicit recapture of source/settings. Previous ingestion ID: " + run.get("ingestionId"));
                jdbc.update("UPDATE kpi_nightly_runs SET stage='WAITING_INPUT',ingestionId=NULL,sourceChecksum=NULL,observedSize=NULL,observedModified=NULL,observedAt=NULL WHERE id=?", id);
            }
            if (config.containsKey("configurationError")) {
                var captured = CommonReportModules.JSON.convertValue(config.get("profile"), NightlyProfile.class);
                config.putAll(validatePrerequisites(fileId, captured)); config.remove("configurationError"); event(id, "CONFIGURATION_REVALIDATED", actor, "Prerequisites rechecked before any child submission.");
            }
            if (refreshMailProfile) {
                if (run.get("deliveryIdsJson") != null) throw new IllegalArgumentException("Cannot change recipients after recipient deliveries were created.");
                var profile = CommonReportModules.JSON.convertValue(config.get("profile"), NightlyProfile.class);
                config.put("mailProfileHash", validatePrerequisites(fileId, profile).get("mailProfileHash")); event(id, "MAIL_PROFILE_REFRESHED", actor, "Explicitly reviewed the current mail profile for this run.");
            }
            jdbc.update("UPDATE kpi_nightly_runs SET status='ACTIVE',attempts=attempts+1,retryPending=TRUE,allowLate=TRUE,message=NULL,finishedAt=NULL,configJson=?,updatedAt=? WHERE id=?", write(config), now(), id);
            event(id, "RETRY_REQUESTED", actor, "Explicit resume; committed imports and accepted recipients are retained. Late completion remains visible."); return null;
        }); dispatch(); return get(id);
    }
    private void link(long id, String column, long childId) {
        if (!Set.of("ingestionId", "analysisId", "reportId").contains(column)) throw new IllegalArgumentException("Invalid nightly child link.");
        jdbc.update("UPDATE kpi_nightly_runs SET " + column + "=?,updatedAt=? WHERE id=?", childId, now(), id); event(id, "CHILD_LINKED", null, column + "=" + childId);
    }
    private void stage(long id, String next) { jdbc.update("UPDATE kpi_nightly_runs SET stage=?,status='ACTIVE',retryPending=FALSE,updatedAt=? WHERE id=?", next, now(), id); event(id, "STAGE", null, next); }
    private void fail(long id, String message) {
        if (jdbc.update("UPDATE kpi_nightly_runs SET status='FAILED',message=?,deadlineStatus=IF(deadlineStatus='PENDING','UNMET',deadlineStatus),finishedAt=?,updatedAt=? WHERE id=? AND status IN ('ACTIVE','BLOCKED')", limit(message), now(), now(), id) > 0) { event(id, "FAILED", null, limit(message)); LOG.error("Nightly run {} failed: {}", id, limit(message)); }
    }
    private void complete(long id, String mode, Instant finished) {
        var run = row(id); boolean met = !finished.isAfter(instant(run, "deadlineUtc"));
        jdbc.update("UPDATE kpi_nightly_runs SET status='COMPLETED',stage='DONE',completionMode=?,deadlineStatus=?,message=NULL,finishedAt=?,updatedAt=? WHERE id=?", mode, met ? "MET" : "MISSED", utc(finished), now(), id);
        if (!met && !"MISSED".equals(run.get("deadlineStatus"))) { jdbc.update("UPDATE kpi_nightly_runs SET deadlineMissedAt=? WHERE id=?", now(), id); event(id, "DEADLINE_MISSED", null, "Completion was confirmed after 02:00 IST."); LOG.error("Nightly run {} completed after its 02:00 IST target.", id); }
        event(id, "COMPLETED", null, mode + "; " + (met ? "02:00 IST goal met." : "Completed after the 02:00 IST target."));
    }
    private void assessDeadlines() {
        var overdue = jdbc.queryForList("SELECT id FROM kpi_nightly_runs WHERE deadlineAt<? AND deadlineStatus IN ('PENDING','UNMET') ORDER BY id LIMIT 100", now());
        for (var row : overdue) { long id = ((Number) row.get("id")).longValue(); if (jdbc.update("UPDATE kpi_nightly_runs SET deadlineStatus='MISSED',deadlineMissedAt=?,updatedAt=? WHERE id=? AND deadlineStatus IN ('PENDING','UNMET')", now(), now(), id) > 0) { event(id, "DEADLINE_MISSED", null, "02:00 IST target was not confirmed. Inspect linked job statuses; active work is not cancelled."); LOG.error("Nightly run {} missed its 02:00 IST completion target.", id); } }
    }
    private void event(long id, String name, Long actor, String details) { jdbc.update("INSERT INTO kpi_nightly_events (runId,event,actorId,details,createdAt) VALUES (?,?,?,?,?)", id, name, actor, details, now()); }
    private static String limit(String value) { return value.substring(0, Math.min(value.length(), 2000)); }
    private String now() { return utc(clock.instant()); } private static String utc(Instant value) { return SQL_TIME.format(value); }
    private static Instant instant(Map<String, Object> row, String key) { return LocalDateTime.parse(row.get(key).toString()).toInstant(ZoneOffset.UTC); }
    private Map<String, Object> row(long id) {
        var rows = jdbc.queryForList(SELECT + " WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Nightly run was not found."); var row = rows.get(0); row.put("dataDate", row.get("dayText")); return row;
    }
    public long fileId(long id) { return ((Number) row(id).get("fileId")).longValue(); }
    public Map<String, Object> get(long id) { var result = publicView(row(id)); result.put("events", jdbc.queryForList("SELECT event,actorId,details,DATE_FORMAT(createdAt,'%Y-%m-%dT%H:%i:%sZ') createdAt FROM kpi_nightly_events WHERE runId=? ORDER BY id DESC LIMIT 100", id)); return result; }
    public List<Map<String, Object>> list(long fileId, int limit) { return jdbc.queryForList(SELECT + " WHERE fileId=? ORDER BY id DESC LIMIT ?", fileId, Math.max(1, Math.min(limit, 50))).stream().map(this::publicView).toList(); }
    private Map<String, Object> publicView(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row); result.remove("configJson"); result.remove("observedSize"); result.remove("observedModified"); result.remove("observedAt"); result.remove("observedUtc"); result.put("dataDate", result.remove("dayText")); result.remove("retryPending");
        for (var field : Map.of("createdAt", "createdUtc", "updatedAt", "updatedUtc", "finishedAt", "finishedUtc", "deadlineMissedAt", "missedUtc").entrySet()) { Object value = result.remove(field.getValue()); result.put(field.getKey(), value == null ? null : value + "Z"); }
        result.put("cutoffAt", result.remove("cutoffUtc") + "Z"); result.put("deadlineAt", result.remove("deadlineUtc") + "Z"); Object recipients = result.remove("deliveryIdsJson"); result.put("deliveryIds", recipients == null ? List.of() : ids(recipients.toString())); result.put("timezone", NightlyProfile.ZONE.toString()); result.put("inboxDeliveryConfirmed", false); return result;
    }
    private static List<Long> ids(String value) { try { return CommonReportModules.JSON.readValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() { }); } catch (Exception ex) { throw new IllegalStateException("Invalid recipient delivery links.", ex); } }
    private static Map<String, Object> map(Object value) { return CommonReportRenderer.map(value); }
    private static String write(Object value) { try { return CommonReportModules.JSON.writeValueAsString(value); } catch (Exception ex) { throw new IllegalArgumentException("Invalid nightly configuration.", ex); } }
    private static Map<String, Object> read(String value) { try { return CommonReportModules.JSON.readValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() { }); } catch (Exception ex) { throw new IllegalStateException("Invalid stored nightly configuration.", ex); } }
}

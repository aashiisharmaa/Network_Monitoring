package com.network.monitoring.alarm.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.monitoring.alarm.config.*;
import com.network.monitoring.alarm.ingestion.AlarmImportPreviewService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.network.monitoring.alarm.config.AlarmConfiguration.*;
import static com.network.monitoring.alarm.config.AlarmConfigurationException.*;

/** Native OSS domain. Immutable observations are separate from KPI-generated alarms. */
@Service
public class AlarmWorkflowService {
    public static final String ADAPTER_VERSION="oss-snapshot-v1";
    public record Scope(long circleId, String circleName, String zoneName) {}
    public record Catalog(long id, String oem, String technology, String matchField, String matchValue,
                          String classification, Boolean included, Boolean serviceAffecting, Boolean siteDown) {}
    public record Site(long id, long circleId, String objectKey, String siteCode) {}
    public record Frozen(Feed feed, Map<String, Scope> circles, List<Catalog> catalog, List<Site> sites, String automationJson, String adapterVersion) {}
    public record Observation(String key, Scope scope, String site, AlarmImportPreviewService.AlarmRow row, Catalog rule) {}
    public record Outcome(int inputRows, int distinctAlarms, int duplicateRows, int unmappedCatalogue,
                          int unmappedSites, Long previousRunId, int newAlarms, int continuingAlarms,
                          int notObservedAgain, String identityPolicy, boolean automaticClearingEnabled) {}

    private final JdbcTemplate jdbc;
    private final AlarmTenantAccess access;
    private final AlarmConfigurationRepository master;
    private final AlarmImportPreviewService parser;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final Path root;
    private final boolean enabled;
    private final java.util.concurrent.Semaphore stagingSlots=new java.util.concurrent.Semaphore(2);

    public AlarmWorkflowService(JdbcTemplate jdbc, AlarmTenantAccess access, AlarmConfigurationRepository master,
            AlarmImportPreviewService parser, ObjectMapper json, PlatformTransactionManager transactions,
            @Value("${app.alarm-workflow.storage-path:uploads/alarm-workflow}") String root,
            @Value("${app.alarm-workflow.enabled:false}") boolean enabled) {
        this.jdbc = jdbc; this.access = access; this.master = master; this.parser = parser;
        this.json = json; this.tx = new TransactionTemplate(transactions);
        this.root = Path.of(root).toAbsolutePath().normalize(); this.enabled = enabled;
    }
    public boolean enabled() { return enabled; }
    public long authorize(long company, boolean write) { return access.require(company, write); }

    public Map<String, Object> submit(long company, long feedId, String snapshotAt, MultipartFile file) {
        long actor = access.require(company, true);
        return stage(company, feedId, snapshotAt, file, actor, null);
    }
    public Map<String,Object> replace(long company,long runId,MultipartFile file) {
        long actor=access.require(company,true);var previous=runInternal(company,runId);
        return stage(company,number(previous,"feed_id"),jdbcInstant(previous.get("snapshot_at")).toString(),file,actor,runId);
    }
    Map<String, Object> submitWatched(long company, long feedId, String snapshotAt, MultipartFile file, long actor) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE id=? AND status='ACTIVE' AND (role='SUPER_ADMIN' OR (role='ADMIN' AND company_id=?))", Integer.class, actor, company) != 1)
            throw new AlarmConfigurationException(403, "AUTOMATION_ACTOR_INACTIVE", "Automation configuration owner is no longer authorized.");
        return stage(company, feedId, snapshotAt, file, actor, null);
    }
    private Map<String, Object> stage(long company, long feedId, String snapshotAt, MultipartFile file, long actor, Long replacement) {
        if(!stagingSlots.tryAcquire()) throw new AlarmConfigurationException(503,"ALARM_STAGING_BUSY","Source staging is busy; retry shortly.");
        try{return stageFile(company,feedId,snapshotAt,file,actor,replacement);}finally{stagingSlots.release();}
    }
    private Map<String, Object> stageFile(long company,long feedId,String snapshotAt,MultipartFile file,long actor,Long replacement) {
        if (!enabled) throw new AlarmConfigurationException(503, "ALARM_WORKFLOW_DISABLED", "Apply migrations 008/009 and enable ALARM_WORKFLOW_ENABLED before importing.");
        Instant asOf = instant(snapshotAt);
        if (asOf.getNano() != 0 || asOf.isAfter(Instant.now().plusSeconds(300))) throw invalid("Snapshot time must use whole seconds and cannot be in the future.");
        if (file == null || file.isEmpty() || file.getSize() > 10 * 1024 * 1024) throw invalid("A non-empty file of at most 10 MiB is required.");
        String original = Optional.ofNullable(file.getOriginalFilename()).orElse("");
        String filename = original.replace('\\', '/'); filename = filename.substring(filename.lastIndexOf('/') + 1);
        if (filename.length() > 255 || !filename.toLowerCase(Locale.ROOT).matches(".+\\.(csv|xlsx)")) throw invalid("Supply a CSV or XLSX filename of at most 255 characters.");
        final byte[] data;
        try (InputStream stream = file.getInputStream()) { data = stream.readNBytes(10 * 1024 * 1024 + 1); }
        catch (IOException e) { throw invalid("Cannot read the supplied source file."); }
        if (data.length > 10 * 1024 * 1024) throw invalid("File exceeds the 10 MiB ingestion budget.");
        String checksum = digest(data), sourceKey = UUID.randomUUID().toString();
        Path source = source(sourceKey);
        try { Files.createDirectories(root); Files.write(source, data, StandardOpenOption.CREATE_NEW); }
        catch (IOException e) { throw new AlarmConfigurationException(503, "ALARM_STORAGE_UNAVAILABLE", "Alarm source staging is unavailable."); }
        final String safeFilename = filename;
        boolean[] retained = {false};
        try {
            return tx.execute(status -> {
                master.lockCompany(company);
                var existing = jdbc.queryForList("SELECT id,checksum,status,revision FROM alarm_ingestion_runs WHERE company_id=? AND feed_id=? AND snapshot_at=? ORDER BY revision DESC LIMIT 1", company, feedId, timestamp(asOf));
                int revision=1;
                if (!existing.isEmpty()) {
                    var prior=existing.get(0);
                    if(replacement==null) {
                        if (!checksum.equals(prior.get("checksum"))) throw conflict("This feed snapshot already has different content. Use explicit replacement for a FAILED run; successful history is immutable.");
                        return runInternal(company,number(prior,"id"));
                    }
                    if(replacement!=number(prior,"id")||!"FAILED".equals(prior.get("status"))) throw conflict("Only the latest FAILED revision can be replaced.");
                    revision=((Number)prior.get("revision")).intValue()+1;
                    jdbc.update("UPDATE alarm_ingestion_runs SET status='SUPERSEDED',message='Replaced by an explicit corrected revision',updated_at=UTC_TIMESTAMP(6) WHERE id=? AND status='FAILED'",replacement);
                } else if(replacement!=null) {
                    throw missing();
                }
                if (jdbc.queryForObject("SELECT COUNT(*) FROM alarm_ingestion_runs WHERE company_id=? AND status IN ('QUEUED','RUNNING')", Integer.class, company) >= 50)
                    throw new AlarmConfigurationException(429, "ALARM_IMPORT_BACKLOG", "Company import backlog is full. Wait for processing before uploading more files.");
                Feed feed = master.feed(company, feedId).orElseThrow(AlarmConfigurationException::missing);
                if (!feed.active()) throw conflict("Activate the reviewed source-feed profile before importing.");
                if (!feed.dumpType().equals("SNAPSHOT")) throw invalid("DELTA profiles require an OEM event/clear adapter. This importer accepts SNAPSHOT state dumps only; changes must not be misreported as complete state.");
                if (!safeFilename.toLowerCase(Locale.ROOT).endsWith("." + feed.fileFormat().toLowerCase(Locale.ROOT))) throw invalid("File extension does not match the feed profile.");
                Frozen config = freeze(company, feed);
                long id = insert("INSERT INTO alarm_ingestion_runs(company_id,feed_id,snapshot_at,revision,supersedes_id,checksum,source_key,file_name,config_json,created_by) VALUES (?,?,?,?,?,?,?,?,?,?)",
                        company, feedId, timestamp(asOf), revision, replacement, checksum, sourceKey, safeFilename, encode(config), actor);
                retained[0] = true;
                return runInternal(company, id);
            });
        } catch (RuntimeException error) {
            retained[0] = false; throw error;
        } finally {
            if (!retained[0]) try { Files.deleteIfExists(source); } catch (IOException ignored) { /* Unreferenced staged file; never used by a job. */ }
        }
    }

    private Frozen freeze(long company, Feed feed) {
        Map<Long, Zone> zones = new HashMap<>();
        for (var zone : bounded("SELECT * FROM alarm_zones WHERE company_id=? LIMIT 2001", company, 2000))
            zones.put(number(zone, "id"), new Zone(number(zone, "id"), company, string(zone, "code"), string(zone, "name"), bool(zone, "active"), 1));
        Map<Long, Scope> circles = new HashMap<>();
        for (var circle : bounded("SELECT * FROM alarm_circles WHERE company_id=? LIMIT 2001", company, 2000)) {
            var zone = zones.get(number(circle, "zone_id"));
            if (bool(circle, "active") && zone != null && zone.active()) circles.put(number(circle, "id"), new Scope(number(circle, "id"), string(circle, "name"), zone.name()));
        }
        if (feed.circleId() != null && !circles.containsKey(feed.circleId())) throw conflict("Feed circle or its zone is inactive.");
        Map<String, Scope> aliases = new HashMap<>();
        for (var alias : bounded("SELECT * FROM alarm_circle_aliases WHERE company_id=? LIMIT 10001", company, 10000)) {
            Scope scope = circles.get(number(alias, "circle_id"));
            if (scope != null) aliases.put(string(alias, "alias_key"), scope);
        }
        var automation = jdbc.queryForList("SELECT settings_json FROM alarm_automation_settings WHERE company_id=?", company);
        return new Frozen(feed, Map.copyOf(aliases), catalogsInternal(company), sitesInternal(company, feed.id()), automation.isEmpty() ? "" : string(automation.get(0), "settings_json"),ADAPTER_VERSION);
    }

    public List<Map<String, Object>> runs(long company, int limit) {
        access.require(company, false); limit(limit);
        return jdbc.queryForList("SELECT id,feed_id,snapshot_at,revision,supersedes_id,status,attempts,message,created_at,updated_at,result_json FROM alarm_ingestion_runs WHERE company_id=? ORDER BY id DESC LIMIT ?", company, limit);
    }
    public Map<String, Object> run(long company, long id) { access.require(company, false); return runInternal(company, id); }
    private Map<String, Object> runInternal(long company, long id) {
        var rows = jdbc.queryForList("SELECT id,feed_id,snapshot_at,revision,supersedes_id,checksum,file_name,status,attempts,message,result_json,created_at,updated_at FROM alarm_ingestion_runs WHERE company_id=? AND id=?", company, id);
        if (rows.isEmpty()) throw missing(); return rows.get(0);
    }
    public Map<String, Object> retry(long company, long id) {
        long actor = access.require(company, true);
        if(!enabled) throw new AlarmConfigurationException(503,"ALARM_WORKFLOW_DISABLED","Alarm processing is disabled.");
        if (jdbc.update("UPDATE alarm_ingestion_runs SET status='QUEUED',lease_token=NULL,lease_until=NULL,retried_by=?,message='Retry queued with original source and frozen configuration',updated_at=UTC_TIMESTAMP(6) WHERE company_id=? AND id=? AND status='FAILED'", actor, company, id) != 1)
            throw conflict("Only a failed run can be retried.");
        return runInternal(company, id);
    }
    public List<Long> pending() {
        jdbc.update("UPDATE alarm_ingestion_runs SET status='FAILED',message='Worker lease expired after recovery budget; manual retry required',updated_at=UTC_TIMESTAMP(6) WHERE status='RUNNING' AND lease_until<UTC_TIMESTAMP(6) AND attempts>=3");
        return jdbc.queryForList("SELECT id FROM alarm_ingestion_runs WHERE status='QUEUED' OR (status='RUNNING' AND lease_until<UTC_TIMESTAMP(6) AND attempts<3) ORDER BY id LIMIT 2", Long.class);
    }

    /** Claim and final transaction are fenced by a unique token; parsing holds no DB transaction. */
    public boolean process(long id) {
        String token = UUID.randomUUID().toString();
        if (jdbc.update("UPDATE alarm_ingestion_runs SET status='RUNNING',attempts=attempts+1,lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE),message='Validating frozen source',updated_at=UTC_TIMESTAMP(6) WHERE id=? AND (status='QUEUED' OR (status='RUNNING' AND lease_until<UTC_TIMESTAMP(6) AND attempts<3))", token, id) != 1) return false;
        long started = System.nanoTime();
        try {
            var run = jdbc.queryForMap("SELECT * FROM alarm_ingestion_runs WHERE id=? AND lease_token=?", id, token);
            long company = number(run, "company_id");
            Frozen config = json.readValue(string(run, "config_json"), Frozen.class);
            if(!ADAPTER_VERSION.equals(config.adapterVersion())) throw invalid("Saved adapter version is not supported by this build. Use its matching adapter or explicitly replace the failed revision.");
            Path staged = source(string(run, "source_key"));
            if (Files.isSymbolicLink(staged) || Files.size(staged) > 10 * 1024 * 1024) throw invalid("Staged source is invalid.");
            byte[] bytes = Files.readAllBytes(staged);
            if (!digest(bytes).equals(string(run, "checksum"))) throw invalid("Staged source checksum changed; import refused.");
            var parsed = parser.parse(new MemoryFile(string(run, "file_name"), bytes), config.feed().sheet(),
                    config.feed().oem().equals("MIXED") ? null : config.feed().oem(), config.feed().technology(), config.feed().sourceTimezone());
            if (parsed.validation().rejectedRows() > 0) {
                fail(id, token, "Source validation failed. No observations committed.", encode(parsed.validation())); return true;
            }
            Instant asOf = jdbcInstant(run.get("snapshot_at"));
            LinkedHashMap<String, Observation> observations = new LinkedHashMap<>();
            Map<String, Site> siteLookup = new HashMap<>();
            config.sites().forEach(site -> siteLookup.put(site.objectKey(), site));
            Map<String, Catalog> ruleLookup = new HashMap<>();
            config.catalog().forEach(rule -> ruleLookup.put(ruleKey(rule.oem(), rule.technology(), rule.matchField(), rule.matchValue()), rule));
            for (var row : parsed.rows()) {
                if (!row.technology().equals(config.feed().technology()) || (!config.feed().oem().equals("MIXED") && !row.oem().equals(config.feed().oem())))
                    throw invalid("Row " + row.sourceRow() + ": OEM/technology conflicts with the feed profile.");
                if (row.raisedAt().isAfter(asOf)) throw invalid("Row " + row.sourceRow() + ": raised time is after the snapshot time.");
                if (row.resource().length() > 512 || row.alarmCode().length() > 255 || row.sourceSeverity().length() > 128)
                    throw invalid("Row " + row.sourceRow() + ": source identity fields exceed the supported storage budget.");
                Scope scope = config.circles().get(row.circle().trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " "));
                if (scope == null || (config.feed().circleId() != null && config.feed().circleId() != scope.circleId()))
                    throw invalid("Row " + row.sourceRow() + ": circle is unmapped, inactive or outside the feed scope.");
                List<Catalog> rules = new ArrayList<>(2);
                Catalog codeRule = ruleLookup.get(ruleKey(row.oem(), row.technology(), "CODE", row.alarmCode()));
                Catalog textRule = ruleLookup.get(ruleKey(row.oem(), row.technology(), "TEXT", row.alarmText()));
                if (codeRule != null) rules.add(codeRule); if (textRule != null) rules.add(textRule);
                if (rules.size() > 1) throw invalid("Row " + row.sourceRow() + ": multiple catalogue rules match; resolve the ambiguity.");
                Site mappedSite = siteLookup.get(row.resource());
                String site = null;
                if (mappedSite != null) {
                    if (mappedSite.circleId() != scope.circleId()) throw invalid("Row " + row.sourceRow() + ": site mapping conflicts with source circle.");
                    site = mappedSite.siteCode();
                }
                String key = digest(encode(List.of(row.oem(), row.technology(), row.resource(), row.alarmCode().isEmpty() ? row.alarmText() : row.alarmCode(), row.raisedAt().toString())).getBytes(StandardCharsets.UTF_8));
                var observation = new Observation(key, scope, site, row, rules.isEmpty() ? null : rules.get(0));
                var previous = observations.putIfAbsent(key, observation);
                if (previous != null && (!previous.row().alarmText().equals(row.alarmText()) || !previous.row().sourceSeverity().equals(row.sourceSeverity())
                        || !previous.row().name().equals(row.name()) || !previous.row().supplementaryInformation().equals(row.supplementaryInformation()) || previous.scope().circleId() != scope.circleId()))
                    throw invalid("Row " + row.sourceRow() + ": conflicting records share the same provisional alarm identity.");
            }
            if (Duration.ofNanos(System.nanoTime() - started).toSeconds() > 120) throw invalid("Parsing exceeded the 120 second processing budget.");
            tx.executeWithoutResult(status -> commit(id, token, company, config, asOf, parsed.rows().size(), observations));
        } catch (AlarmImportPreviewService.PreviewBusyException busy) {
            jdbc.update("UPDATE alarm_ingestion_runs SET status='QUEUED',lease_token=NULL,lease_until=NULL,message='Waiting for parser capacity' WHERE id=? AND lease_token=? AND status='RUNNING'", id, token);
        } catch (Exception error) {
            fail(id, token, error instanceof AlarmConfigurationException || error instanceof IllegalArgumentException
                    ? error.getMessage() : "Import failed. Inspect server diagnostics and retry the frozen source.", null);
        }
        return true;
    }

    private void commit(long id, String token, long company, Frozen config, Instant asOf, int inputRows, Map<String, Observation> observations) {
        var owner = jdbc.queryForList("SELECT id FROM alarm_ingestion_runs WHERE id=? AND lease_token=? AND status='RUNNING' AND lease_until>UTC_TIMESTAMP(6) FOR UPDATE", id, token);
        if (owner.isEmpty()) throw conflict("Worker lease was lost; no observations committed.");
        var currentFeed=jdbc.queryForList("SELECT active FROM alarm_source_feed_profiles WHERE company_id=? AND id=? FOR UPDATE", company, config.feed().id());
        if(currentFeed.isEmpty()||!bool(currentFeed.get(0),"active")) throw conflict("Source feed is inactive; reactivate it before retrying.");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM companies WHERE id=? AND status='ACTIVE'", Integer.class, company) != 1) throw conflict("Company is inactive.");
        var previousRuns = jdbc.queryForList("SELECT id FROM alarm_ingestion_runs WHERE company_id=? AND feed_id=? AND status='SUCCEEDED' AND snapshot_at<? ORDER BY snapshot_at DESC LIMIT 1", company, config.feed().id(), timestamp(asOf));
        Long previousId = previousRuns.isEmpty() ? null : ((Number) previousRuns.get(0).get("id")).longValue();
        Set<String> before = previousId == null ? Set.of() : new HashSet<>(jdbc.queryForList("SELECT identity_key FROM alarm_observations WHERE company_id=? AND run_id=?", String.class, company, previousId));
        List<Object[]> batch = new ArrayList<>();
        int unknownRules = 0, unknownSites = 0, continuing = 0;
        for (var observation : observations.values()) {
            var row = observation.row(); var rule = observation.rule();
            if (rule == null) unknownRules++; if (observation.site() == null) unknownSites++;
            if (before.contains(observation.key())) continuing++;
            batch.add(new Object[]{company, id, observation.key(), observation.scope().circleId(), observation.scope().circleName(), observation.scope().zoneName(), observation.site(), row.oem(), row.technology(), row.resource(), row.alarmCode(), row.alarmText(), row.sourceSeverity(), rule == null ? "UNMAPPED" : rule.classification(), rule == null ? null : rule.included(), rule == null ? null : rule.serviceAffecting(), rule == null ? null : rule.siteDown(), timestamp(row.raisedAt()), encode(row)});
        }
        for (int start = 0; start < batch.size(); start += 250)
            jdbc.batchUpdate("INSERT INTO alarm_observations(company_id,run_id,identity_key,circle_id,circle_name,zone_name,site_code,oem,technology,resource_name,alarm_code,alarm_text,source_severity,classification,included,service_affecting,site_down,raised_at,row_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", batch.subList(start, Math.min(start + 250, batch.size())));
        var result = new Outcome(inputRows, observations.size(), inputRows - observations.size(), unknownRules, unknownSites, previousId,
                observations.size() - continuing, continuing, before.size() - continuing, "PROVISIONAL_RESOURCE_CODE_OR_TEXT_RAISED_AT_V1", false);
        if (jdbc.update("UPDATE alarm_ingestion_runs SET status='SUCCEEDED',result_json=?,message='Imported atomically; missing alarms are not automatically cleared',lease_token=NULL,lease_until=NULL,updated_at=UTC_TIMESTAMP(6) WHERE id=? AND lease_token=? AND status='RUNNING'", encode(result), id, token) != 1) throw conflict("Worker ownership changed.");
    }
    private void fail(long id, String token, String message, String result) {
        String safe = message == null ? "Import failed" : message.substring(0, Math.min(1900, message.length()));
        jdbc.update("UPDATE alarm_ingestion_runs SET status='FAILED',message=?,result_json=?,lease_token=NULL,lease_until=NULL,updated_at=UTC_TIMESTAMP(6) WHERE id=? AND lease_token=? AND status='RUNNING'", safe, result, id, token);
    }

    public List<Catalog> catalogs(long company) { access.require(company, false); return catalogsInternal(company); }
    private List<Catalog> catalogsInternal(long company) {
        return bounded("SELECT * FROM alarm_catalog_rules WHERE company_id=? ORDER BY id LIMIT 1001", company, 1000).stream().map(r -> new Catalog(number(r, "id"), string(r, "oem"), string(r, "technology"), string(r, "match_field"), string(r, "match_value"), string(r, "classification"), bool(r, "included"), bool(r, "service_affecting"), bool(r, "site_down"))).toList();
    }
    public Catalog addCatalog(long company, Catalog rule) {
        long actor = access.require(company, true);
        if (rule.included() == null || rule.serviceAffecting() == null || rule.siteDown() == null) throw invalid("Explicit inclusion, service-affecting and site-down flags are required.");
        String oem = required(rule.oem(), 16).toUpperCase(Locale.ROOT), rat = required(rule.technology(), 2).toUpperCase(Locale.ROOT);
        String field = required(rule.matchField(), 8).toUpperCase(Locale.ROOT), severity = required(rule.classification(), 16).toUpperCase(Locale.ROOT);
        if (!Set.of("NOKIA", "ERICSSON", "ZTE", "HUAWEI", "SAMSUNG").contains(oem) || !Set.of("2G", "4G").contains(rat)
                || !Set.of("CODE", "TEXT").contains(field) || !Set.of("CRITICAL", "MAJOR", "MINOR").contains(severity)) throw invalid("Use supported OEM/RAT, CODE or TEXT matching and CRITICAL/MAJOR/MINOR classification.");
        String value = required(rule.matchValue(), 255);
        return tx.execute(status -> {
            master.lockCompany(company);
            if (catalogsInternal(company).size() >= 1000) throw invalid("Catalogue limit reached.");
            long id = insert("INSERT INTO alarm_catalog_rules(company_id,oem,technology,match_field,match_value,classification,included,service_affecting,site_down,created_by) VALUES (?,?,?,?,?,?,?,?,?,?)", company, oem, rat, field, value, severity, rule.included(), rule.serviceAffecting(), rule.siteDown(), actor);
            return new Catalog(id, oem, rat, field, value, severity, rule.included(), rule.serviceAffecting(), rule.siteDown());
        });
    }
    public void removeCatalog(long company, long id) {
        access.require(company, true);
        if (jdbc.update("DELETE FROM alarm_catalog_rules WHERE company_id=? AND id=?", company, id) != 1) throw missing();
    }
    public List<Site> sites(long company, long feed) { access.require(company, false); master.feed(company, feed).orElseThrow(AlarmConfigurationException::missing); return sitesInternal(company, feed); }
    private List<Site> sitesInternal(long company, long feed) {
        var rows = jdbc.queryForList("SELECT * FROM alarm_site_objects WHERE company_id=? AND feed_id=? ORDER BY id LIMIT 20001", company, feed);
        if (rows.size() > 20000) throw invalid("Site mapping limit exceeded.");
        return rows.stream().map(r -> new Site(number(r, "id"), number(r, "circle_id"), string(r, "object_key"), string(r, "site_code"))).toList();
    }
    public Site addSite(long company, long feed, Site site) {
        long actor = access.require(company, true);
        return tx.execute(status -> {
            master.lockCompany(company);
            master.feed(company, feed).orElseThrow(AlarmConfigurationException::missing);
            master.circle(company, site.circleId()).orElseThrow(AlarmConfigurationException::missing);
            String object = required(site.objectKey(), 512), code = required(site.siteCode(), 128);
            if (sitesInternal(company, feed).size() >= 20000) throw invalid("Site mapping limit reached.");
            long id = insert("INSERT INTO alarm_site_objects(company_id,feed_id,circle_id,object_key,site_code,created_by) VALUES (?,?,?,?,?,?)", company, feed, site.circleId(), object, code, actor);
            return new Site(id, site.circleId(), object, code);
        });
    }
    public void removeSite(long company, long feed, long id) {
        access.require(company, true);
        if (jdbc.update("DELETE FROM alarm_site_objects WHERE company_id=? AND feed_id=? AND id=?", company, feed, id) != 1) throw missing();
    }
    public List<Map<String, Object>> observations(long company, long run, long after, int limit) {
        access.require(company, false); completed(company, run); limit(limit);
        if (after < 0) throw invalid("afterId must be nonnegative.");
        return jdbc.queryForList("SELECT id,identity_key,circle_id,circle_name,zone_name,site_code,oem,technology,resource_name,alarm_code,alarm_text,source_severity,classification,included,service_affecting,site_down,raised_at,JSON_UNQUOTE(JSON_EXTRACT(row_json,'$.supplementaryInformation')) supplementary_information FROM alarm_observations WHERE company_id=? AND run_id=? AND id>? ORDER BY id LIMIT ?", company, run, after, limit);
    }
    public Map<String, Object> history(long company, long feed, String key) {
        access.require(company, false); identity(key);
        var snapshots = jdbc.queryForList("SELECT r.id run_id,r.snapshot_at,o.classification,o.source_severity,o.site_code FROM alarm_observations o JOIN alarm_ingestion_runs r ON r.id=o.run_id AND r.company_id=o.company_id WHERE o.company_id=? AND r.feed_id=? AND o.identity_key=? ORDER BY r.snapshot_at DESC LIMIT 100", company, feed, key);
        if (snapshots.isEmpty()) throw missing();
        var events = jdbc.queryForList("SELECT id,action,note,created_by,created_at FROM alarm_operator_events WHERE company_id=? AND feed_id=? AND identity_key=? ORDER BY id DESC LIMIT 100", company, feed, key);
        return Map.of("snapshots", snapshots, "operatorEvents", events, "warning", "Operator actions are distinct from OSS state. Absence from a dump is not confirmed clearance.");
    }
    public void operatorEvent(long company, long feed, String key, String action, String note) {
        long actor = access.require(company, true); identity(key);
        action = required(action, 16).toUpperCase(Locale.ROOT);
        if (!Set.of("ACKNOWLEDGED", "RESOLVED", "NOTE").contains(action)) throw invalid("Action must be ACKNOWLEDGED, RESOLVED or NOTE.");
        history(company, feed, key);
        insert("INSERT INTO alarm_operator_events(company_id,feed_id,identity_key,action,note,created_by) VALUES (?,?,?,?,?,?)", company, feed, key, action, required(note, 2000), actor);
    }
    private static void identity(String value) { if (value == null || !value.matches("[a-f0-9]{64}")) throw invalid("Invalid alarm identity."); }
    public Map<String, Object> completed(long company, long id) {
        var run = runInternal(company, id);
        if (!"SUCCEEDED".equals(run.get("status"))) throw conflict("The import has not completed successfully.");
        return run;
    }
    public Map<String, Object> dashboard(long company, String asOf) {
        access.require(company, false); return dashboardInternal(company,asOf);
    }
    Map<String, Object> dashboardInternal(long company,String asOf) {
        Instant at = instant(asOf);
        var chosen = sourceSnapshots(company,at);
        List<Map<String, Object>> circles = List.of();
        if (!chosen.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(chosen.size(), "?"));
            List<Object> args = new ArrayList<>(); args.add(company); chosen.forEach(row -> args.add(row.get("id")));
            circles = jdbc.queryForList("SELECT circle_id,circle_name,zone_name,COUNT(*) observed_alarms,SUM(classification='UNMAPPED') unmapped_catalogue,SUM(included=TRUE) included_alarms,SUM(included=TRUE AND classification='CRITICAL') critical,SUM(included=TRUE AND classification='MAJOR') major,SUM(included=TRUE AND classification='MINOR') minor,COUNT(DISTINCT CASE WHEN included=TRUE AND site_down=TRUE THEN site_code END) down_sites,SUM(site_code IS NULL) unmapped_sites FROM alarm_observations WHERE company_id=? AND run_id IN (" + placeholders + ") GROUP BY circle_id,circle_name,zone_name ORDER BY zone_name,circle_name", args.toArray());
        }
        int active = jdbc.queryForObject("SELECT COUNT(*) FROM alarm_source_feed_profiles WHERE company_id=? AND active=TRUE", Integer.class, company);
        return Map.of("asOf", at.toString(), "sources", chosen, "circles", circles, "configuredActiveFeeds", active,
                "warning", "Each feed uses its latest imported snapshot at/before asOf; inspect source timestamps. Missing alarms are not confirmed cleared. MS2/LR and unresolved mappings are not inferred.");
    }
    List<Map<String,Object>> sourceSnapshots(long company,Instant at) {
        var chosen=jdbc.queryForList("SELECT r.id,r.feed_id,r.snapshot_at,r.result_json FROM alarm_ingestion_runs r JOIN alarm_source_feed_profiles f ON f.id=r.feed_id AND f.company_id=r.company_id AND f.active=TRUE WHERE r.company_id=? AND r.status='SUCCEEDED' AND r.snapshot_at<=? AND NOT EXISTS (SELECT 1 FROM alarm_ingestion_runs newer WHERE newer.company_id=r.company_id AND newer.feed_id=r.feed_id AND newer.status='SUCCEEDED' AND newer.snapshot_at<=? AND newer.snapshot_at>r.snapshot_at) ORDER BY r.feed_id LIMIT 201",company,timestamp(at),timestamp(at));
        if(chosen.size()>200) throw invalid("Dashboard supports at most 200 active source feeds.");
        return chosen;
    }

    public Path source(String key) {
        if (!key.matches("[0-9a-f-]{36}")) throw invalid("Invalid staged source identifier.");
        Path path = root.resolve(key + ".source").normalize();
        if (!path.getParent().equals(root) || Files.isSymbolicLink(root)) throw invalid("Invalid storage path.");
        return path;
    }
    public String encode(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("Cannot serialize alarm record", e); } }
    public static Instant instant(String value) { try { return OffsetDateTime.parse(value).toInstant(); } catch (Exception e) { throw invalid("An explicit ISO timestamp with offset is required."); } }
    public static Instant jdbcInstant(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
        if (value instanceof LocalDateTime date) return date.toInstant(ZoneOffset.UTC);
        throw new IllegalStateException("Unexpected database datetime type");
    }
    public static LocalDateTime timestamp(Instant instant) { return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC); }
    private static String ruleKey(String oem, String rat, String field, String value) { return oem + '\0' + rat + '\0' + field + '\0' + value; }
    public static String digest(byte[] data) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); } catch (Exception e) { throw new IllegalStateException(e); } }
    public static long number(Map<String, Object> row, String field) { return ((Number) row.get(field)).longValue(); }
    public static String string(Map<String, Object> row, String field) { return Objects.toString(row.get(field), ""); }
    public static boolean bool(Map<String, Object> row, String field) { Object value = row.get(field); return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() != 0; }
    public static String required(String value, int length) { if (value == null || value.isBlank() || value.length() > length || value.chars().anyMatch(Character::isISOControl)) throw invalid("A required field is blank, too long or contains control characters."); return value.trim(); }
    private static void limit(int value) { if (value < 1 || value > 200) throw invalid("Limit must be between 1 and 200."); }
    private List<Map<String, Object>> bounded(String query, long company, int max) { var rows = jdbc.queryForList(query, company); if (rows.size() > max) throw invalid("Configuration exceeds the supported per-feed processing budget."); return rows; }
    private long insert(String sql, Object... values) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> { var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS); for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]); return statement; }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
    record MemoryFile(String filename, byte[] data) implements MultipartFile {
        public String getName() { return "file"; } public String getOriginalFilename() { return filename; }
        public String getContentType() { return "application/octet-stream"; } public boolean isEmpty() { return data.length == 0; }
        public long getSize() { return data.length; } public byte[] getBytes() { return data; }
        public InputStream getInputStream() { return new ByteArrayInputStream(data); }
        public void transferTo(File file) throws IOException { Files.write(file.toPath(), data); }
    }
}

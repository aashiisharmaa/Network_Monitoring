package com.network.monitoring.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.network.monitoring.entity.KpiThresholdRule;
import com.network.monitoring.repository.KpiThresholdRuleRepository;
import org.springframework.stereotype.Service;
import java.security.DigestInputStream;
import java.util.*;

@Service
public class CommonReportModules {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final List<String> MODULES = List.of("thresholds", "alarmCandidates", "worstCells", "rca", "prediction");
    public record Options(List<String> modules, Map<String, Object> rcaConfig) {
        public Options validated() {
            List<String> requested = modules == null ? MODULES : modules;
            if (requested.stream().anyMatch(module -> !MODULES.contains(module)) || requested.stream().distinct().count() != requested.size()) {
                throw new IllegalArgumentException("Choose unique modules: thresholds, alarmCandidates, worstCells, rca, prediction.");
            }
            return new Options(requested.stream().sorted().toList(), rcaConfig == null ? Map.of() : JSON.convertValue(rcaConfig, new TypeReference<>() { }));
        }
    }
    private final KpiThresholdRuleRepository rules;
    private final KpiThresholdRuleService thresholds;
    private final KpiAlarmService alarms;
    private final RecommendationService rca;
    private final SitePredictionService prediction;

    public CommonReportModules(KpiThresholdRuleRepository rules, KpiThresholdRuleService thresholds,
                                KpiAlarmService alarms, RecommendationService rca, SitePredictionService prediction) {
        this.rules = rules; this.thresholds = thresholds; this.alarms = alarms; this.rca = rca; this.prediction = prediction;
    }

    public String engineFingerprint() {
        Map<String, Object> hashes = new TreeMap<>();
        for (Class<?> type : List.of(CommonReportModules.class, CommonReportRenderer.class, CustomerReportPdf.class, DailyKpiCalculator.class,
                KpiThresholdRuleService.class, KpiAlarmService.class, SitePredictionService.class, RecommendationService.class)) {
            var digest = RecommendationCacheKey.digest();
            try (var raw = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                if (raw == null) throw new IllegalStateException("Report engine class is unavailable.");
                try (var stream = new DigestInputStream(raw, digest)) { stream.transferTo(java.io.OutputStream.nullOutputStream()); }
                hashes.put(type.getName(), HexFormat.of().formatHex(digest.digest()));
            } catch (java.io.IOException ex) { throw new IllegalStateException("Cannot identify report engine.", ex); }
        }
        hashes.put("pdfbox", "3.0.8"); hashes.put("template", "customer-v2");
        return RecommendationCacheKey.build(hashes, "report-engine", Map.of());
    }

    public Map<String, Object> capture(DailyAnalysisService.ReportInput input, Options raw) {
        Options options = raw.validated();
        List<KpiThresholdRule> saved = rules.findAllByEnabledTrue().stream()
                .sorted(Comparator.comparing(rule -> rule.getId() == null ? 0L : rule.getId())).toList();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("options", options);
        config.put("thresholdRules", JSON.convertValue(saved, new TypeReference<List<Map<String, Object>>>() { }));
        config.put("alarmRules", JSON.convertValue(alarms.listRules(), new TypeReference<List<Map<String, Object>>>() { }));
        config.put("engineFingerprint", engineFingerprint());
        Map<String, Object> rcaConfig = new LinkedHashMap<>(options.rcaConfig());
        if (!rcaConfig.isEmpty()) {
            String mode = "single".equals(rcaConfig.get("dateMode")) ? "single" : "pre_post";
            rcaConfig.put("dateMode", mode);
            rcaConfig.put("startDate", input.date().toString()); rcaConfig.put("endDate", input.date().toString());
            rcaConfig.put("preStartDate", input.date().minusDays(7).toString()); rcaConfig.put("preEndDate", input.date().minusDays(1).toString());
            rcaConfig.put("postStartDate", input.date().toString()); rcaConfig.put("postEndDate", input.date().toString());
            rcaConfig.put("inputRowLimit", 50000); rcaConfig.put("responseRowLimit", 200);
        }
        config.put("rcaConfig", rcaConfig);
        config.put("rcaEngineKey", rcaConfig.isEmpty() ? "NOT_REQUESTED" : rca.snapshotCacheKey(rcaConfig, input.sourceFingerprint()).orElse("UNAVAILABLE"));
        // Detach the captured rules and dates before asynchronous execution.
        return JSON.convertValue(config, new TypeReference<>() { });
    }

    public Map<String, Object> run(DailyAnalysisService.ReportInput input, KpiCacheService.Snapshot source,
                                    Map<String, Object> config, int maxRecords, int detailLimit, int maxPredictionCells) {
        if (!engineFingerprint().equals(config.get("engineFingerprint"))) throw new IllegalStateException("Report engine changed. Create a new report request.");
        List<Map<String, Object>> records = records(source.payload());
        List<Map<String, Object>> current = new ArrayList<>(), previous = new ArrayList<>(), window = new ArrayList<>();
        for (Map<String, Object> record : records) {
            java.time.LocalDate date = java.time.LocalDate.parse(record.get("date").toString());
            if (date.equals(input.date())) current.add(record);
            if (date.equals(input.date().minusDays(1))) previous.add(record);
            if (!date.isBefore(input.date().minusDays(7)) && !date.isAfter(input.date())) window.add(record);
        }
        Options options = JSON.convertValue(config.get("options"), Options.class).validated();
        List<KpiThresholdRule> capturedRules = JSON.convertValue(config.get("thresholdRules"), new TypeReference<>() { });
        List<Map<String, Object>> alarmRules = JSON.convertValue(config.get("alarmRules"), new TypeReference<>() { });
        alarmRules = alarmRules.stream().filter(rule -> Boolean.TRUE.equals(rule.get("enabled"))).toList();
        final List<Map<String, Object>> capturedAlarmRules = alarmRules;
        Map<String, Object> results = new LinkedHashMap<>();
        for (String module : MODULES) {
            if (!options.modules().contains(module)) { results.put(module, outcome("NOT_REQUESTED", "Not requested for this report.", Map.of())); continue; }
            if (module.equals("worstCells")) { results.put(module, outcome("PENDING", "Ranked from persisted daily KPI aggregates during rendering.", Map.of())); continue; }
            if (current.isEmpty()) { results.put(module, outcome("SKIPPED", "No current-day KPI observations.", Map.of())); continue; }
            if (current.size() > maxRecords) { results.put(module, outcome("SKIPPED", "Current-day observations exceed the configured report module limit; no sampling was applied.", Map.of())); continue; }
            try {
                switch (module) {
                    case "thresholds" -> {
                        if (capturedRules.isEmpty()) results.put(module, outcome("SKIPPED", "No enabled threshold rules were configured.", Map.of()));
                        else {
                            var result = thresholds.evaluateSnapshot(input.fileId(), current, capturedRules);
                            boolean uncovered = ((Number) result.getOrDefault("valuesWithoutRule", 0)).intValue() > 0 || ((Number) result.getOrDefault("invalidValues", 0)).intValue() > 0 || ((Number) result.getOrDefault("evaluatedValues", 0)).intValue() == 0;
                            results.put(module, outcome(uncovered ? "LIMITED" : "COMPLETED", uncovered ? "Some KPI values have no configured threshold rule." : "Captured threshold rules evaluated on current-day observations.", result));
                        }
                    }
                    case "alarmCandidates" -> {
                        if (capturedRules.isEmpty()) results.put(module, outcome("SKIPPED", "Threshold rules are required for alarm candidates.", Map.of()));
                        else {
                            var today = alarms.evaluateSnapshot(input.fileId(), current, capturedRules, capturedAlarmRules, detailLimit);
                            var yesterday = previous.size() > maxRecords ? Map.<String, Object>of("status", "SKIPPED", "reason", "Previous-day module input exceeds the configured limit.")
                                    : alarms.evaluateSnapshot(input.fileId(), previous, capturedRules, capturedAlarmRules, detailLimit);
                            results.put(module, outcome("LIMITED", "Read-only detected candidates; live acknowledgement/resolution history is not changed or inferred. Candidate detail lists are bounded.",
                                    Map.of("currentDay", today, "previousDay", yesterday, "previousDayDataPresent", !previous.isEmpty())));
                        }
                    }
                    case "prediction" -> {
                        long cells = current.stream().map(row -> row.get("site") + "|" + row.get("cellName")).distinct().count();
                        if (cells > maxPredictionCells) results.put(module, outcome("SKIPPED", "KPI-only prediction exceeds the configured cell limit; no cells were sampled.", Map.of()));
                        else results.put(module, outcome("LIMITED", "KPI-only recommendations; pinned site-master/TA/alarm context is unavailable. This is not future forecasting.", prediction.predictKpiSnapshot(current, detailLimit)));
                    }
                    case "rca" -> {
                        Map<String, Object> body = JSON.convertValue(config.get("rcaConfig"), new TypeReference<>() { });
                        if (body.isEmpty()) results.put(module, outcome("SKIPPED", "RCA column/KPI mapping is not configured.", Map.of()));
                        else if (window.size() > 50000 || window.size() > maxRecords) results.put(module, outcome("SKIPPED", "RCA window exceeds its supported input limit; no source rows were sampled.", Map.of()));
                        else {
                            String expected = config.get("rcaEngineKey").toString();
                            if (!rca.snapshotCacheKey(body, input.sourceFingerprint()).filter(expected::equals).isPresent()) throw new IllegalStateException("RCA engine/presets changed or are unavailable. Create a new report request.");
                            List<Map<String, Object>> rcaRows = "single".equals(body.get("dateMode")) ? current : window;
                            var result = rca.runSnapshot(input.fileId(), new KpiCacheService.Snapshot(Map.of("records", rcaRows), source.fingerprint()), body);
                            try {
                                if (!expected.equals(result.inputCacheKey) || !rca.snapshotCacheKey(body, input.sourceFingerprint()).filter(expected::equals).isPresent()) throw new IllegalStateException("RCA engine changed during this report.");
                                results.put(module, outcome("LIMITED", "RCA ran on the pinned report window; detailed response is limited to 200 anomaly rows. Complete comparisons are included separately.", result.payload));
                            } finally { rca.cleanup(result.workDir); }
                        }
                    }
                }
            } catch (Exception ex) {
                String reason = ex.getMessage() == null ? "Module execution failed." : ex.getMessage();
                results.put(module, outcome("FAILED", reason.substring(0, Math.min(reason.length(), 2000)), Map.of()));
            }
        }
        return results;
    }

    @SuppressWarnings("unchecked") static List<Map<String, Object>> records(Map<String, Object> payload) {
        Object records = payload.get("records");
        if (!(records instanceof List<?> list) || list.stream().anyMatch(row -> !(row instanceof Map<?, ?>))) throw new IllegalArgumentException("Report input must contain KPI observations.");
        return (List<Map<String, Object>>) records;
    }
    static Map<String, Object> outcome(String status, String reason, Map<String, Object> data) { return Map.of("status", status, "reason", reason, "data", data); }
}

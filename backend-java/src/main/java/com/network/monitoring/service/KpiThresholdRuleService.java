package com.network.monitoring.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.monitoring.dto.request.KpiThresholdRuleRequest;
import com.network.monitoring.entity.KpiThresholdRule;
import com.network.monitoring.exception.ResourceNotFoundException;
import com.network.monitoring.repository.KpiThresholdRuleRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class KpiThresholdRuleService {

    private static final String LOWER_IS_BAD = "LOWER_IS_BAD";
    private static final String HIGHER_IS_BAD = "HIGHER_IS_BAD";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, List<String>> KPI_ALIAS_FAMILIES = Map.ofEntries(
            Map.entry("availability", List.of("availability", "networkavailability", "radionwavailability", "rna", "4grna")),
            Map.entry("dcr", List.of("dcr", "dropcallrate", "dropcall", "psdropcallrate", "voltedcr")),
            Map.entry("cssr", List.of("cssr", "setupsuccess", "setupsuccessrate", "erabsetupsuccess", "rrcsetupsuccess", "callsetupsuccess")),
            Map.entry("prb", List.of("prb", "prbutilisation", "prbutilization", "useddlprbs", "usedulprbs")),
            Map.entry("throughput", List.of("throughput", "userthroughput", "dluserthroughput", "uluserthroughput", "tput")),
            Map.entry("packetloss", List.of("packetloss", "packetlossdl", "packetlossul", "lossdl", "lossul")),
            Map.entry("handover", List.of("hosr", "handover", "interfhosr", "intrafhosr", "interfreqhosr", "intrafreqhosr")),
            Map.entry("sinr", List.of("sinr", "puschsinr")),
            Map.entry("cqi", List.of("cqi", "averagecqi")),
            Map.entry("traffic", List.of("traffic", "volume", "datavolume", "4gdatavolume", "voltetraffic")),
            Map.entry("rssi", List.of("rssi", "avgssi", "pucchrssi", "puschrssi", "ni", "noiseinterference")),
            Map.entry("distance", List.of("distance", "uedistance", "averageuedistance"))
    );
    private static final Set<String> TOKEN_STOP_WORDS = Set.of(
            "mv", "kpi", "avg", "average", "rate", "ratio", "percent", "percentage", "std",
            "maps", "mb", "gb", "kbps", "mbps", "dl", "ul", "nw", "ue", "eric", "cbbh", "cdbh"
    );

    private final KpiThresholdRuleRepository repository;
    private final KpiDynamicDataService kpiDynamicDataService;
    private final JdbcTemplate jdbcTemplate;

    public KpiThresholdRuleService(KpiThresholdRuleRepository repository,
                                   KpiDynamicDataService kpiDynamicDataService,
                                   JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.kpiDynamicDataService = kpiDynamicDataService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void ensureTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `kpi_threshold_rules` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT,
                  `metricName` VARCHAR(191) NOT NULL,
                  `technology` VARCHAR(64) NULL,
                  `direction` VARCHAR(32) NOT NULL DEFAULT 'LOWER_IS_BAD',
                  `criticalValue` DOUBLE NULL,
                  `majorValue` DOUBLE NULL,
                  `minorValue` DOUBLE NULL,
                  `warningValue` DOUBLE NULL,
                  `enabled` BIT(1) NOT NULL DEFAULT b'1',
                  `createdAt` DATETIME NULL,
                  `updatedAt` DATETIME NULL,
                  PRIMARY KEY (`id`),
                  INDEX `idx_kpi_threshold_rules_metric` (`metricName`, `technology`, `enabled`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `kpi_threshold_results` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT,
                  `fileId` BIGINT NOT NULL,
                  `totalRows` INT NOT NULL DEFAULT 0,
                  `activeRules` INT NOT NULL DEFAULT 0,
                  `evaluatedValues` INT NOT NULL DEFAULT 0,
                  `matchedRules` INT NOT NULL DEFAULT 0,
                  `valuesWithoutRule` INT NOT NULL DEFAULT 0,
                  `criticalCount` INT NOT NULL DEFAULT 0,
                  `majorCount` INT NOT NULL DEFAULT 0,
                  `minorCount` INT NOT NULL DEFAULT 0,
                  `warningCount` INT NOT NULL DEFAULT 0,
                  `normalCount` INT NOT NULL DEFAULT 0,
                  `hasBreaches` BIT(1) NOT NULL DEFAULT b'0',
                  `summaryJson` LONGTEXT NULL,
                  `createdAt` DATETIME NULL,
                  `updatedAt` DATETIME NULL,
                  PRIMARY KEY (`id`),
                  UNIQUE INDEX `uk_kpi_threshold_results_file` (`fileId`),
                  INDEX `idx_kpi_threshold_results_updated` (`updatedAt`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRules() {
        return repository.findAll().stream()
                .map(this::toPayload)
                .toList();
    }

    @Transactional
    public Map<String, Object> createRule(KpiThresholdRuleRequest request) {
        KpiThresholdRule rule = new KpiThresholdRule();
        applyRequest(rule, request, true);
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());
        return toPayload(repository.save(rule));
    }

    @Transactional
    public Map<String, Object> updateRule(Long id, KpiThresholdRuleRequest request) {
        KpiThresholdRule rule = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Threshold rule not found for id " + id + "."));
        applyRequest(rule, request, false);
        rule.setUpdatedAt(LocalDateTime.now());
        return toPayload(repository.save(rule));
    }

    @Transactional
    public void deleteRule(Long id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("Threshold rule not found for id " + id + ".");
        }
        repository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> evaluate(String metricName, Double value, String technology) {
        if (metricName == null || metricName.isBlank()) {
            throw new IllegalArgumentException("metricName is required.");
        }
        if (value == null) {
            throw new IllegalArgumentException("value is required.");
        }

        KpiThresholdRule rule = findBestRule(metricName, technology);
        String severity = rule == null ? "NORMAL" : calculateSeverity(rule, value);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("metricName", metricName);
        response.put("technology", technology);
        response.put("value", value);
        response.put("severity", severity);
        response.put("rule", rule == null ? null : toPayload(rule));
        return response;
    }

    public Map<String, Object> evaluateSnapshot(Long fileId, List<Map<String, Object>> records, List<KpiThresholdRule> rules) {
        return evaluateRecords(fileId, records, rules);
    }

    public Map<String, Object> evaluateSnapshotValue(String metricName, Double value, String technology, List<KpiThresholdRule> rules) {
        KpiThresholdRule rule = findBestRule(metricName, technology, rules);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("severity", rule == null ? "UNKNOWN" : calculateSeverity(rule, value));
        result.put("rule", rule == null ? null : toPayload(rule));
        return result;
    }

    public KpiThresholdRule snapshotRule(String metricName, String technology, List<KpiThresholdRule> rules) {
        return findBestRule(metricName, technology, rules);
    }

    @Transactional
    public Map<String, Object> evaluateFile(Long fileId) {
        if (fileId == null) {
            throw new IllegalArgumentException("fileId is required.");
        }

        List<Map<String, Object>> records = kpiDynamicDataService.findRecordsByFileId(fileId);
        Map<String, Object> coverage = ensureRulesForRecords(records);
        List<KpiThresholdRule> rules = repository.findAllByEnabledTrue();
        Map<String, Object> summary = evaluateRecords(fileId, records, rules);
        summary.put("ruleCoverage", coverage);
        saveEvaluationResult(fileId, summary);
        return summary;
    }

    @Transactional
    public Map<String, Object> evaluateUploadedRecords(Long fileId, List<Map<String, Object>> records) {
        Map<String, Object> coverage = ensureRulesForRecords(records);
        Map<String, Object> summary = evaluateRecords(fileId, records, repository.findAllByEnabledTrue());
        summary.put("ruleCoverage", coverage);
        return summary;
    }

    @Transactional
    public Map<String, Object> ensureRulesForFile(Long fileId) {
        if (fileId == null) {
            throw new IllegalArgumentException("fileId is required.");
        }
        return ensureRulesForRecords(kpiDynamicDataService.findRecordsByFileId(fileId));
    }

    @Transactional
    public void saveEvaluationResult(Long fileId, Map<String, Object> summary) {
        if (fileId == null || summary == null) {
            return;
        }
        Map<String, Integer> counts = integerMap(summary.get("severityCounts"));
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO `kpi_threshold_results`
                (`fileId`, `totalRows`, `activeRules`, `evaluatedValues`, `matchedRules`, `valuesWithoutRule`,
                 `criticalCount`, `majorCount`, `minorCount`, `warningCount`, `normalCount`, `hasBreaches`,
                 `summaryJson`, `createdAt`, `updatedAt`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  `totalRows` = VALUES(`totalRows`),
                  `activeRules` = VALUES(`activeRules`),
                  `evaluatedValues` = VALUES(`evaluatedValues`),
                  `matchedRules` = VALUES(`matchedRules`),
                  `valuesWithoutRule` = VALUES(`valuesWithoutRule`),
                  `criticalCount` = VALUES(`criticalCount`),
                  `majorCount` = VALUES(`majorCount`),
                  `minorCount` = VALUES(`minorCount`),
                  `warningCount` = VALUES(`warningCount`),
                  `normalCount` = VALUES(`normalCount`),
                  `hasBreaches` = VALUES(`hasBreaches`),
                  `summaryJson` = VALUES(`summaryJson`),
                  `updatedAt` = VALUES(`updatedAt`)
                """,
                fileId,
                intValue(summary.get("totalRows")),
                intValue(summary.get("activeRules")),
                intValue(summary.get("evaluatedValues")),
                intValue(summary.get("matchedRules")),
                intValue(summary.get("valuesWithoutRule")),
                counts.getOrDefault("CRITICAL", 0),
                counts.getOrDefault("MAJOR", 0),
                counts.getOrDefault("MINOR", 0),
                counts.getOrDefault("WARNING", 0),
                counts.getOrDefault("NORMAL", 0),
                Boolean.TRUE.equals(summary.get("hasBreaches")),
                writeJson(summary),
                now,
                now
        );
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listStoredResults(Integer limit) {
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        return jdbcTemplate.query("""
                SELECT r.*, h.`fileName`, h.`uploadedBy`, h.`createdAt` AS `uploadedAt`
                FROM `kpi_threshold_results` r
                LEFT JOIN `uploadhistory` h ON h.`id` = r.`fileId`
                ORDER BY r.`updatedAt` DESC
                LIMIT ?
                """, (rs, rowNum) -> {
            Map<String, Object> result = baseResultPayload(
                    rs.getLong("fileId"),
                    rs.getString("fileName"),
                    rs.getString("uploadedBy"),
                    rs.getObject("uploadedAt"),
                    rs.getObject("updatedAt"),
                    rs.getInt("totalRows"),
                    rs.getInt("activeRules"),
                    rs.getInt("evaluatedValues"),
                    rs.getInt("matchedRules"),
                    rs.getInt("valuesWithoutRule"),
                    rs.getBoolean("hasBreaches"),
                    rs.getInt("criticalCount"),
                    rs.getInt("majorCount"),
                    rs.getInt("minorCount"),
                    rs.getInt("warningCount"),
                    rs.getInt("normalCount")
            );
            result.put("summary", readJsonMap(rs.getString("summaryJson")));
            return result;
        }, safeLimit);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getStoredResult(Long fileId) {
        if (fileId == null) {
            throw new IllegalArgumentException("fileId is required.");
        }
        List<Map<String, Object>> results = jdbcTemplate.query("""
                SELECT r.*, h.`fileName`, h.`uploadedBy`, h.`createdAt` AS `uploadedAt`
                FROM `kpi_threshold_results` r
                LEFT JOIN `uploadhistory` h ON h.`id` = r.`fileId`
                WHERE r.`fileId` = ?
                """, (rs, rowNum) -> {
            Map<String, Object> result = baseResultPayload(
                    rs.getLong("fileId"),
                    rs.getString("fileName"),
                    rs.getString("uploadedBy"),
                    rs.getObject("uploadedAt"),
                    rs.getObject("updatedAt"),
                    rs.getInt("totalRows"),
                    rs.getInt("activeRules"),
                    rs.getInt("evaluatedValues"),
                    rs.getInt("matchedRules"),
                    rs.getInt("valuesWithoutRule"),
                    rs.getBoolean("hasBreaches"),
                    rs.getInt("criticalCount"),
                    rs.getInt("majorCount"),
                    rs.getInt("minorCount"),
                    rs.getInt("warningCount"),
                    rs.getInt("normalCount")
            );
            result.put("summary", readJsonMap(rs.getString("summaryJson")));
            return result;
        }, fileId);
        return results.isEmpty() ? null : results.get(0);
    }

    private Map<String, Object> evaluateRecords(Long fileId, List<Map<String, Object>> records, List<KpiThresholdRule> rules) {
        List<Map<String, Object>> safeRecords = records == null ? List.of() : records;
        Map<String, Integer> severityCounts = createSeverityCounts();
        Map<String, Map<String, Object>> metricSummaries = new LinkedHashMap<>();
        Map<String, Optional<KpiThresholdRule>> ruleMatchCache = new LinkedHashMap<>();
        int evaluatedValues = 0;
        int matchedRules = 0;
        int valuesWithoutRule = 0;
        int invalidValues = 0;

        for (Map<String, Object> record : safeRecords) {
            String technology = technologyFrom(record);
            Map<String, Object> metrics = objectMap(record.get("metrics"));
            for (Map.Entry<String, Object> metric : metrics.entrySet()) {
                Double value = parseNumber(metric.getValue());
                if (value == null || !Double.isFinite(value)) {
                    if (metric.getValue() != null) invalidValues++;
                    continue;
                }

                evaluatedValues++;
                String ruleCacheKey = normalize(technology) + "|" + normalize(metric.getKey());
                KpiThresholdRule rule = ruleMatchCache.computeIfAbsent(
                        ruleCacheKey,
                        ignored -> Optional.ofNullable(findBestRule(metric.getKey(), technology, rules))
                ).orElse(null);
                if (rule == null) {
                    valuesWithoutRule++;
                    continue;
                }

                matchedRules++;
                String severity = calculateSeverity(rule, value);
                incrementSeverity(severityCounts, severity);
                incrementMetricSummary(metricSummaries, metric.getKey(), severity);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileId", fileId);
        response.put("totalRows", safeRecords.size());
        response.put("activeRules", rules.size());
        response.put("evaluatedValues", evaluatedValues);
        response.put("matchedRules", matchedRules);
        response.put("valuesWithoutRule", valuesWithoutRule);
        response.put("invalidValues", invalidValues);
        response.put("severityCounts", severityCounts);
        response.put("hasBreaches", hasBreaches(severityCounts));
        response.put("metrics", metricSummaries.values().stream().toList());
        response.put("generatedAt", LocalDateTime.now());
        return response;
    }

    private Map<String, Object> ensureRulesForRecords(List<Map<String, Object>> records) {
        List<Map<String, Object>> safeRecords = records == null ? List.of() : records;
        List<KpiThresholdRule> existingRules = repository.findAllByEnabledTrue();
        Map<String, List<Double>> valuesByMetric = new LinkedHashMap<>();

        for (Map<String, Object> record : safeRecords) {
            Map<String, Object> metrics = objectMap(record.get("metrics"));
            for (Map.Entry<String, Object> metric : metrics.entrySet()) {
                Double value = parseNumber(metric.getValue());
                if (value == null || !Double.isFinite(value)) {
                    continue;
                }
                valuesByMetric.computeIfAbsent(metric.getKey(), ignored -> new ArrayList<>()).add(value);
            }
        }

        List<KpiThresholdRule> created = new ArrayList<>();
        for (Map.Entry<String, List<Double>> entry : valuesByMetric.entrySet()) {
            String metricName = entry.getKey();
            if (metricName == null || metricName.isBlank()) {
                continue;
            }
            if (findBestRule(metricName, null, existingRules) != null) {
                continue;
            }

            KpiThresholdRule rule = buildAutoRule(metricName, entry.getValue());
            created.add(rule);
            existingRules.add(rule);
        }

        if (!created.isEmpty()) {
            repository.saveAll(created);
        }

        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("totalNumericMetrics", valuesByMetric.size());
        coverage.put("createdRules", created.size());
        coverage.put("existingRules", Math.max(0, valuesByMetric.size() - created.size()));
        coverage.put("createdMetricNames", created.stream().map(KpiThresholdRule::getMetricName).toList());
        return coverage;
    }

    private KpiThresholdRule buildAutoRule(String metricName, List<Double> values) {
        List<Double> sorted = values == null ? List.of() : values.stream()
                .filter(value -> value != null && Double.isFinite(value))
                .sorted()
                .toList();
        String direction = inferDirection(metricName);

        KpiThresholdRule rule = new KpiThresholdRule();
        rule.setMetricName(metricName.trim());
        rule.setTechnology(null);
        rule.setDirection(direction);
        rule.setEnabled(true);
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());

        if (sorted.isEmpty()) {
            applyFallbackThresholds(rule, direction);
            return rule;
        }

        if (LOWER_IS_BAD.equals(direction)) {
            rule.setCriticalValue(round(percentile(sorted, 0.05)));
            rule.setMajorValue(round(percentile(sorted, 0.10)));
            rule.setMinorValue(round(percentile(sorted, 0.20)));
            rule.setWarningValue(round(percentile(sorted, 0.30)));
        } else {
            rule.setCriticalValue(round(percentile(sorted, 0.95)));
            rule.setMajorValue(round(percentile(sorted, 0.90)));
            rule.setMinorValue(round(percentile(sorted, 0.80)));
            rule.setWarningValue(round(percentile(sorted, 0.70)));
        }
        return rule;
    }

    private void applyFallbackThresholds(KpiThresholdRule rule, String direction) {
        if (LOWER_IS_BAD.equals(direction)) {
            rule.setCriticalValue(70.0);
            rule.setMajorValue(80.0);
            rule.setMinorValue(85.0);
            rule.setWarningValue(90.0);
        } else {
            rule.setCriticalValue(95.0);
            rule.setMajorValue(90.0);
            rule.setMinorValue(80.0);
            rule.setWarningValue(70.0);
        }
    }

    private String inferDirection(String metricName) {
        String normalized = normalize(metricName);
        if (containsAny(normalized, List.of(
                "dcr", "drop", "fail", "failure", "block", "blocked", "congestion", "util", "usage",
                "latency", "delay", "packetloss", "loss", "rssi", "interference", "noise", "distance"
        ))) {
            return HIGHER_IS_BAD;
        }
        return LOWER_IS_BAD;
    }

    private boolean containsAny(String text, List<String> needles) {
        for (String needle : needles) {
            if (text.contains(normalize(needle))) {
                return true;
            }
        }
        return false;
    }

    private double percentile(List<Double> sortedValues, double percentile) {
        if (sortedValues == null || sortedValues.isEmpty()) {
            return 0.0;
        }
        int index = (int) Math.round((sortedValues.size() - 1) * Math.max(0.0, Math.min(1.0, percentile)));
        return sortedValues.get(Math.max(0, Math.min(sortedValues.size() - 1, index)));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public String calculateSeverity(KpiThresholdRule rule, Double value) {
        if (rule == null || value == null || !Boolean.TRUE.equals(rule.getEnabled())) {
            return "NORMAL";
        }
        boolean lowerIsBad = LOWER_IS_BAD.equals(rule.getDirection());
        if (matches(value, rule.getCriticalValue(), lowerIsBad)) return "CRITICAL";
        if (matches(value, rule.getMajorValue(), lowerIsBad)) return "MAJOR";
        if (matches(value, rule.getMinorValue(), lowerIsBad)) return "MINOR";
        if (matches(value, rule.getWarningValue(), lowerIsBad)) return "WARNING";
        return "NORMAL";
    }

    private KpiThresholdRule findBestRule(String metricName, String technology) {
        return findBestRule(metricName, technology, repository.findAllByEnabledTrue());
    }

    private KpiThresholdRule findBestRule(String metricName, String technology, List<KpiThresholdRule> rules) {
        String techKey = normalize(technology);

        return rules.stream()
                .map(rule -> Map.entry(rule, ruleScore(rule, metricName, techKey)))
                .filter(entry -> entry.getValue() > 0)
                .max((left, right) -> Integer.compare(left.getValue(), right.getValue()))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private int ruleScore(KpiThresholdRule rule, String metricName, String techKey) {
        int score = metricScore(rule.getMetricName(), metricName);
        if (score <= 0) {
            return 0;
        }

        String ruleTechKey = normalize(rule.getTechnology());
        if (!techKey.isBlank() && ruleTechKey.equals(techKey)) {
            score += 30;
        } else if (ruleTechKey.isBlank()) {
            score += 20;
        } else {
            score += 5;
        }
        return score;
    }

    private int metricScore(String ruleMetricName, String uploadedMetricName) {
        String ruleMetricKey = normalize(ruleMetricName);
        String uploadedMetricKey = normalize(uploadedMetricName);
        if (ruleMetricKey.isBlank() || uploadedMetricKey.isBlank()) {
            return 0;
        }

        if (ruleMetricKey.equals(uploadedMetricKey)) {
            return 120;
        }
        if (uploadedMetricKey.contains(ruleMetricKey) || ruleMetricKey.contains(uploadedMetricKey)) {
            return 95;
        }
        if (sameAliasFamily(ruleMetricKey, uploadedMetricKey)) {
            return 85;
        }

        Set<String> ruleTokens = metricTokens(ruleMetricName);
        Set<String> uploadedTokens = metricTokens(uploadedMetricName);
        if (ruleTokens.isEmpty() || uploadedTokens.isEmpty()) {
            return 0;
        }

        long overlap = ruleTokens.stream().filter(uploadedTokens::contains).count();
        if (overlap == ruleTokens.size()) {
            return 80;
        }
        double overlapRatio = (double) overlap / ruleTokens.size();
        return overlapRatio >= 0.6 ? 55 : 0;
    }

    private boolean sameAliasFamily(String ruleMetricKey, String uploadedMetricKey) {
        return KPI_ALIAS_FAMILIES.values().stream()
                .anyMatch(aliases -> hasAlias(ruleMetricKey, aliases) && hasAlias(uploadedMetricKey, aliases));
    }

    private boolean hasAlias(String metricKey, List<String> aliases) {
        return aliases.stream()
                .map(this::normalize)
                .anyMatch(alias -> metricKey.equals(alias) || metricKey.contains(alias) || alias.contains(metricKey));
    }

    private Set<String> metricTokens(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : splitMetricWords(value)) {
            String normalized = normalize(token);
            if (!normalized.isBlank() && !TOKEN_STOP_WORDS.contains(normalized)) {
                tokens.add(normalized);
            }
        }
        return tokens;
    }

    private List<String> splitMetricWords(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String spaced = value.replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9]+", " ");
        List<String> words = new ArrayList<>();
        for (String word : spaced.trim().split("\\s+")) {
            if (!word.isBlank()) {
                words.add(word);
            }
        }
        return words;
    }

    private void applyRequest(KpiThresholdRule rule, KpiThresholdRuleRequest request, boolean creating) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (creating || request.getMetricName() != null) {
            if (request.getMetricName() == null || request.getMetricName().isBlank()) {
                throw new IllegalArgumentException("metricName is required.");
            }
            rule.setMetricName(request.getMetricName().trim());
        }
        if (request.getTechnology() != null) {
            rule.setTechnology(request.getTechnology().isBlank() ? null : request.getTechnology().trim());
        }
        if (creating || request.getDirection() != null) {
            rule.setDirection(normalizeDirection(request.getDirection()));
        }
        if (request.getCriticalValue() != null) rule.setCriticalValue(request.getCriticalValue());
        if (request.getMajorValue() != null) rule.setMajorValue(request.getMajorValue());
        if (request.getMinorValue() != null) rule.setMinorValue(request.getMinorValue());
        if (request.getWarningValue() != null) rule.setWarningValue(request.getWarningValue());
        if (request.getEnabled() != null) rule.setEnabled(request.getEnabled());
        if (rule.getEnabled() == null) rule.setEnabled(true);
    }

    private String normalizeDirection(String direction) {
        if (direction == null || direction.isBlank()) {
            return LOWER_IS_BAD;
        }
        String normalized = direction.trim().toUpperCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
        if (!Objects.equals(normalized, LOWER_IS_BAD) && !Objects.equals(normalized, HIGHER_IS_BAD)) {
            throw new IllegalArgumentException("direction must be LOWER_IS_BAD or HIGHER_IS_BAD.");
        }
        return normalized;
    }

    private boolean matches(double value, Double threshold, boolean lowerIsBad) {
        if (threshold == null) {
            return false;
        }
        return lowerIsBad ? value <= threshold : value >= threshold;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        return Map.of();
    }

    private String technologyFrom(Map<String, Object> record) {
        String topLevel = stringValue(record.get("tech"));
        if (!topLevel.isBlank()) {
            return topLevel;
        }
        Map<String, Object> dimensions = objectMap(record.get("dimensions"));
        for (String key : List.of("tech", "technology", "rat")) {
            String value = stringValue(dimensions.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private Double parseNumber(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = stringValue(value)
                .replace(",", "")
                .replace("%", "")
                .trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Map<String, Integer> createSeverityCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("CRITICAL", 0);
        counts.put("MAJOR", 0);
        counts.put("MINOR", 0);
        counts.put("WARNING", 0);
        counts.put("NORMAL", 0);
        return counts;
    }

    private void incrementSeverity(Map<String, Integer> counts, String severity) {
        counts.compute(severity == null ? "NORMAL" : severity, (key, value) -> value == null ? 1 : value + 1);
    }

    private void incrementMetricSummary(Map<String, Map<String, Object>> metricSummaries, String metricName, String severity) {
        Map<String, Object> summary = metricSummaries.computeIfAbsent(metricName, ignored -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("metricName", metricName);
            item.put("severityCounts", createSeverityCounts());
            item.put("evaluatedValues", 0);
            return item;
        });
        @SuppressWarnings("unchecked")
        Map<String, Integer> counts = (Map<String, Integer>) summary.get("severityCounts");
        incrementSeverity(counts, severity);
        summary.put("evaluatedValues", ((Number) summary.get("evaluatedValues")).intValue() + 1);
    }

    private boolean hasBreaches(Map<String, Integer> severityCounts) {
        return severityCounts.getOrDefault("CRITICAL", 0) > 0
                || severityCounts.getOrDefault("MAJOR", 0) > 0
                || severityCounts.getOrDefault("MINOR", 0) > 0
                || severityCounts.getOrDefault("WARNING", 0) > 0;
    }

    private Map<String, Object> baseResultPayload(Long fileId,
                                                  String fileName,
                                                  String uploadedBy,
                                                  Object uploadedAt,
                                                  Object evaluatedAt,
                                                  int totalRows,
                                                  int activeRules,
                                                  int evaluatedValues,
                                                  int matchedRules,
                                                  int valuesWithoutRule,
                                                  boolean hasBreaches,
                                                  int critical,
                                                  int major,
                                                  int minor,
                                                  int warning,
                                                  int normal) {
        Map<String, Integer> severityCounts = createSeverityCounts();
        severityCounts.put("CRITICAL", critical);
        severityCounts.put("MAJOR", major);
        severityCounts.put("MINOR", minor);
        severityCounts.put("WARNING", warning);
        severityCounts.put("NORMAL", normal);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fileId", fileId);
        payload.put("fileName", fileName);
        payload.put("uploadedBy", uploadedBy);
        payload.put("uploadedAt", uploadedAt);
        payload.put("evaluatedAt", evaluatedAt);
        payload.put("totalRows", totalRows);
        payload.put("activeRules", activeRules);
        payload.put("evaluatedValues", evaluatedValues);
        payload.put("matchedRules", matchedRules);
        payload.put("valuesWithoutRule", valuesWithoutRule);
        payload.put("hasBreaches", hasBreaches);
        payload.put("severityCounts", severityCounts);
        return payload;
    }

    private Map<String, Integer> integerMap(Object value) {
        Map<String, Integer> result = createSeverityCounts();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), intValue(entry.getValue()));
                }
            }
        }
        return result;
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private Map<String, Object> readJsonMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(value, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private Map<String, Object> toPayload(KpiThresholdRule rule) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", rule.getId());
        payload.put("metricName", rule.getMetricName());
        payload.put("technology", rule.getTechnology());
        payload.put("direction", rule.getDirection());
        payload.put("criticalValue", rule.getCriticalValue());
        payload.put("majorValue", rule.getMajorValue());
        payload.put("minorValue", rule.getMinorValue());
        payload.put("warningValue", rule.getWarningValue());
        payload.put("enabled", rule.getEnabled());
        payload.put("createdAt", rule.getCreatedAt());
        payload.put("updatedAt", rule.getUpdatedAt());
        return payload;
    }
}

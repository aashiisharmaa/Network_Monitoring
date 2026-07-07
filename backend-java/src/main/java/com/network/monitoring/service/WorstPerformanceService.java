package com.network.monitoring.service;

import com.network.monitoring.entity.KpiThresholdRule;
import com.network.monitoring.repository.KpiThresholdRuleRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WorstPerformanceService {

    private final KpiDynamicDataService kpiDynamicDataService;
    private final KpiCacheService kpiCacheService;
    private final KpiThresholdRuleService thresholdRuleService;
    private final KpiThresholdRuleRepository thresholdRuleRepository;
    private final Map<String, CachedResult> worstCache = new ConcurrentHashMap<>();

    public WorstPerformanceService(KpiDynamicDataService kpiDynamicDataService,
                                   KpiCacheService kpiCacheService,
                                   KpiThresholdRuleService thresholdRuleService,
                                   KpiThresholdRuleRepository thresholdRuleRepository) {
        this.kpiDynamicDataService = kpiDynamicDataService;
        this.kpiCacheService = kpiCacheService;
        this.thresholdRuleService = thresholdRuleService;
        this.thresholdRuleRepository = thresholdRuleRepository;
    }

    public Map<String, Object> worstCells(Long fileId, String metric, Integer limit) {
        return worst(fileId, metric, limit, "cell");
    }

    public Map<String, Object> worstSites(Long fileId, String metric, Integer limit) {
        return worst(fileId, metric, limit, "site");
    }

    public Map<String, Object> worstCellDetail(Long fileId, String cellName, String metric) {
        if (fileId == null) {
            throw new IllegalArgumentException("fileId is required.");
        }
        if (cellName == null || cellName.isBlank()) {
            throw new IllegalArgumentException("cellName is required.");
        }
        if (metric == null || metric.isBlank()) {
            throw new IllegalArgumentException("metric is required.");
        }

        List<Map<String, Object>> records = findDynamicRecords(fileId);
        String metricKey = resolveMetricKey(records, metric);
        List<KpiThresholdRule> rules = thresholdRuleRepository.findAllByEnabledTrue();
        Map<String, KpiThresholdRule> ruleCache = new LinkedHashMap<>();
        List<Map<String, Object>> points = new ArrayList<>();
        Aggregate aggregate = new Aggregate(cellName);
        String normalizedCell = normalize(cellName);

        for (Map<String, Object> record : records) {
            String recordCell = dimensionValue(record, "cellName", "cellid", "cell_id", "cell", "shortname", "short_name");
            if (!normalize(recordCell).equals(normalizedCell)) {
                continue;
            }
            Map<String, Object> metrics = objectMap(record.get("metrics"));
            Double value = number(metrics.get(metricKey));
            if (value == null) {
                continue;
            }

            String site = dimensionValue(record, "site", "siteid", "site_id", "sitename", "site_name");
            String technology = dimensionValue(record, "tech", "technology", "rat");
            String severity = severity(metricKey, value, technology, rules, ruleCache);
            aggregate.add(value);
            aggregate.cell = first(recordCell, aggregate.cell);
            aggregate.site = first(site, aggregate.site);
            aggregate.technology = first(technology, aggregate.technology);
            aggregate.severity = worseSeverity(aggregate.severity, severity);
            if (!"NORMAL".equalsIgnoreCase(severity)) {
                aggregate.breachCount++;
            }

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", first(record.get("date"), dimensionValue(record, "date", "date", "datetime", "timestamp")));
            point.put("value", round(value));
            point.put("severity", severity);
            point.put("site", site);
            point.put("technology", technology);
            point.put("band", dimensionValue(record, "band", "band", "frequencyband", "frequency_band", "layer", "carrier"));
            point.put("sectorId", dimensionValue(record, "sectorid", "sectorid", "sector_id", "secid", "sec_id", "sec"));
            points.add(point);
        }

        points.sort(Comparator.comparing(point -> string(point.get("date"))));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileId", fileId);
        response.put("cellName", cellName);
        response.put("metric", metric);
        response.put("metricKey", metricKey);
        response.put("direction", higherIsBad(metric, metricKey) ? "HIGHER_IS_BAD" : "LOWER_IS_BAD");
        response.put("summary", toPayload(aggregate, "cell"));
        response.put("points", points);
        response.put("pointCount", points.size());
        return response;
    }

    private Map<String, Object> worst(Long fileId, String requestedMetric, Integer limit, String groupType) {
        if (fileId == null) {
            throw new IllegalArgumentException("fileId is required.");
        }
        if (requestedMetric == null || requestedMetric.isBlank()) {
            throw new IllegalArgumentException("metric is required.");
        }

        int safeLimit = limit == null ? 10 : Math.max(1, Math.min(limit, 100));
        String cacheKey = fileId + "|" + normalize(requestedMetric) + "|" + groupType + "|" + safeLimit;
        CachedResult cached = worstCache.get(cacheKey);
        if (cached != null && cached.expiresAtMillis() > System.currentTimeMillis()) {
            return copyMap(cached.payload());
        }

        List<Map<String, Object>> records = findDynamicRecords(fileId);
        String metricKey = resolveMetricKey(records, requestedMetric);
        boolean higherIsBad = higherIsBad(requestedMetric, metricKey);
        List<KpiThresholdRule> rules = thresholdRuleRepository.findAllByEnabledTrue();
        Map<String, KpiThresholdRule> ruleCache = new LinkedHashMap<>();
        Map<String, Aggregate> aggregates = new LinkedHashMap<>();

        for (Map<String, Object> record : records) {
            Map<String, Object> metrics = objectMap(record.get("metrics"));
            Double value = number(metrics.get(metricKey));
            if (value == null) {
                continue;
            }
            String site = dimensionValue(record, "site", "siteid", "site_id", "sitename", "site_name");
            String cell = dimensionValue(record, "cellName", "cellid", "cell_id", "cell", "shortname", "short_name");
            String technology = dimensionValue(record, "tech", "technology", "rat");
            String groupKey = "site".equals(groupType) ? first(site, "Unknown Site") : first(cell, "Unknown Cell");
            Aggregate aggregate = aggregates.computeIfAbsent(groupKey, ignored -> new Aggregate(groupKey));
            aggregate.add(value);
            aggregate.site = first(site, aggregate.site);
            aggregate.cell = first(cell, aggregate.cell);
            aggregate.technology = first(technology, aggregate.technology);
            String severity = severity(metricKey, value, aggregate.technology, rules, ruleCache);
            if (!"NORMAL".equalsIgnoreCase(severity)) {
                aggregate.breachCount++;
            }
            aggregate.severity = worseSeverity(aggregate.severity, severity);
        }

        Comparator<Aggregate> comparator = Comparator.comparingDouble(Aggregate::average);
        if (higherIsBad) {
            comparator = comparator.reversed();
        }
        List<Map<String, Object>> items = aggregates.values().stream()
                .sorted(comparator)
                .limit(safeLimit)
                .map(aggregate -> toPayload(aggregate, groupType))
                .toList();
        for (int index = 0; index < items.size(); index++) {
            items.get(index).put("rank", index + 1);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileId", fileId);
        response.put("requestedMetric", requestedMetric);
        response.put("metricKey", metricKey);
        response.put("direction", higherIsBad ? "HIGHER_IS_BAD" : "LOWER_IS_BAD");
        response.put("groupType", groupType);
        response.put("count", items.size());
        response.put("data", items);
        worstCache.put(cacheKey, new CachedResult(copyMap(response), System.currentTimeMillis() + 120_000L));
        return response;
    }

    private List<Map<String, Object>> findDynamicRecords(Long fileId) {
        List<Map<String, Object>> records = kpiDynamicDataService.findRecordsByFileId(fileId);
        if (!records.isEmpty()) {
            return records;
        }
        Map<String, Object> cache = kpiCacheService.readKpiCache(fileId);
        return kpiDynamicDataService.getRecordsFromCachePayload(cache);
    }

    private Map<String, Object> toPayload(Aggregate aggregate, String groupType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(groupType, aggregate.key);
        payload.put("cellName", aggregate.cell);
        payload.put("site", aggregate.site);
        payload.put("technology", aggregate.technology);
        payload.put("sampleCount", aggregate.count);
        payload.put("averageValue", round(aggregate.average()));
        payload.put("minValue", round(aggregate.min));
        payload.put("maxValue", round(aggregate.max));
        payload.put("breachCount", aggregate.breachCount);
        payload.put("severity", aggregate.severity);
        return payload;
    }

    private String resolveMetricKey(List<Map<String, Object>> records, String requestedMetric) {
        String requestKey = normalize(requestedMetric);
        String best = null;
        int bestScore = 0;
        for (Map<String, Object> record : records) {
            for (String key : objectMap(record.get("metrics")).keySet()) {
                if (key == null || key.isBlank()) {
                    continue;
                }
                int score = metricScore(requestKey, normalize(key));
                if (score > bestScore) {
                    bestScore = score;
                    best = key;
                }
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("Metric '" + requestedMetric + "' was not found in this file.");
        }
        return best;
    }

    private int metricScore(String requested, String actual) {
        if (requested.equals(actual)) return 100;
        if (actual.contains(requested) || requested.contains(actual)) return 80;
        int score = 0;
        for (String token : requested.split("(?<=\\G.{3})")) {
            if (!token.isBlank() && actual.contains(token)) {
                score += 5;
            }
        }
        return score;
    }

    private boolean higherIsBad(String requestedMetric, String metricKey) {
        String key = normalize(requestedMetric + " " + metricKey);
        return key.contains("dcr")
                || key.contains("drop")
                || key.contains("loss")
                || key.contains("prb")
                || key.contains("rssi")
                || key.contains("distance")
                || key.contains("utilisation")
                || key.contains("utilization");
    }

    private String severity(String metricKey,
                            Double value,
                            String technology,
                            List<KpiThresholdRule> rules,
                            Map<String, KpiThresholdRule> ruleCache) {
        KpiThresholdRule rule = bestRule(metricKey, technology, rules, ruleCache);
        return thresholdRuleService.calculateSeverity(rule, value);
    }

    private KpiThresholdRule bestRule(String metricName,
                                      String technology,
                                      List<KpiThresholdRule> rules,
                                      Map<String, KpiThresholdRule> cache) {
        String cacheKey = normalize(metricName) + "|" + normalize(technology);
        if (cache.containsKey(cacheKey)) {
            return cache.get(cacheKey);
        }

        String metricKey = normalize(metricName);
        String techKey = normalize(technology);
        KpiThresholdRule best = null;
        int bestScore = 0;
        for (KpiThresholdRule rule : rules) {
            int score = ruleMetricScore(normalize(rule.getMetricName()), metricKey);
            if (score <= 0) {
                continue;
            }
            String ruleTech = normalize(rule.getTechnology());
            if (!techKey.isBlank() && ruleTech.equals(techKey)) {
                score += 30;
            } else if (ruleTech.isBlank()) {
                score += 20;
            } else {
                score += 5;
            }
            if (score > bestScore) {
                bestScore = score;
                best = rule;
            }
        }
        cache.put(cacheKey, best);
        return best;
    }

    private int ruleMetricScore(String ruleMetric, String uploadedMetric) {
        if (ruleMetric.isBlank() || uploadedMetric.isBlank()) return 0;
        if (ruleMetric.equals(uploadedMetric)) return 120;
        if (uploadedMetric.contains(ruleMetric) || ruleMetric.contains(uploadedMetric)) return 95;
        Set<String> ruleTokens = tokens(ruleMetric);
        Set<String> uploadedTokens = tokens(uploadedMetric);
        if (ruleTokens.isEmpty() || uploadedTokens.isEmpty()) return 0;
        long overlap = ruleTokens.stream().filter(uploadedTokens::contains).count();
        return overlap == ruleTokens.size() ? 70 : 0;
    }

    private Set<String> tokens(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (String item : String.valueOf(value).split("[^a-z0-9]+")) {
            if (!item.isBlank()) result.add(item);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private Double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return value == null ? null : Double.parseDouble(String.valueOf(value).replace(",", "").replace("%", ""));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String worseSeverity(String current, String next) {
        return severityRank(next) > severityRank(current) ? next : current;
    }

    private int severityRank(String severity) {
        return switch (String.valueOf(severity).toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> 4;
            case "MAJOR" -> 3;
            case "MINOR" -> 2;
            case "WARNING" -> 1;
            default -> 0;
        };
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String first(Object value, String fallback) {
        String parsed = string(value);
        return parsed.isBlank() ? fallback : parsed;
    }

    private String dimensionValue(Map<String, Object> record, String topLevelKey, String... dimensionKeys) {
        String topLevel = string(record.get(topLevelKey));
        if (!topLevel.isBlank()) {
            return topLevel;
        }
        Map<String, Object> dimensions = objectMap(record.get("dimensions"));
        for (String key : dimensionKeys) {
            String value = string(dimensions.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        return new LinkedHashMap<>(source == null ? Map.of() : source);
    }

    private record CachedResult(Map<String, Object> payload, long expiresAtMillis) {
    }

    private static class Aggregate {
        private final String key;
        private String cell = "";
        private String site = "";
        private String technology = "";
        private String severity = "NORMAL";
        private int count = 0;
        private int breachCount = 0;
        private double sum = 0.0;
        private double min = Double.POSITIVE_INFINITY;
        private double max = Double.NEGATIVE_INFINITY;

        private Aggregate(String key) {
            this.key = key;
        }

        private void add(double value) {
            count++;
            sum += value;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        private double average() {
            return count == 0 ? 0.0 : sum / count;
        }
    }
}

package com.network.monitoring.service;

import com.network.monitoring.entity.KpiThresholdRule;
import com.network.monitoring.repository.KpiThresholdRuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class SiteAnalyticsService {

    private static final String LOWER_IS_BAD = "LOWER_IS_BAD";

    private final KpiDynamicDataService kpiDynamicDataService;
    private final KpiCacheService kpiCacheService;
    private final KpiThresholdRuleRepository thresholdRuleRepository;

    public SiteAnalyticsService(KpiDynamicDataService kpiDynamicDataService,
                                KpiCacheService kpiCacheService,
                                KpiThresholdRuleRepository thresholdRuleRepository) {
        this.kpiDynamicDataService = kpiDynamicDataService;
        this.kpiCacheService = kpiCacheService;
        this.thresholdRuleRepository = thresholdRuleRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> siteSummary(Long fileId) {
        List<Map<String, Object>> records = records(fileId);
        List<KpiThresholdRule> rules = thresholdRuleRepository.findAllByEnabledTrue();
        Map<String, SiteAggregate> sites = aggregateSites(records, rules);

        List<Map<String, Object>> items = sites.values().stream()
                .sorted(Comparator.comparing(SiteAggregate::site, String.CASE_INSENSITIVE_ORDER))
                .map(SiteAggregate::toSummary)
                .toList();

        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        statusCounts.put("GOOD", 0);
        statusCounts.put("WATCH", 0);
        statusCounts.put("BAD", 0);
        statusCounts.put("CRITICAL", 0);
        for (SiteAggregate site : sites.values()) {
            statusCounts.compute(site.status(), (key, value) -> value == null ? 1 : value + 1);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileId", fileId);
        response.put("siteCount", sites.size());
        response.put("rowCount", records.size());
        response.put("statusCounts", statusCounts);
        response.put("data", items);
        return response;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> worstSites(Long fileId, Integer limit) {
        List<Map<String, Object>> records = records(fileId);
        List<KpiThresholdRule> rules = thresholdRuleRepository.findAllByEnabledTrue();
        int safeLimit = limit == null ? 10 : Math.max(1, Math.min(limit, 100));

        List<Map<String, Object>> items = aggregateSites(records, rules).values().stream()
                .sorted(Comparator
                        .comparingInt(SiteAggregate::healthScore)
                        .thenComparing((SiteAggregate site) -> -site.criticalCount)
                        .thenComparing((SiteAggregate site) -> -site.majorCount)
                        .thenComparing(SiteAggregate::site, String.CASE_INSENSITIVE_ORDER))
                .limit(safeLimit)
                .map(SiteAggregate::toSummary)
                .toList();
        for (int index = 0; index < items.size(); index++) {
            items.get(index).put("rank", index + 1);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileId", fileId);
        response.put("count", items.size());
        response.put("data", items);
        return response;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> siteDetails(Long fileId, String siteName) {
        if (siteName == null || siteName.isBlank()) {
            throw new IllegalArgumentException("site is required.");
        }

        String requestedSite = normalize(siteName);
        List<Map<String, Object>> filteredRecords = records(fileId).stream()
                .filter(record -> normalize(siteName(record)).equals(requestedSite))
                .toList();
        if (filteredRecords.isEmpty()) {
            throw new IllegalArgumentException("No KPI data found for site " + siteName + ".");
        }

        List<KpiThresholdRule> rules = thresholdRuleRepository.findAllByEnabledTrue();
        SiteAggregate aggregate = aggregateSites(filteredRecords, rules).values().stream()
                .findFirst()
                .orElseGet(() -> new SiteAggregate(siteName));

        Map<String, CellAggregate> cells = new LinkedHashMap<>();
        List<Map<String, Object>> breaches = new ArrayList<>();
        for (Map<String, Object> record : filteredRecords) {
            String cell = cellName(record);
            CellAggregate cellAggregate = cells.computeIfAbsent(cell, CellAggregate::new);
            cellAggregate.site = aggregate.site;
            cellAggregate.band = firstNonBlank(cellAggregate.band, bandName(record));
            cellAggregate.technology = firstNonBlank(cellAggregate.technology, technologyName(record));
            cellAggregate.sector = firstNonBlank(cellAggregate.sector, sectorName(record));
            cellAggregate.rowCount++;

            Map<String, Object> metrics = objectMap(record.get("metrics"));
            for (Map.Entry<String, Object> entry : metrics.entrySet()) {
                Double numeric = number(entry.getValue());
                if (numeric == null) {
                    continue;
                }
                cellAggregate.addMetric(entry.getKey(), numeric);
                KpiThresholdRule rule = bestRule(entry.getKey(), cellAggregate.technology, rules);
                if (rule == null) {
                    continue;
                }
                String severity = severity(rule, numeric);
                cellAggregate.severity = worseSeverity(cellAggregate.severity, severity);
                if (!"NORMAL".equals(severity)) {
                    cellAggregate.breachCount++;
                    Map<String, Object> breach = new LinkedHashMap<>();
                    breach.put("cellName", cell);
                    breach.put("metricName", entry.getKey());
                    breach.put("value", round(numeric));
                    breach.put("severity", severity);
                    breach.put("threshold", thresholdForSeverity(rule, severity));
                    breach.put("direction", rule.getDirection());
                    breach.put("band", cellAggregate.band);
                    breach.put("technology", cellAggregate.technology);
                    breach.put("sector", cellAggregate.sector);
                    breaches.add(breach);
                }
            }
        }

        breaches.sort((left, right) -> Integer.compare(severityRank(String.valueOf(right.get("severity"))), severityRank(String.valueOf(left.get("severity")))));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileId", fileId);
        response.put("site", aggregate.site);
        response.put("summary", aggregate.toSummary(true));
        response.put("cells", cells.values().stream()
                .sorted(Comparator.comparing(CellAggregate::cell, String.CASE_INSENSITIVE_ORDER))
                .map(CellAggregate::toPayload)
                .toList());
        response.put("topBreaches", breaches.stream().limit(50).toList());
        response.put("breachCount", breaches.size());
        return response;
    }

    private Map<String, SiteAggregate> aggregateSites(List<Map<String, Object>> records, List<KpiThresholdRule> rules) {
        Map<String, SiteAggregate> sites = new LinkedHashMap<>();
        Map<String, KpiThresholdRule> ruleCache = new LinkedHashMap<>();
        for (Map<String, Object> record : records) {
            String siteName = firstNonBlank(siteName(record), "Unknown Site");
            SiteAggregate site = sites.computeIfAbsent(siteName, SiteAggregate::new);
            site.rowCount++;
            add(site.cells, cellName(record));
            add(site.sectors, sectorName(record));
            add(site.bands, bandName(record));
            add(site.technologies, technologyName(record));

            String technology = technologyName(record);
            Map<String, Object> metrics = objectMap(record.get("metrics"));
            for (Map.Entry<String, Object> entry : metrics.entrySet()) {
                Double numeric = number(entry.getValue());
                if (numeric == null) {
                    continue;
                }
                KpiThresholdRule rule = bestRule(entry.getKey(), technology, rules, ruleCache);
                if (rule == null) {
                    site.valuesWithoutRule++;
                    continue;
                }
                site.matchedValues++;
                String severity = severity(rule, numeric);
                site.addSeverity(severity);
                if (!"NORMAL".equals(severity)) {
                    site.breachCount++;
                    site.addWorstMetric(entry.getKey(), severity);
                }
            }
        }
        return sites;
    }

    private List<Map<String, Object>> records(Long fileId) {
        if (fileId == null) {
            throw new IllegalArgumentException("fileId is required.");
        }
        List<Map<String, Object>> records = kpiDynamicDataService.findRecordsByFileId(fileId);
        if (!records.isEmpty()) {
            return records;
        }
        return kpiDynamicDataService.getRecordsFromCachePayload(kpiCacheService.readKpiCache(fileId));
    }

    private KpiThresholdRule bestRule(String metricName, String technology, List<KpiThresholdRule> rules) {
        return bestRule(metricName, technology, rules, null);
    }

    private KpiThresholdRule bestRule(String metricName, String technology, List<KpiThresholdRule> rules, Map<String, KpiThresholdRule> cache) {
        String cacheKey = normalize(metricName) + "|" + normalize(technology);
        if (cache != null && cache.containsKey(cacheKey)) {
            return cache.get(cacheKey);
        }

        String metricKey = normalize(metricName);
        String techKey = normalize(technology);
        KpiThresholdRule best = null;
        int bestScore = 0;
        for (KpiThresholdRule rule : rules) {
            int score = metricScore(normalize(rule.getMetricName()), metricKey);
            if (score <= 0) {
                continue;
            }
            String ruleTech = normalize(rule.getTechnology());
            if (!ruleTech.isBlank() && ruleTech.equals(techKey)) {
                score += 30;
            } else if (ruleTech.isBlank()) {
                score += 20;
            }
            if (score > bestScore) {
                bestScore = score;
                best = rule;
            }
        }

        if (cache != null) {
            cache.put(cacheKey, best);
        }
        return best;
    }

    private int metricScore(String ruleMetric, String uploadedMetric) {
        if (ruleMetric.isBlank() || uploadedMetric.isBlank()) return 0;
        if (ruleMetric.equals(uploadedMetric)) return 120;
        if (uploadedMetric.contains(ruleMetric) || ruleMetric.contains(uploadedMetric)) return 90;
        Set<String> ruleTokens = tokens(ruleMetric);
        Set<String> uploadedTokens = tokens(uploadedMetric);
        if (ruleTokens.isEmpty() || uploadedTokens.isEmpty()) return 0;
        long overlap = ruleTokens.stream().filter(uploadedTokens::contains).count();
        return overlap == ruleTokens.size() ? 70 : 0;
    }

    private Set<String> tokens(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (String item : value.split("[^a-z0-9]+")) {
            if (!item.isBlank()) result.add(item);
        }
        return result;
    }

    private String severity(KpiThresholdRule rule, double value) {
        boolean lowerIsBad = LOWER_IS_BAD.equals(rule.getDirection());
        if (matches(value, rule.getCriticalValue(), lowerIsBad)) return "CRITICAL";
        if (matches(value, rule.getMajorValue(), lowerIsBad)) return "MAJOR";
        if (matches(value, rule.getMinorValue(), lowerIsBad)) return "MINOR";
        if (matches(value, rule.getWarningValue(), lowerIsBad)) return "WARNING";
        return "NORMAL";
    }

    private boolean matches(double value, Double threshold, boolean lowerIsBad) {
        if (threshold == null) return false;
        return lowerIsBad ? value <= threshold : value >= threshold;
    }

    private Double thresholdForSeverity(KpiThresholdRule rule, String severity) {
        return switch (String.valueOf(severity).toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> rule.getCriticalValue();
            case "MAJOR" -> rule.getMajorValue();
            case "MINOR" -> rule.getMinorValue();
            case "WARNING" -> rule.getWarningValue();
            default -> rule.getWarningValue();
        };
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

    private String status(int score) {
        if (score >= 85) return "GOOD";
        if (score >= 65) return "WATCH";
        if (score >= 40) return "BAD";
        return "CRITICAL";
    }

    private int healthScore(int critical, int major, int minor, int warning) {
        int score = 100 - (critical * 20) - (major * 10) - (minor * 5) - (warning * 2);
        return Math.max(0, Math.min(100, score));
    }

    private String siteName(Map<String, Object> record) {
        return firstNonBlank(value(record, "site"), dimension(record, "site", "siteid", "site_id", "sitename", "site_name"), deriveSiteFromCell(value(record, "cellName")));
    }

    private String cellName(Map<String, Object> record) {
        return firstNonBlank(
                value(record, "cellName"),
                dimension(record, "cellname", "cell_name", "cellid", "cell_id", "cell", "shortname", "short_name"),
                "Unknown Cell"
        );
    }

    private String bandName(Map<String, Object> record) {
        return firstNonBlank(
                value(record, "band"),
                dimension(record, "band", "frequencyband", "frequency_band", "layer", "carrier", "earfcn", "arfcn", "nrarfcn"),
                deriveLayerFromShortName(record),
                value(record, "tech"),
                dimension(record, "tech")
        );
    }

    private String technologyName(Map<String, Object> record) {
        String explicit = firstNonBlank(
                dimension(record, "technology", "rat", "networktype", "network_type"),
                value(record, "technology")
        );
        if (!explicit.isBlank()) {
            return explicit;
        }

        String tech = firstNonBlank(value(record, "tech"), dimension(record, "tech"));
        if (tech.toUpperCase(Locale.ROOT).matches("F\\d+|L\\d+|B\\d+|\\d{3,5}")) {
            return "4G";
        }
        return tech;
    }

    private String sectorName(Map<String, Object> record) {
        return firstNonBlank(
                value(record, "sectorname"),
                value(record, "sectorid"),
                dimension(record, "sectorname", "sector_name", "sectorid", "sector_id", "sector", "secid", "sec_id", "sec"),
                deriveSectorFromShortName(record),
                deriveSectorFromCell(record)
        );
    }

    private String deriveSiteFromCell(String cellName) {
        if (cellName == null || cellName.isBlank()) return "";
        String[] parts = cellName.split("[_\\-]");
        return parts.length == 0 ? cellName : parts[0];
    }

    private String deriveLayerFromShortName(Map<String, Object> record) {
        String shortName = firstNonBlank(dimension(record, "shortname", "short_name"), value(record, "cellName"));
        if (shortName.isBlank()) return "";
        for (String part : shortName.split("[_\\-\\s]+")) {
            String candidate = part.trim().toUpperCase(Locale.ROOT);
            if (candidate.matches("F\\d+|L\\d+|B\\d+|\\d{3,5}")) {
                return candidate;
            }
        }
        return "";
    }

    private String deriveSectorFromShortName(Map<String, Object> record) {
        String shortName = firstNonBlank(dimension(record, "shortname", "short_name"), value(record, "cellName"));
        if (shortName.isBlank()) return "";
        String[] parts = shortName.split("[_\\-\\s]+");
        if (parts.length == 0) return "";
        String last = parts[parts.length - 1].trim().toUpperCase(Locale.ROOT);
        return last.matches("[A-Z0-9]{1,3}") ? last : "";
    }

    private String deriveSectorFromCell(Map<String, Object> record) {
        String cell = firstNonBlank(value(record, "cellName"), dimension(record, "cellid", "cell_id", "cellname", "cell_name"));
        if (cell.isBlank()) return "";
        String suffix = cell.substring(cell.length() - 1).toUpperCase(Locale.ROOT);
        return suffix.matches("[A-Z0-9]") ? suffix : "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private String dimension(Map<String, Object> record, String... keys) {
        Map<String, Object> dimensions = objectMap(record.get("dimensions"));
        for (String key : keys) {
            String value = value(dimensions, key);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private Double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            String text = value == null ? "" : String.valueOf(value).replace(",", "").replace("%", "").trim();
            return text.isBlank() ? null : Double.parseDouble(text);
        } catch (Exception ex) {
            return null;
        }
    }

    private void add(Set<String> target, String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                target.add(value);
                return;
            }
        }
    }

    private String value(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private class SiteAggregate {
        private final String site;
        private final Set<String> cells = new LinkedHashSet<>();
        private final Set<String> sectors = new LinkedHashSet<>();
        private final Set<String> bands = new LinkedHashSet<>();
        private final Set<String> technologies = new LinkedHashSet<>();
        private final Map<String, MetricAggregate> metrics = new LinkedHashMap<>();
        private final Map<String, Integer> worstMetrics = new LinkedHashMap<>();
        private int rowCount;
        private int matchedValues;
        private int valuesWithoutRule;
        private int breachCount;
        private int criticalCount;
        private int majorCount;
        private int minorCount;
        private int warningCount;
        private int normalCount;

        private SiteAggregate(String site) {
            this.site = site;
        }

        private String site() {
            return site;
        }

        private void addMetric(String key, double value) {
            metrics.computeIfAbsent(key, MetricAggregate::new).add(value);
        }

        private void addWorstMetric(String key, String severity) {
            worstMetrics.merge(key, severityRank(severity), Math::max);
        }

        private void addSeverity(String severity) {
            switch (String.valueOf(severity).toUpperCase(Locale.ROOT)) {
                case "CRITICAL" -> criticalCount++;
                case "MAJOR" -> majorCount++;
                case "MINOR" -> minorCount++;
                case "WARNING" -> warningCount++;
                default -> normalCount++;
            }
        }

        private int healthScore() {
            int evaluated = criticalCount + majorCount + minorCount + warningCount + normalCount;
            if (evaluated <= 0) {
                return 100;
            }

            double penalty =
                    (criticalCount * 1.0)
                            + (majorCount * 0.65)
                            + (minorCount * 0.35)
                            + (warningCount * 0.15);
            int score = (int) Math.round(100.0 - ((penalty / evaluated) * 100.0));
            return Math.max(0, Math.min(100, score));
        }

        private String status() {
            return SiteAnalyticsService.this.status(healthScore());
        }

        private Map<String, Object> toSummary() {
            return toSummary(false);
        }

        private Map<String, Object> toSummary(boolean includeMetricAverages) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("site", site);
            payload.put("healthScore", healthScore());
            payload.put("status", status());
            payload.put("rowCount", rowCount);
            payload.put("totalCells", cells.size());
            payload.put("totalSectors", sectors.size());
            payload.put("bands", bands.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
            payload.put("technologies", technologies.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
            payload.put("matchedValues", matchedValues);
            payload.put("valuesWithoutRule", valuesWithoutRule);
            payload.put("breachCount", breachCount);
            payload.put("criticalCount", criticalCount);
            payload.put("majorCount", majorCount);
            payload.put("minorCount", minorCount);
            payload.put("warningCount", warningCount);
            payload.put("normalCount", normalCount);
            if (includeMetricAverages) {
                payload.put("metricAverages", metrics.values().stream().limit(20).map(MetricAggregate::toPayload).toList());
            }
            payload.put("worstMetrics", worstMetrics.entrySet().stream()
                    .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                    .limit(10)
                    .map(entry -> Map.of("metricName", entry.getKey(), "severityRank", entry.getValue()))
                    .toList());
            return payload;
        }
    }

    private class CellAggregate {
        private final String cell;
        private String site = "";
        private String band = "";
        private String technology = "";
        private String sector = "";
        private String severity = "NORMAL";
        private int rowCount;
        private int breachCount;
        private final Map<String, MetricAggregate> metrics = new LinkedHashMap<>();

        private CellAggregate(String cell) {
            this.cell = cell;
        }

        private String cell() {
            return cell;
        }

        private void addMetric(String key, double value) {
            metrics.computeIfAbsent(key, MetricAggregate::new).add(value);
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("cellName", cell);
            payload.put("site", site);
            payload.put("band", band);
            payload.put("technology", technology);
            payload.put("sector", sector);
            payload.put("severity", severity);
            payload.put("rowCount", rowCount);
            payload.put("breachCount", breachCount);
            payload.put("metricAverages", metrics.values().stream().limit(15).map(MetricAggregate::toPayload).toList());
            return payload;
        }
    }

    private class MetricAggregate {
        private final String key;
        private int count;
        private double sum;
        private double min = Double.POSITIVE_INFINITY;
        private double max = Double.NEGATIVE_INFINITY;

        private MetricAggregate(String key) {
            this.key = key;
        }

        private void add(double value) {
            count++;
            sum += value;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("metricName", key);
            payload.put("sampleCount", count);
            payload.put("average", count == 0 ? 0.0 : round(sum / count));
            payload.put("min", count == 0 ? 0.0 : round(min));
            payload.put("max", count == 0 ? 0.0 : round(max));
            return payload;
        }
    }
}

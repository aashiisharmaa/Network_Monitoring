package com.network.monitoring.service;

import com.network.monitoring.entity.SiteData;
import com.network.monitoring.entity.KpiThresholdRule;
import com.network.monitoring.repository.SiteDataRepository;
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
import java.time.LocalDateTime;

@Service
public class SiteAnalyticsService {

    private static final String LOWER_IS_BAD = "LOWER_IS_BAD";

    private final KpiDynamicDataService kpiDynamicDataService;
    private final KpiCacheService kpiCacheService;
    private final KpiThresholdRuleRepository thresholdRuleRepository;
    private final SiteDataRepository siteDataRepository;

    public SiteAnalyticsService(KpiDynamicDataService kpiDynamicDataService,
                                KpiCacheService kpiCacheService,
                                KpiThresholdRuleRepository thresholdRuleRepository,
                                SiteDataRepository siteDataRepository) {
        this.kpiDynamicDataService = kpiDynamicDataService;
        this.kpiCacheService = kpiCacheService;
        this.thresholdRuleRepository = thresholdRuleRepository;
        this.siteDataRepository = siteDataRepository;
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

    @Transactional(readOnly = true)
    public Map<String, Object> siteIntelligence(Long fileId, Long siteFileId, Integer limit) {
        // Site intelligence must work with a site upload alone. KPI rows enrich the
        // result when available, but are not required for topology analysis.
        List<Map<String, Object>> records = fileId == null ? List.of() : records(fileId);
        List<KpiThresholdRule> rules = thresholdRuleRepository.findAllByEnabledTrue();
        List<SiteData> siteRows = siteFileId != null
                ? siteDataRepository.findByFileId(siteFileId)
                : fileId == null ? List.of() : siteDataRepository.findByFileId(fileId);
        if (siteRows.isEmpty() && siteFileId != null && fileId != null && !siteFileId.equals(fileId)) {
            siteRows = siteDataRepository.findByFileId(fileId);
        }
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 1000));

        Map<String, SiteAggregate> kpiSites = aggregateSites(records, rules);
        Map<String, SiteMasterAggregate> masters = aggregateSiteMasters(siteRows);
        if (masters.isEmpty()) {
            for (SiteAggregate site : kpiSites.values()) {
                masters.put(normalize(site.site()), new SiteMasterAggregate(site.site()));
            }
        }

        Map<String, SiteSignalSummary> signalSummaries = aggregateSignalSummaries(records);
        List<Map<String, Object>> items = new ArrayList<>();
        for (SiteMasterAggregate site : masters.values()) {
            SiteAggregate kpiSite = matchKpiSite(kpiSites, site);
            SiteSignalSummary signalSummary = signalSummaries.getOrDefault(normalize(site.displayName()), new SiteSignalSummary(site.displayName()));
            Map<String, Object> payload = buildSiteIntelligencePayload(site, kpiSite, signalSummary, masters.values());
            items.add(payload);
        }

        items.sort(Comparator
                .comparingDouble((Map<String, Object> item) -> number(item.get("impactScore")))
                .reversed()
                .thenComparing(item -> String.valueOf(item.get("site")), String.CASE_INSENSITIVE_ORDER));
        for (int index = 0; index < items.size(); index++) {
            items.get(index).put("rank", index + 1);
        }

        List<Map<String, Object>> topSites = items.stream().limit(safeLimit).toList();
        Map<String, Object> clusterSummary = clusterSummary(items);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileId", fileId);
        response.put("siteFileId", siteFileId != null ? siteFileId : fileId);
        response.put("analysisMode", "SITE_CONFIGURATION");
        response.put("analysisSource", "site_csv");
        response.put("kpiEnrichmentAvailable", fileId != null && !records.isEmpty());
        response.put("calculatedMetrics", List.of(
                "clusterization", "nearby_neighbours", "pci_collision", "pci_confusion",
                "band_relations", "technology_relations", "overshooting_risk", "missing_neighbours"
        ));
        response.put("generatedAt", LocalDateTime.now().toString());
        response.put("siteCount", items.size());
        response.put("clusterCount", ((List<?>) clusterSummary.getOrDefault("data", List.of())).size());
        response.put("clusterSummary", clusterSummary);
        response.put("data", topSites);
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

    private Map<String, SiteMasterAggregate> aggregateSiteMasters(List<SiteData> siteRows) {
        Map<String, SiteMasterAggregate> sites = new LinkedHashMap<>();
        for (SiteData row : siteRows) {
            String label = firstNonBlank(row.getSiteId(), row.getSiteName(), deriveSiteFromCell(row.getCellName()), "Unknown Site");
            String key = normalize(label);
            SiteMasterAggregate site = sites.computeIfAbsent(key, ignored -> new SiteMasterAggregate(label));
            site.merge(row);
        }
        return sites;
    }

    private Map<String, SiteSignalSummary> aggregateSignalSummaries(List<Map<String, Object>> records) {
        Map<String, SiteSignalSummary> summaries = new LinkedHashMap<>();
        for (Map<String, Object> record : records) {
            String label = firstNonBlank(siteName(record), deriveSiteFromCell(value(record, "cellName")), "Unknown Site");
            String key = normalize(label);
            SiteSignalSummary summary = summaries.computeIfAbsent(key, ignored -> new SiteSignalSummary(label));
            summary.merge(record);
        }
        return summaries;
    }

    private SiteAggregate matchKpiSite(Map<String, SiteAggregate> kpiSites, SiteMasterAggregate site) {
        if (kpiSites.isEmpty()) {
            return null;
        }
        String key = normalize(site.displayName());
        SiteAggregate direct = kpiSites.get(key);
        if (direct != null) {
            return direct;
        }
        if (!site.siteId.isBlank()) {
            direct = kpiSites.get(normalize(site.siteId));
            if (direct != null) {
                return direct;
            }
        }
        if (!site.siteName.isBlank()) {
            direct = kpiSites.get(normalize(site.siteName));
            if (direct != null) {
                return direct;
            }
        }
        for (SiteAggregate candidate : kpiSites.values()) {
            String candidateKey = normalize(candidate.site());
            if (candidateKey.equals(key) || candidateKey.contains(key) || key.contains(candidateKey)) {
                return candidate;
            }
        }
        return null;
    }

    private Map<String, Object> buildSiteIntelligencePayload(SiteMasterAggregate site,
                                                             SiteAggregate kpiSite,
                                                             SiteSignalSummary signals,
                                                             java.util.Collection<SiteMasterAggregate> allSites) {
        List<Map<String, Object>> neighbours = inferNeighbourCandidates(site, allSites);
        double avgDistanceKm = averageNumber(neighbours, "distanceKm");
        double averageTimingAdvance = signals.averageTimingAdvance();
        double averageHandoverSuccess = normalizeRate(signals.averageHandoverSuccess(), 60.0);
        double averageHandoverAttempts = normalizeVolume(signals.averageHandoverAttempts(), 0.0);
        double averageHandoverFailures = normalizeVolume(signals.averageHandoverFailures(), 0.0);
        double interferenceRisk = clamp(signals.averageInterferenceRisk(), 0.0, 100.0);
        double healthScore = kpiSite == null ? 100.0 : kpiSite.healthScore();
        int samePciNeighbourCount = countMatches(neighbours, "samePci", true);
        int facingNeighbourCount = countMatches(neighbours, "facing", true);
        int clusterNeighbourCount = countMatches(neighbours, "sameCluster", true);
        double pciCollisionScore = clamp((samePciNeighbourCount * 28.0) + (site.pciCount() >= 2 ? 12.0 : 0.0), 0.0, 100.0);
        double pciConfusionScore = clamp((samePciNeighbourCount >= 2 ? 55.0 : samePciNeighbourCount == 1 ? 25.0 : 0.0)
                + (facingNeighbourCount * 12.0)
                + (site.pciCount() >= 3 ? 10.0 : 0.0), 0.0, 100.0);
        double overshootingScore = clamp(distanceRisk(avgDistanceKm) * 0.6 + timingAdvanceRisk(averageTimingAdvance) * 0.4, 0.0, 100.0);
        double missingNeighborScore = missingNeighborRisk(site, neighbours.size(), clusterNeighbourCount);
        double handoverRisk = handoverRisk(averageHandoverSuccess, averageHandoverAttempts, averageHandoverFailures);
        double impactScore = clamp((100.0 - healthScore) * 0.30
                + pciCollisionScore * 0.20
                + pciConfusionScore * 0.10
                + overshootingScore * 0.20
                + handoverRisk * 0.15
                + missingNeighborScore * 0.05
                + interferenceRisk * 0.05, 0.0, 100.0);

        List<String> recommendations = new ArrayList<>();
        if (pciCollisionScore >= 40.0) {
            recommendations.add("Review PCI reuse and neighbour overlap.");
        }
        if (pciConfusionScore >= 40.0) {
            recommendations.add("Reduce same-PCI confusion and verify dominant neighbour coverage.");
        }
        if (overshootingScore >= 45.0) {
            recommendations.add("Check overshooting using average distance and timing advance.");
        }
        if (handoverRisk >= 45.0) {
            recommendations.add("Inspect handover attempts, success rate, hysteresis, and TTT.");
        }
        if (missingNeighborScore >= 35.0) {
            recommendations.add("Add or verify missing neighbor relations.");
        }
        if (interferenceRisk >= 45.0) {
            recommendations.add("Investigate interference, SINR, CQI, and overshooting overlap.");
        }
        if (recommendations.isEmpty()) {
            recommendations.add("Observe the site and continue trend monitoring.");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("site", site.displayName());
        payload.put("analysisMode", "SITE_CONFIGURATION");
        payload.put("analysisSource", "site_csv");
        payload.put("siteId", site.siteId);
        payload.put("siteName", site.siteName);
        payload.put("cluster", site.cluster);
        payload.put("region", site.region);
        payload.put("technology", site.primaryTechnology());
        payload.put("band", site.primaryBand());
        payload.put("cellCount", site.cellCount());
        payload.put("sectorCount", site.sectorCount());
        payload.put("pciValues", site.pcis.stream().sorted().toList());
        payload.put("latitude", site.lat());
        payload.put("longitude", site.lon());
        payload.put("neighborCount", neighbours.size());
        payload.put("samePciNeighbourCount", samePciNeighbourCount);
        payload.put("facingNeighbourCount", facingNeighbourCount);
        payload.put("clusterNeighbourCount", clusterNeighbourCount);
        payload.put("avgDistanceKm", round(avgDistanceKm));
        payload.put("avgTimingAdvance", round(averageTimingAdvance));
        payload.put("handoverSuccessRate", round(averageHandoverSuccess));
        payload.put("handoverAttemptPressure", round(averageHandoverAttempts));
        payload.put("handoverFailurePressure", round(averageHandoverFailures));
        payload.put("interferenceRisk", round(interferenceRisk));
        payload.put("healthScore", round(healthScore));
        payload.put("pciCollisionScore", round(pciCollisionScore));
        payload.put("pciConfusionScore", round(pciConfusionScore));
        payload.put("overshootingScore", round(overshootingScore));
        payload.put("missingNeighborScore", round(missingNeighborScore));
        payload.put("handoverRisk", round(handoverRisk));
        payload.put("impactScore", round(impactScore));
        payload.put("status", riskStatus(impactScore));
        payload.put("generatedAt", LocalDateTime.now().toString());
        payload.put("latestObservedAt", signals.latestObservedAt());
        payload.put("calculationInputs", new LinkedHashMap<>(Map.of(
                "siteRows", site.rowCount,
                "cellCount", site.cellCount(),
                "neighbourCount", neighbours.size(),
                "kpiRows", kpiSite == null ? 0 : kpiSite.rowCount,
                "kpiEnriched", kpiSite != null
        )));
        payload.put("dataQuality", dataQuality(site, kpiSite, signals, neighbours));
        payload.put("recommendations", recommendations);
        payload.put("neighbours", neighbours);
        if (kpiSite != null) {
            payload.put("kpiSummary", kpiSite.toSummary(true));
        }
        payload.put("mapOverlay", buildMapOverlay(site, neighbours, impactScore, samePciNeighbourCount, facingNeighbourCount, clusterNeighbourCount,
                pciCollisionScore, pciConfusionScore, overshootingScore, handoverRisk, missingNeighborScore));
        return payload;
    }

    private Map<String, Object> dataQuality(SiteMasterAggregate site,
                                             SiteAggregate kpiSite,
                                             SiteSignalSummary signals,
                                             List<Map<String, Object>> neighbours) {
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("coordinatesAvailable", site.lat() != null && site.lon() != null);
        quality.put("topologyAvailable", !neighbours.isEmpty());
        quality.put("kpiAvailable", kpiSite != null && kpiSite.rowCount > 0);
        quality.put("handoverMetricsAvailable", !signals.handoverAttempts.isEmpty() || !signals.handoverSuccess.isEmpty());
        quality.put("timingAdvanceAvailable", !signals.timingAdvance.isEmpty());
        quality.put("observedAt", signals.latestObservedAt());
        return quality;
    }

    private Map<String, Object> buildMapOverlay(SiteMasterAggregate site,
                                                List<Map<String, Object>> neighbours,
                                                double impactScore,
                                                int samePciNeighbourCount,
                                                int facingNeighbourCount,
                                                int clusterNeighbourCount,
                                                double pciCollisionScore,
                                                double pciConfusionScore,
                                                double overshootingScore,
                                                double handoverRisk,
                                                double missingNeighborScore) {
        Map<String, Object> overlay = new LinkedHashMap<>();
        overlay.put("siteId", site.siteId);
        overlay.put("siteName", site.siteName);
        overlay.put("site", site.displayName());
        overlay.put("latitude", site.lat());
        overlay.put("longitude", site.lon());
        overlay.put("impactScore", round(impactScore));
        overlay.put("counts", mapIssueCounts(samePciNeighbourCount, facingNeighbourCount, clusterNeighbourCount, neighbours.size()));

        List<Map<String, Object>> issueMarkers = new ArrayList<>();
        addIssueMarker(issueMarkers, site, "PCI_COLLISION", "PCI Collision", "PCI", pciCollisionScore, "#EF4444", issueRadius(pciCollisionScore));
        addIssueMarker(issueMarkers, site, "PCI_CONFUSION", "PCI Confusion", "PCI", pciConfusionScore, "#F97316", issueRadius(pciConfusionScore));
        addIssueMarker(issueMarkers, site, "OVERSHOOTING", "Overshooting", "OVR", overshootingScore, "#A855F7", issueRadius(overshootingScore));
        addIssueMarker(issueMarkers, site, "HANDOVER", "Handover", "HO", handoverRisk, "#06B6D4", issueRadius(handoverRisk));
        addIssueMarker(issueMarkers, site, "MISSING_NEIGHBOR", "Missing Neighbour", "NEI", missingNeighborScore, "#10B981", issueRadius(missingNeighborScore));
        overlay.put("issueMarkers", issueMarkers);

        List<Map<String, Object>> relationLines = new ArrayList<>();
        for (Map<String, Object> neighbour : neighbours) {
            relationLines.add(buildRelationOverlay(site, neighbour));
        }
        overlay.put("handoverRelations", relationLines);
        return overlay;
    }

    private Map<String, Object> mapIssueCounts(int samePciNeighbourCount,
                                               int facingNeighbourCount,
                                               int clusterNeighbourCount,
                                               int neighbourCount) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("total", neighbourCount);
        counts.put("samePci", samePciNeighbourCount);
        counts.put("facing", facingNeighbourCount);
        counts.put("sameCluster", clusterNeighbourCount);
        counts.put("nearby", Math.max(0, neighbourCount - samePciNeighbourCount - facingNeighbourCount - clusterNeighbourCount));
        return counts;
    }

    private void addIssueMarker(List<Map<String, Object>> markers,
                                SiteMasterAggregate site,
                                String key,
                                String label,
                                String shortLabel,
                                double score,
                                String color,
                                double radius) {
        if (!Double.isFinite(score) || score <= 0.0) {
            return;
        }
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("issueKey", key);
        marker.put("issueLabel", label);
        marker.put("issueShortLabel", shortLabel);
        marker.put("issueScore", round(score));
        marker.put("color", color);
        marker.put("radius", radius);
        marker.put("siteId", site.siteId);
        marker.put("siteName", site.siteName);
        marker.put("site", site.displayName());
        marker.put("latitude", site.lat());
        marker.put("longitude", site.lon());
        markers.add(marker);
    }

    private Map<String, Object> buildRelationOverlay(SiteMasterAggregate source, Map<String, Object> neighbour) {
        Map<String, Object> relation = new LinkedHashMap<>();
        relation.put("relationType", relationTypeFromNeighbour(neighbour));
        relation.put("relationScore", neighbour.get("relationScore"));
        relation.put("color", relationColorFromNeighbour(neighbour));
        relation.put("width", relationWidthFromNeighbour(neighbour));
        relation.put("distanceKm", neighbour.get("distanceKm"));
        relation.put("samePci", neighbour.get("samePci"));
        relation.put("sameCluster", neighbour.get("sameCluster"));
        relation.put("facing", neighbour.get("facing"));
        relation.put("pciOverlap", neighbour.get("pciOverlap"));
        relation.put("azimuthDelta", neighbour.get("azimuthDelta"));
        relation.put("recommendation", neighbour.get("recommendation"));
        relation.put("sourceSite", mapSiteOverlay(source));
        relation.put("targetSite", neighbour);
        relation.put("sourcePosition", new double[] { source.lon() == null ? 0.0 : source.lon(), source.lat() == null ? 0.0 : source.lat() });
        relation.put("targetPosition", new double[] {
                number(neighbour.get("longitude")),
                number(neighbour.get("latitude"))
        });
        return relation;
    }

    private String relationTypeFromNeighbour(Map<String, Object> neighbour) {
        if (Boolean.TRUE.equals(neighbour.get("samePci"))) {
            return "SAME_PCI";
        }
        if (Boolean.TRUE.equals(neighbour.get("facing"))) {
            return "HANDOVER_FACE";
        }
        if (Boolean.TRUE.equals(neighbour.get("sameCluster"))) {
            return "SAME_CLUSTER";
        }
        return "NEARBY";
    }

    private String relationColorFromNeighbour(Map<String, Object> neighbour) {
        if (Boolean.TRUE.equals(neighbour.get("samePci"))) {
            return "#DC2626";
        }
        if (Boolean.TRUE.equals(neighbour.get("facing"))) {
            return "#2563EB";
        }
        if (Boolean.TRUE.equals(neighbour.get("sameCluster"))) {
            return "#7C3AED";
        }
        return "#059669";
    }

    private int relationWidthFromNeighbour(Map<String, Object> neighbour) {
        if (Boolean.TRUE.equals(neighbour.get("samePci"))) {
            return 4;
        }
        if (Boolean.TRUE.equals(neighbour.get("facing"))) {
            return 3;
        }
        return 2;
    }

    private Map<String, Object> mapSiteOverlay(SiteMasterAggregate site) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("siteId", site.siteId);
        payload.put("siteName", site.siteName);
        payload.put("site", site.displayName());
        payload.put("cluster", site.cluster);
        payload.put("technology", site.primaryTechnology());
        payload.put("band", site.primaryBand());
        payload.put("latitude", site.lat());
        payload.put("longitude", site.lon());
        return payload;
    }

    private double issueRadius(double score) {
        if (!Double.isFinite(score) || score <= 0.0) {
            return 8.0;
        }
        return clamp(8.0 + (score / 9.0), 8.0, 18.0);
    }

    private List<Map<String, Object>> inferNeighbourCandidates(SiteMasterAggregate source, java.util.Collection<SiteMasterAggregate> allSites) {
        if (source.lat() == null || source.lon() == null) {
            return List.of();
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (SiteMasterAggregate candidate : allSites) {
            if (candidate == null || candidate == source || candidate.lat() == null || candidate.lon() == null) {
                continue;
            }
            double distanceKm = distanceKm(source.lat(), source.lon(), candidate.lat(), candidate.lon());
            if (!Double.isFinite(distanceKm) || distanceKm > 8.0) {
                continue;
            }

            int pciOverlap = intersectionCount(source.pcis, candidate.pcis);
            boolean samePci = pciOverlap > 0;
            boolean sameCluster = !source.cluster.isBlank() && source.cluster.equalsIgnoreCase(candidate.cluster);
            boolean facing = source.azimuth != null && candidate.azimuth != null
                    && angleDeltaDeg(source.azimuth, candidate.azimuth) <= 80.0;
            boolean close = distanceKm <= 1.0;
            double relationScore = 0.0;
            if (samePci) {
                relationScore += 40.0;
            }
            if (facing) {
                relationScore += 25.0;
            }
            if (sameCluster) {
                relationScore += 10.0;
            }
            relationScore += distanceRisk(distanceKm) * 0.25;
            if (close) {
                relationScore += 10.0;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("site", candidate.displayName());
            payload.put("siteId", candidate.siteId);
            payload.put("siteName", candidate.siteName);
            payload.put("cluster", candidate.cluster);
            payload.put("technology", candidate.primaryTechnology());
            payload.put("band", candidate.primaryBand());
            payload.put("latitude", candidate.lat());
            payload.put("longitude", candidate.lon());
            payload.put("distanceKm", round(distanceKm));
            payload.put("samePci", samePci);
            payload.put("sameCluster", sameCluster);
            payload.put("facing", facing);
            payload.put("pciOverlap", pciOverlap);
            payload.put("azimuthDelta", candidate.azimuth == null || source.azimuth == null ? null : round(angleDeltaDeg(source.azimuth, candidate.azimuth)));
            payload.put("relationScore", round(relationScore));
            payload.put("recommendation", neighbourRecommendation(samePci, facing, sameCluster, distanceKm, pciOverlap));
            candidates.add(payload);
        }

        candidates.sort(Comparator
                .comparingDouble((Map<String, Object> item) -> number(item.get("relationScore")))
                .reversed()
                .thenComparingDouble(item -> number(item.get("distanceKm"))));
        return candidates.stream().limit(8).toList();
    }

    private Map<String, Object> clusterSummary(List<Map<String, Object>> items) {
        Map<String, ClusterAggregate> clusters = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String cluster = firstNonBlank(value(item, "cluster"), "Unclustered");
            ClusterAggregate aggregate = clusters.computeIfAbsent(normalize(cluster), ignored -> new ClusterAggregate(cluster));
            aggregate.add(item);
        }
        List<Map<String, Object>> data = clusters.values().stream()
                .sorted(Comparator.comparingDouble(ClusterAggregate::averageImpactScore).reversed())
                .map(ClusterAggregate::toPayload)
                .toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", data.size());
        response.put("data", data);
        return response;
    }

    private String riskStatus(double impactScore) {
        if (impactScore >= 80.0) {
            return "CRITICAL";
        }
        if (impactScore >= 60.0) {
            return "BAD";
        }
        if (impactScore >= 35.0) {
            return "WATCH";
        }
        return "GOOD";
    }

    private double averageNumber(List<Map<String, Object>> rows, String key) {
        return rows.stream()
                .mapToDouble(row -> number(row.get(key)))
                .filter(Double::isFinite)
                .average()
                .orElse(0.0);
    }

    private int countMatches(List<Map<String, Object>> rows, String key, boolean expected) {
        int count = 0;
        for (Map<String, Object> row : rows) {
            Object value = row.get(key);
            if (Boolean.TRUE.equals(value) == expected) {
                count++;
            }
        }
        return count;
    }

    private int intersectionCount(Set<Integer> left, Set<Integer> right) {
        int count = 0;
        for (Integer value : left) {
            if (value != null && right.contains(value)) {
                count++;
            }
        }
        return count;
    }

    private double normalizeRate(Double value, double fallback) {
        if (value == null || !Double.isFinite(value)) {
            return fallback;
        }
        double normalized = value;
        if (normalized >= 0.0 && normalized <= 1.5) {
            normalized *= 100.0;
        }
        return clamp(normalized, 0.0, 100.0);
    }

    private double normalizeVolume(Double value, double fallback) {
        if (value == null || !Double.isFinite(value)) {
            return fallback;
        }
        return clamp(Math.log1p(Math.max(0.0, value)) * 22.0, 0.0, 100.0);
    }

    private double handoverRisk(double successRate, double attemptPressure, double failurePressure) {
        return clamp((100.0 - successRate) * 0.70 + attemptPressure * 0.15 + failurePressure * 0.15, 0.0, 100.0);
    }

    private double distanceRisk(double distanceKm) {
        if (!Double.isFinite(distanceKm) || distanceKm <= 0.0) {
            return 20.0;
        }
        if (distanceKm <= 1.0) {
            return 15.0;
        }
        if (distanceKm <= 3.0) {
            return 35.0;
        }
        if (distanceKm <= 5.0) {
            return 60.0;
        }
        if (distanceKm <= 8.0) {
            return 80.0;
        }
        return 95.0;
    }

    private double timingAdvanceRisk(double timingAdvance) {
        if (!Double.isFinite(timingAdvance) || timingAdvance <= 0.0) {
            return 20.0;
        }
        if (timingAdvance <= 2.0) {
            return 20.0;
        }
        if (timingAdvance <= 5.0) {
            return 40.0;
        }
        if (timingAdvance <= 10.0) {
            return 65.0;
        }
        return 85.0;
    }

    private double missingNeighborRisk(SiteMasterAggregate site, int neighbourCount, int clusterNeighbourCount) {
        int expected = Math.max(3, site.sectorCount() + 1);
        int deficit = Math.max(0, expected - neighbourCount);
        double risk = deficit * 22.0;
        if (clusterNeighbourCount == 0 && site.cellCount() > 1) {
            risk += 20.0;
        }
        return clamp(risk, 0.0, 100.0);
    }

    private String neighbourRecommendation(boolean samePci, boolean facing, boolean sameCluster, double distanceKm, int pciOverlap) {
        if (samePci && facing) {
            return "High PCI collision risk. Review PCI reuse, azimuth overlap, and neighbour relation.";
        }
        if (samePci) {
            return "Review PCI reuse and same-PCI neighbour overlap.";
        }
        if (facing && distanceKm <= 3.0) {
            return "Check overshooting, neighbor dominance, and handover tuning.";
        }
        if (sameCluster) {
            return "Verify cluster neighbors and handover relation quality.";
        }
        if (pciOverlap > 0) {
            return "Inspect PCI overlap and handover behavior.";
        }
        return "Monitor the neighbour and validate relation coverage.";
    }

    private double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private double distanceKm(Double lat1, Double lon1, Double lat2, Double lon2) {
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) {
            return Double.POSITIVE_INFINITY;
        }
        double earthRadiusKm = 6371.0;
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaPhi = Math.toRadians(lat2 - lat1);
        double deltaLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
        return round(earthRadiusKm * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
    }

    private double angleDeltaDeg(Integer left, Integer right) {
        if (left == null || right == null) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.abs(((left - right + 540.0) % 360.0) - 180.0);
    }

    private String deriveClusterFromSite(String siteName) {
        if (siteName == null || siteName.isBlank()) {
            return "";
        }
        String[] parts = siteName.split("[_\\-]");
        return parts.length == 0 ? siteName : parts[0];
    }

    private class SiteMasterAggregate {
        private final String siteKey;
        private String siteId = "";
        private String siteName = "";
        private String cluster = "";
        private String region = "";
        private String technology = "";
        private String band = "";
        private Integer azimuth;
        private Double latSum;
        private Double lonSum;
        private int coordinateCount;
        private int rowCount;
        private final Set<String> cells = new LinkedHashSet<>();
        private final Set<String> sectors = new LinkedHashSet<>();
        private final Set<String> bands = new LinkedHashSet<>();
        private final Set<String> technologies = new LinkedHashSet<>();
        private final Set<Integer> pcis = new LinkedHashSet<>();

        private SiteMasterAggregate(String label) {
            this.siteKey = label == null || label.isBlank() ? "Unknown Site" : label;
            this.siteName = this.siteKey;        }

        private void merge(SiteData row) {
            rowCount++;
            siteId = firstNonBlank(siteId, row.getSiteId());
            siteName = firstNonBlank(siteName, row.getSiteName(), row.getSiteId(), siteKey);
            cluster = firstNonBlank(cluster, row.getCluster(), deriveClusterFromSite(siteName));
            region = firstNonBlank(region, row.getRegion());
            technology = firstNonBlank(technology, row.getTechnology());
            band = firstNonBlank(band, row.getBand());
            if (row.getAzimuth() != null && azimuth == null) {
                azimuth = row.getAzimuth();
            }
            if (row.getLat() != null) {
                latSum = (latSum == null ? 0.0 : latSum) + row.getLat();
                coordinateCount++;
            }
            if (row.getLon() != null) {
                lonSum = (lonSum == null ? 0.0 : lonSum) + row.getLon();
            }
            add(cells, row.getCellName());
            add(sectors, row.getSector(), row.getSecId());
            add(bands, row.getBand());
            add(technologies, row.getTechnology());
            if (row.getPci() != null) {
                pcis.add(row.getPci());
            }
        }

        private String displayName() {
            return firstNonBlank(siteName, siteId, siteKey);
        }

        private String primaryTechnology() {
            return firstNonBlank(technology, technologies.stream().findFirst().orElse(""));
        }

        private String primaryBand() {
            return firstNonBlank(band, bands.stream().findFirst().orElse(""));
        }

        private int cellCount() {
            return cells.size();
        }

        private int sectorCount() {
            return sectors.size();
        }

        private int pciCount() {
            return pcis.size();
        }

        private Double lat() {
            if (coordinateCount <= 0 || latSum == null) {
                return null;
            }
            return latSum / coordinateCount;
        }

        private Double lon() {
            if (coordinateCount <= 0 || lonSum == null) {
                return null;
            }
            return lonSum / coordinateCount;
        }
    }

    private class SiteSignalSummary {
        private final String site;
        private final List<Double> handoverSuccess = new ArrayList<>();
        private final List<Double> handoverAttempts = new ArrayList<>();
        private final List<Double> handoverFailures = new ArrayList<>();
        private final List<Double> timingAdvance = new ArrayList<>();
        private final List<Double> distances = new ArrayList<>();
        private final List<Double> interference = new ArrayList<>();
        private String latestObservedAt = "";

        private SiteSignalSummary(String site) {
            this.site = site;
        }

        private void merge(Map<String, Object> record) {
            String date = firstNonBlank(value(record, "date"), value(record, "datetime"));
            if (!date.isBlank() && (latestObservedAt.isBlank() || date.compareTo(latestObservedAt) > 0)) {
                latestObservedAt = date;
            }
            Map<String, Object> metrics = objectMap(record.get("metrics"));
            for (Map.Entry<String, Object> entry : metrics.entrySet()) {
                Double numeric = number(entry.getValue());
                if (numeric == null) {
                    continue;
                }
                String key = normalize(entry.getKey());
                if (containsAny(key, "hosr", "success", "successrate", "handoversuccess", "handoverrate")) {
                    handoverSuccess.add(numeric);
                }
                if (containsAny(key, "attempt", "att", "handoverattempt")) {
                    handoverAttempts.add(numeric);
                }
                if (containsAny(key, "fail", "failure", "drop", "handoverfail")) {
                    handoverFailures.add(numeric);
                }
                if (containsAny(key, "ta", "timingadvance", "timing", "advancedistance")) {
                    timingAdvance.add(numeric);
                }
                if (containsAny(key, "distance", "uedistance", "cellradius")) {
                    distances.add(numeric);
                }
                double risk = interferenceRiskFromMetric(key, numeric);
                if (risk > 0.0) {
                    interference.add(risk);
                }
            }
        }

        private double averageHandoverSuccess() {
            return average(handoverSuccess);
        }

        private double averageHandoverAttempts() {
            return average(handoverAttempts);
        }

        private double averageHandoverFailures() {
            return average(handoverFailures);
        }

        private double averageTimingAdvance() {
            return average(timingAdvance);
        }

        private double averageDistance() {
            return average(distances);
        }

        private double averageInterferenceRisk() {
            return average(interference);
        }

        private String latestObservedAt() {
            return latestObservedAt;
        }

        private double average(List<Double> values) {
            return values.stream().filter(Double::isFinite).mapToDouble(Double::doubleValue).average().orElse(0.0);
        }

        private boolean containsAny(String text, String... needles) {
            for (String needle : needles) {
                if (text.contains(needle)) {
                    return true;
                }
            }
            return false;
        }

        private double interferenceRiskFromMetric(String key, double value) {
            if (containsAny(key, "sinr")) {
                return clamp((10.0 - value) * 10.0, 0.0, 100.0);
            }
            if (containsAny(key, "cqi")) {
                return clamp((7.0 - value) * 15.0, 0.0, 100.0);
            }
            if (containsAny(key, "rssi", "noise", "interference")) {
                return clamp((value + 113.0) * 8.0, 0.0, 100.0);
            }
            return 0.0;
        }
    }

    private class ClusterAggregate {
        private final String cluster;
        private int siteCount;
        private double impactSum;
        private int criticalSites;
        private String worstSite = "";
        private double worstImpact = -1.0;

        private ClusterAggregate(String cluster) {
            this.cluster = cluster;
        }

        private void add(Map<String, Object> site) {
            siteCount++;
            double impact = number(site.get("impactScore"));
            impactSum += impact;
            String status = String.valueOf(site.get("status"));
            if ("CRITICAL".equalsIgnoreCase(status)) {
                criticalSites++;
            }
            if (impact > worstImpact) {
                worstImpact = impact;
                worstSite = String.valueOf(site.get("site"));
            }
        }

        private double averageImpactScore() {
            return siteCount == 0 ? 0.0 : impactSum / siteCount;
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("cluster", cluster);
            payload.put("siteCount", siteCount);
            payload.put("averageImpactScore", Math.round(averageImpactScore() * 100.0) / 100.0);
            payload.put("criticalSites", criticalSites);
            payload.put("worstSite", worstSite);
            payload.put("worstImpactScore", Math.round(Math.max(0.0, worstImpact) * 100.0) / 100.0);
            return payload;
        }
    }
}

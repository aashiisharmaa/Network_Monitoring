package com.network.monitoring.service;

import com.network.monitoring.entity.UploadHistory;
import com.network.monitoring.repository.UploadHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class KpiValidationReportService {

    private final UploadHistoryRepository uploadHistoryRepository;
    private final KpiCacheService kpiCacheService;
    private final KpiDynamicDataService kpiDynamicDataService;
    private final KpiThresholdRuleService thresholdRuleService;

    public KpiValidationReportService(UploadHistoryRepository uploadHistoryRepository,
                                      KpiCacheService kpiCacheService,
                                      KpiDynamicDataService kpiDynamicDataService,
                                      KpiThresholdRuleService thresholdRuleService) {
        this.uploadHistoryRepository = uploadHistoryRepository;
        this.kpiCacheService = kpiCacheService;
        this.kpiDynamicDataService = kpiDynamicDataService;
        this.thresholdRuleService = thresholdRuleService;
    }

    @Transactional
    public Map<String, Object> buildReport(Long fileId) {
        if (fileId == null) {
            throw new IllegalArgumentException("fileId is required.");
        }

        UploadHistory upload = uploadHistoryRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("No upload found for fileId " + fileId + "."));
        Map<String, Object> cache = kpiCacheService.readKpiCache(fileId);
        List<Map<String, Object>> records = kpiDynamicDataService.findRecordsByFileId(fileId);
        if (records.isEmpty() && cache != null) {
            records = kpiDynamicDataService.getRecordsFromCachePayload(cache);
        }
        Map<String, Object> thresholdResult = thresholdRuleService.getStoredResult(fileId);
        if ((thresholdResult == null || intValue(thresholdResult.get("evaluatedValues")) == 0) && !records.isEmpty()) {
            Map<String, Object> generatedThresholdSummary = thresholdRuleService.evaluateUploadedRecords(fileId, records);
            thresholdRuleService.saveEvaluationResult(fileId, generatedThresholdSummary);
            thresholdResult = thresholdRuleService.getStoredResult(fileId);
        }

        List<String> warnings = new ArrayList<>();
        Map<String, Integer> missing = missingDimensionCounts(records);
        missing.forEach((key, count) -> {
            if (count > 0) {
                warnings.add(count + " row(s) have missing " + key + ".");
            }
        });

        int metricColumns = listSize(cache == null ? null : cache.get("selectedMetricKeys"));
        if (metricColumns == 0) {
            metricColumns = countMetricKeys(records);
        }
        if (metricColumns == 0) {
            warnings.add("No numeric KPI metric columns were detected.");
        }

        int duplicateRows = countDuplicateRows(records);
        if (duplicateRows > 0) {
            warnings.add(duplicateRows + " duplicate dynamic KPI row(s) were detected.");
        }

        int rowsWithoutMetrics = countRowsWithoutMetrics(records);
        if (rowsWithoutMetrics > 0) {
            warnings.add(rowsWithoutMetrics + " row(s) have no numeric KPI metrics.");
        }

        if (thresholdResult == null) {
            warnings.add("No stored threshold result exists for this file. Recheck thresholds to create one.");
        } else if (intValue(thresholdResult.get("matchedRules")) == 0) {
            warnings.add("No threshold rules matched this file's KPI metric names.");
        }

        Map<String, Object> detectedColumns = new LinkedHashMap<>();
        if (cache != null) {
            detectedColumns.put("metrics", cache.getOrDefault("selectedMetricKeys", List.of()));
            detectedColumns.put("filters", cache.getOrDefault("selectedFilterKeys", List.of()));
            detectedColumns.put("columnStats", cache.getOrDefault("columnStats", Map.of()));
        }

        int invalidRows = missing.values().stream().mapToInt(Integer::intValue).sum() + rowsWithoutMetrics;
        String status = warnings.isEmpty() ? "PASSED" : invalidRows > 0 ? "WARNING" : "INFO";

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("fileId", fileId);
        report.put("fileName", upload.getFileName());
        report.put("uploadedBy", upload.getUploadedBy());
        report.put("uploadedAt", upload.getCreatedAt());
        report.put("status", status);
        report.put("totalRows", records.size());
        report.put("validRows", Math.max(0, records.size() - invalidRows));
        report.put("invalidRows", invalidRows);
        report.put("duplicateRows", duplicateRows);
        report.put("metricColumns", metricColumns);
        report.put("rowsWithoutMetrics", rowsWithoutMetrics);
        report.put("missingDimensions", missing);
        report.put("detectedColumns", detectedColumns);
        report.put("thresholdResult", thresholdResult);
        report.put("warnings", warnings);
        return report;
    }

    private Map<String, Integer> missingDimensionCounts(List<Map<String, Object>> records) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("date", 0);
        counts.put("cell", 0);
        counts.put("site", 0);
        counts.put("technology", 0);
        for (Map<String, Object> record : records) {
            if (blank(dimensionValue(record, "date", "date", "datetime", "timestamp"))) counts.compute("date", (k, v) -> v + 1);
            if (blank(dimensionValue(record, "cellName", "cellid", "cell_id", "cell", "shortname", "short_name"))) counts.compute("cell", (k, v) -> v + 1);
            if (blank(dimensionValue(record, "site", "siteid", "site_id", "sitename", "site_name"))) counts.compute("site", (k, v) -> v + 1);
            if (blank(dimensionValue(record, "tech", "tech", "technology", "rat"))) counts.compute("technology", (k, v) -> v + 1);
        }
        return counts;
    }

    @SuppressWarnings("unchecked")
    private int countMetricKeys(List<Map<String, Object>> records) {
        Set<String> keys = new LinkedHashSet<>();
        for (Map<String, Object> record : records) {
            Object metrics = record.get("metrics");
            if (metrics instanceof Map<?, ?> map) {
                map.keySet().forEach(key -> {
                    if (key != null) keys.add(String.valueOf(key));
                });
            }
        }
        return keys.size();
    }

    private int countRowsWithoutMetrics(List<Map<String, Object>> records) {
        int count = 0;
        for (Map<String, Object> record : records) {
            if (!(record.get("metrics") instanceof Map<?, ?> map) || map.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private int countDuplicateRows(List<Map<String, Object>> records) {
        Set<String> seen = new LinkedHashSet<>();
        int duplicates = 0;
        for (Map<String, Object> record : records) {
            String key = string(record.get("date")) + "|" + string(record.get("cellName")) + "|" + string(record.get("site")) + "|" + string(record.get("tech"));
            if (!seen.add(key)) {
                duplicates++;
            }
        }
        return duplicates;
    }

    private int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private int intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private boolean blank(Object value) {
        return string(value).isBlank();
    }

    private String dimensionValue(Map<String, Object> record, String topLevelKey, String... dimensionKeys) {
        String topLevel = string(record.get(topLevelKey));
        if (!topLevel.isBlank()) {
            return topLevel;
        }
        Object rawDimensions = record.get("dimensions");
        if (rawDimensions instanceof Map<?, ?> dimensions) {
            for (String key : dimensionKeys) {
                String value = string(dimensions.get(key));
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return "";
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}

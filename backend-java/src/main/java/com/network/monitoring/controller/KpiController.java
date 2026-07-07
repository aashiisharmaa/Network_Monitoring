package com.network.monitoring.controller;

import com.network.monitoring.entity.UploadData;
import com.network.monitoring.service.KpiService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/kpi")
public class KpiController {

    private static final int DEFAULT_EXPORT_LONG_ROW_LIMIT = 10000;
    private static final int MAX_EXPORT_LONG_ROW_LIMIT = 50000;
    private static final int MAX_EXPORT_GROUP_SHEETS = 6;

    private final KpiService kpiService;

    public KpiController(KpiService kpiService) {
        this.kpiService = kpiService;
    }

    @GetMapping("/available-metrics")
    public ResponseEntity<Map<String, Object>> getAvailableMetrics(
            @RequestParam(value = "fileId", required = false) Long fileId) {
        Map<String, Integer> metricCounts = kpiService.countAvailableMetrics(fileId);
        List<String> availableMetrics = metricCounts.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .toList();
        List<Map<String, Object>> dynamicMetrics = List.of();
        if (fileId != null) {
            Map<String, Object> cache = kpiService.readKpiCache(fileId);
            if (cache == null) {
                cache = buildCacheFromDynamicRecords(kpiService.findDynamicRecords(fileId));
            }
            if (cache != null) {
                dynamicMetrics = buildDynamicMetricList(cache, getSelectedMetricKeys(cache), getStringMap(cache, "metricLabels"));
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("fileId", fileId);
        response.put("availableMetrics", availableMetrics);
        response.put("metricCounts", metricCounts);
        response.put("dynamicAvailableMetrics", dynamicMetrics);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/all")
    public ResponseEntity<Map<String, Object>> getAllKpis(
            @RequestParam(value = "fileId", required = false) Long fileId) {
        List<UploadData> rows = kpiService.findAllKpis(fileId);
        List<Map<String, Object>> data = rows.stream().map(this::toAllKpiPayload).toList();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "count", data.size(),
                "data", data
        ));
    }

    @GetMapping("/ul-prb-utilization")
    public ResponseEntity<Map<String, Object>> getUlPrbUtilization(
            @RequestParam(value = "fileId", required = false) Long fileId) {
        return buildMetricResponse("ulPrbUtilizationRate", "UL PRB Utilization Rate(%)", 10000, fileId);
    }

    @GetMapping("/dl-prb-utilization")
    public ResponseEntity<Map<String, Object>> getDlPrbUtilization(
            @RequestParam(value = "fileId", required = false) Long fileId) {
        return buildMetricResponse("dlPrbUtilizationRate", "DL PRB Utilization Rate(%)", 1000, fileId);
    }

    @GetMapping("/data-volume")
    public ResponseEntity<Map<String, Object>> getDataVolume(
            @RequestParam(value = "fileId", required = false) Long fileId) {
        return buildMetricResponse("ume4gDataVolumeStdMapsMb9035931", "UME_4G_Data_Volume_STD_MAPS_MB_903593_1", 2000, fileId, true);
    }

    @GetMapping("/ul-throughput")
    public ResponseEntity<Map<String, Object>> getUlThroughput(
            @RequestParam(value = "fileId", required = false) Long fileId) {
        return buildMetricResponse("umeEutranIpThroughputUeUlStdKbps", "UME_E-UTRAN IP Throughput UE UL_STD(Kbps)", 2000, fileId);
    }

    @GetMapping("/dl-throughput")
    public ResponseEntity<Map<String, Object>> getDlThroughput(
            @RequestParam(value = "fileId", required = false) Long fileId) {
        return buildMetricResponse("umeEutranIpThroughputUeDlStdKbps", "UME_E-UTRAN IP Throughput UE DL_STD(Kbps)", 2000, fileId);
    }

    @GetMapping("/erab-drop-rate")
    public ResponseEntity<Map<String, Object>> getErabDropRate(
            @RequestParam(value = "fileId", required = false) Long fileId) {
        return buildMetricResponse("erabDropRate", "E-RAB Drop Rate(%)", 2000, fileId);
    }

    @GetMapping("/erab-success-rate")
    public ResponseEntity<Map<String, Object>> getErabSuccessRate(
            @RequestParam(value = "fileId", required = false) Long fileId) {
        return buildMetricResponse("initialErabEstablishmentSuccessRate", "initial ERAB Establishment Success Rate", 2000, fileId);
    }

    @GetMapping("/rrc-success-rate")
    public ResponseEntity<Map<String, Object>> getRrcSuccessRate(
            @RequestParam(value = "fileId", required = false) Long fileId) {
        return buildMetricResponse("rrcEstablishmentSuccessRate", "RRC Establishment Success Rate(%)", 2000, fileId);
    }

    @GetMapping("/mean-rrc-users")
    public ResponseEntity<Map<String, Object>> getMeanRrcUsers(
            @RequestParam(value = "fileId", required = false) Long fileId) {
        return buildMetricResponse("meanRrcConnectedUserNumber", "Mean RRC-Connected User Number", 2000, fileId);
    }

    @GetMapping("/max-rrc-users")
    public ResponseEntity<Map<String, Object>> getMaxRrcUsers(
            @RequestParam(value = "fileId", required = false) Long fileId) {
        return buildMetricResponse("maximumRrcConnectedUserNumber", "Maximum RRC-Connected User Number", 2000, fileId);
    }

    @GetMapping("/erab-setup-rate")
    public ResponseEntity<Map<String, Object>> getErabSetupRate(
            @RequestParam(value = "fileId", required = false) Long fileId) {
        return buildMetricResponse("erabSetupSuccessRate", "E-RAB Setup Success Rate(%)", 2000, fileId);
    }

    @GetMapping("/rrc-drop-rate")
    public ResponseEntity<Map<String, Object>> getRrcDropRate(
            @RequestParam(value = "fileId", required = false) Long fileId) {
        return buildMetricResponse("rrcDropRate", "RRC Drop Rate(%)", 2000, fileId);
    }

    @GetMapping("/volte-cssr")
    public ResponseEntity<Map<String, Object>> getVolteCssr(
            @RequestParam(value = "fileId", required = false) Long fileId) {
        return buildMetricResponse("volteCssrEric", "VOLTE CSSR_Eric", 2000, fileId);
    }

    @GetMapping("/volte-dcr")
    public ResponseEntity<Map<String, Object>> getVolteDcr(
            @RequestParam(value = "fileId", required = false) Long fileId) {
        return buildMetricResponse("volteDcrEric", "VOLTE DCR_Eric", 2000, fileId);
    }

    @GetMapping("/inter-freq-hosr")
    public ResponseEntity<Map<String, Object>> getInterFreqHosr(
            @RequestParam(value = "fileId", required = false) Long fileId) {
        return buildMetricResponse("interFreqHosr", "Inter Freq HOSR", 2000, fileId);
    }

    @GetMapping("/intra-freq-hosr")
    public ResponseEntity<Map<String, Object>> getIntraFreqHosr(
            @RequestParam(value = "fileId", required = false) Long fileId) {
        return buildMetricResponse("intraFreqHosr", "Intra Freq HOSR", 2000, fileId);
    }

    @GetMapping("/csfb-success-rate")
    public ResponseEntity<Map<String, Object>> getCsfbSuccessRate(
            @RequestParam(value = "fileId", required = false) Long fileId) {
        return buildMetricResponse("csfbSuccessRate", "CSFB Success rate", 2000, fileId);
    }

    @GetMapping("/dynamic/metrics")
    public ResponseEntity<Map<String, Object>> getDynamicMetrics(
            @RequestParam(value = "fileId") Long fileId) {
        Map<String, Object> cache = kpiService.readKpiCache(fileId);
        if (cache == null) {
            cache = buildCacheFromDynamicRecords(kpiService.findDynamicRecords(fileId));
        }
        List<String> selectedMetricKeys = getSelectedMetricKeys(cache);
        Map<String, String> metricLabels = getStringMap(cache, "metricLabels");
        List<Map<String, Object>> metrics = buildDynamicMetricList(cache, selectedMetricKeys, metricLabels);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "fileId", fileId,
                "metrics", metrics
        ));
    }

    @GetMapping("/dynamic/summary")
    public ResponseEntity<Map<String, Object>> getDynamicSummary(
            @RequestParam(value = "fileId") Long fileId) {
        Map<String, Object> cache = kpiService.readKpiCache(fileId);
        if (cache == null) {
            cache = buildCacheFromDynamicRecords(kpiService.findDynamicRecords(fileId));
        }
        List<String> selectedMetricKeys = getSelectedMetricKeys(cache);
        Map<String, String> metricLabels = getStringMap(cache, "metricLabels");
        List<Map<String, Object>> metrics = buildDynamicSummaryMetrics(cache, selectedMetricKeys, metricLabels);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "fileId", fileId,
                "metrics", metrics
        ));
    }

    @GetMapping("/dynamic/columns")
    public ResponseEntity<Map<String, Object>> getDynamicColumns(
            @RequestParam(value = "fileId") Long fileId) {
        Map<String, Object> cache = kpiService.readKpiCache(fileId);
        if (cache == null) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "fileId", fileId,
                    "columns", List.of(),
                    "selectedMetricKeys", List.of(),
                    "selectedFilterKeys", List.of()
            ));
        }
        Map<String, Object> columnStats = getObjectMap(cache.get("columnStats"));
        List<String> selectedMetricKeys = getSelectedMetricKeys(cache);
        List<String> selectedFilterKeys = getSelectedFilterKeys(cache);
        Map<String, String> metricLabels = getStringMap(cache, "metricLabels");

        List<Map<String, Object>> columns = columnStats.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> stat = getObjectMap(entry.getValue());
                    String key = entry.getKey();
                    double numericRatio = toDouble(stat.get("numericRatio")) * 100.0;
                    return Map.<String, Object>of(
                            "key", key,
                            "label", formatMetricLabel(metricLabels.getOrDefault(key, String.valueOf(stat.getOrDefault("label", key)))),
                            "rawLabel", String.valueOf(stat.getOrDefault("label", metricLabels.getOrDefault(key, key))),
                            "numericRatio", Math.round(numericRatio * 10.0) / 10.0,
                            "numericCount", toInt(stat.get("numericCount")),
                            "nonEmptyCount", toInt(stat.get("nonEmptyCount")),
                            "isDimension", Boolean.TRUE.equals(stat.get("isDimension")),
                            "hasKpiHint", Boolean.TRUE.equals(stat.get("hasKpiHint")),
                            "selected", selectedMetricKeys.contains(key),
                            "selectedForFilter", selectedFilterKeys.contains(key)
                    );
                })
                .toList();

        return ResponseEntity.ok(Map.of(
                "success", true,
                "fileId", fileId,
                "columns", columns,
                "selectedMetricKeys", selectedMetricKeys,
                "selectedFilterKeys", selectedFilterKeys
        ));
    }

    @GetMapping("/dynamic/data")
    public ResponseEntity<Map<String, Object>> getDynamicData(
            @RequestParam(value = "fileId") Long fileId,
            @RequestParam(value = "metricKey", required = false) String metricKey) {
        metricKey = metricKey == null ? "" : metricKey.trim();
        if (metricKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "fileId and metricKey query parameters are required."
            ));
        }
        Map<String, Object> cache = kpiService.readKpiCache(fileId);
        if (cache == null) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "fileId", fileId,
                    "metricKey", metricKey,
                    "kpi", formatMetricLabel(metricKey),
                    "data", List.of(),
                    "statistics", buildStatistics(List.of())
            ));
        }
        Map<String, String> metricLabels = getStringMap(cache, "metricLabels");
        List<String> selectedMetricKeys = getSelectedMetricKeys(cache);
        if (!selectedMetricKeys.contains(metricKey)) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "fileId", fileId,
                    "metricKey", metricKey,
                    "kpi", formatMetricLabel(metricLabels.getOrDefault(metricKey, metricKey)),
                    "data", List.of(),
                    "statistics", buildStatistics(List.of())
            ));
        }

        List<Map<String, Object>> records = getMapRecordList(cache.get("records"));
        List<Map<String, Object>> data = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (Map<String, Object> row : records) {
            Map<String, Object> metrics = getObjectMap(row.get("metrics"));
            Object rawValue = metrics.get(metricKey);
            if (rawValue == null) {
                continue;
            }
            double value = toDouble(rawValue);
            if (!Double.isFinite(value)) {
                continue;
            }
            values.add(value);
            data.add(buildDynamicRowPayload(row, value));
        }
        Map<String, Object> stats = buildStatistics(values);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "fileId", fileId,
                "metricKey", metricKey,
                "kpi", formatMetricLabel(metricLabels.getOrDefault(metricKey, metricKey)),
                "data", data,
                "statistics", Map.of(
                        "count", stats.get("count"),
                        "average", roundDouble(stats.get("average"), 2),
                        "maximum", stats.get("maximum"),
                        "minimum", stats.get("minimum"),
                        "median", roundDouble(stats.get("median"), 2)
                )
        ));
    }

    @GetMapping("/dynamic/batch-data")
    public ResponseEntity<Map<String, Object>> getDynamicBatchData(
            @RequestParam(value = "fileId") Long fileId) {
        Map<String, Object> cache = kpiService.readKpiCache(fileId);
        if (cache == null) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "fileId", fileId,
                    "metrics", List.of()
            ));
        }
        List<String> selectedMetricKeys = getSelectedMetricKeys(cache);
        Map<String, String> metricLabels = getStringMap(cache, "metricLabels");
        List<Map<String, Object>> metrics = buildDynamicBatchMetrics(cache, selectedMetricKeys, metricLabels);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "fileId", fileId,
                "metrics", metrics
        ));
    }

    @PostMapping("/dynamic/selection")
    public ResponseEntity<Map<String, Object>> updateDynamicSelection(
            @RequestParam(value = "fileId") Long fileId,
            @RequestBody(required = false) Map<String, Object> body) {
        boolean hasMetricKeysProp = body != null && (body.containsKey("metricKeys") || body.containsKey("selectedMetricKeys"));
        boolean hasFilterKeysProp = body != null && (body.containsKey("filterKeys") || body.containsKey("selectedFilterKeys"));
        Map<String, Object> updated = kpiService.updateKpiCache(fileId, current -> {
            if (current == null) {
                return Map.of();
            }
            Map<String, Object> result = new LinkedHashMap<>(current);
            List<String> allowedMetrics = getStringList(current, "detectedMetricKeys", "selectedMetricKeys");
            List<String> allowedFilters = getStringList(current, "detectedFilterKeys", "selectedFilterKeys");
            List<String> currentSelectedMetrics = getSelectedMetricKeys(current);
            List<String> currentSelectedFilters = getSelectedFilterKeys(current);
            List<String> metricKeys = toStringList(body == null ? null : body.get(hasMetricKeysProp && body.containsKey("metricKeys") ? "metricKeys" : "selectedMetricKeys"));
            List<String> filterKeys = toStringList(body == null ? null : body.get(hasFilterKeysProp && body.containsKey("filterKeys") ? "filterKeys" : "selectedFilterKeys"));
            List<String> selectedMetrics = hasMetricKeysProp
                    ? metricKeys.stream().filter(allowedMetrics::contains).toList()
                    : currentSelectedMetrics;
            List<String> selectedFilters = hasFilterKeysProp
                    ? filterKeys.stream().filter(allowedFilters::contains).toList()
                    : currentSelectedFilters;
            result.put("selectedMetricKeys", selectedMetrics);
            result.put("selectedFilterKeys", selectedFilters);
            return result;
        });
        if (updated == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("success", false, "message", "KPI cache not found for this file."));
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "fileId", fileId,
                "selectedMetricKeys", getSelectedMetricKeys(updated),
                "selectedFilterKeys", getSelectedFilterKeys(updated)
        ));
    }

    @PostMapping("/dynamic/bad-days")
    public ResponseEntity<Map<String, Object>> getDynamicBadDays(
            @RequestParam(value = "fileId") Long fileId,
            @RequestBody(required = false) Map<String, Object> body) {
        String dimensionKey = body == null || body.get("dimensionKey") == null
                ? ""
                : String.valueOf(body.get("dimensionKey")).trim();
        if (dimensionKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "fileId query and dimensionKey body fields are required."
            ));
        }
        Map<String, Object> cache = kpiService.readKpiCache(fileId);
        if (cache == null) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "fileId", fileId,
                    "mode", "bad_days",
                    "rows", List.of(),
                    "totalComparedCells", 0
            ));
        }
        Map<String, Object> payload = buildDynamicBadDaysPayload(fileId, cache, body == null ? Map.of() : body);
        return ResponseEntity.ok(payload);
    }

    @PostMapping("/dynamic/export")
    public ResponseEntity<?> exportDynamicReport(
            @RequestParam(value = "fileId", required = false) Long fileId,
            @RequestBody(required = false) Map<String, Object> body) {
        if (fileId == null || fileId <= 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "fileId query parameter is required."
            ));
        }

        try {
            Map<String, Object> cache = kpiService.readKpiCache(fileId);
            if (cache == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "success", false,
                        "message", "KPI cache not found for this file."
                ));
            }

            byte[] payload = buildDynamicExportWorkbook(cache, body == null ? Map.of() : body, fileId);
            String filename = String.format("kpi_report_%d_%d.xlsx", fileId, System.currentTimeMillis());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            headers.setContentDisposition(ContentDisposition.builder("attachment")
                    .filename(filename, StandardCharsets.UTF_8)
                    .build());
            return new ResponseEntity<>(payload, headers, HttpStatus.OK);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "message", ex.getMessage()
            ));
        }
    }

    private ResponseEntity<Map<String, Object>> buildMetricResponse(String propertyName, String label, int maxResults, Long fileId) {
        return buildMetricResponse(propertyName, label, maxResults, fileId, false);
    }

    private ResponseEntity<Map<String, Object>> buildMetricResponse(String propertyName, String label, int maxResults, Long fileId, boolean includeTotal) {
        try {
            List<UploadData> rows = kpiService.findUploadDataByMetric(propertyName, fileId, maxResults);
            List<Double> values = rows.stream()
                    .map(row -> getMetricValue(row, propertyName))
                    .filter(Objects::nonNull)
                    .map(Number::doubleValue)
                    .toList();

            Map<String, Object> statistics = new LinkedHashMap<>(buildStatistics(values));
            if (includeTotal) {
                statistics.put("total", roundDouble(values.stream().mapToDouble(Double::doubleValue).sum(), 2));
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("kpi", label);
            result.put("data", rows.stream().map(row -> Map.<String, Object>of(
                    "date", row.getDate(),
                    "cellName", row.getCellName(),
                    "site", row.getSite(),
                    "band", row.getBand(),
                    "tech", row.getTech(),
                    "sectorid", row.getSectorId(),
                    "sectorname", row.getSectorName(),
                    "groups", row.getGroups(),
                    "value", getMetricValue(row, propertyName)
            )).collect(Collectors.toList()));
            result.put("statistics", statistics);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", ex.getMessage()
            ));
        }
    }

    private Map<String, Object> toAllKpiPayload(UploadData row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("date", row.getDate());
        payload.put("Groups", row.getGroups());
        payload.put("Sector_ID", row.getSectorId());
        payload.put("Sector_Name", row.getSectorName());
        payload.put("Cell_Name", row.getCellName());
        payload.put("Site", row.getSite());
        payload.put("Band", row.getBand());
        payload.put("Tech", row.getTech());
        payload.put("UL_PRB_Utilization_Rate", row.getUlPrbUtilizationRate());
        payload.put("DL_PRB_Utilization_Rate", row.getDlPrbUtilizationRate());
        payload.put("UME_4G_Data_Volume_STD_MAPS_MB_903593_1", row.getUme4gDataVolumeStdMapsMb9035931());
        payload.put("UME_E_UTRAN_IP_Throughput_UE_UL_STD_Kbps", row.getUmeEutranIpThroughputUeUlStdKbps());
        payload.put("UME_E_UTRAN_IP_Throughput_UE_DL_STD_Kbps", row.getUmeEutranIpThroughputUeDlStdKbps());
        payload.put("E_RAB_Drop_Rate", row.getErabDropRate());
        payload.put("Initial_ERAB_Establishment_Success_Rate", row.getInitialErabEstablishmentSuccessRate());
        payload.put("RRC_Establishment_Success_Rate", row.getRrcEstablishmentSuccessRate());
        payload.put("Mean_RRC_Connected_User_Number", row.getMeanRrcConnectedUserNumber());
        payload.put("Maximum_RRC_Connected_User_Number", row.getMaximumRrcConnectedUserNumber());
        payload.put("E_RAB_Setup_Success_Rate", row.getErabSetupSuccessRate());
        payload.put("RRC_Drop_Rate", row.getRrcDropRate());
        payload.put("VOLTE_CSSR_Eric", row.getVolteCssrEric());
        payload.put("VOLTE_DCR_Eric", row.getVolteDcrEric());
        payload.put("Inter_Freq_HOSR", row.getInterFreqHosr());
        payload.put("Intra_Freq_HOSR", row.getIntraFreqHosr());
        payload.put("CSFB_Success_Rate", row.getCsfbSuccessRate());
        return payload;
    }

    private String formatMetricLabel(String rawKey) {
        return String.valueOf(rawKey == null ? "" : rawKey)
                .replaceAll("\\[[^\\]]*\\]", "")
                .replace('_', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getObjectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    result.put(key, entry.getValue());
                }
            }
            return result;
        }
        return new LinkedHashMap<>();
    }

    private Map<String, String> getStringMap(Map<String, Object> cache, String key) {
        Map<String, Object> map = getObjectMap(cache.get(key));
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            result.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return result;
    }

    private List<String> getSelectedMetricKeys(Map<String, Object> cache) {
        List<String> selected = getStringList(cache, "selectedMetricKeys", "detectedMetricKeys");
        return selected;
    }

    private Map<String, Object> buildCacheFromDynamicRecords(List<Map<String, Object>> records) {
        Map<String, Object> cache = new LinkedHashMap<>();
        if (records == null || records.isEmpty()) {
            cache.put("records", List.of());
            cache.put("metricLabels", Map.of());
            cache.put("detectedMetricKeys", List.of());
            cache.put("selectedMetricKeys", List.of());
            return cache;
        }

        LinkedHashSet<String> metricKeys = new LinkedHashSet<>();
        for (Map<String, Object> record : records) {
            metricKeys.addAll(getObjectMap(record.get("metrics")).keySet());
        }

        Map<String, String> metricLabels = new LinkedHashMap<>();
        for (String key : metricKeys) {
            metricLabels.put(key, key);
        }

        cache.put("records", records);
        cache.put("metricLabels", metricLabels);
        cache.put("detectedMetricKeys", new ArrayList<>(metricKeys));
        cache.put("selectedMetricKeys", new ArrayList<>(metricKeys));
        return cache;
    }

    private List<String> getSelectedFilterKeys(Map<String, Object> cache) {
        List<String> selected = getStringList(cache, "selectedFilterKeys", "detectedFilterKeys");
        return selected;
    }

    private List<String> getStringList(Map<String, Object> cache, String primaryKey, String fallbackKey) {
        Object value = cache.get(primaryKey);
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        value = cache.get(fallbackKey);
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> item == null ? "" : String.valueOf(item).trim())
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        if (value == null) {
            return List.of();
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? List.of() : List.of(text);
    }

    private int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int normalizeExportLimit(int value) {
        if (value <= 0) {
            return DEFAULT_EXPORT_LONG_ROW_LIMIT;
        }
        return Math.min(value, MAX_EXPORT_LONG_ROW_LIMIT);
    }

    private double toDouble(Object value) {
        if (value == null) {
            return Double.NaN;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            String text = String.valueOf(value).trim();
            if (text.isBlank()) {
                return Double.NaN;
            }
            return Double.parseDouble(text);
        } catch (Exception ignored) {
            return Double.NaN;
        }
    }

    private List<Map<String, Object>> buildDynamicMetricList(Map<String, Object> cache, List<String> selectedMetricKeys, Map<String, String> metricLabels) {
        List<Map<String, Object>> records = getMapRecordList(cache.get("records"));
        Map<String, Integer> metricCounts = new LinkedHashMap<>();
        for (Map<String, Object> row : records) {
            Map<String, Object> metrics = getObjectMap(row.get("metrics"));
            for (String key : metrics.keySet()) {
                if (!selectedMetricKeys.contains(key)) {
                    continue;
                }
                metricCounts.put(key, metricCounts.getOrDefault(key, 0) + 1);
            }
        }

        return metricCounts.entrySet().stream()
                .sorted(Comparator.comparing(entry -> formatMetricLabel(metricLabels.getOrDefault(entry.getKey(), entry.getKey()))))
                .map(entry -> Map.<String, Object>of(
                        "key", entry.getKey(),
                        "label", formatMetricLabel(metricLabels.getOrDefault(entry.getKey(), entry.getKey())),
                        "count", entry.getValue()
                ))
                .toList();
    }

    private List<Map<String, Object>> buildDynamicSummaryMetrics(Map<String, Object> cache, List<String> selectedMetricKeys, Map<String, String> metricLabels) {
        List<Map<String, Object>> records = getMapRecordList(cache.get("records"));
        Map<String, List<Double>> valuesByMetric = new LinkedHashMap<>();
        for (String key : selectedMetricKeys) {
            valuesByMetric.put(key, new ArrayList<>());
        }

        for (Map<String, Object> row : records) {
            Map<String, Object> metrics = getObjectMap(row.get("metrics"));
            for (Map.Entry<String, Object> entry : metrics.entrySet()) {
                if (!valuesByMetric.containsKey(entry.getKey())) {
                    continue;
                }
                double value = toDouble(entry.getValue());
                if (Double.isFinite(value)) {
                    valuesByMetric.get(entry.getKey()).add(value);
                }
            }
        }

        return valuesByMetric.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .sorted(Comparator.comparing(entry -> formatMetricLabel(metricLabels.getOrDefault(entry.getKey(), entry.getKey()))))
                .map(entry -> {
                    Map<String, Object> stats = buildStatistics(entry.getValue());
                    Map<String, Object> statistics = new LinkedHashMap<>();
                    statistics.put("count", stats.get("count"));
                    statistics.put("average", roundDouble(stats.get("average"), 2));
                    statistics.put("maximum", stats.get("maximum"));
                    statistics.put("minimum", stats.get("minimum"));
                    statistics.put("median", roundDouble(stats.get("median"), 2));
                    return Map.<String, Object>of(
                            "key", entry.getKey(),
                            "label", formatMetricLabel(metricLabels.getOrDefault(entry.getKey(), entry.getKey())),
                            "count", stats.get("count"),
                            "statistics", statistics
                    );
                })
                .toList();
    }

    private List<Map<String, Object>> buildDynamicBatchMetrics(Map<String, Object> cache, List<String> selectedMetricKeys, Map<String, String> metricLabels) {
        List<Map<String, Object>> records = getMapRecordList(cache.get("records"));
        Map<String, List<Map<String, Object>>> dataByMetric = new LinkedHashMap<>();
        for (String key : selectedMetricKeys) {
            dataByMetric.put(key, new ArrayList<>());
        }

        for (Map<String, Object> row : records) {
            Map<String, Object> metrics = getObjectMap(row.get("metrics"));
            for (Map.Entry<String, Object> entry : metrics.entrySet()) {
                String metricKey = entry.getKey();
                if (!dataByMetric.containsKey(metricKey)) {
                    continue;
                }
                double value = toDouble(entry.getValue());
                if (!Double.isFinite(value)) {
                    continue;
                }
                dataByMetric.get(metricKey).add(buildDynamicRowPayload(row, value));
            }
        }

        return dataByMetric.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .sorted(Comparator.comparing(entry -> formatMetricLabel(metricLabels.getOrDefault(entry.getKey(), entry.getKey()))))
                .map(entry -> {
                    List<Double> values = entry.getValue().stream()
                            .map(item -> toDouble(item.get("value")))
                            .filter(Double::isFinite)
                            .toList();
                    Map<String, Object> stats = buildStatistics(values);
                    Map<String, Object> statistics = new LinkedHashMap<>();
                    statistics.put("count", stats.get("count"));
                    statistics.put("average", roundDouble(stats.get("average"), 2));
                    statistics.put("maximum", stats.get("maximum"));
                    statistics.put("minimum", stats.get("minimum"));
                    statistics.put("median", roundDouble(stats.get("median"), 2));
                    String label = formatMetricLabel(metricLabels.getOrDefault(entry.getKey(), entry.getKey()));
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("key", entry.getKey());
                    payload.put("metricKey", entry.getKey());
                    payload.put("label", label);
                    payload.put("kpi", label);
                    payload.put("count", stats.get("count"));
                    payload.put("data", entry.getValue());
                    payload.put("statistics", statistics);
                    return payload;
                })
                .toList();
    }

    private Map<String, Object> buildDynamicRowPayload(Map<String, Object> row, double value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("date", row.get("date"));
        payload.put("cellName", row.get("cellName"));
        payload.put("site", row.get("site"));
        payload.put("band", row.get("band"));
        payload.put("tech", row.get("tech"));
        payload.put("sectorid", row.get("sectorid"));
        payload.put("sectorname", row.get("sectorname"));
        payload.put("groups", row.get("groups"));
        Map<String, Object> dimensions = getObjectMap(row.get("dimensions"));
        payload.putAll(dimensions);
        payload.put("value", value);
        return payload;
    }

    private byte[] buildDynamicExportWorkbook(Map<String, Object> cache, Map<String, Object> body, Long fileId) throws Exception {
        List<Map<String, Object>> records = getMapRecordList(cache.get("records"));
        Map<String, String> metricLabels = getStringMap(cache, "metricLabels");
        List<String> selectedMetricKeys = getSelectedMetricKeys(cache);
        List<String> selectedFilterKeys = getSelectedFilterKeys(cache);
        List<String> requestedMetricKeys = toStringList(body.get("metricKeys"));
        List<String> requestedGroupByKeys = toStringList(body.get("groupByKeys"));
        Map<String, Object> activeFiltersInput = getObjectMap(body.get("activeFilters"));
        Map<String, Object> compareRangesInput = getObjectMap(body.get("compareRanges"));
        String exportFormat = "date_matrix".equalsIgnoreCase(String.valueOf(body.getOrDefault("exportFormat", "standard"))) ? "date_matrix" : "standard";
        int longRowLimit = normalizeExportLimit(toInt(body.getOrDefault("maxLongRows", DEFAULT_EXPORT_LONG_ROW_LIMIT)));

        Map<String, String> compareRanges = new LinkedHashMap<>();
        compareRanges.put("preStart", toFilterString(compareRangesInput.getOrDefault("preStart", "")));
        compareRanges.put("preEnd", toFilterString(compareRangesInput.getOrDefault("preEnd", "")));
        compareRanges.put("postStart", toFilterString(compareRangesInput.getOrDefault("postStart", "")));
        compareRanges.put("postEnd", toFilterString(compareRangesInput.getOrDefault("postEnd", "")));

        boolean hasPreRange = !compareRanges.get("preStart").isBlank() || !compareRanges.get("preEnd").isBlank();
        boolean hasPostRange = !compareRanges.get("postStart").isBlank() || !compareRanges.get("postEnd").isBlank();
        boolean isComparisonMode = hasPreRange || hasPostRange;

        Map<String, List<String>> activeFilters = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : activeFiltersInput.entrySet()) {
            List<String> cleaned = toStringList(entry.getValue());
            if (!cleaned.isEmpty()) {
                activeFilters.put(entry.getKey(), cleaned);
            }
        }

        List<String> metricKeys = (requestedMetricKeys.isEmpty() ? selectedMetricKeys : requestedMetricKeys).stream()
                .filter(selectedMetricKeys::contains)
                .toList();
        List<String> groupByKeys = (requestedGroupByKeys.isEmpty() ? selectedFilterKeys : requestedGroupByKeys).stream()
                .filter(selectedFilterKeys::contains)
                .limit(MAX_EXPORT_GROUP_SHEETS)
                .toList();

        List<Map<String, Object>> filteredRecords = records.stream()
                .filter(row -> activeFilters.entrySet().stream().allMatch(entry -> {
                    String rowValue = findRowDimensionValue(row, entry.getKey());
                    return entry.getValue().contains(rowValue);
                }))
                .toList();

        List<PeriodRow> periodRecords = new ArrayList<>();
        if (isComparisonMode) {
            for (Map<String, Object> row : filteredRecords) {
                String datePart = getDatePart(row.get("date"));
                if (hasPreRange && isDatePartWithinRange(datePart, compareRanges.get("preStart"), compareRanges.get("preEnd"))) {
                    periodRecords.add(new PeriodRow("Pre", row));
                }
                if (hasPostRange && isDatePartWithinRange(datePart, compareRanges.get("postStart"), compareRanges.get("postEnd"))) {
                    periodRecords.add(new PeriodRow("Post", row));
                }
            }
        } else {
            for (Map<String, Object> row : filteredRecords) {
                periodRecords.add(new PeriodRow("All", row));
            }
        }

        int preRecordCount = (int) periodRecords.stream().filter(item -> "Pre".equals(item.period())).count();
        int postRecordCount = (int) periodRecords.stream().filter(item -> "Post".equals(item.period())).count();

        List<Map<String, Object>> summaryRows = buildDynamicExportSummaryRows(
                fileId,
                records,
                filteredRecords,
                metricKeys,
                selectedFilterKeys,
                groupByKeys,
                exportFormat,
                isComparisonMode,
                compareRanges,
                preRecordCount,
                postRecordCount,
                activeFilters,
                longRowLimit
        );
        List<Map<String, Object>> kpiOverviewRows = buildDynamicExportOverviewRows(periodRecords, metricKeys, metricLabels, isComparisonMode);
        List<Map<String, Object>> longRows = buildDynamicExportLongRows(periodRecords, metricKeys, selectedFilterKeys, metricLabels, longRowLimit);

        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200)) {
            workbook.setCompressTempFiles(true);
            writeSheetFromRows(workbook, "Summary", summaryRows);
            writeSheetFromRows(workbook, "KPI_Overview", kpiOverviewRows);
            writeSheetFromRows(workbook, "KPI_Data_Long", longRows);

            Set<String> usedSheetNames = new LinkedHashSet<>(List.of("Summary", "KPI_Overview", "KPI_Data_Long"));
            for (int index = 0; index < groupByKeys.size(); index++) {
                String groupKey = groupByKeys.get(index);
                List<Map<String, Object>> sheetRows = buildDynamicExportGroupRows(groupKey, periodRecords, metricKeys, metricLabels, exportFormat, isComparisonMode);
                String sheetName = sanitizeSheetName("By_" + groupKey, "By_Filter_" + (index + 1));
                while (usedSheetNames.contains(sheetName)) {
                    sheetName = sanitizeSheetName(sheetName + "_" + (index + 1), "By_Filter_" + (index + 1));
                }
                usedSheetNames.add(sheetName);
                writeSheetFromRows(workbook, sheetName, sheetRows);
            }

            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                workbook.write(out);
                workbook.dispose();
                return out.toByteArray();
            }
        }
    }

    private List<Map<String, Object>> buildDynamicExportSummaryRows(
            Long fileId,
            List<Map<String, Object>> records,
            List<Map<String, Object>> filteredRecords,
            List<String> metricKeys,
            List<String> selectedFilterKeys,
            List<String> groupByKeys,
            String exportFormat,
            boolean isComparisonMode,
            Map<String, String> compareRanges,
            int preRecordCount,
            int postRecordCount,
            Map<String, List<String>> activeFilters,
            int longRowLimit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(rowOf("Field", "File ID", "Value", fileId));
        rows.add(rowOf("Field", "Exported At", "Value", java.time.Instant.now().toString()));
        rows.add(rowOf("Field", "Total Records (cache)", "Value", records.size()));
        rows.add(rowOf("Field", "Records After Filters", "Value", filteredRecords.size()));
        rows.add(rowOf("Field", "KPI Data Long Row Limit", "Value", longRowLimit));
        rows.add(rowOf("Field", "Group Sheets Included", "Value", groupByKeys.size()));
        rows.add(rowOf("Field", "Selected KPI Count", "Value", metricKeys.size()));
        rows.add(rowOf("Field", "Selected KPI Keys", "Value", String.join(", ", metricKeys)));
        rows.add(rowOf("Field", "Selected Filter Keys", "Value", String.join(", ", selectedFilterKeys)));
        rows.add(rowOf("Field", "GroupBy Keys", "Value", String.join(", ", groupByKeys)));
        rows.add(rowOf("Field", "Export Format", "Value", exportFormat));
        rows.add(rowOf("Field", "Comparison Mode", "Value", isComparisonMode ? "Yes" : "No"));
        rows.add(rowOf("Field", "Pre Start", "Value", compareRanges.getOrDefault("preStart", "")));
        rows.add(rowOf("Field", "Pre End", "Value", compareRanges.getOrDefault("preEnd", "")));
        rows.add(rowOf("Field", "Post Start", "Value", compareRanges.getOrDefault("postStart", "")));
        rows.add(rowOf("Field", "Post End", "Value", compareRanges.getOrDefault("postEnd", "")));
        rows.add(rowOf("Field", "Pre Records", "Value", preRecordCount));
        rows.add(rowOf("Field", "Post Records", "Value", postRecordCount));
        rows.add(rowOf(
                "Field",
                "Applied Filters",
                "Value",
                activeFilters.entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + String.join("|", entry.getValue()))
                        .collect(Collectors.joining("; "))
        ));
        return rows;
    }

    private List<Map<String, Object>> buildDynamicExportOverviewRows(
            List<PeriodRow> periodRecords,
            List<String> metricKeys,
            Map<String, String> metricLabels,
            boolean isComparisonMode) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String metricKey : metricKeys) {
            String metricName = formatMetricLabel(metricLabels.getOrDefault(metricKey, metricKey));
            if (!isComparisonMode) {
                List<Double> values = new ArrayList<>();
                for (PeriodRow periodRow : periodRecords) {
                    Object rawValue = getObjectMap(periodRow.row().get("metrics")).get(metricKey);
                    double numeric = toDouble(rawValue);
                    if (Double.isFinite(numeric)) {
                        values.add(numeric);
                    }
                }
                Map<String, Object> stats = buildStatistics(values);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("metricKey", metricKey);
                row.put("metricName", metricName);
                row.put("count", stats.get("count"));
                row.put("average", roundDouble(stats.get("average"), 2));
                row.put("minimum", stats.get("minimum"));
                row.put("maximum", stats.get("maximum"));
                row.put("median", roundDouble(stats.get("median"), 2));
                rows.add(row);
                continue;
            }

            List<Double> preValues = new ArrayList<>();
            List<Double> postValues = new ArrayList<>();
            for (PeriodRow periodRow : periodRecords) {
                Object rawValue = getObjectMap(periodRow.row().get("metrics")).get(metricKey);
                double numeric = toDouble(rawValue);
                if (!Double.isFinite(numeric)) {
                    continue;
                }
                if ("Pre".equals(periodRow.period())) {
                    preValues.add(numeric);
                } else if ("Post".equals(periodRow.period())) {
                    postValues.add(numeric);
                }
            }

            Map<String, Object> preStats = buildStatistics(preValues);
            Map<String, Object> postStats = buildStatistics(postValues);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("metricKey", metricKey);
            row.put("metricName", metricName);
            row.put("pre_count", preStats.get("count"));
            row.put("pre_average", roundDouble(preStats.get("average"), 2));
            row.put("pre_minimum", preStats.get("minimum"));
            row.put("pre_maximum", preStats.get("maximum"));
            row.put("pre_median", roundDouble(preStats.get("median"), 2));
            row.put("post_count", postStats.get("count"));
            row.put("post_average", roundDouble(postStats.get("average"), 2));
            row.put("post_minimum", postStats.get("minimum"));
            row.put("post_maximum", postStats.get("maximum"));
            row.put("post_median", roundDouble(postStats.get("median"), 2));
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> buildDynamicExportLongRows(
            List<PeriodRow> periodRecords,
            List<String> metricKeys,
            List<String> selectedFilterKeys,
            Map<String, String> metricLabels,
            int rowLimit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int safeLimit = normalizeExportLimit(rowLimit);
        for (PeriodRow periodRow : periodRecords) {
            Map<String, Object> row = periodRow.row();
            Map<String, Object> baseDimensions = new LinkedHashMap<>();
            for (String filterKey : selectedFilterKeys) {
                String value = findRowDimensionValue(row, filterKey);
                if (!value.isBlank()) {
                    baseDimensions.put(filterKey, value);
                }
            }
            Map<String, Object> metrics = getObjectMap(row.get("metrics"));
            for (String metricKey : metricKeys) {
                Object rawValue = metrics.get(metricKey);
                double numeric = toDouble(rawValue);
                if (!Double.isFinite(numeric)) {
                    continue;
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("period", periodRow.period());
                payload.put("date", row.get("date"));
                payload.put("metricKey", metricKey);
                payload.put("metricName", formatMetricLabel(metricLabels.getOrDefault(metricKey, metricKey)));
                payload.put("value", numeric);
                payload.putAll(baseDimensions);
                rows.add(payload);
                if (rows.size() >= safeLimit) {
                    return rows;
                }
            }
        }
        return rows;
    }

    private List<Map<String, Object>> buildDynamicExportGroupRows(
            String groupKey,
            List<PeriodRow> periodRecords,
            List<String> metricKeys,
            Map<String, String> metricLabels,
            String exportFormat,
            boolean isComparisonMode) {
        if ("date_matrix".equalsIgnoreCase(exportFormat)) {
            return buildDynamicExportGroupRowsDateMatrix(groupKey, periodRecords, metricKeys, metricLabels, isComparisonMode);
        }
        return buildDynamicExportGroupRowsStandard(groupKey, periodRecords, metricKeys, metricLabels);
    }

    private List<Map<String, Object>> buildDynamicExportGroupRowsDateMatrix(
            String groupKey,
            List<PeriodRow> periodRecords,
            List<String> metricKeys,
            Map<String, String> metricLabels,
            boolean isComparisonMode) {
        List<String> periods = isComparisonMode ? List.of("Pre", "Post") : List.of("All");
        Map<String, List<String>> dateHeadersByPeriod = new LinkedHashMap<>();
        for (String period : periods) {
            Set<String> dates = new LinkedHashSet<>();
            for (PeriodRow periodRow : periodRecords) {
                if (!period.equals(periodRow.period())) {
                    continue;
                }
                dates.add(formatDateColumnKey(periodRow.row().get("date")));
            }
            List<String> sortedDates = new ArrayList<>(dates);
            sortedDates.sort(String::compareTo);
            dateHeadersByPeriod.put(period, sortedDates);
        }

        List<String> orderedDateHeaders = new ArrayList<>();
        for (String period : periods) {
            for (String dateKey : dateHeadersByPeriod.getOrDefault(period, List.of())) {
                orderedDateHeaders.add(isComparisonMode ? period + "_" + dateKey : dateKey);
            }
        }

        Map<String, Map<String, Map<String, List<Double>>>> matrixBucket = new LinkedHashMap<>();
        for (PeriodRow periodRow : periodRecords) {
            String groupValue = findRowDimensionValue(periodRow.row(), groupKey);
            if (groupValue.isBlank()) {
                groupValue = "N/A";
            }
            String dateKey = formatDateColumnKey(periodRow.row().get("date"));
            String dateHeader = isComparisonMode ? periodRow.period() + "_" + dateKey : dateKey;
            Map<String, Object> metrics = getObjectMap(periodRow.row().get("metrics"));
            for (String metricKey : metricKeys) {
                double numeric = toDouble(metrics.get(metricKey));
                if (!Double.isFinite(numeric)) {
                    continue;
                }
                matrixBucket
                        .computeIfAbsent(groupValue, ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(metricKey, ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(dateHeader, ignored -> new ArrayList<>())
                        .add(numeric);
            }
        }

        List<String> orderedGroupValues = new ArrayList<>(matrixBucket.keySet());
        orderedGroupValues.sort(String::compareTo);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String groupValue : orderedGroupValues) {
            Map<String, Map<String, List<Double>>> metricMap = matrixBucket.get(groupValue);
            for (String metricKey : metricKeys) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put(groupKey, groupValue);
                row.put("KPI", formatMetricLabel(metricLabels.getOrDefault(metricKey, metricKey)));
                for (String dateHeader : orderedDateHeaders) {
                    List<Double> values = metricMap.getOrDefault(metricKey, Map.of()).getOrDefault(dateHeader, List.of());
                    if (values.isEmpty()) {
                        row.put(dateHeader, "");
                        continue;
                    }
                    double average = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                    row.put(dateHeader, roundDouble(average, 4));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private List<Map<String, Object>> buildDynamicExportGroupRowsStandard(
            String groupKey,
            List<PeriodRow> periodRecords,
            List<String> metricKeys,
            Map<String, String> metricLabels) {
        Map<String, Map<String, Map<String, List<Double>>>> bucket = new LinkedHashMap<>();
        for (PeriodRow periodRow : periodRecords) {
            String groupValue = findRowDimensionValue(periodRow.row(), groupKey);
            if (groupValue.isBlank()) {
                groupValue = "N/A";
            }
            Map<String, Object> metrics = getObjectMap(periodRow.row().get("metrics"));
            Map<String, Map<String, List<Double>>> periodBucket = bucket.computeIfAbsent(periodRow.period(), ignored -> new LinkedHashMap<>());
            Map<String, List<Double>> groupBucket = periodBucket.computeIfAbsent(groupValue, ignored -> new LinkedHashMap<>());
            for (String metricKey : metricKeys) {
                double numeric = toDouble(metrics.get(metricKey));
                if (!Double.isFinite(numeric)) {
                    continue;
                }
                groupBucket.computeIfAbsent(metricKey, ignored -> new ArrayList<>()).add(numeric);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Map<String, Map<String, List<Double>>>> periodEntry : bucket.entrySet()) {
            for (Map.Entry<String, Map<String, List<Double>>> groupEntry : periodEntry.getValue().entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put(groupKey, groupEntry.getKey());
                row.put("period", periodEntry.getKey());
                int recordCount = 0;
                for (List<Double> values : groupEntry.getValue().values()) {
                    recordCount = Math.max(recordCount, values.size());
                }
                row.put("recordCount", recordCount);
                for (String metricKey : metricKeys) {
                    List<Double> values = groupEntry.getValue().getOrDefault(metricKey, List.of());
                    if (values.isEmpty()) {
                        row.put(formatMetricLabel(metricLabels.getOrDefault(metricKey, metricKey)), "");
                        continue;
                    }
                    double average = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                    row.put(formatMetricLabel(metricLabels.getOrDefault(metricKey, metricKey)), roundDouble(average, 4));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private void writeSheetFromRows(Workbook workbook, String sheetName, List<Map<String, Object>> rows) {
        Sheet sheet = workbook.createSheet(sheetName);
        if (rows == null || rows.isEmpty()) {
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("No data");
            return;
        }

        LinkedHashSet<String> columns = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            columns.addAll(row.keySet());
        }

        List<String> columnList = new ArrayList<>(columns);
        Row header = sheet.createRow(0);
        for (int i = 0; i < columnList.size(); i++) {
            header.createCell(i).setCellValue(columnList.get(i));
        }

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Row dataRow = sheet.createRow(rowIndex + 1);
            Map<String, Object> row = rows.get(rowIndex);
            for (int columnIndex = 0; columnIndex < columnList.size(); columnIndex++) {
                Cell cell = dataRow.createCell(columnIndex);
                writeCellValue(cell, row.get(columnList.get(columnIndex)));
            }
        }
    }

    private void writeCellValue(Cell cell, Object value) {
        if (value == null) {
            cell.setBlank();
            return;
        }
        if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
            return;
        }
        if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
            return;
        }
        cell.setCellValue(String.valueOf(value));
    }

    private Map<String, Object> rowOf(Object firstKey, Object firstValue, Object secondKey, Object secondValue) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(String.valueOf(firstKey), firstValue);
        row.put(String.valueOf(secondKey), secondValue);
        return row;
    }

    private String sanitizeSheetName(String value, String fallback) {
        String base = String.valueOf(value == null ? fallback : value)
                .replaceAll("[:\\\\/?*\\[\\]]", "_")
                .trim();
        String safe = base.isBlank() ? fallback : base;
        return safe.length() > 31 ? safe.substring(0, 31) : safe;
    }

    private String formatDateColumnKey(Object value) {
        java.time.LocalDateTime parsed = parseComparableDate(value);
        if (parsed == null) {
            return "Unknown_Date";
        }
        return parsed.toLocalDate().toString();
    }

    private Map<String, Object> buildDynamicBadDaysPayload(Long fileId, Map<String, Object> cache, Map<String, Object> body) {
        String dimensionKey = body.get("dimensionKey") == null ? "" : String.valueOf(body.get("dimensionKey")).trim();
        List<Map<String, Object>> records = getMapRecordList(cache.get("records"));
        Map<String, String> metricLabels = getStringMap(cache, "metricLabels");
        List<String> selectedMetricKeys = getSelectedMetricKeys(cache);
        Map<String, ThresholdContext> thresholdContexts = buildThresholdContexts(body, selectedMetricKeys, metricLabels);
        int minimumBadDays = 1;
        Object rawMinimumBadDays = body.get("minimumBadDays");
        if (rawMinimumBadDays != null) {
            try {
                minimumBadDays = Math.max(1, (int) Math.floor(Double.parseDouble(String.valueOf(rawMinimumBadDays))));
            } catch (Exception ignored) {
            }
        }
        Map<String, Object> activeRangeMap = getObjectMap(body.get("activeRange"));
        String activeRangeStart = toFilterString(activeRangeMap.getOrDefault("start", body.get("start")));
        String activeRangeEnd = toFilterString(activeRangeMap.getOrDefault("end", body.get("end")));

        if (thresholdContexts.isEmpty()) {
            return Map.of(
                    "success", true,
                    "fileId", fileId,
                    "mode", "bad_days",
                    "minimumBadDays", minimumBadDays,
                    "activeRange", Map.of("start", activeRangeStart, "end", activeRangeEnd),
                    "rows", List.of(),
                    "totalComparedCells", 0
            );
        }

        Map<String, Map<String, Map<String, MetricAggregate>>> metricDayBuckets = new LinkedHashMap<>();
        Set<String> comparedCellSet = new LinkedHashSet<>();

        for (Map<String, Object> row : records) {
            String datePart = getDatePart(row.get("date"));
            if (!isDatePartWithinRange(datePart, activeRangeStart, activeRangeEnd)) {
                continue;
            }

            String dimensionValue = toFilterString(findRowDimensionValue(row, dimensionKey));
            if (dimensionValue.isBlank()) {
                continue;
            }

            comparedCellSet.add(dimensionValue);
            Map<String, Object> rowMetrics = getObjectMap(row.get("metrics"));
            Map<String, Object> rowMeta = extractDynamicRowMeta(row, dimensionKey, dimensionValue);

            for (Map.Entry<String, Object> metricEntry : rowMetrics.entrySet()) {
                ThresholdContext thresholdContext = thresholdContexts.get(metricEntry.getKey());
                if (thresholdContext == null) {
                    continue;
                }
                double numericValue = toDouble(metricEntry.getValue());
                if (!Double.isFinite(numericValue)) {
                    continue;
                }
                metricDayBuckets
                        .computeIfAbsent(metricEntry.getKey(), ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(dimensionValue, ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(datePart, ignored -> new MetricAggregate(rowMeta))
                        .add(numericValue, rowMeta);
            }
        }

        Map<String, CellState> cellState = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Map<String, MetricAggregate>>> metricEntry : metricDayBuckets.entrySet()) {
            ThresholdContext thresholdContext = thresholdContexts.get(metricEntry.getKey());
            if (thresholdContext == null) {
                continue;
            }
            for (Map.Entry<String, Map<String, MetricAggregate>> cellEntry : metricEntry.getValue().entrySet()) {
                CellState state = cellState.computeIfAbsent(cellEntry.getKey(), key -> new CellState(key));
                for (Map.Entry<String, MetricAggregate> dayEntry : cellEntry.getValue().entrySet()) {
                    MetricAggregate aggregate = dayEntry.getValue();
                    if (aggregate.count == 0) {
                        continue;
                    }
                    double averageValue = aggregate.sum / aggregate.count;
                    boolean pass = evaluateThresholdCondition(averageValue, thresholdContext.thresholdValue, thresholdContext.thresholdOperator);
                    if (pass) {
                        continue;
                    }
                    state.meta = mergeMeta(state.meta, aggregate.meta, cellEntry.getKey());
                    state.badDayMap.computeIfAbsent(dayEntry.getKey(), ignored -> new ArrayList<>()).add(Map.of(
                            "metricKey", thresholdContext.metricKey,
                            "metricLabel", thresholdContext.metricLabel,
                            "thresholdKey", thresholdContext.thresholdKey,
                            "thresholdOperator", thresholdContext.thresholdOperator,
                            "thresholdValue", thresholdContext.thresholdValue,
                            "averageValue", averageValue,
                            "sampleCount", aggregate.count
                    ));
                }
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (CellState cell : cellState.values()) {
            List<Map<String, Object>> badDays = cell.badDayMap.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> Map.<String, Object>of(
                            "date", entry.getKey(),
                            "degradedKpis", entry.getValue(),
                            "degradedKpiCount", entry.getValue().size()
                    ))
                    .toList();
            int badDayCount = badDays.size();
            if (badDayCount < minimumBadDays) {
                continue;
            }

            Map<String, String> uniqueKpiMap = new LinkedHashMap<>();
            int degradedKpiEventCount = 0;
            for (Map<String, Object> day : badDays) {
                degradedKpiEventCount += toInt(day.get("degradedKpiCount"));
                List<Map<String, Object>> degradedKpis = getMapList(day.get("degradedKpis"));
                for (Map<String, Object> kpiItem : degradedKpis) {
                    String metricKey = String.valueOf(kpiItem.get("metricKey"));
                    String metricLabel = String.valueOf(kpiItem.get("metricLabel"));
                    uniqueKpiMap.putIfAbsent(metricKey, metricLabel);
                }
            }

            Map<String, Object> rowPayload = new LinkedHashMap<>();
            rowPayload.put("value", cell.value);
            rowPayload.put("meta", cell.meta);
            rowPayload.put("badDayCount", badDayCount);
            rowPayload.put("degradedKpiUniqueCount", uniqueKpiMap.size());
            rowPayload.put("degradedKpiEventCount", degradedKpiEventCount);
            rowPayload.put("degradedKpiNames", uniqueKpiMap.values().stream().sorted().toList());
            rowPayload.put("badDays", badDays);
            rowPayload.put("activeRange", Map.of("start", activeRangeStart, "end", activeRangeEnd));
            rows.add(rowPayload);
        }

        rows.sort((a, b) -> {
            int badDayCompare = Integer.compare(toInt(b.get("badDayCount")), toInt(a.get("badDayCount")));
            if (badDayCompare != 0) return badDayCompare;
            int eventCompare = Integer.compare(toInt(b.get("degradedKpiEventCount")), toInt(a.get("degradedKpiEventCount")));
            if (eventCompare != 0) return eventCompare;
            int uniqueCompare = Integer.compare(toInt(b.get("degradedKpiUniqueCount")), toInt(a.get("degradedKpiUniqueCount")));
            if (uniqueCompare != 0) return uniqueCompare;
            return String.valueOf(a.get("value")).compareTo(String.valueOf(b.get("value")));
        });

        return Map.of(
                "success", true,
                "fileId", fileId,
                "mode", "bad_days",
                "minimumBadDays", minimumBadDays,
                "activeRange", Map.of("start", activeRangeStart, "end", activeRangeEnd),
                "rows", rows,
                "totalComparedCells", comparedCellSet.size(),
                "evaluatedMetricCount", thresholdContexts.size()
        );
    }

    private Map<String, ThresholdContext> buildThresholdContexts(Map<String, Object> body, List<String> selectedMetricKeys, Map<String, String> metricLabels) {
        Map<String, ThresholdContext> contexts = new LinkedHashMap<>();
        List<Map<String, Object>> thresholdContexts = getMapList(body.get("thresholdContexts"));
        for (Map<String, Object> context : thresholdContexts) {
            String metricKey = String.valueOf(context.get("metricKey") == null ? "" : context.get("metricKey")).trim();
            if (metricKey.isBlank() || !selectedMetricKeys.contains(metricKey)) {
                continue;
            }
            double thresholdValue;
            try {
                thresholdValue = Double.parseDouble(String.valueOf(context.get("thresholdValue")));
            } catch (Exception ex) {
                continue;
            }
            String operator = String.valueOf(context.get("thresholdOperator") == null ? ">=" : context.get("thresholdOperator")).trim();
            if (!Set.of(">", ">=", "<", "<=").contains(operator)) {
                operator = ">=";
            }
            String label = formatMetricLabel(String.valueOf(context.get("metricLabel") == null ? metricLabels.getOrDefault(metricKey, metricKey) : context.get("metricLabel")));
            contexts.put(metricKey, new ThresholdContext(metricKey, label, String.valueOf(context.get("thresholdKey") == null ? metricKey : context.get("thresholdKey")), thresholdValue, operator));
        }
        return contexts;
    }

    private Map<String, Object> extractDynamicRowMeta(Map<String, Object> row, String dimensionKey, String dimensionValue) {
        Map<String, Object> dimensions = getObjectMap(row.get("dimensions"));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("shortName", firstNonBlank(String.valueOf(row.get("shortName")), String.valueOf(row.get("cellName")), String.valueOf(dimensions.get("cellName")), dimensionValue));
        meta.put("cellId", firstNonBlank(String.valueOf(row.get("cellId")), String.valueOf(row.get("cellName")), String.valueOf(dimensions.get("cellId")), dimensionValue));
        meta.put("siteId", firstNonBlank(String.valueOf(row.get("siteId")), String.valueOf(row.get("site")), String.valueOf(dimensions.get("siteId")), String.valueOf(dimensions.get("site"))));
        meta.put("tech", firstNonBlank(String.valueOf(row.get("tech")), String.valueOf(dimensions.get("tech"))));
        meta.put("sector", firstNonBlank(String.valueOf(row.get("sector")), String.valueOf(row.get("sectorname")), String.valueOf(row.get("sectorid")), String.valueOf(dimensions.get("sector")), String.valueOf(dimensions.get("sectorname")), String.valueOf(dimensions.get("sectorid"))));
        meta.put("dimensionValue", toFilterString(findRowDimensionValue(row, dimensionKey)));
        return meta;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isBlank() && !"null".equalsIgnoreCase(trimmed)) {
                return trimmed;
            }
        }
        return "";
    }

    private String toFilterString(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        return text.equalsIgnoreCase("null") ? "" : text;
    }

    private String canonicalizeKey(Object value) {
        return String.valueOf(value == null ? "" : value)
                .toLowerCase()
                .replaceAll("[^a-z0-9]", "");
    }

    private String findRowDimensionValue(Map<String, Object> row, String key) {
        if (row == null || key == null || key.isBlank()) {
            return "";
        }
        Object direct = row.get(key);
        if (direct != null && !String.valueOf(direct).trim().isBlank()) {
            return toFilterString(direct);
        }
        String target = canonicalizeKey(key);
        Map<String, Object> dimensions = getObjectMap(row.get("dimensions"));
        for (Map.Entry<String, Object> entry : dimensions.entrySet()) {
            if (canonicalizeKey(entry.getKey()).equals(target)) {
                return toFilterString(entry.getValue());
            }
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (canonicalizeKey(entry.getKey()).equals(target)) {
                return toFilterString(entry.getValue());
            }
        }
        return "";
    }

    private String getDatePart(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        if (text.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            return text;
        }
        java.time.LocalDateTime parsed = parseComparableDate(value);
        return parsed == null ? "" : parsed.toLocalDate().toString();
    }

    private java.time.LocalDateTime parseComparableDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.time.LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof java.time.LocalDate localDate) {
            return localDate.atStartOfDay();
        }
        if (value instanceof java.util.Date date) {
            return java.time.LocalDateTime.ofInstant(date.toInstant(), java.time.ZoneId.systemDefault());
        }
        if (value instanceof Number number) {
            double excelValue = number.doubleValue();
            long millis = Math.round((excelValue - 25569d) * 86400d * 1000d);
            return java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault());
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDateTime.parse(text);
        } catch (Exception ignored) {
        }
        try {
            return java.time.LocalDate.parse(text).atStartOfDay();
        } catch (Exception ignored) {
        }
        try {
            return java.time.Instant.parse(text).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean isDatePartWithinRange(String datePart, String start, String end) {
        if (datePart == null || datePart.isBlank()) {
            return false;
        }
        if (start != null && !start.isBlank() && datePart.compareTo(start) < 0) {
            return false;
        }
        if (end != null && !end.isBlank() && datePart.compareTo(end) > 0) {
            return false;
        }
        return true;
    }

    private boolean evaluateThresholdCondition(double value, double threshold, String operator) {
        return switch (operator) {
            case ">" -> value > threshold;
            case ">=" -> value >= threshold;
            case "<" -> value < threshold;
            case "<=" -> value <= threshold;
            default -> value >= threshold;
        };
    }

    private double roundDouble(Object value, int scale) {
        double numeric = toDouble(value);
        double factor = Math.pow(10, scale);
        return Math.round(numeric * factor) / factor;
    }

    private List<Map<String, Object>> getMapRecordList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> getObjectMap(item))
                    .toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getMapList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }

    private List<Map<String, Object>> buildDynamicExportRows(Map<String, Object> cache, Map<String, Object> body) {
        List<Map<String, Object>> records = getMapRecordList(cache.get("records"));
        Map<String, String> metricLabels = getStringMap(cache, "metricLabels");
        List<String> selectedMetricKeys = getSelectedMetricKeys(cache);
        List<String> selectedFilterKeys = getSelectedFilterKeys(cache);
        List<String> requestedMetricKeys = toStringList(body.get("metricKeys"));
        List<String> requestedGroupByKeys = toStringList(body.get("groupByKeys"));
        Map<String, Object> activeFiltersInput = getObjectMap(body.get("activeFilters"));
        Map<String, Object> compareRangesInput = getObjectMap(body.get("compareRanges"));
        String exportFormat = "date_matrix".equalsIgnoreCase(String.valueOf(body.getOrDefault("exportFormat", "standard"))) ? "date_matrix" : "standard";

        List<String> metricKeys = (requestedMetricKeys.isEmpty() ? selectedMetricKeys : requestedMetricKeys).stream()
                .filter(selectedMetricKeys::contains)
                .toList();
        List<String> groupByKeys = (requestedGroupByKeys.isEmpty() ? selectedFilterKeys : requestedGroupByKeys).stream()
                .filter(selectedFilterKeys::contains)
                .toList();

        Map<String, List<String>> activeFilters = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : activeFiltersInput.entrySet()) {
            activeFilters.put(entry.getKey(), toStringList(entry.getValue()));
        }

        List<Map<String, Object>> filteredRecords = records.stream()
                .filter(row -> activeFilters.entrySet().stream().allMatch(entry -> {
                    String rowValue = findRowDimensionValue(row, entry.getKey());
                    return entry.getValue().contains(rowValue);
                }))
                .toList();

        // Keep the export lightweight but stable. The TS version builds a multi-sheet workbook;
        // here we preserve the same information flow in a single export payload.
        List<Map<String, Object>> exportRows = new ArrayList<>();
        Map<String, Object> summaryRow = new LinkedHashMap<>();
        summaryRow.put("File ID", body.getOrDefault("fileId", ""));
        summaryRow.put("Exported At", java.time.Instant.now().toString());
        summaryRow.put("Total Records (cache)", records.size());
        summaryRow.put("Records After Filters", filteredRecords.size());
        summaryRow.put("Selected KPI Count", metricKeys.size());
        summaryRow.put("Selected KPI Keys", String.join(", ", metricKeys));
        summaryRow.put("Selected Filter Keys", String.join(", ", selectedFilterKeys));
        summaryRow.put("GroupBy Keys", String.join(", ", groupByKeys));
        summaryRow.put("Export Format", exportFormat);
        summaryRow.put("Comparison Mode", "No");
        summaryRow.put("Pre Start", String.valueOf(compareRangesInput.getOrDefault("preStart", "")));
        summaryRow.put("Pre End", String.valueOf(compareRangesInput.getOrDefault("preEnd", "")));
        summaryRow.put("Post Start", String.valueOf(compareRangesInput.getOrDefault("postStart", "")));
        summaryRow.put("Post End", String.valueOf(compareRangesInput.getOrDefault("postEnd", "")));
        summaryRow.put("Pre Records", 0);
        summaryRow.put("Post Records", 0);
        summaryRow.put("Applied Filters", activeFilters.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + String.join("|", entry.getValue()))
                .collect(Collectors.joining("; ")));
        exportRows.add(summaryRow);

        for (Map<String, Object> row : filteredRecords) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("date", row.get("date"));
            payload.put("cellName", row.get("cellName"));
            payload.put("site", row.get("site"));
            payload.put("band", row.get("band"));
            payload.put("tech", row.get("tech"));
            payload.put("sectorid", row.get("sectorid"));
            payload.put("sectorname", row.get("sectorname"));
            payload.put("groups", row.get("groups"));
            Map<String, Object> metrics = getObjectMap(row.get("metrics"));
            for (String metricKey : metricKeys) {
                payload.put(formatMetricLabel(metricLabels.getOrDefault(metricKey, metricKey)), metrics.get(metricKey));
            }
            exportRows.add(payload);
        }

        return exportRows;
    }

    private record PeriodRow(String period, Map<String, Object> row) {
    }

    private record ThresholdContext(String metricKey, String metricLabel, String thresholdKey, double thresholdValue, String thresholdOperator) {
    }

    private static class MetricAggregate {
        private double sum;
        private int count;
        private Map<String, Object> meta;

        private MetricAggregate(Map<String, Object> meta) {
            this.meta = new LinkedHashMap<>(meta);
        }

        private void add(double value, Map<String, Object> rowMeta) {
            this.sum += value;
            this.count += 1;
            this.meta = mergeMeta(this.meta, rowMeta, String.valueOf(this.meta.getOrDefault("cellId", "")));
        }
    }

    private static class CellState {
        private final String value;
        private Map<String, Object> meta;
        private final Map<String, List<Map<String, Object>>> badDayMap = new LinkedHashMap<>();

        private CellState(String value) {
            this.value = value;
        }
    }

    private static Map<String, Object> mergeMeta(Map<String, Object> existing, Map<String, Object> incoming, String fallbackValue) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("shortName", firstNonBlankStatic(existing, incoming, "shortName", fallbackValue));
        meta.put("cellId", firstNonBlankStatic(existing, incoming, "cellId", fallbackValue));
        meta.put("siteId", firstNonBlankStatic(existing, incoming, "siteId", ""));
        meta.put("tech", firstNonBlankStatic(existing, incoming, "tech", ""));
        meta.put("sector", firstNonBlankStatic(existing, incoming, "sector", ""));
        return meta;
    }

    private static String firstNonBlankStatic(Map<String, Object> existing, Map<String, Object> incoming, String key, String fallback) {
        String first = existing == null ? "" : String.valueOf(existing.getOrDefault(key, ""));
        if (first != null && !first.trim().isBlank() && !"null".equalsIgnoreCase(first.trim())) {
            return first.trim();
        }
        String second = incoming == null ? "" : String.valueOf(incoming.getOrDefault(key, ""));
        if (second != null && !second.trim().isBlank() && !"null".equalsIgnoreCase(second.trim())) {
            return second.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }

    private Number getMetricValue(UploadData row, String propertyName) {
        return switch (propertyName) {
            case "ulPrbUtilizationRate" -> row.getUlPrbUtilizationRate();
            case "dlPrbUtilizationRate" -> row.getDlPrbUtilizationRate();
            case "ume4gDataVolumeStdMapsMb9035931" -> row.getUme4gDataVolumeStdMapsMb9035931();
            case "umeEutranIpThroughputUeUlStdKbps" -> row.getUmeEutranIpThroughputUeUlStdKbps();
            case "umeEutranIpThroughputUeDlStdKbps" -> row.getUmeEutranIpThroughputUeDlStdKbps();
            case "erabDropRate" -> row.getErabDropRate();
            case "initialErabEstablishmentSuccessRate" -> row.getInitialErabEstablishmentSuccessRate();
            case "rrcEstablishmentSuccessRate" -> row.getRrcEstablishmentSuccessRate();
            case "meanRrcConnectedUserNumber" -> row.getMeanRrcConnectedUserNumber();
            case "maximumRrcConnectedUserNumber" -> row.getMaximumRrcConnectedUserNumber();
            case "erabSetupSuccessRate" -> row.getErabSetupSuccessRate();
            case "rrcDropRate" -> row.getRrcDropRate();
            case "volteCssrEric" -> row.getVolteCssrEric();
            case "volteDcrEric" -> row.getVolteDcrEric();
            case "interFreqHosr" -> row.getInterFreqHosr();
            case "intraFreqHosr" -> row.getIntraFreqHosr();
            case "csfbSuccessRate" -> row.getCsfbSuccessRate();
            default -> null;
        };
    }

    private Map<String, Object> buildStatistics(List<Double> values) {
        if (values.isEmpty()) {
            return Map.of(
                    "count", 0,
                    "average", 0,
                    "maximum", 0,
                    "minimum", 0,
                    "median", 0
            );
        }

        List<Double> sorted = values.stream().sorted().toList();
        double sum = values.stream().mapToDouble(Double::doubleValue).sum();
        double average = sum / values.size();
        double minimum = sorted.get(0);
        double maximum = sorted.get(sorted.size() - 1);
        double median = sorted.size() % 2 == 0
                ? (sorted.get(sorted.size() - 1) + sorted.get(sorted.size() - 2)) / 2
                : sorted.get(sorted.size() / 2);

        return Map.of(
                "count", values.size(),
                "average", Math.round(average * 100.0) / 100.0,
                "maximum", maximum,
                "minimum", minimum,
                "median", Math.round(median * 100.0) / 100.0
        );
    }

    private byte[] buildExcelPayload(List<Map<String, Object>> rows) throws Exception {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200)) {
            workbook.setCompressTempFiles(true);
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("Export");
            if (rows.isEmpty()) {
                sheet.createRow(0).createCell(0).setCellValue("No data");
            } else {
                org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
                LinkedHashSet<String> columnSet = new LinkedHashSet<>();
                for (Map<String, Object> row : rows) {
                    columnSet.addAll(row.keySet());
                }
                List<String> columns = new ArrayList<>(columnSet);
                for (int i = 0; i < columns.size(); i++) {
                    header.createCell(i).setCellValue(columns.get(i));
                }

                for (int i = 0; i < rows.size(); i++) {
                    org.apache.poi.ss.usermodel.Row dataRow = sheet.createRow(i + 1);
                    Map<String, Object> row = rows.get(i);
                    for (int j = 0; j < columns.size(); j++) {
                        Object value = row.get(columns.get(j));
                        dataRow.createCell(j).setCellValue(value == null ? "" : String.valueOf(value));
                    }
                }
            }
            try (var out = new java.io.ByteArrayOutputStream()) {
                workbook.write(out);
                workbook.dispose();
                return out.toByteArray();
            }
        }
    }
}

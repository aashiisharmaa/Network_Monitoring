package com.network.monitoring.service;

import com.fasterxml.jackson.core.JsonGenerator;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Streams the complete comparison appendix; keeps only five worst groups per metric in memory. */
@Component
public class CommonReportRenderer {
    @FunctionalInterface public interface Comparisons { void visit(Consumer<Map<String, Object>> consumer); }

    public Map<String, Object> render(Path directory, DailyAnalysisService.ReportInput input,
                                      Map<String, Object> modules, Comparisons comparisons, int maxRows) throws IOException {
        Files.createDirectories(directory);
        Map<String, Map<String, Long>> trends = new TreeMap<>();
        Map<String, List<Map<String, Object>>> worst = new TreeMap<>();
        long[] count = {0};
        SXSSFWorkbook book = new SXSSFWorkbook(100);
        try (var output = Files.newOutputStream(directory.resolve("report.json"));
             JsonGenerator json = CommonReportModules.JSON.getFactory().createGenerator(output)) {
            Sheet sheet = book.createSheet("Daily comparisons");
            List<String> headings = new ArrayList<>(List.of("Dimensions", "Metric", "Unit", "Aggregation", "Direction", "Date", "Current", "Current status",
                    "Previous day", "Day difference", "Day relative %", "Day trend", "Day status", "Previous seven days", "Week difference", "Week relative %", "Week trend", "Week status"));
            for (int i = 7; i >= 0; i--) headings.add(input.date().minusDays(i).toString());
            row(sheet, headings, 0);
            sheet.createFreezePane(2, 1); sheet.setColumnWidth(0, 10000); sheet.setColumnWidth(1, 7000);
            for (int i = 2; i < headings.size(); i++) sheet.setColumnWidth(i, 4500);
            json.writeStartObject(); json.writeObjectField("manifest", manifest(input)); json.writeArrayFieldStart("comparisons");
            comparisons.visit(raw -> {
                if (++count[0] > maxRows) throw new IllegalArgumentException("Comparison export exceeds the configured row limit; narrow the analysis profile or raise the limit.");
                Map<String, Object> item = map(raw), current = map(item.get("current")), day = map(item.get("previousDay")), week = map(item.get("previousSevenDays"));
                try { json.writeObject(item); } catch (IOException ex) { throw new UncheckedIOException(ex); }
                List<Object> cells = new ArrayList<>();
                cells.add(map(item.get("dimensions")).toString());
                for (String key : List.of("metric", "unit", "aggregation", "direction", "date")) cells.add(item.get(key));
                cells.add(current.get("value")); cells.add(current.get("status"));
                for (Map<String, Object> baseline : List.of(day, week)) for (String key : List.of("baselineValue", "difference", "relativeChangePercent", "trend", "status")) cells.add(baseline.get(key));
                for (Object point : (List<?>) item.getOrDefault("trend", List.of())) cells.add(map(map(point).get("observation")).get("value"));
                row(sheet, cells, Math.toIntExact(count[0]));
                String metric = String.valueOf(item.get("metric"));
                Map<String, Long> totals = trends.computeIfAbsent(metric, ignored -> new TreeMap<>());
                for (var baseline : Map.of("day", day, "week", week).entrySet()) totals.merge(baseline.getKey() + ":" + baseline.getValue().getOrDefault("trend", "UNASSESSED"), 1L, Long::sum);
                String direction = String.valueOf(item.get("direction"));
                if (!"NEUTRAL".equals(direction) && "OK".equals(current.get("status")) && current.get("value") instanceof Number value && Double.isFinite(value.doubleValue())) {
                    List<Map<String, Object>> ranked = worst.computeIfAbsent(metric, ignored -> new ArrayList<>());
                    ranked.add(item);
                    Comparator<Map<String, Object>> order = Comparator.comparingDouble(valueMap -> ((Number) map(valueMap.get("current")).get("value")).doubleValue());
                    if ("LOWER_IS_BETTER".equals(direction)) order = order.reversed();
                    ranked.sort(order.thenComparing(valueMap -> map(valueMap.get("dimensions")).toString()));
                    if (ranked.size() > 5) ranked.remove(5);
                }
            });
            json.writeEndArray();
            if ("PENDING".equals(map(modules.get("worstCells")).get("status"))) modules.put("worstCells", CommonReportModules.outcome(
                    worst.isEmpty() ? "SKIPPED" : "COMPLETED", "Five worst daily groups per metric, ranked using the configured KPI direction. Neutral or invalid values are excluded.", Map.of("byMetric", worst)));
            boolean partial = !"AVAILABLE".equals(input.summary().get("dataStatus")) || modules.values().stream().map(CommonReportRenderer::map)
                    .anyMatch(module -> !List.of("COMPLETED", "NOT_REQUESTED").contains(module.get("status")));
            Map<String, Object> summary = new LinkedHashMap<>(Map.of("coverage", partial ? "PARTIAL" : "COMPLETE", "comparisonRows", count[0],
                    "kpiTrends", trends, "dataStatus", input.summary().getOrDefault("dataStatus", "UNKNOWN"), "delivery", "DOWNLOAD_ONLY"));
            summary.put("moduleStatuses", modules.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> map(entry.getValue()).get("status"))));
            json.writeObjectField("modules", modules); json.writeObjectField("summary", summary); json.writeEndObject();
            Sheet sections = book.createSheet("Analysis modules"); row(sections, List.of("Module", "Status", "Coverage and reason", "Details"), 0);
            int index = 1;
            for (var entry : modules.entrySet()) {
                var value = map(entry.getValue());
                row(sections, List.of(entry.getKey(), value.get("status"), value.get("reason"), CommonReportModules.JSON.writeValueAsString(value.get("data"))), index++);
                Map<String, Object> details = map(value.get("data"));
                Object detailRows = details.getOrDefault("items", details.get("anomaly_details"));
                if (detailRows instanceof List<?> list && !list.isEmpty()) {
                    Sheet detailSheet = book.createSheet(entry.getKey());
                    Set<String> keys = new LinkedHashSet<>(); list.forEach(item -> keys.addAll(map(item).keySet()));
                    List<String> columns = new ArrayList<>(keys); row(detailSheet, columns, 0); int detailIndex = 1;
                    for (Object item : list) { Map<String, Object> detail = map(item); row(detailSheet, columns.stream().map(detail::get).toList(), detailIndex++); }
                    for (int column = 0; column < columns.size(); column++) detailSheet.setColumnWidth(column, 7000);
                }
            }
            for (int i = 0; i < 4; i++) sections.setColumnWidth(i, i < 2 ? 6000 : 18000);
            Sheet metadata = book.createSheet("Manifest"); index = 0;
            for (var entry : manifest(input).entrySet()) row(metadata, List.of(entry.getKey(), String.valueOf(entry.getValue())), index++);
            metadata.setColumnWidth(0, 6500); metadata.setColumnWidth(1, 22000);
            try (var excel = Files.newOutputStream(directory.resolve("report.xlsx"))) { book.write(excel); }
            pdf(directory.resolve("report.pdf"), input, summary, modules, worst);
            return summary;
        } finally { try { book.dispose(); } finally { book.close(); } }
    }

    static Map<String, Object> manifest(DailyAnalysisService.ReportInput input) {
        return Map.of("snapshotId", input.snapshotId(), "fileId", input.fileId(), "date", input.date().toString(), "sourceFingerprint", input.sourceFingerprint(),
                "datasetVersion", input.datasetVersion(), "profileHash", input.profileHash(), "profile", input.profile(), "analysisSummary", input.summary(), "formatVersion", "common-v1");
    }

    static Map<String, Object> map(Object value) {
        if (value == null) return Map.of();
        return CommonReportModules.JSON.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() { });
    }

    private static void row(Sheet sheet, List<?> values, int index) {
        Row row = sheet.createRow(index);
        for (int column = 0; column < values.size(); column++) {
            Object value = values.get(column); if (value == null) continue;
            Cell cell = row.createCell(column);
            if (value instanceof Number number && Double.isFinite(number.doubleValue())) cell.setCellValue(number.doubleValue());
            else { String text = value.toString(); cell.setCellValue(text.length() > 32000 ? text.substring(0, 31900) + " [Full details in report.json]" : text); }
        }
    }

    private void pdf(Path path, DailyAnalysisService.ReportInput input, Map<String, Object> summary,
                     Map<String, Object> modules, Map<String, List<Map<String, Object>>> worst) throws IOException {
        CustomerReportPdf.write(path, input, summary, modules, worst);
    }
}

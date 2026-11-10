package com.network.monitoring.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static com.network.monitoring.service.DailyKpiCalculator.*;
import static org.junit.jupiter.api.Assertions.*;

class CommonReportRendererTest {
    @TempDir Path directory;
    static final LocalDate DAY = LocalDate.of(2026, 11, 5);
    static Profile profile() { return new Profile(List.of("cellName"), List.of(new Metric("prb", Aggregation.MEAN, Direction.LOWER_IS_BETTER, "PERCENT", null, null, null, true)), 7, 1, true).validated(); }
    static DailyAnalysisService.ReportInput input() { return new DailyAnalysisService.ReportInput(1, 500, "500.json", "a".repeat(64), "fixture", "b".repeat(64), DAY, profile(), Map.of("dataStatus", "AVAILABLE")); }
    static Map<String, Object> comparison(String cell, double current, boolean missing) {
        Map<LocalDate, Value> values = new HashMap<>();
        for (int i = 0; i < 8; i++) if (!missing || i != 2) values.put(DAY.minusDays(i), new Value(i == 0 ? current : 40.0, i == 0 ? current : 40.0, 0, 1, 0, 0, 1, "OK"));
        return compare(Map.of("cellName", cell), profile().metrics().get(0), values, DAY, profile());
    }
    @Test void exportsAgreePreserveMissingNumericValuesAndAvoidExcelFormulas() throws Exception {
        Map<String, Object> modules = new LinkedHashMap<>();
        modules.put("worstCells", CommonReportModules.outcome("PENDING", "", Map.of()));
        modules.put("rca", CommonReportModules.outcome("SKIPPED", "RCA mapping is required.", Map.of()));
        var summary = new CommonReportRenderer().render(directory, input(), modules, sink -> {
            sink.accept(comparison("=HYPERLINK(\"example\")", 80, true)); sink.accept(comparison("Cell B", 30, false));
        }, 100);
        assertEquals("PARTIAL", summary.get("coverage")); assertEquals(2L, summary.get("comparisonRows"));
        var json = CommonReportModules.JSON.readTree(directory.resolve("report.json").toFile());
        assertEquals(80.0, json.at("/comparisons/0/current/value").asDouble());
        assertTrue(json.at("/comparisons/0/trend/5/observation").isNull());
        assertEquals("=HYPERLINK(\"example\")", json.at("/modules/worstCells/data/byMetric/prb/0/dimensions/cellName").asText());
        try (var excel = WorkbookFactory.create(directory.resolve("report.xlsx").toFile())) {
            Row row = excel.getSheetAt(0).getRow(1);
            assertEquals(CellType.STRING, row.getCell(0).getCellType());
            assertEquals(80.0, row.getCell(6).getNumericCellValue()); assertNull(row.getCell(23));
            assertEquals("=HYPERLINK(\"example\")", json.at("/comparisons/0/dimensions/cellName").asText());
        }
        try (var pdf = Loader.loadPDF(directory.resolve("report.pdf").toFile())) {
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Reporting date: 2026-11-05")); assertTrue(text.contains("PARTIAL")); assertTrue(text.contains("RCA mapping is required"));
        }
        String preview = System.getenv("KPI_REPORT_PREVIEW_DIR");
        if (preview != null) {
            Path clean = directory.resolve("preview");
            Map<String, Object> cleanModules = new LinkedHashMap<>();
            cleanModules.put("worstCells", CommonReportModules.outcome("PENDING", "", Map.of()));
            cleanModules.put("rca", CommonReportModules.outcome("SKIPPED", "RCA mapping is required. This is a synthetic sample report.", Map.of()));
            new CommonReportRenderer().render(clean, input(), cleanModules, sink -> { sink.accept(comparison("Demo Cell A", 80, true)); sink.accept(comparison("Demo Cell B", 30, false)); }, 100);
            Files.createDirectories(Path.of(preview));
            for (String ext : List.of("pdf", "xlsx", "json")) Files.copy(clean.resolve("report." + ext), Path.of(preview).resolve("sample-common-report." + ext), StandardCopyOption.REPLACE_EXISTING);
        }
    }
    @Test void exportLimitFailsInsteadOfSilentlySampling() {
        assertThrows(IllegalArgumentException.class, () -> new CommonReportRenderer().render(directory, input(), new LinkedHashMap<>(), sink -> { sink.accept(comparison("A", 90, false)); sink.accept(comparison("B", 80, false)); }, 1));
    }
}

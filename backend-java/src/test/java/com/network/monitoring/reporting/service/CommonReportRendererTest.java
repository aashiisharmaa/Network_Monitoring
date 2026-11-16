package com.network.monitoring.reporting.service;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.network.monitoring.reporting.service.DailyKpiCalculator.*;
import static com.network.monitoring.reporting.support.ReportFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class CommonReportRendererTest {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper()
            .findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @TempDir Path directory;
    @Test void exportsAgreePreserveMissingNumericValuesAndAvoidExcelFormulas() throws Exception {
        Map<String, Object> modules = new LinkedHashMap<>();
        modules.put("worstCells", CommonReportModules.outcome("PENDING", "", Map.of()));
        modules.put("rca", CommonReportModules.outcome("SKIPPED", "RCA mapping is required.", Map.of()));
        var summary = new CommonReportRenderer().render(directory, input(), modules, sink -> {
            sink.accept(comparison("=HYPERLINK(\"example\")", 80, true)); sink.accept(comparison("Cell B", 30, false));
        }, 100);
        assertEquals("PARTIAL", summary.get("coverage")); assertEquals(2L, summary.get("comparisonRows"));
        var json = JSON.readTree(directory.resolve("report.json").toFile());
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

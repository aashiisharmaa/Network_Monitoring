package com.network.monitoring.service;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import org.apache.poi.util.IOUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Service
public class ParserService {

    private static final int POI_BYTE_ARRAY_MAX_OVERRIDE = 250_000_000;
    private static final int PREVIEW_SAMPLE_LIMIT = 20;
    private static final List<String> REQUIRED_COLUMN_GROUPS = List.of(
            "cell",
            "site",
            "technology"
    );
    private static final Map<String, List<String>> COLUMN_ALIASES = Map.of(
            "cell", List.of("cell", "cellname", "cell_name", "cellid", "cell_id", "shortname", "short_name", "eutrancell", "eutran_cell"),
            "site", List.of("site", "siteid", "site_id", "sitename", "site_name", "nodeb", "enodeb", "gnodeb", "mecontextid", "mecontext_id"),
            "technology", List.of("tech", "technology", "rat", "networktype", "network_type"),
            "band", List.of("band", "frequencyband", "frequency_band", "carrier", "layer", "arfcn", "earfcn", "nrarfcn"),
            "sector", List.of("sector", "sectorid", "sector_id", "sectorname", "sector_name", "secid", "sec_id", "sec")
    );

    public List<Map<String, Object>> parseFile(Path filePath) throws IOException {
        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("File not found: " + filePath);
        }

        String extension = getExtension(filePath.getFileName().toString()).toLowerCase(Locale.ROOT);
        if (extension.equals("xlsb")) {
            throw new IllegalArgumentException("XLSB Binary Workbooks are not supported. Please save the file as .xlsx or export it as .csv, then upload again.");
        }
        if (extension.equals("xlsx") || extension.equals("xls")) {
            return parseExcel(filePath.toFile());
        }
        if (extension.equals("csv")) {
            return parseCsv(filePath.toFile());
        }
        throw new IllegalArgumentException("Unsupported file type: " + extension);
    }

    public Map<String, Object> previewFile(Path filePath) throws IOException {
        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("File not found: " + filePath);
        }

        String fileName = filePath.getFileName().toString();
        String extension = getExtension(fileName).toLowerCase(Locale.ROOT);
        if (extension.equals("xlsb")) {
            throw new IllegalArgumentException("XLSB Binary Workbooks are not supported. Please save the file as .xlsx or export it as .csv, then upload again.");
        }
        if (extension.equals("xlsx") || extension.equals("xls")) {
            return previewExcel(filePath.toFile(), fileName, extension);
        }
        if (extension.equals("csv")) {
            return previewCsv(filePath.toFile(), fileName, extension);
        }
        throw new IllegalArgumentException("Unsupported file type: " + extension);
    }

    private Map<String, Object> previewExcel(File file, String fileName, String extension) throws IOException {
        IOUtils.setByteArrayMaxOverride(POI_BYTE_ARRAY_MAX_OVERRIDE);
        try (FileInputStream stream = new FileInputStream(file);
             Workbook workbook = WorkbookFactory.create(stream)) {
            List<Map<String, Object>> sheets = new ArrayList<>();
            List<String> allHeaders = new ArrayList<>();
            List<Map<String, Object>> combinedSamples = new ArrayList<>();
            int totalRows = 0;

            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(sheetIndex);
                Iterator<Row> rows = sheet.rowIterator();
                Map<String, Object> sheetPreview = new LinkedHashMap<>();
                sheetPreview.put("sheetName", sheet.getSheetName());

                if (!rows.hasNext()) {
                    sheetPreview.put("headers", List.of());
                    sheetPreview.put("rowCount", 0);
                    sheetPreview.put("sampleRows", List.of());
                    sheetPreview.put("warnings", List.of("Sheet is empty."));
                    sheets.add(sheetPreview);
                    continue;
                }

                Row headerRow = rows.next();
                List<String> headers = getHeaders(headerRow).stream().map(this::normalizeHeader).toList();
                List<Map<String, Object>> sampleRows = new ArrayList<>();
                int sheetRowCount = 0;

                while (rows.hasNext()) {
                    Row row = rows.next();
                    if (isBlankExcelRow(row, headers.size())) {
                        continue;
                    }
                    sheetRowCount++;
                    if (sampleRows.size() < PREVIEW_SAMPLE_LIMIT) {
                        Map<String, Object> sample = excelRowToMap(row, headers);
                        sample.put("_sheetName", sheet.getSheetName());
                        sampleRows.add(sample);
                        if (combinedSamples.size() < PREVIEW_SAMPLE_LIMIT) {
                            combinedSamples.add(sample);
                        }
                    }
                }

                totalRows += sheetRowCount;
                allHeaders.addAll(headers);
                sheetPreview.put("headers", headers);
                sheetPreview.put("rowCount", sheetRowCount);
                sheetPreview.put("sampleRows", sampleRows);
                sheetPreview.put("warnings", getSheetWarnings(headers, sheetRowCount));
                sheets.add(sheetPreview);
            }

            return buildPreviewResponse(fileName, extension, sheets, distinctHeaders(allHeaders), combinedSamples, totalRows);
        } catch (Exception ex) {
            if (String.valueOf(ex.getMessage()).toLowerCase(Locale.ROOT).contains("xlsb")) {
                throw new IOException("XLSB Binary Workbooks are not supported. Please save the file as .xlsx or export it as .csv, then upload again.", ex);
            }
            throw new IOException("Failed to preview Excel: " + ex.getMessage(), ex);
        }
    }

    private Map<String, Object> previewCsv(File file, String fileName, String extension) throws IOException {
        try (CSVReader reader = new CSVReaderBuilder(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))
                .withCSVParser(new CSVParserBuilder().withSeparator(',').build())
                .build()) {
            String[] rawHeaders = reader.readNext();
            if (rawHeaders == null) {
                return buildPreviewResponse(fileName, extension, List.of(Map.of(
                        "sheetName", "CSV",
                        "headers", List.of(),
                        "rowCount", 0,
                        "sampleRows", List.of(),
                        "warnings", List.of("CSV file is empty.")
                )), List.of(), List.of(), 0);
            }

            List<String> headers = Arrays.stream(rawHeaders).map(this::normalizeHeader).toList();
            List<Map<String, Object>> sampleRows = new ArrayList<>();
            int totalRows = 0;
            String[] row;
            while ((row = reader.readNext()) != null) {
                if (isBlankCsvRow(row)) {
                    continue;
                }
                totalRows++;
                if (sampleRows.size() < PREVIEW_SAMPLE_LIMIT) {
                    Map<String, Object> sample = csvRowToMap(row, headers);
                    sample.put("_sheetName", "CSV");
                    sampleRows.add(sample);
                }
            }

            Map<String, Object> sheet = new LinkedHashMap<>();
            sheet.put("sheetName", "CSV");
            sheet.put("headers", headers);
            sheet.put("rowCount", totalRows);
            sheet.put("sampleRows", sampleRows);
            sheet.put("warnings", getSheetWarnings(headers, totalRows));
            return buildPreviewResponse(fileName, extension, List.of(sheet), headers, sampleRows, totalRows);
        } catch (Exception ex) {
            throw new IOException("Failed to preview CSV: " + ex.getMessage(), ex);
        }
    }

    private List<Map<String, Object>> parseExcel(File file) throws IOException {
        IOUtils.setByteArrayMaxOverride(POI_BYTE_ARRAY_MAX_OVERRIDE);
        try (FileInputStream stream = new FileInputStream(file);
             Workbook workbook = WorkbookFactory.create(stream)) {
            if (workbook.getNumberOfSheets() == 0) {
                return Collections.emptyList();
            }
            List<Map<String, Object>> records = new ArrayList<>();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(sheetIndex);
                Iterator<Row> rows = sheet.rowIterator();
                if (!rows.hasNext()) {
                    continue;
                }
                Row headerRow = rows.next();
                List<String> headers = getHeaders(headerRow);
                while (rows.hasNext()) {
                    Row row = rows.next();
                    Map<String, Object> record = new LinkedHashMap<>();
                    for (int i = 0; i < headers.size(); i++) {
                        String normalizedKey = normalizeHeader(headers.get(i));
                        Cell cell = row.getCell(i);
                        record.put(normalizedKey, readCell(cell));
                    }
                    record.put("_sheetName", sheet.getSheetName());
                    records.add(record);
                }
            }
            return records;
        } catch (Exception ex) {
            if (String.valueOf(ex.getMessage()).toLowerCase(Locale.ROOT).contains("xlsb")) {
                throw new IOException("XLSB Binary Workbooks are not supported. Please save the file as .xlsx or export it as .csv, then upload again.", ex);
            }
            throw new IOException("Failed to parse Excel: " + ex.getMessage(), ex);
        }
    }

    private List<Map<String, Object>> parseCsv(File file) throws IOException {
        try (CSVReader reader = new CSVReaderBuilder(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))
                .withCSVParser(new CSVParserBuilder().withSeparator(',').build())
                .build()) {
            List<String[]> lines = reader.readAll();
            if (lines.isEmpty()) {
                return Collections.emptyList();
            }
            String[] rawHeaders = lines.get(0);
            List<String> headers = new ArrayList<>();
            for (String header : rawHeaders) {
                headers.add(normalizeHeader(header));
            }
            List<Map<String, Object>> records = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                String[] row = lines.get(i);
                Map<String, Object> record = new LinkedHashMap<>();
                for (int j = 0; j < headers.size(); j++) {
                    record.put(headers.get(j), j < row.length ? cleanValue(row[j]) : null);
                }
                record.put("_sheetName", "CSV");
                records.add(record);
            }
            return records;
        } catch (Exception ex) {
            throw new IOException("Failed to parse CSV: " + ex.getMessage(), ex);
        }
    }

    private List<String> getHeaders(Row headerRow) {
        List<String> headers = new ArrayList<>();
        for (Cell cell : headerRow) {
            headers.add(cell.getStringCellValue());
        }
        return headers;
    }

    private Map<String, Object> buildPreviewResponse(String fileName,
                                                     String extension,
                                                     List<Map<String, Object>> sheets,
                                                     List<String> headers,
                                                     List<Map<String, Object>> sampleRows,
                                                     int totalRows) {
        List<String> duplicateHeaders = findDuplicateHeaders(headers);
        List<String> detectedMetrics = detectNumericMetricColumns(headers, sampleRows);
        Map<String, String> detectedColumns = detectColumnGroups(headers);
        List<String> missingRecommendedColumns = REQUIRED_COLUMN_GROUPS.stream()
                .filter(group -> !detectedColumns.containsKey(group))
                .toList();

        List<String> warnings = new ArrayList<>();
        if (totalRows == 0) {
            warnings.add("No data rows were found.");
        }
        if (!duplicateHeaders.isEmpty()) {
            warnings.add("Duplicate columns detected: " + String.join(", ", duplicateHeaders));
        }
        if (!missingRecommendedColumns.isEmpty()) {
            warnings.add("Recommended telecom columns missing or not detected: " + String.join(", ", missingRecommendedColumns));
        }
        if (detectedMetrics.isEmpty()) {
            warnings.add("No numeric KPI metric columns were detected. Dashboard metrics may be empty after upload.");
        }

        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("readyToUpload", totalRows > 0 && !detectedMetrics.isEmpty());
        validation.put("warnings", warnings);
        validation.put("missingRecommendedColumns", missingRecommendedColumns);
        validation.put("duplicateHeaders", duplicateHeaders);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fileName", fileName);
        data.put("fileType", extension);
        data.put("sheetCount", sheets.size());
        data.put("totalRows", totalRows);
        data.put("headers", headers);
        data.put("sheets", sheets);
        data.put("sampleRows", sampleRows);
        data.put("detectedColumns", detectedColumns);
        data.put("detectedMetricColumns", detectedMetrics);
        data.put("validation", validation);
        data.put("previewLimit", PREVIEW_SAMPLE_LIMIT);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", warnings.isEmpty() ? "KPI file preview is ready." : "KPI file preview is ready with warning(s).");
        response.put("data", data);
        return response;
    }

    private Map<String, Object> excelRowToMap(Row row, List<String> headers) {
        Map<String, Object> record = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            record.put(headers.get(i), readCell(row.getCell(i)));
        }
        return record;
    }

    private Map<String, Object> csvRowToMap(String[] row, List<String> headers) {
        Map<String, Object> record = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            record.put(headers.get(i), i < row.length ? cleanValue(row[i]) : null);
        }
        return record;
    }

    private List<String> getSheetWarnings(List<String> headers, int rowCount) {
        List<String> warnings = new ArrayList<>();
        if (headers.isEmpty()) {
            warnings.add("No header columns were found.");
        }
        if (rowCount == 0) {
            warnings.add("No data rows were found.");
        }
        List<String> duplicates = findDuplicateHeaders(headers);
        if (!duplicates.isEmpty()) {
            warnings.add("Duplicate columns detected: " + String.join(", ", duplicates));
        }
        return warnings;
    }

    private List<String> distinctHeaders(List<String> headers) {
        return headers.stream()
                .filter(header -> header != null && !header.isBlank())
                .distinct()
                .toList();
    }

    private List<String> findDuplicateHeaders(List<String> headers) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (String header : headers) {
            if (header == null || header.isBlank()) {
                continue;
            }
            String normalized = normalizeForMatching(header);
            if (!seen.add(normalized)) {
                duplicates.add(header);
            }
        }
        return new ArrayList<>(duplicates);
    }

    private Map<String, String> detectColumnGroups(List<String> headers) {
        Map<String, String> detected = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : COLUMN_ALIASES.entrySet()) {
            for (String header : headers) {
                String normalized = normalizeForMatching(header);
                if (entry.getValue().contains(normalized)) {
                    detected.put(entry.getKey(), header);
                    break;
                }
            }
        }
        return detected;
    }

    private List<String> detectNumericMetricColumns(List<String> headers, List<Map<String, Object>> sampleRows) {
        List<String> metrics = new ArrayList<>();
        Set<String> dimensionColumns = new LinkedHashSet<>(detectColumnGroups(headers).values());
        dimensionColumns.add("_sheetName");
        for (String header : headers) {
            if (header == null || header.isBlank() || dimensionColumns.contains(header)) {
                continue;
            }
            int numericValues = 0;
            int checkedValues = 0;
            for (Map<String, Object> row : sampleRows) {
                Object value = row.get(header);
                if (value == null || String.valueOf(value).isBlank()) {
                    continue;
                }
                checkedValues++;
                if (isNumeric(value)) {
                    numericValues++;
                }
            }
            if (numericValues > 0 && numericValues >= Math.max(1, checkedValues / 2)) {
                metrics.add(header);
            }
        }
        return metrics;
    }

    private boolean isBlankExcelRow(Row row, int headerCount) {
        for (int i = 0; i < headerCount; i++) {
            Object value = readCell(row.getCell(i));
            if (value != null && !String.valueOf(value).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private boolean isBlankCsvRow(String[] row) {
        for (String value : row) {
            if (value != null && !value.trim().isBlank()) {
                return false;
            }
        }
        return true;
    }

    private boolean isNumeric(Object value) {
        if (value instanceof Number) {
            return true;
        }
        String text = String.valueOf(value).trim();
        if (text.endsWith("%")) {
            text = text.substring(0, text.length() - 1);
        }
        text = text.replace(",", "");
        if (text.isBlank()) {
            return false;
        }
        try {
            Double.parseDouble(text);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private Object readCell(Cell cell) {
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case STRING -> cleanValue(cell.getStringCellValue());
            case NUMERIC -> cell.getNumericCellValue();
            case BOOLEAN -> cell.getBooleanCellValue();
            case FORMULA -> cleanValue(cell.getCellFormula());
            default -> null;
        };
    }

    private String cleanValue(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim().replace("\u00A0", "");
        return cleaned.isEmpty() ? null : cleaned;
    }

    private String normalizeHeader(String key) {
        if (key == null) {
            return "";
        }
        return key.trim()
                .replaceAll("\\s+", "_")
                .replaceAll("[-()]", "_")
                .replaceAll("[^\\w_ ]", "")
                .replaceAll("^_+|_+$", "");
    }

    private String normalizeForMatching(String key) {
        return normalizeHeader(key).toLowerCase(Locale.ROOT).replace("_", "");
    }

    private String getExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot != -1 ? filename.substring(lastDot + 1) : "";
    }
}

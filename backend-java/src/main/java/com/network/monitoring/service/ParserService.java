package com.network.monitoring.service;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
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

    public List<Map<String, Object>> parseFile(Path filePath) throws IOException {
        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("File not found: " + filePath);
        }

        String extension = getExtension(filePath.getFileName().toString()).toLowerCase(Locale.ROOT);
        if (extension.equals("xlsx") || extension.equals("xls")) {
            return parseExcel(filePath.toFile());
        }
        if (extension.equals("csv")) {
            return parseCsv(filePath.toFile());
        }
        throw new IllegalArgumentException("Unsupported file type: " + extension);
    }

    private List<Map<String, Object>> parseExcel(File file) throws IOException {
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

    private String getExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot != -1 ? filename.substring(lastDot + 1) : "";
    }
}

package com.network.monitoring.alarm.ingestion;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import java.io.*;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import static com.network.monitoring.alarm.dto.AlarmImport.*;

/** Read-only contract validation. Does not classify, reconcile, or persist alarms. */
@Service
public class AlarmImportPreviewService {
    private static final long MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_ROWS = 5000, MAX_COLUMNS = 80, MAX_TEXT = 4000;
    private static final Set<String> OEMS = Set.of("NOKIA", "ERICSSON", "ZTE", "HUAWEI", "SAMSUNG");
    private static final Map<String, String> ALIASES = aliases();
    private static final Set<String> ANNOTATION_FIELDS = Set.of("remarks","l2Remarks","circleUpdate");
    private final Semaphore previewSlot = new Semaphore(1);

    public static final class PreviewBusyException extends RuntimeException {
        public PreviewBusyException() { super("Alarm preview is busy. Retry after the current preview finishes."); }
    }

    public Preview preview(MultipartFile file, String sheetName, String defaultOem,
                           String technology, String sourceTimezone) {
        if (!previewSlot.tryAcquire()) throw new PreviewBusyException();
        try { return decode(file, sheetName, defaultOem, technology, sourceTimezone, false, true).validation(); }
        finally { previewSlot.release(); }
    }

    /** Full bounded parse for durable imports; never persist the 50-row preview sample. */
    public Parsed parse(MultipartFile file, String sheet, String oem, String technology, String timezone) {
        return parse(file,sheet,oem,technology,timezone,true);
    }

    /** Frozen v1 jobs keep their pre-annotation column/formula policy. */
    public Parsed parse(MultipartFile file, String sheet, String oem, String technology, String timezone, boolean captureAnnotations) {
        if (!previewSlot.tryAcquire()) throw new PreviewBusyException();
        try { return decode(file, sheet, oem, technology, timezone, true, captureAnnotations); }
        finally { previewSlot.release(); }
    }

    private Parsed decode(MultipartFile file, String sheetName, String defaultOem,
                           String technology, String sourceTimezone, boolean allRows, boolean captureAnnotations) {
        if (file == null || file.isEmpty()) throw invalid("A non-empty alarm file is required.");
        if (file.getSize() > MAX_BYTES) throw invalid("Alarm preview file must be at most 10 MiB.");
        String oem = oem(defaultOem);
        String rat = clean(technology).toUpperCase(Locale.ROOT);
        if (!rat.isEmpty() && !Set.of("2G", "4G").contains(rat)) throw invalid("Technology must be 2G or 4G.");
        final ZoneId zone;
        try { zone = ZoneId.of(clean(sourceTimezone)); }
        catch (DateTimeException e) { throw invalid("A valid explicit source timezone is required."); }
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        try (InputStream input = file.getInputStream()) {
            byte[] data = input.readNBytes((int) MAX_BYTES + 1);
            if (data.length > MAX_BYTES) throw invalid("Alarm preview file must be at most 10 MiB.");
            Collector collector = new Collector(zone, oem, rat, allRows, captureAnnotations);
            String selected;
            if (name.endsWith(".csv")) {
                if (!clean(sheetName).isEmpty()) throw invalid("Sheet selection is only valid for XLSX files.");
                selected = "CSV";
                try (CSVReader csv = new CSVReaderBuilder(new InputStreamReader(
                        new ByteArrayInputStream(data), StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)))
                        .withMultilineLimit(10).build()) {
                    while (true) {
                        int sourceLine = Math.toIntExact(csv.getLinesRead() + 1);
                        String[] row = csv.readNext();
                        if (row == null) break;
                        collector.accept(sourceLine, Arrays.asList(row));
                    }
                }
            } else if (name.endsWith(".xlsx")) {
                if (clean(sheetName).isEmpty()) throw invalid("Select the raw alarm sheet explicitly; summary sheets are never merged.");
                validateArchive(data);
                try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(data))) {
                    Sheet sheet = workbook.getSheet(sheetName);
                    if (sheet == null) throw invalid("Selected sheet does not exist in this workbook.");
                    if (sheet.getPhysicalNumberOfRows() > MAX_ROWS + 1)
                        throw invalid("Preview supports at most 5001 physical workbook rows. Supply a representative sample.");
                    selected = sheet.getSheetName();
                    DataFormatter formatter = new DataFormatter(Locale.ROOT);
                    for (Row row : sheet) {
                        if (row.getLastCellNum() > MAX_COLUMNS) throw invalid("Alarm preview supports at most 80 columns.");
                        List<String> values = new ArrayList<>();
                        for (int i = 0; i < row.getLastCellNum(); i++) {
                            Cell cell = row.getCell(i);
                            // Never evaluate workbook formulas or follow external links.
                            if (cell != null && cell.getCellType() == CellType.FORMULA) {
                                if (!collector.header || collector.columns.containsValue(i))
                                    throw invalid("A mapped alarm column contains formulas. Supply source values for alarm fields.");
                                values.add(""); // Ignore derived report columns; never use cached formula results.
                                continue;
                            }
                            values.add(cell != null && cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)
                                    ? cell.getLocalDateTimeCellValue().toString() : formatter.formatCellValue(cell));
                        }
                        collector.accept(row.getRowNum() + 1, values);
                    }
                }
            } else throw invalid("Alarm contract preview supports UTF-8 CSV and XLSX only.");
            return new Parsed(collector.result(selected), List.copyOf(collector.all));
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw invalid("Cannot read alarm file. Check its format, encoding and CSV quoting."); }
    }

    private static void validateArchive(byte[] data) throws IOException {
        long expanded = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            byte[] buffer = new byte[8192];
            while (zip.getNextEntry() != null) {
                if (++entries > 1000) throw invalid("Workbook has too many archive entries.");
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    expanded += count;
                    if (expanded > 50 * 1024 * 1024) throw invalid("Expanded workbook exceeds the 50 MiB preview budget.");
                }
            }
        }
    }

    private static final class Collector {
        private final ZoneId zone;
        private final String defaultOem, defaultRat;
        private final Map<String, Integer> columns = new HashMap<>();
        private final List<AlarmRow> sample = new ArrayList<>();
        private final List<RowIssue> issues = new ArrayList<>();
        private final List<AlarmRow> all = new ArrayList<>();
        private final boolean allRows;
        private final boolean captureAnnotations;
        private int rows, valid, missingSeverity, missingCode, issueCount;
        private boolean header;

        Collector(ZoneId zone, String oem, String rat, boolean allRows, boolean captureAnnotations) {
            this.zone = zone; defaultOem = oem; defaultRat = rat; this.allRows = allRows; this.captureAnnotations = captureAnnotations;
        }

        void accept(int line, List<String> cells) {
            if (cells.size() > MAX_COLUMNS) throw invalid("Alarm preview supports at most 80 columns.");
            if (cells.stream().anyMatch(v -> v.length() > MAX_TEXT)) throw invalid("A cell exceeds the 4000 character preview limit.");
            if (cells.stream().allMatch(v -> clean(v).isEmpty())) return;
            if (!header) {
                for (int i = 0; i < cells.size(); i++) {
                    String field = ALIASES.get(key(cells.get(i)));
                    if (!captureAnnotations && ANNOTATION_FIELDS.contains(field == null ? "" : field)) field = null;
                    if (field != null && columns.putIfAbsent(field, i) != null)
                        throw invalid("Ambiguous duplicate column for " + field + ". Supply one canonical column.");
                }
                for (String required : List.of("circle", "text", "raisedAt"))
                    if (!columns.containsKey(required)) throw invalid("Missing required column: " + required + ".");
                if (!columns.containsKey("resource") && !columns.containsKey("name")) throw invalid("A resource or Name column is required.");
                header = true;
                return;
            }
            if (++rows > MAX_ROWS) throw invalid("Preview is limited to 5000 non-empty rows. Supply a representative sample.");
            int before = issueCount;
            String vendor = value(cells, "oem");
            if (vendor.isEmpty()) vendor = defaultOem;
            try { vendor = oem(vendor); }
            catch (IllegalArgumentException e) { issue(line, "oem", "Unsupported OEM."); }
            if (vendor.isEmpty()) issue(line, "oem", "OEM is required in the row or request.");
            String rat = value(cells, "technology").toUpperCase(Locale.ROOT);
            if (rat.isEmpty()) rat = defaultRat;
            if (!Set.of("2G", "4G").contains(rat)) issue(line, "technology", "Explicit 2G or 4G technology is required.");
            String circle = value(cells, "circle"), resource = value(cells, "resource"), name = value(cells, "name");
            if (resource.isEmpty()) resource = name;
            if (circle.isEmpty()) issue(line, "circle", "Circle is required; master-data resolution is pending.");
            if (resource.isEmpty()) issue(line, "resource", "Source resource or Name is required.");
            String text = value(cells, "text"), severity = value(cells, "severity"), code = value(cells, "code");
            if (text.isEmpty()) issue(line, "text", "Alarm text is required.");
            if (severity.isEmpty()) missingSeverity++;
            if (code.isEmpty()) missingCode++;
            Instant raised = null;
            try { raised = timestamp(value(cells, "raisedAt"), zone); }
            catch (IllegalArgumentException e) { issue(line, "raisedAt", e.getMessage()); }
            if (before == issueCount) {
                valid++;
                var normalized = new AlarmRow(line, vendor, rat, circle, resource, name,
                        code, text, severity, "UNMAPPED", raised, value(cells, "supplementary"), annotations(cells));
                if (sample.size() < 50) sample.add(normalized);
                if (allRows) all.add(normalized);
            }
        }

        Map<String,String> annotations(List<String> cells) {
            Map<String,String> values=new LinkedHashMap<>();
            for(String field:List.of("remarks","l2Remarks","circleUpdate")) {
                String text=value(cells,field);
                if(!text.isEmpty()) values.put(field,text);
            }
            return values;
        }
        String value(List<String> cells, String field) {
            Integer index = columns.get(field);
            return index == null || index >= cells.size() ? "" : clean(cells.get(index));
        }
        void issue(int line, String field, String message) {
            issueCount++;
            if (issues.size() < 100) issues.add(new RowIssue(line, field, message));
        }
        Preview result(String sheet) {
            if (!header || (rows == 0 && !allRows)) throw invalid("No alarm data rows found.");
            return new Preview("alarm-preview-v1", sheet, zone.getId(), rows, valid, rows - valid,
                    missingSeverity, missingCode, List.copyOf(sample), List.copyOf(issues), issueCount > issues.size(),
                    List.of("Read-only validation: no alarms imported or cleared.",
                            "Customer catalogue and inclusion rules are pending; all customer classifications are UNMAPPED.",
                            "Circle/site mapping, stable alarm identity and snapshot completeness are not yet validated.",
                            "Sample contains at most 50 valid rows; errors contain at most 100 issues."));
        }
    }

    private static Instant timestamp(String value, ZoneId zone) {
        try { return OffsetDateTime.parse(value).toInstant(); } catch (DateTimeParseException ignored) { }
        LocalDateTime local = null;
        for (DateTimeFormatter format : List.of(DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(java.time.format.ResolverStyle.STRICT),
                DateTimeFormatter.ofPattern("dd-MM-uuuu HH:mm:ss").withResolverStyle(java.time.format.ResolverStyle.STRICT))) {
            try { local = LocalDateTime.parse(value, format); break; } catch (DateTimeParseException ignored) { }
        }
        if (local == null) throw invalid("Use an ISO timestamp or yyyy-MM-dd/dd-MM-yyyy HH:mm:ss.");
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        if (offsets.size() != 1) throw invalid("Ambiguous or nonexistent local time; provide an explicit ISO offset.");
        return local.toInstant(offsets.get(0));
    }
    private static String oem(String value) {
        String normalized = clean(value).toUpperCase(Locale.ROOT);
        if (normalized.equals("E///")) normalized = "ERICSSON";
        if (!normalized.isEmpty() && !OEMS.contains(normalized)) throw invalid("OEM must be Nokia, Ericsson, ZTE, Huawei or Samsung.");
        return normalized;
    }
    private static String clean(String value) { return value == null ? "" : value.replace("\uFEFF", "").trim(); }
    private static String key(String value) { return clean(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    private static Map<String, String> aliases() {
        Map<String, String> aliases = new HashMap<>();
        add(aliases, "circle", "Circle", "Circle Name", "SubNetwork_id");
        add(aliases, "resource", "EnodeB ID", "EnodeB_ID", "MeContext_id", "Network Object", "Object DN", "Resource");
        add(aliases, "name", "Name", "userLabel", "NE Name", "Node Name");
        add(aliases, "text", "Alarm Text", "Alarm Name", "alarmSlogan");
        add(aliases, "code", "Alarm Number", "Alarm Code", "alarmPortId");
        add(aliases, "severity", "Severity", "perceivedSeverity");
        add(aliases, "raisedAt", "Alarm Time", "DateTime", "Raised At", "Event Time");
        add(aliases, "supplementary", "Supplementry Info", "Supplementary Info", "Supplementary Information", "Supplementry Information");
        add(aliases, "oem", "OEM", "Vendor");
        add(aliases, "technology", "Technology", "RAT");
        add(aliases, "remarks", "Remarks", "Responsibility");
        add(aliases, "l2Remarks", "L2 Remarks");
        add(aliases, "circleUpdate", "Circle Update", "Circle Remark", "Circle Remarks");
        return Map.copyOf(aliases);
    }
    private static void add(Map<String, String> map, String field, String... names) {
        for (String name : names) map.put(key(name), field);
    }
}

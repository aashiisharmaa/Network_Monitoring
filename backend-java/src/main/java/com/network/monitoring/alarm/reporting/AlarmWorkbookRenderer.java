package com.network.monitoring.alarm.reporting;

import com.network.monitoring.alarm.dto.AlarmReport.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import static com.network.monitoring.alarm.support.AlarmValues.*;

/** Bounded Excel report. Source strings are text cells; customer formulas are never executed. */
public final class AlarmWorkbookRenderer {
    private AlarmWorkbookRenderer() {}

    public static byte[] render(List<Source> sources, Summary summary) {
        SXSSFWorkbook book = new SXSSFWorkbook(100);
        book.setCompressTempFiles(true);
        try (book; var output = new ByteArrayOutputStream()) {
            CellStyle heading = book.createCellStyle();
            Font font = book.createFont(); font.setBold(true); font.setColor(IndexedColors.WHITE.getIndex());
            heading.setFont(font); heading.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            heading.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            summarySheet(book, "Zone Summary", summary.zones(), heading);
            summarySheet(book, "Circle Summary", summary.circles(), heading);
            Sheet detail = book.createSheet("Alarm Evidence");
            row(detail, List.of("Import ID", "Feed ID", "Snapshot UTC", "Circle", "Zone", "Technology", "Site", "Resource",
                    "Alarm Code", "Alarm Text", "Source Severity", "Customer Classification", "Included",
                    "Service Affecting", "Site Down", "Raised UTC", "Age Hours", "Supplementary Information", "Source Remarks", "L2 Remarks", "Circle Update"), heading);
            for (Source source : sources) for (var alarm : source.observations()) {
                row(detail, Arrays.asList(source.runId(), source.feedId(), source.snapshotAt().toString(),
                        alarm.get("circle_name"), alarm.get("zone_name"), alarm.get("technology"), alarm.get("site_code"),
                        alarm.get("resource_name"), alarm.get("alarm_code"), alarm.get("alarm_text"), alarm.get("source_severity"),
                        alarm.get("classification"), alarm.get("included"), alarm.get("service_affecting"), alarm.get("site_down"),
                        jdbcInstant(alarm.get("raised_at")).toString(), java.time.Duration.between(jdbcInstant(alarm.get("raised_at")), source.snapshotAt()).getSeconds() / 3600.0,
                        alarm.get("supplementary_information"), alarm.get("source_remarks"), alarm.get("l2_remarks"), alarm.get("circle_update")), null);
            }
            finish(detail, 21);
            Sheet quality = book.createSheet("Sources and Quality");
            row(quality, List.of("Import ID", "Feed ID", "Source snapshot UTC", "Observed records"), heading);
            for (Source source : sources) row(quality, List.of(source.runId(), source.feedId(), source.snapshotAt().toString(), source.observations().size()), null);
            for (String note : summary.notes()) row(quality, List.of(note), null);
            finish(quality, 4); quality.setColumnWidth(0, 20000);
            Sheet pending = book.createSheet("Pending Inputs");
            row(pending, List.of("Customer operational input", "Status"), heading);
            for (String input : summary.pendingOperationalInputs()) row(pending, List.of(input, "Not supplied; not inferred from alarm counts"), null);
            finish(pending, 2); pending.setColumnWidth(0, 16000); pending.setColumnWidth(1, 20000);
            book.write(output);
            if (output.size() > 20 * 1024 * 1024) throw new IllegalStateException("Alarm workbook exceeds the 20 MiB artifact budget; narrow the scope.");
            return output.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot render alarm workbook", error);
        } finally {
            book.dispose();
        }
    }

    private static void summarySheet(SXSSFWorkbook book, String name, List<Group> groups, CellStyle style) {
        Sheet sheet = book.createSheet(name);
        row(sheet, List.of("Zone", "Circle", "Technology", "Observed records", "Included records", "Critical", "Major", "Minor",
                "Service-affecting records", "Known down sites", "Down <12h", "Down 12h to 48h", "Down >48h and <72h", "Down >=72h",
                "Unmapped catalogue", "Unmapped sites", "MS2 pending", "LR pending", "Owner", "Major reason"), style);
        for (Group group : groups) row(sheet, Arrays.asList(group.zone(), group.circle(), group.technology(), group.observedRecords(),
                group.includedRecords(), group.critical(), group.major(), group.minor(), group.serviceAffectingRecords(), group.knownDownSites(),
                group.downSiteAge().under12Hours(), group.downSiteAge().from12To48Hours(), group.downSiteAge().above48Under72Hours(),
                group.downSiteAge().atLeast72Hours(), group.unmappedCatalogue(), group.unmappedSites(), null, null, null, null), null);
        finish(sheet, 20);
    }
    private static void row(Sheet sheet, List<?> values, CellStyle style) {
        Row row = sheet.createRow(sheet.getPhysicalNumberOfRows());
        for (int i = 0; i < values.size(); i++) {
            Cell cell = row.createCell(i); Object value = values.get(i);
            if (value instanceof Number number) cell.setCellValue(number.doubleValue());
            else if (value instanceof Boolean bool) cell.setCellValue(bool);
            else if (value != null) cell.setCellValue(value.toString());
            if (style != null) cell.setCellStyle(style);
        }
    }
    private static void finish(Sheet sheet, int columns) {
        sheet.createFreezePane(3, 1);
        if (sheet.getLastRowNum() > 0) sheet.setAutoFilter(new CellRangeAddress(0, sheet.getLastRowNum(), 0, columns - 1));
        for (int i = 0; i < columns; i++) sheet.setColumnWidth(i, 6500);
    }
}

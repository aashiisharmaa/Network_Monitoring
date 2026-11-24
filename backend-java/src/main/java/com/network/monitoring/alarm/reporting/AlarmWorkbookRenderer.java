package com.network.monitoring.alarm.reporting;

import com.network.monitoring.alarm.dto.AlarmReport.*;
import com.network.monitoring.alarm.dto.AlarmOperational.Snapshot;
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
        return render(sources,summary,Snapshot.none());
    }
    public static byte[] render(List<Source> sources, Summary summary, Snapshot operational) {
        SXSSFWorkbook book = new SXSSFWorkbook(100);
        book.setCompressTempFiles(true);
        try (book; var output = new ByteArrayOutputStream()) {
            CellStyle heading = book.createCellStyle();
            Font font = book.createFont(); font.setBold(true); font.setColor(IndexedColors.WHITE.getIndex());
            heading.setFont(font); heading.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            heading.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            summarySheet(book, "Zone Summary", summary.zones(), heading,operational,summary);
            summarySheet(book, "Circle Summary", summary.circles(), heading,operational,summary);
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
            if(operational.id()!=null) {
                row(quality,List.of("Operational snapshot",operational.id(),"Revision",operational.revision()),null);
                row(quality,List.of("Effective UTC",operational.effectiveAt(),"Source",operational.sourceReference()),null);
                row(quality,List.of("Operational payload SHA256",operational.checksum()),null);
                operationalSheets(book,heading,operational);
            }
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

    private static void summarySheet(SXSSFWorkbook book, String name, List<Group> groups, CellStyle style,Snapshot operational,Summary summary) {
        Sheet sheet = book.createSheet(name);
        row(sheet, List.of("Zone", "Circle", "Technology", "Observed records", "Included records", "Critical", "Major", "Minor",
                "Service-affecting records", "Known down sites", "Down <12h", "Down 12h to 48h", "Down >48h and <72h", "Down >=72h",
                "Unmapped catalogue", "Unmapped sites", name.equals("Zone Summary")?"MS2 pending (represented circles)":"MS2 pending (reported)", name.equals("Zone Summary")?"LR pending (represented circles)":"LR pending (reported)", "Owner", "Major reason",
                "Site inventory (reported)","Site up (reported)","GPS alarms (reported)","VSWR alarms (reported)","Zone owner"), style);
        for (Group group : groups) {
            var values=AlarmOperationalContext.values(operational,group,summary);
            row(sheet, Arrays.asList(group.zone(), group.circle(), group.technology(), group.observedRecords(),
                group.includedRecords(), group.critical(), group.major(), group.minor(), group.serviceAffectingRecords(), group.knownDownSites(),
                group.downSiteAge().under12Hours(), group.downSiteAge().from12To48Hours(), group.downSiteAge().above48Under72Hours(),
                group.downSiteAge().atLeast72Hours(), group.unmappedCatalogue(), group.unmappedSites(), values.ms2Pending(),values.lrPending(),
                group.circleId()==null?values.zoneOwner():values.circleOwner(),values.majorReason(),values.inventory(),values.siteUp(),values.gps(),values.vswr(),values.zoneOwner()), null);
        }
        finish(sheet, 25);
    }
    private static void operationalSheets(SXSSFWorkbook book,CellStyle heading,Snapshot snapshot) {
        Sheet inputs=book.createSheet("Operational Inputs");
        row(inputs,List.of("Zone","Circle","Circle ID","Technology","MS2 pending","LR pending","Inventory total","Reported site up","GPS alarms","VSWR alarms","Zone owner","Circle owner","Major reason","Snapshot ID","Effective UTC","Revision","Source reference"),heading);
        Sheet sites=book.createSheet("Reported Site Updates");
        row(sites,List.of("Circle","Circle ID","Technology","Site code","Reported state","Observed UTC","LR pending","Owner","Recovery reason","Responsibility","Operational snapshot"),heading);
        for(var context:snapshot.circles()) {
            var input=context.input();
            row(inputs,Arrays.asList(context.zoneName(),context.circleName(),input.circleId(),input.technology(),input.ms2Pending(),input.lrPending(),input.siteInventoryTotal(),input.siteUp(),input.gpsAlarms(),input.vswrAlarms(),input.zoneOwner(),input.circleOwner(),input.majorReason(),snapshot.id(),snapshot.effectiveAt(),snapshot.revision(),snapshot.sourceReference()),null);
            for(var site:input.sites()) row(sites,Arrays.asList(context.circleName(),input.circleId(),input.technology(),site.siteCode(),site.state(),site.observedAt(),site.lrPending(),site.owner(),site.recoveryReason(),site.responsibility(),snapshot.id()),null);
        }
        finish(inputs,17);finish(sites,11);
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

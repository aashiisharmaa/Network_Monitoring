package com.network.monitoring.alarm.ingestion;

import com.network.monitoring.alarm.dto.AlarmImport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;

class AlarmImportPreviewServiceTest {
    private final AlarmImportPreviewService service = new AlarmImportPreviewService();
    private static final String HEADER = "Circle Name,EnodeB ID,Name,Alarm Number,Alarm Text,Supplementry Information,Severity,Alarm Time,OEM\n";

    @Test void supportsAllFiveOemsWithoutInventingClassificationOrCodes() {
        String csv = HEADER;
        for (String oem : new String[]{"Nokia", "E///", "ZTE", "Huawei", "Samsung"})
            csv += "MU,PLMN/BSC-1/BTS-2,,,%s alarm,extra,,2026-11-01 09:30:00,%s\n".formatted(oem, oem);
        var preview = preview(csv, null, "2G", "Asia/Kolkata");
        assertEquals(5, preview.validRows());
        assertEquals(0, preview.rejectedRows());
        assertEquals(5, preview.missingAlarmCode());
        assertEquals(5, preview.missingSourceSeverity());
        assertEquals("ERICSSON", preview.sample().get(1).oem());
        var row = preview.sample().get(0);
        assertEquals("MU", row.circle());
        assertEquals("PLMN/BSC-1/BTS-2", row.resource());
        assertEquals("", row.alarmCode());
        assertEquals("UNMAPPED", row.customerClassification());
        assertEquals("extra", row.supplementaryInformation());
        assertEquals(Instant.parse("2026-11-01T04:00:00Z"), row.raisedAt());
    }

    @Test void reportsBadRowsAndPreservesSourceSeverityWithoutInferringTechnology() {
        var result = preview(HEADER + "MU,node1,,,Alarm,,INDETERMINATE,2026-11-01 09:30:00,Nokia\n"
                + ",node2,,,Alarm,,Major,not-a-date,ZTE\n", null, null, "UTC");
        assertEquals(2, result.rejectedRows());
        assertTrue(result.issues().stream().anyMatch(i -> i.sourceRow() == 3 && i.field().equals("circle")));
        assertTrue(result.issues().stream().anyMatch(i -> i.field().equals("technology")));
        assertTrue(result.issues().stream().anyMatch(i -> i.field().equals("raisedAt")));
        var valid = preview(HEADER + "MU,node1,,,Alarm,,INDETERMINATE,2026-11-01T09:30:00+05:30,Nokia\n", null, "4G", "UTC");
        assertEquals("INDETERMINATE", valid.sample().get(0).sourceSeverity());
        assertEquals("UNMAPPED", valid.sample().get(0).customerClassification());
    }

    @Test void rejectsAmbiguousHeadersInvalidTimezoneAndUnsupportedFiles() {
        assertThrows(IllegalArgumentException.class, () -> preview(
                HEADER.replace("Circle Name,", "Circle Name,Circle,"), "Nokia", "4G", "UTC"));
        assertThrows(IllegalArgumentException.class, () -> preview(HEADER, "Nokia", "4G", ""));
        assertThrows(IllegalArgumentException.class, () -> preview("foo,bar\nx,y\n", "Nokia", "4G", "UTC"));
        assertThrows(IllegalArgumentException.class, () -> service.preview(
                new MockMultipartFile("file", "alarms.xls", "application/octet-stream", new byte[]{1}), null, "Nokia", "4G", "UTC"));
    }

    @Test void supportsBomQuotedMultilineTextAndReportsPhysicalSourceLines() {
        var result = preview("\uFEFF" + HEADER
                + "MU,node,,,\"Transport,\nlink alarm\",,Major,2026-11-01 09:30:00,Nokia\n"
                + ",node,,,Alarm,,Major,bad,Nokia\n", null, "4G", "UTC");
        assertEquals(1, result.validRows());
        assertEquals("Transport,\nlink alarm", result.sample().get(0).alarmText());
        assertEquals(4, result.issues().get(0).sourceRow());
    }

    @Test void refusesAmbiguousDstAndInvalidCalendarDatesButAcceptsExplicitOffsets() {
        for (String date : new String[]{"2026-11-01 01:30:00", "2026-03-08 02:30:00", "2026-02-30 12:00:00"}) {
            var result = preview(HEADER + "MU,node,,,Alarm,,Major," + date + ",Nokia\n", null, "4G", "America/New_York");
            assertEquals(1, result.rejectedRows(), date);
        }
        assertEquals(1, preview(HEADER + "MU,node,,,Alarm,,Major,2026-11-01T01:30:00-04:00,Nokia\n",
                null, "4G", "America/New_York").validRows());
    }

    @Test void boundsSamplesErrorsAndInputRowCount() {
        String good = "MU,node,,,Alarm,,Major,2026-11-01 12:00:00,Nokia\n";
        var result = preview(HEADER + good.repeat(60), null, "4G", "UTC");
        assertEquals(60, result.validRows());
        assertEquals(50, result.sample().size());
        var bad = preview(HEADER + ",node,,,Alarm,,Major,bad,Nokia\n".repeat(101), null, "4G", "UTC");
        assertEquals(101, bad.rejectedRows());
        assertEquals(100, bad.issues().size());
        assertTrue(bad.issuesTruncated());
        assertThrows(IllegalArgumentException.class, () -> preview(HEADER + good.repeat(5001), null, "4G", "UTC"));
        assertThrows(IllegalArgumentException.class, () -> preview(HEADER + good.replace("Alarm", "X".repeat(4001)), null, "4G", "UTC"));
        assertThrows(IllegalArgumentException.class, () -> service.preview(new MockMultipartFile("file", "oversize.csv", "text/csv",
                new byte[10 * 1024 * 1024 + 1]), null, "Nokia", "4G", "UTC"));
        assertThrows(IllegalArgumentException.class, () -> service.preview(new MockMultipartFile("file", "invalid.csv", "text/csv",
                new byte[]{(byte) 0xC3, (byte) 0x28}), null, "Nokia", "4G", "UTC"));
    }

    @Test void rejectsConcurrentParsingWithoutQueueingAndReleasesSlotAfterFailure() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var file = new MockMultipartFile("file", "alarms.csv", "text/csv", "bad".getBytes()) {
            @Override public InputStream getInputStream() throws IOException {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                return super.getInputStream();
            }
        };
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> assertThrows(IllegalArgumentException.class,
                    () -> service.preview(file, null, "Nokia", "4G", "UTC")));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(AlarmImportPreviewService.PreviewBusyException.class,
                    () -> service.preview(file, null, "Nokia", "4G", "UTC"));
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertThrows(IllegalArgumentException.class, () -> preview("bad", "Nokia", "4G", "UTC"));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void requiresExplicitSheetAndNeverMergesSummaryOrEvaluatesDerivedFormulas() throws Exception {
        var file = workbook(false);
        assertThrows(IllegalArgumentException.class, () -> service.preview(file, null, "Nokia", "4G", "UTC"));
        assertThrows(IllegalArgumentException.class, () -> service.preview(file, "missing", "Nokia", "4G", "UTC"));
        var result = service.preview(file, "Alarm Dump", "Nokia", "4G", "UTC");
        assertEquals(1, result.validRows());
        assertEquals("Alarm Dump", result.sheet());
        assertEquals("Major", result.sample().get(0).sourceSeverity());
        assertThrows(IllegalArgumentException.class, () -> service.preview(workbook(true), "Alarm Dump", "Nokia", "4G", "UTC"));
    }

    private MockMultipartFile workbook(boolean mappedFormula) throws Exception {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            workbook.createSheet("Summary").createRow(0).createCell(0).setCellValue("Not alarm input");
            var sheet = workbook.createSheet("Alarm Dump");
            String[] headers = {"Circle", "Name", "Alarm Text", "Alarm Time", "Severity", "Derived Age"};
            String[] values = {"MU", "node", "Alarm", "2026-11-01 09:30:00", "Major", ""};
            var header = sheet.createRow(0);
            var row = sheet.createRow(1);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
                row.createCell(i).setCellValue(values[i]);
            }
            row.getCell(mappedFormula ? 3 : 5).setCellFormula("NOW()");
            workbook.write(out);
            return new MockMultipartFile("file", "alarms.xlsx", "application/octet-stream", out.toByteArray());
        }
    }

    private AlarmImport.Preview preview(String csv, String oem, String rat, String zone) {
        return service.preview(new MockMultipartFile("file", "alarms.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)),
                null, oem, rat, zone);
    }
}

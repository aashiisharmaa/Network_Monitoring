package com.network.monitoring.alarm.dto;

import java.time.Instant;
import java.util.List;

/** Stable JSON records, independent of service implementations. */
public final class AlarmImport {
    private AlarmImport() {}
    public record AlarmRow(int sourceRow, String oem, String technology, String circle,
                           String resource, String name, String alarmCode, String alarmText,
                           String sourceSeverity, String customerClassification,
                           Instant raisedAt, String supplementaryInformation) {}
    public record RowIssue(int sourceRow, String field, String message) {}
    public record Preview(String contractVersion, String sheet, String sourceTimezone,
                          int rowsRead, int validRows, int rejectedRows, int missingSourceSeverity,
                          int missingAlarmCode, List<AlarmRow> sample, List<RowIssue> issues,
                          boolean issuesTruncated, List<String> warnings) {}
    public record Parsed(Preview validation, List<AlarmRow> rows) {}

}

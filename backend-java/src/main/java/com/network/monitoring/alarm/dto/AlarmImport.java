package com.network.monitoring.alarm.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Stable JSON records, independent of service implementations. */
public final class AlarmImport {
    private AlarmImport() {}
    public record AlarmRow(int sourceRow, String oem, String technology, String circle,
                           String resource, String name, String alarmCode, String alarmText,
                           String sourceSeverity, String customerClassification,
                           Instant raisedAt, String supplementaryInformation, Map<String,String> sourceAnnotations) {
        public AlarmRow { sourceAnnotations = sourceAnnotations == null ? Map.of() : Map.copyOf(sourceAnnotations); }
        public AlarmRow(int sourceRow, String oem, String technology, String circle, String resource, String name,
                        String alarmCode, String alarmText, String sourceSeverity, String customerClassification,
                        Instant raisedAt, String supplementaryInformation) {
            this(sourceRow,oem,technology,circle,resource,name,alarmCode,alarmText,sourceSeverity,
                    customerClassification,raisedAt,supplementaryInformation,Map.of());
        }
    }
    public record RowIssue(int sourceRow, String field, String message) {}
    public record Preview(String contractVersion, String sheet, String sourceTimezone,
                          int rowsRead, int validRows, int rejectedRows, int missingSourceSeverity,
                          int missingAlarmCode, List<AlarmRow> sample, List<RowIssue> issues,
                          boolean issuesTruncated, List<String> warnings) {}
    public record Parsed(Preview validation, List<AlarmRow> rows) {}

}

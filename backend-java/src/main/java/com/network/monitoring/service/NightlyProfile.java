package com.network.monitoring.service;

import java.nio.file.*;
import java.time.*;
import java.util.*;

public record NightlyProfile(boolean enabled, String folder, String filePattern, List<String> identityFields,
                              Map<String, String> columnMapping, String deliveryMode, String cutoffTime,
                              CommonReportModules.Options reportOptions) {
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    public NightlyProfile validated() {
        String pattern = filePattern == null || filePattern.isBlank() ? "kpi-{date}.csv" : filePattern;
        if (!pattern.matches("[A-Za-z0-9_.-]*\\{date\\}[A-Za-z0-9_.-]*\\.(csv|xlsx|xls)") || pattern.length() > 200) throw new IllegalArgumentException("Use one {date} in a plain CSV/Excel filename, e.g. kpi-{date}.csv.");
        String directory = folder == null ? "" : folder.trim();
        if (!directory.isEmpty() && !Path.of(directory).isAbsolute()) throw new IllegalArgumentException("Configure an absolute input folder.");
        if (enabled && (directory.isEmpty() || !Files.isDirectory(Path.of(directory), LinkOption.NOFOLLOW_LINKS))) throw new IllegalArgumentException("An accessible input folder is required before enabling automation.");
        var fields = !enabled && (identityFields == null || identityFields.isEmpty()) ? List.<String>of() : KpiObservationMerge.validateFields(identityFields);
        String mode = deliveryMode == null ? "REPORT_ONLY" : deliveryMode;
        if (!List.of("REPORT_ONLY", "CAPTURE", "SMTP").contains(mode)) throw new IllegalArgumentException("Choose REPORT_ONLY, CAPTURE or SMTP delivery.");
        LocalTime cutoff;
        try { cutoff = LocalTime.parse(cutoffTime == null ? "01:30" : cutoffTime); } catch (DateTimeException ex) { throw new IllegalArgumentException("Use an HH:mm input cutoff."); }
        if (cutoff.isBefore(LocalTime.of(1, 0)) || !cutoff.isBefore(LocalTime.of(2, 0)) || cutoff.getSecond() != 0 || cutoff.getNano() != 0) throw new IllegalArgumentException("Input cutoff must be between 01:00 and 01:59 IST.");
        return new NightlyProfile(enabled, directory, pattern, fields, columnMapping == null ? Map.of() : Map.copyOf(columnMapping), mode, cutoff.toString(), (reportOptions == null ? new CommonReportModules.Options(null, null) : reportOptions).validated());
    }
    public Path input(LocalDate date) { return Path.of(folder).resolve(filePattern.replace("{date}", date.toString())).toAbsolutePath().normalize(); }
    public LocalDateTime cutoff(LocalDate date) { return date.plusDays(1).atTime(LocalTime.parse(cutoffTime)); }
    public static LocalDateTime deadline(LocalDate date) { return date.plusDays(1).atTime(2, 0); }
}

package com.network.monitoring.alarm.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Source-pinned summary contracts. Unknown operational inputs are not numeric zero. */
public final class AlarmReport {
    private AlarmReport() {}

    public record Source(long runId, long feedId, Instant snapshotAt, List<Map<String, Object>> observations) {}
    public record AgeBands(long under12Hours, long from12To48Hours, long above48Under72Hours, long atLeast72Hours) {}
    public record Group(String zone, Long circleId, String circle, String technology,
                        long observedRecords, long includedRecords, long critical, long major, long minor,
                        long serviceAffectingRecords, long knownDownSites, long unmappedCatalogue,
                        long unmappedSites, AgeBands downSiteAge) {}
    public record Summary(String contractVersion, List<Group> zones, List<Group> circles,
                          long observedRecords, long includedRecords, long unmappedCatalogue,
                          long knownDownSites, List<String> pendingOperationalInputs, List<String> notes) {}
}

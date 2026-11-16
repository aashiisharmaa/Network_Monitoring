package com.network.monitoring.alarm.dto;

import java.time.*;
import java.util.*;
import static com.network.monitoring.alarm.dto.AlarmConfiguration.*;

/** Stable JSON records, independent of service implementations. */
public final class AlarmModels {
    private AlarmModels() {}
    public record Scope(long circleId, String circleName, String zoneName) {}
    public record Catalog(long id, String oem, String technology, String matchField, String matchValue,
                          String classification, Boolean included, Boolean serviceAffecting, Boolean siteDown) {}
    public record Site(long id, long circleId, String objectKey, String siteCode) {}
    public record Frozen(Feed feed, Map<String, Scope> circles, List<Catalog> catalog, List<Site> sites, String automationJson, String adapterVersion) {}
    public record Observation(String key, Scope scope, String site, AlarmImport.AlarmRow row, Catalog rule) {}
    public record Outcome(int inputRows, int distinctAlarms, int duplicateRows, int unmappedCatalogue,
                          int unmappedSites, Long previousRunId, int newAlarms, int continuingAlarms,
                          int notObservedAgain, String identityPolicy, boolean automaticClearingEnabled) {}

}

package com.network.monitoring.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NightlyProfileTest {
    @TempDir Path folder;
    @Test void disabledDraftDoesNotNeedAnInventedFolderOrObservationGrain() {
        var draft = new NightlyProfile(false, null, null, null, null, null, null, null).validated();
        assertFalse(draft.enabled()); assertEquals("REPORT_ONLY", draft.deliveryMode()); assertEquals("kpi-{date}.csv", draft.filePattern());
    }
    @Test void rejectsTraversalAmbiguousNamesInvalidCutoffsAndMissingIdentity() {
        for (String pattern : List.of("../kpi-{date}.csv", "*.csv", "kpi.csv", "{date}-{date}.csv")) assertThrows(IllegalArgumentException.class, () -> new NightlyProfile(false, folder.toString(), pattern, List.of("date", "cellName"), null, null, null, null).validated());
        assertThrows(IllegalArgumentException.class, () -> new NightlyProfile(true, folder.toString(), null, List.of("date"), null, null, null, null).validated());
        assertThrows(IllegalArgumentException.class, () -> new NightlyProfile(false, folder.toString(), null, null, null, null, "02:00", null).validated());
    }
    @Test void scheduleUsesThePreviousDataDayAndExplicitIndiaTimezone() {
        LocalDate day = LocalDate.of(2026, 11, 4);
        assertEquals(Instant.parse("2026-11-04T20:30:00Z"), NightlyProfile.deadline(day).atZone(NightlyProfile.ZONE).toInstant());
        assertEquals(day, Instant.parse("2026-11-04T19:30:00Z").atZone(NightlyProfile.ZONE).toLocalDate().minusDays(1));
    }
}

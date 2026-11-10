package com.network.monitoring.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class KpiObservationMergeTest {
    private final List<String> grain = List.of("date", "cellName");

    private Map<String, Object> row(String date, Number value) {
        return Map.of("date", date, "cellName", "CELL_A", "metrics", Map.of("prb", value));
    }

    @Test void overlapAndSameFileDuplicateRowsSkipWithoutChangingHistory() {
        var result = KpiObservationMerge.merge(List.of(row("2026-11-01", 10)),
                List.of(row("2026-11-01", 10.0), row("2026-11-02", 20), row("2026-11-02", 20)), grain);
        assertEquals(3, result.duplicates() + result.added().size());
        assertEquals(2, result.duplicates());
        assertEquals(2, result.all().size());
        assertEquals(List.of(1), result.acceptedIndexes());
        assertEquals("2026-11-01", result.firstDate());
        assertEquals("2026-11-02", result.lastDate());
    }

    @Test void changedObservationRejectsTheEntireFile() {
        assertThrows(IllegalArgumentException.class, () -> KpiObservationMerge.merge(
                List.of(row("2026-11-01", 10)), List.of(row("2026-11-02", 20), row("2026-11-01", 11)), grain));
    }

    @Test void missingOrInvalidIdentityAndNonFiniteMetricsReject() {
        assertThrows(IllegalArgumentException.class, () -> KpiObservationMerge.validateFields(List.of("cellName")));
        assertThrows(IllegalArgumentException.class, () -> KpiObservationMerge.validateFields(List.of("date", "cellName", "metrics.prb")));
        assertThrows(IllegalArgumentException.class, () -> KpiObservationMerge.merge(List.of(), List.of(row("invalid", 10)), grain));
        assertThrows(IllegalArgumentException.class, () -> KpiObservationMerge.merge(List.of(), List.of(row("2026-11-01", Double.NaN)), grain));
        assertThrows(IllegalArgumentException.class, () -> KpiObservationMerge.merge(List.of(),
                List.of(Map.of("date", "2026-11-01", "metrics", Map.of("prb", 1))), grain));
    }

    @Test void additionalGrainFieldsSeparateObservationsAndOrderIsCanonical() {
        var fields = List.of("dimensions.hour", "cellName", "date");
        var result = KpiObservationMerge.merge(List.of(), List.of(
                Map.of("date", "2026-11-01", "cellName", "A", "dimensions", Map.of("hour", "01"), "metrics", Map.of("prb", 1)),
                Map.of("date", "2026-11-01", "cellName", "A", "dimensions", Map.of("hour", "02"), "metrics", Map.of("prb", 2))), fields);
        assertEquals(2, result.added().size());
        assertEquals(List.of("cellName", "date", "dimensions.hour"), KpiObservationMerge.validateFields(fields));
    }
}

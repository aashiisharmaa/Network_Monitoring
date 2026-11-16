package com.network.monitoring.reporting.support;

import com.network.monitoring.reporting.service.DailyAnalysisService;
import java.time.LocalDate;
import java.util.*;
import static com.network.monitoring.reporting.service.DailyKpiCalculator.*;

/** Synthetic report fixtures shared by rendering and delivery tests. */
public final class ReportFixtures {
    private ReportFixtures() {}
    public static final LocalDate DAY = LocalDate.of(2026, 11, 5);

    public static Profile profile() {
        return new Profile(List.of("cellName"), List.of(new Metric("prb", Aggregation.MEAN,
                Direction.LOWER_IS_BETTER, "PERCENT", null, null, null, true)), 7, 1, true).validated();
    }

    public static DailyAnalysisService.ReportInput input() {
        return new DailyAnalysisService.ReportInput(1, 500, "500.json", "a".repeat(64), "fixture",
                "b".repeat(64), DAY, profile(), Map.of("dataStatus", "AVAILABLE"));
    }

    public static Map<String, Object> comparison(String cell, double current, boolean missing) {
        Map<LocalDate, Value> values = new HashMap<>();
        for (int i = 0; i < 8; i++) {
            if (!missing || i != 2) values.put(DAY.minusDays(i), new Value(i == 0 ? current : 40.0,
                    i == 0 ? current : 40.0, 0, 1, 0, 0, 1, "OK"));
        }
        return compare(Map.of("cellName", cell), profile().metrics().get(0), values, DAY, profile());
    }
}

package com.network.monitoring.service;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static com.network.monitoring.service.DailyKpiCalculator.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyKpiCalculatorTest {
    private final LocalDate day = LocalDate.of(2026, 11, 8);
    private Metric metric(String key, Aggregation aggregation, Direction direction, String unit) {
        return new Metric(key, aggregation, direction, unit, null, null, null, true);
    }
    private Profile profile(Metric metric) { return new Profile(List.of("cellName"), List.of(metric), 7, 1, true).validated(); }
    private Map<String, Object> row(LocalDate date, Map<String, Object> values) {
        return Map.of("date", date.toString(), "cellName", "A", "metrics", values);
    }
    private Map<LocalDate, Value> values(Build build, String metric) {
        Map<LocalDate, Value> days = new HashMap<>(); build.days().forEach(value -> days.put(value.date(), value.metrics().get(metric))); return days;
    }
    @SuppressWarnings("unchecked") private Map<String, Object> part(Map<String, Object> result, String key) { return (Map<String, Object>) result.get(key); }

    @Test void latestSourceDateAndPreviousCalendarDayAndSevenDayMeanAreCorrect() {
        Metric metric = metric("prb", Aggregation.MEAN, Direction.LOWER_IS_BETTER, "PERCENT");
        Profile profile = profile(metric);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 8; i++) rows.add(row(day.minusDays(7 - i), Map.of("prb", i + 1)));
        Build build = DailyKpiCalculator.build(rows, profile, null, 100);
        assertEquals(day, build.date());
        var result = compare(Map.of("cellName", "A"), metric, values(build, "prb"), day, profile);
        assertEquals(1.0, part(result, "previousDay").get("difference"));
        assertEquals("DEGRADED", part(result, "previousDay").get("trend"));
        assertEquals(4.0, part(result, "previousSevenDays").get("baselineValue"));
        assertEquals(4.0, part(result, "previousSevenDays").get("difference"));
        assertEquals(100.0, part(result, "previousSevenDays").get("relativeChangePercent"));
        assertEquals(true, part(result, "previousSevenDays").get("baselineComplete"));
    }

    @Test void percentPointsAndRelativePercentAreSeparate() {
        Metric metric = metric("success", Aggregation.MEAN, Direction.HIGHER_IS_BETTER, "PERCENT");
        var profile = profile(metric);
        var build = DailyKpiCalculator.build(List.of(row(day.minusDays(1), Map.of("success", 98)), row(day, Map.of("success", 99))), profile, day, 100);
        var previous = part(compare(Map.of(), metric, values(build, "success"), day, profile), "previousDay");
        assertEquals(1.0, previous.get("percentagePointChange"));
        assertEquals(100.0 / 98, (Double) previous.get("relativeChangePercent"), 1e-9);
        assertEquals("IMPROVED", previous.get("trend"));
    }

    @Test void ratioUsesPooledCountersWithinDayAndAcrossBaselineDays() {
        Metric metric = new Metric("success", Aggregation.RATIO, Direction.HIGHER_IS_BETTER, "PERCENT", "ok", "attempts", 100.0, true);
        var profile = new Profile(List.of("cellName"), List.of(metric), 1, 1, false).validated();
        var build = DailyKpiCalculator.build(List.of(
                row(day.minusDays(2), Map.of("ok", 90, "attempts", 100)),
                row(day.minusDays(1), Map.of("ok", 1, "attempts", 10)),
                row(day, Map.of("ok", 95, "attempts", 100)), row(day, Map.of("ok", 1, "attempts", 10))), profile, day, 100);
        assertEquals(96.0 / 110 * 100, values(build, "success").get(day).value(), 1e-9);
        var baseline = part(compare(Map.of(), metric, values(build, "success"), day, profile), "previousSevenDays");
        assertEquals(91.0 / 110 * 100, (Double) baseline.get("baselineValue"), 1e-9);
        assertEquals("POOLED_COUNTERS", baseline.get("strategy"));
        assertEquals(false, baseline.get("baselineComplete"));
    }

    @Test void sumAndSampleMeanFollowExplicitPolicies() {
        Metric sum = metric("traffic", Aggregation.SUM, Direction.NEUTRAL, "COUNT");
        Metric mean = metric("prb", Aggregation.MEAN, Direction.LOWER_IS_BETTER, "PERCENT");
        var profile = new Profile(List.of("cellName"), List.of(sum, mean), 7, 1, true).validated();
        var build = DailyKpiCalculator.build(List.of(row(day, Map.of("traffic", 10, "prb", 20)), row(day, Map.of("traffic", 30, "prb", 40))), profile, day, 100);
        assertEquals(40, values(build, "traffic").get(day).value());
        assertEquals(30, values(build, "prb").get(day).value());
    }

    @Test void missingDayIsNotReplacedByEarlierAvailableDateOrZero() {
        var metric = metric("prb", Aggregation.MEAN, Direction.LOWER_IS_BETTER, "PERCENT");
        var profile = profile(metric);
        var build = DailyKpiCalculator.build(List.of(row(day.minusDays(2), Map.of("prb", 5)), row(day, Map.of("prb", 8))), profile, day, 100);
        var result = compare(Map.of(), metric, values(build, "prb"), day, profile);
        assertNull(part(result, "previousDay").get("baselineValue"));
        assertEquals("MISSING_BASELINE", part(result, "previousDay").get("status"));
        assertEquals("INSUFFICIENT_BASELINE", part(result, "previousSevenDays").get("status"));
        assertEquals("UNASSESSED", part(result, "previousSevenDays").get("trend"));
        assertEquals(6, ((List<?>) part(result, "previousSevenDays").get("missingDates")).size());
    }

    @Test void zeroBaselineAllowsAbsoluteDeltaButNotRelativeDivision() {
        var metric = metric("traffic", Aggregation.SUM, Direction.HIGHER_IS_BETTER, "COUNT");
        var profile = profile(metric);
        var build = DailyKpiCalculator.build(List.of(row(day.minusDays(1), Map.of("traffic", 0)), row(day, Map.of("traffic", 10))), profile, day, 100);
        var previous = part(compare(Map.of(), metric, values(build, "traffic"), day, profile), "previousDay");
        assertEquals(10.0, previous.get("difference"));
        assertNull(previous.get("relativeChangePercent"));
        assertEquals("ZERO_BASELINE", previous.get("relativeChangeReason"));
    }

    @Test void absentCurrentGroupAndMissingMetricRemainMissing() {
        var metric = metric("prb", Aggregation.MEAN, Direction.LOWER_IS_BETTER, "PERCENT");
        var profile = profile(metric);
        var build = DailyKpiCalculator.build(List.of(row(day.minusDays(1), Map.of("prb", 5))), profile, day, 100);
        var previous = part(compare(Map.of(), metric, values(build, "prb"), day, profile), "previousDay");
        assertEquals("MISSING_CURRENT", previous.get("status"));
        assertEquals("UNASSESSED", previous.get("trend"));
        var missing = DailyKpiCalculator.build(List.of(row(day, Map.of("another", 10))), profile, day, 100);
        assertNull(values(missing, "prb").get(day).value());
        assertEquals(1, values(missing, "prb").get(day).missingSamples());
    }

    @Test void partialDataAndUnequalSampleCoverageCannotClaimImprovement() {
        var metric = metric("prb", Aggregation.MEAN, Direction.LOWER_IS_BETTER, "PERCENT");
        var profile = profile(metric);
        var build = DailyKpiCalculator.build(List.of(row(day.minusDays(1), Map.of("prb", 50)),
                row(day, Map.of("prb", 10)), row(day, Map.of("prb", 20))), profile, day, 100);
        assertEquals("COVERAGE_CHANGED", part(compare(Map.of(), metric, values(build, "prb"), day, profile), "previousDay").get("status"));
        var partial = DailyKpiCalculator.build(List.of(row(day.minusDays(1), Map.of("prb", 50)),
                row(day, Map.of("prb", 10)), row(day, Map.of())), profile, day, 100);
        assertEquals("PARTIAL_DATA", part(compare(Map.of(), metric, values(partial, "prb"), day, profile), "previousDay").get("status"));
    }

    @Test void zeroDenominatorsInvalidCountersAndNegativeBaselinesAreExplicit() {
        var ratio = new Metric("rate", Aggregation.RATIO, Direction.HIGHER_IS_BETTER, "PERCENT", "ok", "total", null, true);
        var profile = profile(ratio);
        var build = DailyKpiCalculator.build(List.of(row(day, Map.of("ok", 0, "total", 0))), profile, day, 100);
        assertEquals("ZERO_DENOMINATOR", values(build, "rate").get(day).status());
        var invalid = DailyKpiCalculator.build(List.of(row(day, Map.of("ok", 1, "total", 0))), profile, day, 100);
        assertEquals("INVALID_DATA", values(invalid, "rate").get(day).status());
        var rsrp = metric("rsrp", Aggregation.MEAN, Direction.HIGHER_IS_BETTER, "DBM");
        var rsrpProfile = profile(rsrp);
        var power = DailyKpiCalculator.build(List.of(row(day.minusDays(1), Map.of("rsrp", -90)), row(day, Map.of("rsrp", -85))), rsrpProfile, day, 100);
        var previous = part(compare(Map.of(), rsrp, values(power, "rsrp"), day, rsrpProfile), "previousDay");
        assertEquals("LOGARITHMIC_UNIT", previous.get("relativeChangeReason"));
        assertEquals("IMPROVED", previous.get("trend"));
    }

    @Test void invalidProfilesGroupingAndResourceLimitsFailClearly() {
        var metric = metric("prb", Aggregation.MEAN, Direction.LOWER_IS_BETTER, "PERCENT");
        assertThrows(IllegalArgumentException.class, () -> new Profile(List.of("site"), List.of(metric), 7, 1, true).validated());
        assertThrows(IllegalArgumentException.class, () -> new Profile(List.of("cellName"), List.of(metric, metric), 7, 1, true).validated());
        assertThrows(IllegalArgumentException.class, () -> profile(metric("bad", Aggregation.SUM, Direction.NEUTRAL, "PERCENT")));
        assertThrows(IllegalArgumentException.class, () -> DailyKpiCalculator.build(List.of(row(day, Map.of("prb", 1))),
                new Profile(List.of("cellName", "tech"), List.of(metric), 7, 1, true), day, 100));
        assertThrows(IllegalArgumentException.class, () -> DailyKpiCalculator.build(List.of(row(day, Map.of("prb", 1)), row(day.minusDays(1), Map.of("prb", 2))), profile(metric), day, 1));
    }
}

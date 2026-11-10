package com.network.monitoring.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.*;

/** Versioned, explicit aggregation; a sample mean is never inferred to be a counter-weighted rate. */
public final class DailyKpiCalculator {
    public enum Aggregation { MEAN, SUM, RATIO }
    public enum Direction { HIGHER_IS_BETTER, LOWER_IS_BETTER, NEUTRAL }
    public record Metric(String key, Aggregation aggregation, Direction direction, String unit,
                         String numeratorKey, String denominatorKey, Double scale, Boolean relativeChangeEnabled) { }
    public record Profile(List<String> groupBy, List<Metric> metrics, int minimumBaselineDays,
                          int minimumSamplesPerDay, Boolean requireEqualSampleCounts) {
        public Profile validated() {
            if (groupBy == null || !groupBy.contains("cellName") || groupBy.size() > 12
                    || groupBy.stream().distinct().count() != groupBy.size()) {
                throw new IllegalArgumentException("groupBy must explicitly include cellName (maximum 12 unique dimensions).");
            }
            for (String field : groupBy) {
                if (field == null || !(Set.of("cellName", "site", "tech", "band", "sectorid", "sectorname", "groups").contains(field)
                        || field.matches("dimensions\\.[a-zA-Z0-9_]+"))) throw new IllegalArgumentException("Invalid analysis grouping dimension.");
            }
            if (metrics == null || metrics.isEmpty() || metrics.size() > 50) throw new IllegalArgumentException("Configure 1 to 50 KPI metrics.");
            Set<String> keys = new HashSet<>();
            for (Metric metric : metrics) {
                if (metric == null || metric.key() == null || !metric.key().matches("[a-zA-Z0-9_]{1,191}")
                        || !keys.add(metric.key()) || metric.aggregation() == null || metric.direction() == null
                        || metric.unit() == null || !metric.unit().matches("[A-Z][A-Z0-9_]{0,31}")) {
                    throw new IllegalArgumentException("Each KPI requires a unique canonical key, aggregation, direction and unit.");
                }
                if (metric.aggregation() == Aggregation.RATIO && (metric.numeratorKey() == null || metric.denominatorKey() == null
                        || !metric.numeratorKey().matches("[a-zA-Z0-9_]{1,191}") || !metric.denominatorKey().matches("[a-zA-Z0-9_]{1,191}"))) {
                    throw new IllegalArgumentException("RATIO requires canonical numeratorKey and denominatorKey counters.");
                }
                if (metric.scale() != null && (!Double.isFinite(metric.scale()) || metric.scale() <= 0)) throw new IllegalArgumentException("Scale must be positive and finite.");
                if (metric.aggregation() == Aggregation.RATIO && "PERCENT".equals(metric.unit())
                        && metric.scale() != null && metric.scale() != 100) throw new IllegalArgumentException("PERCENT ratio scale must be 100.");
                if (metric.aggregation() == Aggregation.SUM && "PERCENT".equals(metric.unit())) {
                    throw new IllegalArgumentException("Do not sum percentages. Configure counter-based RATIO or an explicitly descriptive sample MEAN.");
                }
            }
            int days = minimumBaselineDays == 0 ? 7 : minimumBaselineDays;
            int samples = minimumSamplesPerDay == 0 ? 1 : minimumSamplesPerDay;
            if (days < 1 || days > 7 || samples < 1) throw new IllegalArgumentException("Baseline days must be 1..7 and minimum samples must be positive.");
            return new Profile(groupBy.stream().sorted().toList(), metrics.stream().sorted(Comparator.comparing(Metric::key)).toList(),
                    days, samples, requireEqualSampleCounts == null || requireEqualSampleCounts);
        }
    }

    public record Value(Double value, double sum, double denominatorSum, int validSamples, int missingSamples,
                        int invalidSamples, int totalSamples, String status) { }
    public record GroupDay(LocalDate date, String groupHash, Map<String, String> dimensions,
                           Map<String, Value> metrics, int records) { }
    public record Build(LocalDate date, List<GroupDay> days, int groups, int inputRecords) { }

    private static final MathContext MC = MathContext.DECIMAL128;

    public static Build build(List<Map<String, Object>> records, Profile raw, LocalDate requested, int maxGroups) {
        Profile profile = raw.validated();
        if (records.isEmpty()) throw new IllegalArgumentException("No KPI observations are available.");
        LocalDate latest = null;
        for (Map<String, Object> record : records) {
            LocalDate date = date(record);
            if (latest == null || date.isAfter(latest)) latest = date;
        }
        LocalDate asOf = requested == null ? latest : requested;
        Map<String, Bucket> buckets = new LinkedHashMap<>();
        Set<String> groups = new HashSet<>();
        int included = 0;
        for (Map<String, Object> record : records) {
            LocalDate day = date(record);
            if (day.isBefore(asOf.minusDays(7)) || day.isAfter(asOf)) continue;
            Map<String, String> dimensions = new TreeMap<>();
            for (String field : profile.groupBy()) {
                Object value = field.startsWith("dimensions.") && record.get("dimensions") instanceof Map<?, ?> dims
                        ? dims.get(field.substring(11)) : record.get(field);
                if (value == null || value.toString().isBlank()) throw new IllegalArgumentException("Missing comparison group dimension: " + field);
                dimensions.put(field, value.toString().trim());
            }
            String hash = RecommendationCacheKey.build(Map.of(), KpiObservationMerge.json(dimensions), Map.of("kind", "daily-group-v1"));
            Bucket bucket = buckets.computeIfAbsent(day + ":" + hash, key -> new Bucket(day, hash, dimensions, profile));
            if (buckets.size() > maxGroups) throw new IllegalArgumentException("Daily analysis exceeds configured group-day limit. Narrow the dataset/profile before retrying.");
            bucket.add(record);
            groups.add(hash);
            included++;
        }
        return new Build(asOf, buckets.values().stream().map(Bucket::finish)
                .sorted(Comparator.comparing(GroupDay::date).thenComparing(GroupDay::groupHash)).toList(), groups.size(), included);
    }

    private static LocalDate date(Map<String, Object> record) {
        try { return LocalDate.parse(String.valueOf(record.get("date"))); }
        catch (java.time.DateTimeException ex) { throw new IllegalArgumentException("Analysis requires valid canonical data dates."); }
    }

    private static class Bucket {
        final LocalDate date;
        final String hash;
        final Map<String, String> dimensions;
        final Profile profile;
        final Map<String, Accumulator> metrics = new LinkedHashMap<>();
        int count;
        Bucket(LocalDate date, String hash, Map<String, String> dimensions, Profile profile) {
            this.date = date; this.hash = hash; this.dimensions = dimensions; this.profile = profile;
            profile.metrics().forEach(metric -> metrics.put(metric.key(), new Accumulator(metric)));
        }
        void add(Map<String, Object> record) {
            Map<?, ?> values = record.get("metrics") instanceof Map<?, ?> map ? map : Map.of();
            metrics.values().forEach(accumulator -> accumulator.add(values));
            count++;
        }
        GroupDay finish() {
            Map<String, Value> values = new LinkedHashMap<>();
            metrics.forEach((key, accumulator) -> values.put(key, accumulator.finish(profile.minimumSamplesPerDay())));
            return new GroupDay(date, hash, dimensions, values, count);
        }
    }

    private static class Accumulator {
        final Metric metric;
        BigDecimal sum = BigDecimal.ZERO, denominator = BigDecimal.ZERO;
        int valid, missing, invalid;
        Accumulator(Metric metric) { this.metric = metric; }
        void add(Map<?, ?> values) {
            Object raw = values.get(metric.aggregation() == Aggregation.RATIO ? metric.numeratorKey() : metric.key());
            Object rawDenominator = metric.aggregation() == Aggregation.RATIO ? values.get(metric.denominatorKey()) : 1;
            if (raw == null || rawDenominator == null) { missing++; return; }
            try {
                BigDecimal value = numeric(raw), den = numeric(rawDenominator);
                if (metric.aggregation() == Aggregation.RATIO && (value.signum() < 0 || den.signum() < 0
                        || den.signum() == 0 && value.signum() > 0)) { invalid++; return; }
                if ("PERCENT".equals(metric.unit()) && (value.signum() < 0
                        || metric.aggregation() == Aggregation.RATIO && value.compareTo(den) > 0
                        || metric.aggregation() != Aggregation.RATIO && value.compareTo(BigDecimal.valueOf(100)) > 0)) {
                    invalid++; return;
                }
                sum = sum.add(value); denominator = denominator.add(den); valid++;
            } catch (NumberFormatException ex) { invalid++; }
        }
        Value finish(int minimum) {
            if (!Double.isFinite(sum.doubleValue()) || !Double.isFinite(denominator.doubleValue())) {
                return new Value(null, 0, 0, valid, missing, invalid, valid + missing + invalid, "NON_FINITE_AGGREGATE");
            }
            Double value = valid == 0 ? null : aggregate(metric, sum, denominator, valid);
            String status = valid == 0 ? (invalid > 0 ? "INVALID_DATA" : "MISSING")
                    : value == null ? (metric.aggregation() == Aggregation.RATIO && denominator.signum() == 0 ? "ZERO_DENOMINATOR" : "NON_FINITE_AGGREGATE")
                    : valid < minimum ? "INSUFFICIENT_SAMPLES" : missing > 0 || invalid > 0 ? "PARTIAL_DATA" : "OK";
            return new Value(value, sum.doubleValue(), denominator.doubleValue(), valid, missing, invalid, valid + missing + invalid, status);
        }
    }

    private static BigDecimal numeric(Object value) {
        BigDecimal number = new BigDecimal(value.toString());
        if (!Double.isFinite(number.doubleValue())) throw new NumberFormatException("Non-finite KPI");
        return number;
    }

    private static Double aggregate(Metric metric, BigDecimal sum, BigDecimal denominator, int valid) {
        BigDecimal value = switch (metric.aggregation()) {
            case SUM -> sum;
            case MEAN -> sum.divide(BigDecimal.valueOf(valid), MC);
            case RATIO -> denominator.signum() == 0 ? null : sum.divide(denominator, MC).multiply(BigDecimal.valueOf(
                    metric.scale() == null ? ("PERCENT".equals(metric.unit()) ? 100 : 1) : metric.scale()), MC);
        };
        if (value == null || !Double.isFinite(value.doubleValue())) return null;
        return value.doubleValue();
    }

    public static Map<String, Object> compare(Map<String, String> dimensions, Metric metric, Map<LocalDate, Value> days,
                                               LocalDate date, Profile profile) {
        Value current = days.get(date), previous = days.get(date.minusDays(1));
        List<LocalDate> missing = new ArrayList<>();
        List<Value> baseline = new ArrayList<>();
        for (int offset = 7; offset >= 1; offset--) {
            LocalDate day = date.minusDays(offset);
            Value value = days.get(day);
            if (value == null || value.value() == null) missing.add(day);
            else baseline.add(value);
        }
        Double weekly = null;
        if (!baseline.isEmpty()) {
            BigDecimal numerator = BigDecimal.ZERO, denominator = BigDecimal.ZERO;
            for (Value value : baseline) {
                numerator = numerator.add(BigDecimal.valueOf(metric.aggregation() == Aggregation.RATIO ? value.sum() : value.value()));
                denominator = denominator.add(BigDecimal.valueOf(metric.aggregation() == Aggregation.RATIO ? value.denominatorSum() : 1));
            }
            weekly = metric.aggregation() == Aggregation.RATIO ? aggregate(metric, numerator, denominator, 1)
                    : numerator.divide(denominator, MC).doubleValue();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dimensions", dimensions);
        result.put("metric", metric.key());
        result.put("unit", metric.unit());
        result.put("aggregation", metric.aggregation());
        result.put("direction", metric.direction());
        result.put("date", date.toString());
        result.put("current", current);
        result.put("previousDay", delta(metric, current, previous == null ? null : previous.value(), previous == null ? List.of() : List.of(previous), 1, profile));
        Map<String, Object> week = delta(metric, current, weekly, baseline, profile.minimumBaselineDays(), profile);
        week.put("daysAvailable", baseline.size());
        week.put("baselineComplete", baseline.size() == 7);
        week.put("missingDates", missing.stream().map(LocalDate::toString).toList());
        week.put("windowStart", date.minusDays(7).toString());
        week.put("windowEnd", date.minusDays(1).toString());
        week.put("strategy", metric.aggregation() == Aggregation.RATIO ? "POOLED_COUNTERS" : "MEAN_DAILY_VALUES");
        result.put("previousSevenDays", week);
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int offset = 7; offset >= 0; offset--) {
            LocalDate day = date.minusDays(offset);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", day.toString()); point.put("observation", days.get(day)); trend.add(point);
        }
        result.put("trend", trend);
        return result;
    }

    private static Map<String, Object> delta(Metric metric, Value current, Double baseline, List<Value> history,
                                              int minimumDays, Profile profile) {
        Double now = current == null ? null : current.value();
        Double difference = now == null || baseline == null ? null : now - baseline;
        if (difference != null && !Double.isFinite(difference)) difference = null;
        String status = now == null ? "MISSING_CURRENT" : baseline == null ? "MISSING_BASELINE"
                : difference == null ? "NON_FINITE_CHANGE"
                : history.size() < minimumDays ? "INSUFFICIENT_BASELINE"
                : !"OK".equals(current.status()) || history.stream().anyMatch(value -> !"OK".equals(value.status())) ? "PARTIAL_DATA"
                : Boolean.TRUE.equals(profile.requireEqualSampleCounts()) && history.stream().anyMatch(value -> value.validSamples() != current.validSamples())
                    ? "COVERAGE_CHANGED" : "OK";
        String trend = "UNASSESSED";
        if ("OK".equals(status) && metric.direction() != Direction.NEUTRAL) {
            trend = difference == 0 ? "UNCHANGED" : (difference > 0) == (metric.direction() == Direction.HIGHER_IS_BETTER) ? "IMPROVED" : "DEGRADED";
        }
        String relativeReason = !Boolean.TRUE.equals(metric.relativeChangeEnabled()) ? "DISABLED_BY_PROFILE"
                : Set.of("DB", "DBM").contains(metric.unit()) ? "LOGARITHMIC_UNIT"
                : baseline == null || now == null ? "MISSING_DATA" : baseline == 0 ? "ZERO_BASELINE" : baseline < 0 ? "NEGATIVE_BASELINE" : null;
        Double relative = relativeReason == null && difference != null ? difference / baseline * 100 : null;
        if (relativeReason == null && (relative == null || !Double.isFinite(relative))) {
            relative = null; relativeReason = "NON_FINITE_CHANGE";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("baselineValue", baseline); result.put("difference", difference);
        result.put("percentagePointChange", "PERCENT".equals(metric.unit()) ? difference : null);
        result.put("relativeChangePercent", relative);
        result.put("relativeChangeReason", relativeReason); result.put("status", status); result.put("trend", trend);
        return result;
    }
}

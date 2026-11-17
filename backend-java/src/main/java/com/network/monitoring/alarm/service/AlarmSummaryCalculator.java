package com.network.monitoring.alarm.service;

import com.network.monitoring.alarm.dto.AlarmReport.*;
import java.time.Duration;
import java.util.*;
import static com.network.monitoring.alarm.support.AlarmValues.*;

/** Deterministic source-snapshot counts, independent of database, rendering and transport. */
public final class AlarmSummaryCalculator {
    private AlarmSummaryCalculator() {}
    public static final List<String> PENDING = List.of("MS2 pending 2G/4G", "LR pending status/count",
            "Zone/circle owners and major reasons", "Site recovery and circle remarks",
            "GPS/VSWR and responsibility category rules");
    private record CircleKey(String zone, long id, String name, String technology) {}
    private record ZoneKey(String zone, String technology) {}
    private record SiteKey(long circleId, String technology, String site) {}

    public static Summary summarize(List<Source> sources) {
        Map<CircleKey, Counts> circles = new LinkedHashMap<>();
        Map<ZoneKey, Counts> zones = new LinkedHashMap<>();
        Counts national = new Counts();
        for (Source source : sources) {
            for (Map<String, Object> row : source.observations()) {
                String zone = string(row, "zone_name"), circle = string(row, "circle_name"), rat = string(row, "technology");
                long circleId = number(row, "circle_id");
                circles.computeIfAbsent(new CircleKey(zone, circleId, circle, rat), ignored -> new Counts()).add(source, row);
                zones.computeIfAbsent(new ZoneKey(zone, rat), ignored -> new Counts()).add(source, row);
                national.add(source, row);
            }
        }
        List<Group> circleRows = circles.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<CircleKey, Counts> e) -> e.getKey().zone())
                        .thenComparing(e -> e.getKey().name()).thenComparing(e -> e.getKey().technology())
                        .thenComparingLong(e -> e.getKey().id()))
                .map(e -> e.getValue().group(e.getKey().zone(), e.getKey().id(), e.getKey().name(), e.getKey().technology())).toList();
        List<Group> zoneRows = zones.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<ZoneKey, Counts> e) -> e.getKey().zone()).thenComparing(e -> e.getKey().technology()))
                .map(e -> e.getValue().group(e.getKey().zone(), null, null, e.getKey().technology())).toList();
        return new Summary("alarm-summary-v1", zoneRows, circleRows, national.observed, national.included,
                national.unmapped, national.downSites.size(), PENDING,
                List.of("Counts are source observations, not certified unique incidents across feeds.",
                        "Customer classification and inclusion come from the captured catalogue; source severity is separate.",
                        "Known down sites are distinct by circle, technology and mapped site; unmapped sites are reported separately.",
                        "Age is measured at each source snapshot. A site uses its greatest observed age across selected sources.",
                        "Age bands are <12h, 12h through 48h, >48h and <72h, and >=72h; boundaries follow the source formula.",
                        "Missing observations do not confirm clearance. Site-up and MS2/LR totals are not inferred."));
    }

    private static final class Counts {
        long observed, included, critical, major, minor, serviceAffecting, unmapped, unmappedSites;
        final Map<SiteKey, Long> downSites = new HashMap<>();
        void add(Source source, Map<String, Object> row) {
            observed++;
            if ("UNMAPPED".equals(string(row, "classification"))) unmapped++;
            String site = string(row, "site_code");
            if (site.isBlank()) unmappedSites++;
            if (!bool(row, "included")) return;
            included++;
            switch (string(row, "classification")) {
                case "CRITICAL" -> critical++;
                case "MAJOR" -> major++;
                case "MINOR" -> minor++;
                default -> { }
            }
            if (bool(row, "service_affecting")) serviceAffecting++;
            if (bool(row, "site_down") && !site.isBlank()) {
                long seconds = Duration.between(jdbcInstant(row.get("raised_at")), source.snapshotAt()).getSeconds();
                if (seconds < 0) throw new IllegalStateException("Raised time is after the captured source snapshot.");
                downSites.merge(new SiteKey(number(row, "circle_id"), string(row, "technology"), site), seconds, Math::max);
            }
        }
        Group group(String zone, Long circleId, String circle, String technology) {
            long a = 0, b = 0, c = 0, d = 0;
            for (long age : downSites.values()) {
                if (age < 12 * 3600L) a++;
                else if (age <= 48 * 3600L) b++;
                else if (age < 72 * 3600L) c++;
                else d++;
            }
            return new Group(zone, circleId, circle, technology, observed, included, critical, major, minor,
                    serviceAffecting, downSites.size(), unmapped, unmappedSites, new AgeBands(a, b, c, d));
        }
    }
}

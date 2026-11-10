package com.network.monitoring.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.monitoring.entity.AlarmData;
import com.network.monitoring.entity.SiteData;
import com.network.monitoring.repository.AlarmDataRepository;
import com.network.monitoring.repository.SiteDataRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

@Service
public class SitePredictionService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final long RECOMMENDATION_CACHE_TTL_MILLIS = 120_000L;
    private static final long CONTEXT_CACHE_TTL_MILLIS = 300_000L;

    private final KpiDynamicDataService kpiDynamicDataService;
    private final KpiCacheService kpiCacheService;
    private final SiteDataRepository siteDataRepository;
    private final AlarmDataRepository alarmDataRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Map<Long, CachedRecommendations> recommendationCache = new ConcurrentHashMap<>();
    private final Map<Long, Object> buildLocks = new ConcurrentHashMap<>();
    private final TaskExecutor warmupExecutor;
    private final Set<Long> warmingFiles = ConcurrentHashMap.newKeySet();
    private volatile MasterContext cachedMasterContext;
    private volatile long masterContextLoadedAt;
    private volatile AlarmContext cachedAlarmContext;
    private volatile long alarmContextLoadedAt;

    public SitePredictionService(KpiDynamicDataService kpiDynamicDataService,
                                 KpiCacheService kpiCacheService,
                                 SiteDataRepository siteDataRepository,
                                 AlarmDataRepository alarmDataRepository,
                                 JdbcTemplate jdbcTemplate,
                                 @Qualifier("predictionWarmupExecutor") TaskExecutor warmupExecutor) {
        this.kpiDynamicDataService = kpiDynamicDataService;
        this.kpiCacheService = kpiCacheService;
        this.siteDataRepository = siteDataRepository;
        this.alarmDataRepository = alarmDataRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.warmupExecutor = warmupExecutor;
        ensurePredictionTable();
    }

    public Map<String, Object> summary(Long fileId) {
        List<Recommendation> recommendations = recommendationsFor(fileId, null);
        Map<String, Long> actionCounts = new LinkedHashMap<>();
        actionCounts.put("LOAD_BALANCE", 0L);
        actionCounts.put("CAPACITY_REVIEW", 0L);
        actionCounts.put("COVERAGE_CHECK", 0L);
        actionCounts.put("QUALITY_CHECK", 0L);
        actionCounts.put("OBSERVE", 0L);

        Map<String, Long> severityCounts = new LinkedHashMap<>();
        severityCounts.put("CRITICAL", 0L);
        severityCounts.put("MAJOR", 0L);
        severityCounts.put("MINOR", 0L);
        severityCounts.put("NORMAL", 0L);

        Set<String> sites = new LinkedHashSet<>();
        Set<String> cells = new LinkedHashSet<>();
        for (Recommendation recommendation : recommendations) {
            sites.add(recommendation.site);
            cells.add(recommendation.cellName);
            actionCounts.compute(recommendation.actionCode, (key, value) -> value == null ? 1L : value + 1L);
            severityCounts.compute(recommendation.severity, (key, value) -> value == null ? 1L : value + 1L);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileId", fileId);
        response.put("siteCount", sites.size());
        response.put("cellCount", cells.size());
        response.put("recommendationCount", recommendations.size());
        response.put("actionCounts", actionCounts);
        response.put("severityCounts", severityCounts);
        response.put("topRecommendations", recommendations.stream().limit(10).map(Recommendation::toPayload).toList());
        return response;
    }

    public Map<String, Object> recommendations(Long fileId, String site, Integer limit) {
        int safeLimit = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        List<Recommendation> recommendations = recommendationsFor(fileId, site);
        List<Map<String, Object>> items = recommendations.stream()
                .limit(safeLimit)
                .map(Recommendation::toPayload)
                .toList();
        for (int index = 0; index < items.size(); index++) {
            items.get(index).put("rank", index + 1);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileId", fileId);
        response.put("site", site == null || site.isBlank() ? "ALL" : site);
        response.put("count", items.size());
        response.put("data", items);
        return response;
    }

    private List<Recommendation> recommendationsFor(Long fileId, String requestedSite) {
        if (fileId == null) {
            throw new IllegalArgumentException("fileId is required.");
        }
        List<Recommendation> recommendations = cachedRecommendations(fileId);
        String siteFilter = normalize(requestedSite);
        if (siteFilter.isBlank()) {
            return recommendations;
        }
        return recommendations.stream()
                .filter(recommendation ->
                        normalize(recommendation.site).equals(siteFilter)
                                || normalize(String.valueOf(recommendation.siteInfo.get("siteId"))).equals(siteFilter)
                                || normalize(String.valueOf(recommendation.siteInfo.get("siteName"))).equals(siteFilter))
                .toList();
    }

    private List<Recommendation> cachedRecommendations(Long fileId) {
        long now = System.currentTimeMillis();
        CachedRecommendations cached = recommendationCache.get(fileId);
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.items();
        }

        Object lock = buildLocks.computeIfAbsent(fileId, ignored -> new Object());
        synchronized (lock) {
            long lockedNow = System.currentTimeMillis();
            CachedRecommendations lockedCached = recommendationCache.get(fileId);
            if (lockedCached != null && lockedCached.expiresAtMillis() > lockedNow) {
                return lockedCached.items();
            }

            List<Recommendation> persisted = readPersistedRecommendations(fileId);
            if (!persisted.isEmpty()) {
                recommendationCache.put(fileId, new CachedRecommendations(persisted, lockedNow + RECOMMENDATION_CACHE_TTL_MILLIS));
                return persisted;
            }

            List<Recommendation> built = buildRecommendations(fileId);
            persistRecommendations(fileId, built);
            recommendationCache.put(fileId, new CachedRecommendations(built, lockedNow + RECOMMENDATION_CACHE_TTL_MILLIS));
            return built;
        }
    }

    public void invalidateFile(Long fileId) {
        if (fileId == null) return;
        recommendationCache.remove(fileId);
        ensurePredictionTable();
        jdbcTemplate.update("DELETE FROM `site_prediction_results` WHERE `fileId` = ?", fileId);
    }

    public void warmFileAsync(Long fileId) {
        if (fileId == null || !warmingFiles.add(fileId)) return;
        try {
            warmupExecutor.execute(() -> {
                try {
                    Thread.sleep(1_500L);
                    cachedRecommendations(fileId);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    recommendationCache.remove(fileId);
                } catch (Exception ignored) {
                    recommendationCache.remove(fileId);
                } finally {
                    warmingFiles.remove(fileId);
                }
            });
        } catch (RejectedExecutionException ex) {
            // Optional optimization: on-demand prediction remains available when warmup is full.
            warmingFiles.remove(fileId);
        }
    }

    private void ensurePredictionTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `site_prediction_results` (
                    `id` BIGINT NOT NULL AUTO_INCREMENT,
                    `fileId` BIGINT NOT NULL,
                    `rankOrder` INT NOT NULL,
                    `site` VARCHAR(255) NULL,
                    `cellName` VARCHAR(255) NULL,
                    `actionCode` VARCHAR(64) NULL,
                    `severity` VARCHAR(32) NULL,
                    `score` INT NULL,
                    `payloadJson` LONGTEXT NULL,
                    `createdAt` DATETIME NULL,
                    `updatedAt` DATETIME NULL,
                    PRIMARY KEY (`id`),
                    INDEX `idx_site_prediction_file` (`fileId`),
                    INDEX `idx_site_prediction_file_site` (`fileId`, `site`),
                    INDEX `idx_site_prediction_file_action` (`fileId`, `actionCode`),
                    INDEX `idx_site_prediction_file_severity` (`fileId`, `severity`)
                )
                """);
    }

    private List<Recommendation> readPersistedRecommendations(Long fileId) {
        ensurePredictionTable();
        String sql = "SELECT `payloadJson` FROM `site_prediction_results` WHERE `fileId` = ? ORDER BY `rankOrder` ASC, `score` DESC, `id` ASC";
        List<Map<String, Object>> payloads = jdbcTemplate.query(sql, (rs, rowNum) -> readJsonMap(rs.getString("payloadJson")), fileId);
        if (!payloads.isEmpty() && payloads.stream().noneMatch(payload -> payload.containsKey("neighborCandidates"))) {
            jdbcTemplate.update("DELETE FROM `site_prediction_results` WHERE `fileId` = ?", fileId);
            return List.of();
        }
        return payloads.stream()
                .map(this::recommendationFromPayload)
                .filter(recommendation -> recommendation != null)
                .toList();
    }

    private void persistRecommendations(Long fileId, List<Recommendation> recommendations) {
        ensurePredictionTable();
        jdbcTemplate.update("DELETE FROM `site_prediction_results` WHERE `fileId` = ?", fileId);
        if (recommendations == null || recommendations.isEmpty()) return;

        String sql = "INSERT INTO `site_prediction_results` " +
                "(`fileId`, `rankOrder`, `site`, `cellName`, `actionCode`, `severity`, `score`, `payloadJson`, `createdAt`, `updatedAt`) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ZoneId.of("UTC")));
        List<Object[]> args = new ArrayList<>();
        for (int index = 0; index < recommendations.size(); index++) {
            Recommendation recommendation = recommendations.get(index);
            args.add(new Object[]{
                    fileId,
                    index + 1,
                    recommendation.site,
                    recommendation.cellName,
                    recommendation.actionCode,
                    recommendation.severity,
                    recommendation.score,
                    writeJson(recommendation.toPayload()),
                    now,
                    now
            });
        }
        jdbcTemplate.batchUpdate(sql, args);
    }

    private Recommendation recommendationFromPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return null;
        Recommendation recommendation = new Recommendation(
                string(payload.get("site")),
                string(payload.get("cellName"))
        );
        recommendation.band = string(payload.get("band"));
        recommendation.technology = string(payload.get("technology"));
        recommendation.sector = string(payload.get("sector"));
        recommendation.actionCode = firstNonBlank(string(payload.get("actionCode")), "OBSERVE");
        recommendation.action = string(payload.get("action"));
        recommendation.severity = firstNonBlank(string(payload.get("severity")), "NORMAL");
        recommendation.score = intValue(payload.get("score"));
        recommendation.targetCell = string(payload.get("targetCell"));
        recommendation.targetBand = string(payload.get("targetBand"));
        recommendation.reasons = stringList(payload.get("reasons"));
        recommendation.evidence = stringList(payload.get("evidence"));
        recommendation.neighborCandidates = mapList(payload.get("neighborCandidates"));
        recommendation.metrics.putAll(objectMap(payload.get("metrics")));
        recommendation.siteInfo.putAll(objectMap(payload.get("siteInfo")));
        recommendation.alarmContext.putAll(objectMap(payload.get("alarmContext")));
        return recommendation;
    }

    private List<Recommendation> buildRecommendations(Long fileId) {
        List<Map<String, Object>> records = records(fileId);
        MasterContext masterContext = masterContext();
        AlarmContext alarmContext = alarmContext();
        return buildRecommendations(records, masterContext, alarmContext);
    }

    public Map<String, Object> predictKpiSnapshot(List<Map<String, Object>> records, int limit) {
        List<Recommendation> results = buildRecommendations(records, new MasterContext(), new AlarmContext());
        Map<String, Long> actions = new LinkedHashMap<>();
        for (Recommendation result : results) actions.merge(result.actionCode, 1L, Long::sum);
        return Map.of("total", results.size(), "actions", actions,
                "items", results.stream().limit(Math.max(1, limit)).map(Recommendation::toPayload).toList(),
                "detailsLimited", results.size() > limit, "mode", "KPI_ONLY",
                "limitation", "Current-data heuristics only; no pinned site-master, alarm context, TA or future forecasting.");
    }

    private List<Recommendation> buildRecommendations(List<Map<String, Object>> records, MasterContext masterContext, AlarmContext alarmContext) {
        Map<String, CellStats> cells = new LinkedHashMap<>();

        for (Map<String, Object> record : records) {
            String site = siteName(record);
            String cell = cellName(record);
            String key = normalize(site) + "|" + normalize(cell);
            CellStats stats = cells.computeIfAbsent(key, ignored -> new CellStats(site, cell));
            stats.band = firstNonBlank(stats.band, bandName(record));
            stats.technology = firstNonBlank(stats.technology, technologyName(record));
            stats.sector = firstNonBlank(stats.sector, sectorName(record));
            stats.applySiteData(masterContext.find(site, cell));
            stats.applyAlarmContext(alarmContext.find(site, cell));
            stats.rowCount++;

            Map<String, Object> metrics = objectMap(record.get("metrics"));
            for (Map.Entry<String, Object> entry : metrics.entrySet()) {
                Double value = number(entry.getValue());
                if (value == null) {
                    continue;
                }
                stats.addMetric(entry.getKey(), value);
            }
        }

        Map<String, List<CellStats>> bySite = new LinkedHashMap<>();
        List<CellStats> allCells = new ArrayList<>(cells.values());
        for (CellStats stats : cells.values()) {
            bySite.computeIfAbsent(stats.site, ignored -> new ArrayList<>()).add(stats);
        }

        List<Recommendation> recommendations = new ArrayList<>();
        for (List<CellStats> siteCells : bySite.values()) {
            siteCells.sort(Comparator.comparing(CellStats::cellName, String.CASE_INSENSITIVE_ORDER));
            for (CellStats cell : siteCells) {
                recommendations.add(recommend(cell, siteCells, allCells));
            }
        }

        recommendations.sort(Comparator
                .comparingInt(Recommendation::priority).reversed()
                .thenComparing(Recommendation::score).reversed()
                .thenComparing(Recommendation::site, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Recommendation::cellName, String.CASE_INSENSITIVE_ORDER));
        return recommendations;
    }

    private Recommendation recommend(CellStats cell, List<CellStats> siteCells, List<CellStats> allCells) {
        double prb = cell.avg(Category.PRB);
        double dlThroughput = cell.avg(Category.DL_THROUGHPUT);
        double sinr = cell.avg(Category.SINR);
        double rsrpPoor = cell.avg(Category.RSRP_POOR);
        double packetLoss = cell.avg(Category.PACKET_LOSS);
        double dropRate = cell.avg(Category.DROP_RATE);
        double availability = cell.avg(Category.AVAILABILITY);
        double traffic = cell.avg(Category.TRAFFIC);
        double users = cell.avg(Category.USERS);
        double distance = cell.avg(Category.DISTANCE);
        MetricSignal dynamicSignal = cell.dynamicSignal();

        List<String> reasons = new ArrayList<>();
        List<String> evidences = new ArrayList<>();
        int score = 0;

        if (prb >= 80) {
            score += 35;
            reasons.add("High DL/UL PRB utilization indicates congestion.");
            evidences.add("PRB avg " + round(prb));
        } else if (prb >= 65) {
            score += 20;
            reasons.add("PRB utilization is nearing busy-hour risk.");
            evidences.add("PRB avg " + round(prb));
        }

        if (dlThroughput > 0 && dlThroughput < 5) {
            score += 25;
            reasons.add("Low DL throughput suggests user experience degradation.");
            evidences.add("DL throughput avg " + round(dlThroughput));
        }

        if (sinr > 0 && sinr < 6) {
            score += 18;
            reasons.add("Low SINR points to interference or radio quality issues.");
            evidences.add("SINR avg " + round(sinr));
        }

        if (rsrpPoor >= 35 || distance >= 1.5) {
            score += 16;
            reasons.add("Coverage indicators show weak or far-user dominance.");
            if (rsrpPoor >= 35) evidences.add("Poor RSRP sample avg " + round(rsrpPoor));
            if (distance >= 1.5) evidences.add("UE distance avg " + round(distance));
        }

        if (packetLoss >= 0.3 || dropRate >= 1.0) {
            score += 18;
            reasons.add("Packet loss/drop rate indicates service quality risk.");
            if (packetLoss >= 0.3) evidences.add("Packet loss avg " + round(packetLoss));
            if (dropRate >= 1.0) evidences.add("Drop rate avg " + round(dropRate));
        }

        if (availability > 0 && availability < 97) {
            score += 18;
            reasons.add("Availability is below normal target.");
            evidences.add("Availability avg " + round(availability));
        }

        if (cell.alarmCount > 0) {
            score += Math.min(25, cell.alarmCount * 5);
            reasons.add("Uploaded alarm data has active/fault context for this cell/site.");
            evidences.add(cell.alarmCount + " related alarm(s)");
            if (!cell.worstAlarmSeverity.isBlank()) {
                evidences.add("Worst alarm severity " + cell.worstAlarmSeverity);
            }
        }

        if (dynamicSignal.score > 0) {
            score += dynamicSignal.score;
            reasons.add(dynamicSignal.reason);
            evidences.addAll(dynamicSignal.evidence);
        }

        CellStats offloadTarget = findOffloadTarget(cell, siteCells);
        List<Map<String, Object>> neighborCandidates = neighborCandidates(cell, allCells, offloadTarget);
        String actionCode;
        String action;
        if (cell.hasBlockingAlarm()) {
            actionCode = "QUALITY_CHECK";
            action = "Resolve related alarm/fault context before applying load-balancing changes.";
        } else if (prb >= 70 && offloadTarget != null) {
            actionCode = "LOAD_BALANCE";
            action = "Shift traffic from " + cell.cellName + " to lower loaded layer/cell " + offloadTarget.cellName + ".";
            reasons.add("A lower-loaded candidate exists in the same site.");
            evidences.add("Target " + offloadTarget.cellName + " PRB avg " + round(offloadTarget.avg(Category.PRB)));
            score += 20;
        } else if (prb >= 80 || users >= 40 || traffic >= 50) {
            actionCode = "CAPACITY_REVIEW";
            action = "Review capacity expansion, parameter tuning, or carrier/layer balancing for this cell.";
        } else if ((sinr > 0 && sinr < 6) || rsrpPoor >= 35 || distance >= 1.5) {
            actionCode = "COVERAGE_CHECK";
            action = "Check antenna/coverage/interference conditions before load-balancing.";
        } else if (packetLoss >= 0.3 || dropRate >= 1.0 || (availability > 0 && availability < 97)) {
            actionCode = "QUALITY_CHECK";
            action = "Investigate service quality, drop, packet-loss, and availability causes.";
        } else if (!dynamicSignal.actionCode.isBlank()) {
            actionCode = dynamicSignal.actionCode;
            action = dynamicSignal.action;
            if (score < 25) {
                score = 25;
            }
        } else {
            actionCode = "OBSERVE";
            action = "No strong LB action required. Keep this cell under observation.";
            if (reasons.isEmpty()) {
                reasons.add("KPI indicators are within normal operating range.");
            }
        }

        String severity = score >= 75 ? "CRITICAL" : score >= 50 ? "MAJOR" : score >= 25 ? "MINOR" : "NORMAL";
        Recommendation recommendation = new Recommendation(cell.site, cell.cellName);
        recommendation.band = cell.band;
        recommendation.technology = cell.technology;
        recommendation.sector = cell.sector;
        recommendation.actionCode = actionCode;
        recommendation.action = action;
        recommendation.severity = severity;
        recommendation.score = Math.max(0, Math.min(100, score));
        recommendation.reasons = reasons;
        recommendation.evidence = evidences;
        recommendation.targetCell = offloadTarget == null ? "" : offloadTarget.cellName;
        recommendation.targetBand = offloadTarget == null ? "" : offloadTarget.band;
        recommendation.neighborCandidates = neighborCandidates;
        recommendation.metrics.put("prb", safeRound(prb));
        recommendation.metrics.put("dlThroughput", safeRound(dlThroughput));
        recommendation.metrics.put("sinr", safeRound(sinr));
        recommendation.metrics.put("poorRsrp", safeRound(rsrpPoor));
        recommendation.metrics.put("packetLoss", safeRound(packetLoss));
        recommendation.metrics.put("dropRate", safeRound(dropRate));
        recommendation.metrics.put("availability", safeRound(availability));
        recommendation.metrics.put("traffic", safeRound(traffic));
        recommendation.metrics.put("users", safeRound(users));
        recommendation.metrics.put("distance", safeRound(distance));
        recommendation.siteInfo.put("siteName", cell.siteName);
        recommendation.siteInfo.put("siteId", cell.siteId);
        recommendation.siteInfo.put("region", cell.region);
        recommendation.siteInfo.put("cluster", cell.cluster);
        recommendation.siteInfo.put("latitude", cell.lat);
        recommendation.siteInfo.put("longitude", cell.lon);
        recommendation.siteInfo.put("azimuth", cell.azimuth);
        recommendation.siteInfo.put("pci", cell.pci);
        recommendation.siteInfo.put("tac", cell.tac);
        recommendation.siteInfo.put("bandwidth", cell.bandwidth);
        recommendation.siteInfo.put("frequency", cell.frequency);
        recommendation.alarmContext.put("alarmCount", cell.alarmCount);
        recommendation.alarmContext.put("worstSeverity", cell.worstAlarmSeverity);
        recommendation.alarmContext.put("latestAlarm", cell.latestAlarm);
        recommendation.alarmContext.put("latestAlarmTime", cell.latestAlarmTime);
        return recommendation;
    }

    private List<Map<String, Object>> neighborCandidates(CellStats source, List<CellStats> allCells, CellStats offloadTarget) {
        if (source == null || source.lat == null || source.lon == null) {
            return List.of();
        }
        String sourceKey = normalize(source.site) + "|" + normalize(source.cellName);
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (CellStats candidate : allCells) {
            if (candidate == null || candidate.lat == null || candidate.lon == null) {
                continue;
            }
            String candidateKey = normalize(candidate.site) + "|" + normalize(candidate.cellName);
            if (sourceKey.equals(candidateKey)) {
                continue;
            }
            double distanceKm = distanceKm(source.lat, source.lon, candidate.lat, candidate.lon);
            if (!Double.isFinite(distanceKm) || distanceKm > 8.0) {
                continue;
            }

            boolean samePci = source.pci != null && candidate.pci != null && source.pci.equals(candidate.pci);
            boolean sameSite = normalize(source.site).equals(normalize(candidate.site))
                    || normalize(source.siteId).equals(normalize(candidate.siteId));
            boolean isOffloadTarget = offloadTarget != null
                    && normalize(offloadTarget.cellName).equals(normalize(candidate.cellName));
            double sourcePrb = source.avg(Category.PRB);
            double candidatePrb = candidate.avg(Category.PRB);
            String relationType = isOffloadTarget
                    ? "OFFLOAD_TARGET"
                    : samePci
                        ? "SAME_PCI"
                        : sameSite
                            ? "SAME_SITE_NEIGHBOUR"
                            : "NEARBY_NEIGHBOUR";

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("relationType", relationType);
            payload.put("site", candidate.site);
            payload.put("siteId", candidate.siteId);
            payload.put("siteName", firstNonBlank(candidate.siteName, candidate.site));
            payload.put("cellName", candidate.cellName);
            payload.put("band", candidate.band);
            payload.put("technology", candidate.technology);
            payload.put("sector", candidate.sector);
            payload.put("latitude", candidate.lat);
            payload.put("longitude", candidate.lon);
            payload.put("distanceKm", round(distanceKm));
            payload.put("samePci", samePci);
            payload.put("sameSite", sameSite);
            payload.put("pci", candidate.pci);
            payload.put("sourcePci", source.pci);
            payload.put("azimuth", candidate.azimuth);
            payload.put("sourceAzimuth", source.azimuth);
            payload.put("azimuthDelta", azimuthDelta(source.azimuth, candidate.azimuth));
            payload.put("sourcePrb", safeRound(sourcePrb));
            payload.put("candidatePrb", safeRound(candidatePrb));
            payload.put("offloadSuitable", candidatePrb > 0 && candidatePrb <= 55 && !samePci);
            payload.put("recommendation", relationRecommendation(relationType, samePci, distanceKm, candidatePrb));
            candidates.add(payload);
        }

        candidates.sort(Comparator
                .comparingInt((Map<String, Object> item) -> relationPriority(String.valueOf(item.get("relationType"))))
                .thenComparingDouble(item -> number(item.get("distanceKm")) == null ? Double.MAX_VALUE : number(item.get("distanceKm"))));
        return candidates.stream().limit(8).toList();
    }

    private int relationPriority(String relationType) {
        return switch (String.valueOf(relationType).toUpperCase(Locale.ROOT)) {
            case "SAME_PCI" -> 0;
            case "OFFLOAD_TARGET" -> 1;
            case "SAME_SITE_NEIGHBOUR" -> 2;
            default -> 3;
        };
    }

    private String relationRecommendation(String relationType, boolean samePci, double distanceKm, double candidatePrb) {
        if (samePci) {
            return "PCI conflict candidate. Review PCI reuse, distance, azimuth overlap, and neighbour relation.";
        }
        if ("OFFLOAD_TARGET".equals(relationType)) {
            return "Preferred offload target. Candidate has lower load and is close enough for balancing review.";
        }
        if (candidatePrb > 0 && candidatePrb <= 55) {
            return "Possible neighbour/offload candidate. Validate coverage overlap and handover relation.";
        }
        if (distanceKm <= 1.0) {
            return "Close neighbour. Review interference, PCI planning, and relation tuning.";
        }
        return "Nearby neighbour candidate for coverage and relation review.";
    }

    private double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double earthRadiusKm = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return earthRadiusKm * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private Object azimuthDelta(Integer sourceAzimuth, Integer candidateAzimuth) {
        if (sourceAzimuth == null || candidateAzimuth == null) {
            return null;
        }
        int delta = Math.abs(sourceAzimuth - candidateAzimuth) % 360;
        return Math.min(delta, 360 - delta);
    }

    private CellStats findOffloadTarget(CellStats source, List<CellStats> siteCells) {
        return siteCells.stream()
                .filter(candidate -> !normalize(candidate.cellName).equals(normalize(source.cellName)))
                .filter(candidate -> candidate.avg(Category.PRB) > 0 && candidate.avg(Category.PRB) <= 55)
                .filter(candidate -> source.band.isBlank() || candidate.band.isBlank() || !normalize(candidate.band).equals(normalize(source.band)))
                .sorted(Comparator
                        .comparingDouble((CellStats candidate) -> candidate.avg(Category.PRB))
                        .thenComparing(CellStats::cellName, String.CASE_INSENSITIVE_ORDER))
                .findFirst()
                .orElse(null);
    }

    private List<Map<String, Object>> records(Long fileId) {
        if (fileId == null) {
            throw new IllegalArgumentException("fileId is required.");
        }
        List<Map<String, Object>> records = kpiDynamicDataService.findRecordsByFileId(fileId);
        if (!records.isEmpty()) {
            return records;
        }
        return kpiDynamicDataService.getRecordsFromCachePayload(kpiCacheService.readKpiCache(fileId));
    }

    private MasterContext masterContext() {
        long now = System.currentTimeMillis();
        MasterContext cached = cachedMasterContext;
        if (cached != null && now - masterContextLoadedAt < CONTEXT_CACHE_TTL_MILLIS) {
            return cached;
        }

        MasterContext context = new MasterContext();
        for (SiteData siteData : siteDataRepository.findAll()) {
            context.add(siteData);
        }
        cachedMasterContext = context;
        masterContextLoadedAt = now;
        return context;
    }

    private AlarmContext alarmContext() {
        long now = System.currentTimeMillis();
        AlarmContext cached = cachedAlarmContext;
        if (cached != null && now - alarmContextLoadedAt < CONTEXT_CACHE_TTL_MILLIS) {
            return cached;
        }

        AlarmContext context = new AlarmContext();
        for (AlarmData alarmData : alarmDataRepository.findAll()) {
            context.add(alarmData);
        }
        cachedAlarmContext = context;
        alarmContextLoadedAt = now;
        return context;
    }

    private Category category(String metricName) {
        String key = normalize(metricName);
        if (key.contains("prb") || key.contains("useddlprbs") || key.contains("usedulprbs")) return Category.PRB;
        if (key.contains("dluserthroughput") || key.contains("dlthroughput") || key.contains("downlinkthroughput")) return Category.DL_THROUGHPUT;
        if (key.contains("sinr")) return Category.SINR;
        if (key.contains("rsrp") || key.contains("110dbm")) return Category.RSRP_POOR;
        if (key.contains("packetloss") || key.contains("lossdl") || key.contains("lossul")) return Category.PACKET_LOSS;
        if (key.contains("drop") || key.contains("dcr")) return Category.DROP_RATE;
        if (key.contains("availability") || key.contains("rna")) return Category.AVAILABILITY;
        if (key.contains("traffic") || key.contains("volume")) return Category.TRAFFIC;
        if (key.contains("connecteduser") || key.contains("rrcconnecteduser") || key.contains("avgconnecteduser")) return Category.USERS;
        if (key.contains("distance") || key.contains("uedistance")) return Category.DISTANCE;
        return Category.OTHER;
    }

    private String siteName(Map<String, Object> record) {
        String cell = firstNonBlank(
                value(record, "cellName"),
                dimension(record, "cellname", "cell_name", "cellid", "cell_id", "cell", "shortname", "short_name")
        );
        return firstNonBlank(
                value(record, "site"),
                dimension(record, "site", "siteid", "site_id", "sitename", "site_name", "node", "nodeb", "enodeb", "gnodeb"),
                deriveSiteFromCell(cell),
                "Unknown Site"
        );
    }

    private String cellName(Map<String, Object> record) {
        return firstNonBlank(
                value(record, "cellName"),
                dimension(record, "cellname", "cell_name", "cellid", "cell_id", "cell", "shortname", "short_name", "eutrancell", "eutrancellfdd"),
                "Unknown Cell"
        );
    }

    private String bandName(Map<String, Object> record) {
        return firstNonBlank(value(record, "band"), dimension(record, "band", "frequencyband", "frequency_band", "layer", "carrier"), deriveLayerFromShortName(record), value(record, "tech"), dimension(record, "tech"));
    }

    private String technologyName(Map<String, Object> record) {
        String explicit = firstNonBlank(dimension(record, "technology", "rat", "networktype", "network_type"), value(record, "technology"));
        if (!explicit.isBlank()) return explicit;
        String tech = firstNonBlank(value(record, "tech"), dimension(record, "tech"));
        return tech.toUpperCase(Locale.ROOT).matches("F\\d+|L\\d+|B\\d+|\\d{3,5}") ? "4G" : tech;
    }

    private String sectorName(Map<String, Object> record) {
        return firstNonBlank(value(record, "sectorname"), value(record, "sectorid"), dimension(record, "sectorname", "sector_name", "sectorid", "sector_id", "sector", "secid", "sec_id", "sec"), deriveSectorFromShortName(record), deriveSectorFromCell(record));
    }

    private String deriveSiteFromCell(String cellName) {
        if (cellName == null || cellName.isBlank()) return "";
        String[] parts = cellName.split("[_\\-]");
        String candidate = parts.length == 0 ? cellName : parts[0];
        return stripSectorSuffix(candidate);
    }

    private String stripSectorSuffix(String value) {
        if (value == null) return "";
        String text = value.trim();
        if (text.length() <= 4) return text;
        return text.replaceFirst("(?i)(?<=\\d)[A-Z]$", "");
    }

    private String deriveLayerFromShortName(Map<String, Object> record) {
        String shortName = firstNonBlank(dimension(record, "shortname", "short_name"), value(record, "cellName"));
        if (shortName.isBlank()) return "";
        for (String part : shortName.split("[_\\-\\s]+")) {
            String candidate = part.trim().toUpperCase(Locale.ROOT);
            if (candidate.matches("F\\d+|L\\d+|B\\d+|\\d{3,5}")) return candidate;
        }
        return "";
    }

    private String deriveSectorFromShortName(Map<String, Object> record) {
        String shortName = firstNonBlank(dimension(record, "shortname", "short_name"), value(record, "cellName"));
        if (shortName.isBlank()) return "";
        String[] parts = shortName.split("[_\\-\\s]+");
        if (parts.length == 0) return "";
        String last = parts[parts.length - 1].trim().toUpperCase(Locale.ROOT);
        return last.matches("[A-Z0-9]{1,3}") ? last : "";
    }

    private String deriveSectorFromCell(Map<String, Object> record) {
        String cell = firstNonBlank(value(record, "cellName"), dimension(record, "cellid", "cell_id", "cellname", "cell_name"));
        if (cell.isBlank()) return "";
        String suffix = cell.substring(cell.length() - 1).toUpperCase(Locale.ROOT);
        return suffix.matches("[A-Z0-9]") ? suffix : "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private String dimension(Map<String, Object> record, String... keys) {
        Map<String, Object> dimensions = objectMap(record.get("dimensions"));
        for (String key : keys) {
            String value = value(dimensions, key);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private Double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            String text = value == null ? "" : String.valueOf(value).replace(",", "").replace("%", "").trim();
            return text.isBlank() ? null : Double.parseDouble(text);
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, Object> readJsonMap(String value) {
        if (value == null || value.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(value, MAP_TYPE);
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to serialize site prediction payload", ex);
        }
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            String text = value == null ? "" : String.valueOf(value).replace(",", "").trim();
            return text.isBlank() ? 0 : Integer.parseInt(text);
        } catch (Exception ex) {
            return 0;
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> items)) return List.of();
        return items.stream()
                .map(this::string)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> items)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> map = objectMap(item);
            if (!map.isEmpty()) {
                result.add(map);
            }
        }
        return result;
    }

    private String value(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private boolean containsAny(String value, String... needles) {
        if (value == null || value.isBlank()) return false;
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private Object safeRound(double value) {
        return value <= 0 ? null : round(value);
    }

    private enum Category {
        PRB,
        DL_THROUGHPUT,
        SINR,
        RSRP_POOR,
        PACKET_LOSS,
        DROP_RATE,
        AVAILABILITY,
        TRAFFIC,
        USERS,
        DISTANCE,
        OTHER
    }

    private static class MetricStats {
        private int count;
        private double sum;
        private double min = Double.POSITIVE_INFINITY;
        private double max = Double.NEGATIVE_INFINITY;

        private void add(double value) {
            count++;
            sum += value;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        private double avg() {
            return count == 0 ? 0.0 : sum / count;
        }
    }

    private static class MetricSignal {
        private String actionCode = "";
        private String action = "";
        private String reason = "";
        private int score;
        private final List<String> evidence = new ArrayList<>();
    }

    private record CachedRecommendations(List<Recommendation> items, long expiresAtMillis) {
    }

    private static class Recommendation {
        private final String site;
        private final String cellName;
        private String band = "";
        private String technology = "";
        private String sector = "";
        private String actionCode = "OBSERVE";
        private String action = "";
        private String severity = "NORMAL";
        private int score;
        private String targetCell = "";
        private String targetBand = "";
        private List<String> reasons = List.of();
        private List<String> evidence = List.of();
        private List<Map<String, Object>> neighborCandidates = List.of();
        private final Map<String, Object> metrics = new LinkedHashMap<>();
        private final Map<String, Object> siteInfo = new LinkedHashMap<>();
        private final Map<String, Object> alarmContext = new LinkedHashMap<>();

        private Recommendation(String site, String cellName) {
            this.site = site;
            this.cellName = cellName;
        }

        private int priority() {
            return switch (severity) {
                case "CRITICAL" -> 4;
                case "MAJOR" -> 3;
                case "MINOR" -> 2;
                default -> 1;
            };
        }

        private int score() {
            return score;
        }

        private String site() {
            return site;
        }

        private String cellName() {
            return cellName;
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("site", site);
            payload.put("cellName", cellName);
            payload.put("band", band);
            payload.put("technology", technology);
            payload.put("sector", sector);
            payload.put("actionCode", actionCode);
            payload.put("action", action);
            payload.put("severity", severity);
            payload.put("score", score);
            payload.put("targetCell", targetCell);
            payload.put("targetBand", targetBand);
            payload.put("reasons", reasons);
            payload.put("evidence", evidence);
            payload.put("neighborCandidates", neighborCandidates);
            payload.put("metrics", metrics);
            payload.put("siteInfo", siteInfo);
            payload.put("alarmContext", alarmContext);
            return payload;
        }
    }

    private class CellStats {
        private final String site;
        private final String cellName;
        private String band = "";
        private String technology = "";
        private String sector = "";
        private String siteName = "";
        private String siteId = "";
        private String region = "";
        private String cluster = "";
        private Double lat;
        private Double lon;
        private Integer azimuth;
        private Integer pci;
        private Integer tac;
        private Integer bandwidth;
        private Integer frequency;
        private int rowCount;
        private int alarmCount;
        private String worstAlarmSeverity = "";
        private String latestAlarm = "";
        private String latestAlarmTime = "";
        private final Map<Category, MetricStats> categoryStats = new LinkedHashMap<>();
        private final Map<String, MetricStats> allMetrics = new LinkedHashMap<>();

        private CellStats(String site, String cellName) {
            this.site = site;
            this.cellName = cellName;
        }

        private String cellName() {
            return cellName;
        }

        private void addMetric(String metricName, double value) {
            allMetrics.computeIfAbsent(metricName, ignored -> new MetricStats()).add(value);
            Category category = category(metricName);
            if (category == Category.OTHER) return;
            categoryStats.computeIfAbsent(category, ignored -> new MetricStats()).add(value);
        }

        private double avg(Category category) {
            MetricStats stats = categoryStats.get(category);
            return stats == null ? 0.0 : stats.avg();
        }

        private MetricSignal dynamicSignal() {
            MetricSignal best = new MetricSignal();
            for (Map.Entry<String, MetricStats> entry : allMetrics.entrySet()) {
                MetricSignal candidate = signalFor(entry.getKey(), entry.getValue());
                if (candidate.score > best.score) {
                    best = candidate;
                }
            }
            return best;
        }

        private MetricSignal signalFor(String metricName, MetricStats stats) {
            MetricSignal signal = new MetricSignal();
            if (stats == null || stats.count == 0) return signal;

            String key = normalize(metricName);
            double avg = stats.avg();
            double max = stats.max == Double.NEGATIVE_INFINITY ? avg : stats.max;
            double min = stats.min == Double.POSITIVE_INFINITY ? avg : stats.min;

            if (containsAny(key, "prb", "util", "resourceblock", "load", "busy", "cbbh")) {
                if (avg >= 70 || max >= 85) {
                    signal.actionCode = "LOAD_BALANCE";
                    signal.action = "Review load balancing or carrier/layer offload for this congested cell.";
                    signal.reason = "Dynamic KPI classification found load/congestion pressure.";
                    signal.score = avg >= 85 || max >= 95 ? 35 : 24;
                    signal.evidence.add(metricName + " avg " + round(avg));
                }
            } else if (containsAny(key, "drop", "dcr", "loss", "fail", "failure", "blocked", "unavailable")) {
                if (avg >= 1 || max >= 2) {
                    signal.actionCode = "QUALITY_CHECK";
                    signal.action = "Investigate drop, failure, loss, and service quality causes before optimization.";
                    signal.reason = "Dynamic KPI classification found quality degradation.";
                    signal.score = avg >= 3 || max >= 5 ? 35 : 24;
                    signal.evidence.add(metricName + " avg " + round(avg));
                }
            } else if (containsAny(key, "success", "accessibility", "retainability", "rna", "availability")) {
                if (avg > 0 && avg < 98) {
                    signal.actionCode = "QUALITY_CHECK";
                    signal.action = "Investigate success-rate or availability degradation for this cell.";
                    signal.reason = "Dynamic KPI classification found below-target quality/availability.";
                    signal.score = avg < 95 ? 35 : 24;
                    signal.evidence.add(metricName + " avg " + round(avg));
                }
            } else if (containsAny(key, "rsrp", "rsrq", "sinr", "distance", "uedistance", "ta", "timingadvance", "coverage")) {
                boolean weakNegativeSignal = (key.contains("rsrp") || key.contains("rsrq")) && avg < -105;
                boolean poorSinr = key.contains("sinr") && avg > 0 && avg < 8;
                boolean farUsers = containsAny(key, "distance", "uedistance", "ta", "timingadvance") && avg >= 1;
                boolean poorShare = (key.contains("poor") || key.contains("bad") || key.contains("below")) && avg >= 20;
                if (weakNegativeSignal || poorSinr || farUsers || poorShare) {
                    signal.actionCode = "COVERAGE_CHECK";
                    signal.action = "Check antenna, coverage, interference, overshooting, and neighbor conditions.";
                    signal.reason = "Dynamic KPI classification found coverage or radio-quality risk.";
                    signal.score = (weakNegativeSignal || poorShare || avg >= 1.5) ? 32 : 22;
                    signal.evidence.add(metricName + " avg " + round(avg));
                }
            } else if (containsAny(key, "traffic", "volume", "user", "connected", "throughput", "thp", "data")) {
                boolean highDemand = avg >= 30 || max >= 50;
                boolean poorThroughput = containsAny(key, "throughput", "thp") && avg > 0 && avg < 5;
                if (highDemand || poorThroughput) {
                    signal.actionCode = poorThroughput ? "QUALITY_CHECK" : "CAPACITY_REVIEW";
                    signal.action = poorThroughput
                            ? "Review throughput bottlenecks, interference, capacity, and scheduling conditions."
                            : "Review capacity expansion, carrier split, or layer balancing for this demand hotspot.";
                    signal.reason = "Dynamic KPI classification found capacity or user-experience pressure.";
                    signal.score = poorThroughput || avg >= 50 ? 30 : 20;
                    signal.evidence.add(metricName + " avg " + round(avg));
                }
            }
            return signal;
        }

        private void applySiteData(SiteData siteData) {
            if (siteData == null) return;
            siteName = firstNonBlank(siteName, siteData.getSiteName());
            siteId = firstNonBlank(siteId, siteData.getSiteId());
            sector = firstNonBlank(sector, siteData.getSecId());
            region = firstNonBlank(region, siteData.getRegion());
            cluster = firstNonBlank(cluster, siteData.getCluster());
            lat = lat == null ? siteData.getLat() : lat;
            lon = lon == null ? siteData.getLon() : lon;
            azimuth = azimuth == null ? siteData.getAzimuth() : azimuth;
            pci = pci == null ? siteData.getPci() : pci;
            tac = tac == null ? siteData.getTac() : tac;
            bandwidth = bandwidth == null ? siteData.getBandwidth() : bandwidth;
            frequency = frequency == null ? firstNonNull(siteData.getFrequency(), siteData.getDownlinkCenterFrequency()) : frequency;
        }

        private void applyAlarmContext(AlarmMatch alarmMatch) {
            if (alarmMatch == null) return;
            alarmCount = alarmMatch.count;
            worstAlarmSeverity = alarmMatch.worstSeverity;
            latestAlarm = alarmMatch.latestAlarm;
            latestAlarmTime = alarmMatch.latestAlarmTime;
        }

        private boolean hasBlockingAlarm() {
            String alarm = normalize(latestAlarm);
            String severity = normalize(worstAlarmSeverity);
            return alarmCount > 0 && (
                    severity.contains("critical")
                            || severity.contains("major")
                            || alarm.contains("fault")
                            || alarm.contains("down")
                            || alarm.contains("fail")
                            || alarm.contains("unavailable")
                            || alarm.contains("transmission")
                            || alarm.contains("power")
            );
        }
    }

    @SafeVarargs
    private <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private class MasterContext {
        private final Map<String, SiteData> byCell = new LinkedHashMap<>();
        private final Map<String, SiteData> bySite = new LinkedHashMap<>();
        private final Map<String, SiteData> byCellBase = new LinkedHashMap<>();
        private final Map<String, SiteData> bySiteBase = new LinkedHashMap<>();
        private final List<SiteData> all = new ArrayList<>();

        private void add(SiteData siteData) {
            if (siteData == null) return;
            all.add(siteData);
            put(byCell, siteData, siteData.getCellName(), siteData.getCellId(), siteData.getEgci(), siteData.getSiCi());
            put(bySite, siteData, siteData.getSiteId(), siteData.getSiteName(), siteData.getSuNetworkId());
            putBase(byCellBase, siteData, siteData.getCellName(), siteData.getCellId());
            putBase(bySiteBase, siteData, siteData.getSiteId(), siteData.getSiteName(), siteData.getCellName());
        }

        private SiteData find(String site, String cell) {
            SiteData match = firstNonNull(
                    byCell.get(normalize(cell)),
                    bySite.get(normalize(site)),
                    byCellBase.get(baseKey(cell)),
                    bySiteBase.get(baseKey(site)),
                    bySiteBase.get(baseKey(cell))
            );
            if (match != null) return match;

            String normalizedSite = normalize(site);
            String normalizedCell = normalize(cell);
            String baseSite = baseKey(site);
            String baseCell = baseKey(cell);
            for (SiteData candidate : all) {
                if (matches(candidate, normalizedSite, normalizedCell, baseSite, baseCell)) {
                    return candidate;
                }
            }
            return null;
        }

        private void put(Map<String, SiteData> target, SiteData data, String... keys) {
            for (String key : keys) {
                String normalized = normalize(key);
                if (!normalized.isBlank()) {
                    target.putIfAbsent(normalized, data);
                }
            }
        }

        private void putBase(Map<String, SiteData> target, SiteData data, String... keys) {
            for (String key : keys) {
                String normalized = baseKey(key);
                if (!normalized.isBlank()) {
                    target.putIfAbsent(normalized, data);
                }
            }
        }

        private String baseKey(String value) {
            return normalize(stripSectorSuffix(value));
        }

        private boolean matches(SiteData candidate, String site, String cell, String baseSite, String baseCell) {
            List<String> candidateKeys = List.of(
                    normalize(candidate.getCellName()),
                    normalize(candidate.getCellId()),
                    normalize(candidate.getSiteId()),
                    normalize(candidate.getSiteName()),
                    normalize(candidate.getEgci()),
                    normalize(candidate.getSiCi()),
                    normalize(candidate.getSuNetworkId())
            );
            for (String candidateKey : candidateKeys) {
                if (candidateKey.isBlank()) continue;
                if (relaxedEquals(candidateKey, cell)
                        || relaxedEquals(candidateKey, site)
                        || relaxedEquals(stripSectorSuffix(candidateKey), baseCell)
                        || relaxedEquals(stripSectorSuffix(candidateKey), baseSite)) {
                    return true;
                }
            }
            return false;
        }

        private boolean relaxedEquals(String left, String right) {
            if (left == null || right == null || left.isBlank() || right.isBlank()) return false;
            if (left.equals(right)) return true;
            return left.length() >= 5 && right.length() >= 5 && (left.contains(right) || right.contains(left));
        }
    }

    private class AlarmContext {
        private final Map<String, AlarmMatch> byCell = new LinkedHashMap<>();
        private final Map<String, AlarmMatch> bySite = new LinkedHashMap<>();

        private void add(AlarmData alarmData) {
            if (alarmData == null) return;
            String cell = firstNonBlank(
                    alarmData.getUserLabel(),
                    alarmData.getAlarmPortId(),
                    alarmData.getVsAlarmPortId()
            );
            String site = firstNonBlank(
                    alarmData.getMeContextId(),
                    alarmData.getManagedElementId(),
                    alarmData.getSubNetworkId(),
                    alarmData.getSubNetwork2Id()
            );
            AlarmMatch match = AlarmMatch.from(alarmData);
            merge(byCell, cell, match);
            merge(bySite, site, match);
        }

        private AlarmMatch find(String site, String cell) {
            AlarmMatch cellMatch = byCell.get(normalize(cell));
            AlarmMatch siteMatch = bySite.get(normalize(site));
            if (cellMatch == null) return siteMatch;
            if (siteMatch == null) return cellMatch;
            return cellMatch.merge(siteMatch);
        }

        private void merge(Map<String, AlarmMatch> target, String key, AlarmMatch incoming) {
            String normalized = normalize(key);
            if (normalized.isBlank()) return;
            target.merge(normalized, incoming, AlarmMatch::merge);
        }
    }

    private static class AlarmMatch {
        private int count;
        private String worstSeverity = "";
        private String latestAlarm = "";
        private String latestAlarmTime = "";

        private static AlarmMatch from(AlarmData alarmData) {
            AlarmMatch match = new AlarmMatch();
            match.count = 1;
            match.worstSeverity = alarmData.getPerceivedSeverity() == null ? "" : alarmData.getPerceivedSeverity();
            match.latestAlarm = alarmData.getAlarmSlogan() == null ? "" : alarmData.getAlarmSlogan();
            match.latestAlarmTime = alarmData.getDatetime() == null ? "" : String.valueOf(alarmData.getDatetime());
            return match;
        }

        private AlarmMatch merge(AlarmMatch other) {
            AlarmMatch merged = new AlarmMatch();
            merged.count = this.count + other.count;
            merged.worstSeverity = severityRank(other.worstSeverity) > severityRank(this.worstSeverity)
                    ? other.worstSeverity
                    : this.worstSeverity;
            merged.latestAlarm = other.latestAlarm == null || other.latestAlarm.isBlank() ? this.latestAlarm : other.latestAlarm;
            merged.latestAlarmTime = other.latestAlarmTime == null || other.latestAlarmTime.isBlank() ? this.latestAlarmTime : other.latestAlarmTime;
            return merged;
        }

        private static int severityRank(String severity) {
            String normalized = severity == null ? "" : severity.toLowerCase(Locale.ROOT);
            if (normalized.contains("critical")) return 4;
            if (normalized.contains("major")) return 3;
            if (normalized.contains("minor")) return 2;
            if (normalized.contains("warning")) return 1;
            return 0;
        }
    }
}

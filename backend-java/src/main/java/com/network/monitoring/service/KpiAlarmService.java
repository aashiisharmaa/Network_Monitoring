package com.network.monitoring.service;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class KpiAlarmService {

    private static final int BATCH_SIZE = 1000;
    private static final List<String> SEVERITIES = List.of("CRITICAL", "MAJOR", "MINOR", "WARNING");
    private static final List<String> STATUSES = List.of("OPEN", "ACKNOWLEDGED", "RESOLVED", "IGNORED");

    private final JdbcTemplate jdbcTemplate;
    private final KpiDynamicDataService kpiDynamicDataService;
    private final KpiThresholdRuleService thresholdRuleService;

    public KpiAlarmService(JdbcTemplate jdbcTemplate,
                           KpiDynamicDataService kpiDynamicDataService,
                           KpiThresholdRuleService thresholdRuleService) {
        this.jdbcTemplate = jdbcTemplate;
        this.kpiDynamicDataService = kpiDynamicDataService;
        this.thresholdRuleService = thresholdRuleService;
    }

    @PostConstruct
    public void ensureTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `kpi_alarm_rules` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT,
                  `metricName` VARCHAR(191) NOT NULL,
                  `technology` VARCHAR(64) NULL,
                  `severity` VARCHAR(32) NULL,
                  `messageTemplate` VARCHAR(512) NULL,
                  `recommendation` TEXT NULL,
                  `enabled` BIT(1) NOT NULL DEFAULT b'1',
                  `createdAt` DATETIME NULL,
                  `updatedAt` DATETIME NULL,
                  PRIMARY KEY (`id`),
                  INDEX `idx_kpi_alarm_rules_metric` (`metricName`, `technology`, `severity`, `enabled`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `kpi_alarms` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT,
                  `fileId` BIGINT NOT NULL,
                  `cellName` VARCHAR(255) NULL,
                  `site` VARCHAR(255) NULL,
                  `technology` VARCHAR(64) NULL,
                  `band` VARCHAR(255) NULL,
                  `sectorId` VARCHAR(255) NULL,
                  `metricName` VARCHAR(255) NOT NULL,
                  `metricValue` DOUBLE NULL,
                  `thresholdValue` DOUBLE NULL,
                  `severity` VARCHAR(32) NOT NULL,
                  `status` VARCHAR(32) NOT NULL DEFAULT 'OPEN',
                  `message` VARCHAR(512) NULL,
                  `recommendation` TEXT NULL,
                  `createdAt` DATETIME NULL,
                  `updatedAt` DATETIME NULL,
                  `acknowledgedBy` VARCHAR(255) NULL,
                  `acknowledgedAt` DATETIME NULL,
                  `resolvedBy` VARCHAR(255) NULL,
                  `resolvedAt` DATETIME NULL,
                  `remarks` TEXT NULL,
                  PRIMARY KEY (`id`),
                  INDEX `idx_kpi_alarms_file` (`fileId`, `severity`, `status`),
                  INDEX `idx_kpi_alarms_cell` (`fileId`, `cellName`),
                  INDEX `idx_kpi_alarms_site` (`fileId`, `site`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `kpi_alarm_history` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT,
                  `alarmId` BIGINT NOT NULL,
                  `action` VARCHAR(64) NOT NULL,
                  `actionBy` VARCHAR(255) NULL,
                  `remarks` TEXT NULL,
                  `createdAt` DATETIME NULL,
                  PRIMARY KEY (`id`),
                  INDEX `idx_kpi_alarm_history_alarm` (`alarmId`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRules() {
        return jdbcTemplate.query("""
                SELECT `id`, `metricName`, `technology`, `severity`, `messageTemplate`, `recommendation`, `enabled`, `createdAt`, `updatedAt`
                FROM `kpi_alarm_rules`
                ORDER BY `updatedAt` DESC, `id` DESC
                """, (rs, rowNum) -> {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("id", rs.getLong("id"));
            rule.put("metricName", rs.getString("metricName"));
            rule.put("technology", rs.getString("technology"));
            rule.put("severity", rs.getString("severity"));
            rule.put("messageTemplate", rs.getString("messageTemplate"));
            rule.put("recommendation", rs.getString("recommendation"));
            rule.put("enabled", rs.getBoolean("enabled"));
            rule.put("createdAt", rs.getObject("createdAt"));
            rule.put("updatedAt", rs.getObject("updatedAt"));
            return rule;
        });
    }

    @Transactional
    public Map<String, Object> createRule(Map<String, Object> request) {
        LocalDateTime now = LocalDateTime.now();
        String metricName = requiredString(request, "metricName");
        String severity = optionalSeverity(stringValue(request.get("severity")));
        jdbcTemplate.update("""
                INSERT INTO `kpi_alarm_rules`
                (`metricName`, `technology`, `severity`, `messageTemplate`, `recommendation`, `enabled`, `createdAt`, `updatedAt`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                metricName,
                blankToNull(stringValue(request.get("technology"))),
                severity,
                blankToNull(stringValue(request.get("messageTemplate"))),
                blankToNull(stringValue(request.get("recommendation"))),
                !Boolean.FALSE.equals(request.get("enabled")),
                Timestamp.valueOf(now),
                Timestamp.valueOf(now));
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return getRule(id);
    }

    @Transactional
    public Map<String, Object> updateRule(Long id, Map<String, Object> request) {
        if (id == null) {
            throw new IllegalArgumentException("id is required.");
        }
        getRule(id);
        jdbcTemplate.update("""
                UPDATE `kpi_alarm_rules`
                SET `metricName` = ?, `technology` = ?, `severity` = ?, `messageTemplate` = ?, `recommendation` = ?, `enabled` = ?, `updatedAt` = ?
                WHERE `id` = ?
                """,
                requiredString(request, "metricName"),
                blankToNull(stringValue(request.get("technology"))),
                optionalSeverity(stringValue(request.get("severity"))),
                blankToNull(stringValue(request.get("messageTemplate"))),
                blankToNull(stringValue(request.get("recommendation"))),
                !Boolean.FALSE.equals(request.get("enabled")),
                Timestamp.valueOf(LocalDateTime.now()),
                id);
        return getRule(id);
    }

    @Transactional
    public void deleteRule(Long id) {
        int deleted = jdbcTemplate.update("DELETE FROM `kpi_alarm_rules` WHERE `id` = ?", id);
        if (deleted == 0) {
            throw new IllegalArgumentException("Alarm rule not found for id " + id + ".");
        }
    }

    @Transactional
    public Map<String, Object> generateAlarms(Long fileId) {
        if (fileId == null) {
            throw new IllegalArgumentException("fileId is required.");
        }
        List<Map<String, Object>> records = kpiDynamicDataService.findRecordsByFileId(fileId);
        List<Map<String, Object>> alarmRules = listRules().stream()
                .filter(rule -> Boolean.TRUE.equals(rule.get("enabled")))
                .toList();
        List<GeneratedAlarm> alarms = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (Map<String, Object> record : records) {
            Map<String, Object> metrics = objectMap(record.get("metrics"));
            String technology = firstNonBlank(record.get("tech"), dimension(record, "tech", "technology", "rat"));
            for (Map.Entry<String, Object> metric : metrics.entrySet()) {
                Double value = parseNumber(metric.getValue());
                if (value == null) {
                    continue;
                }
                Map<String, Object> thresholdResult = thresholdRuleService.evaluate(metric.getKey(), value, technology);
                String severity = stringValue(thresholdResult.get("severity")).toUpperCase(Locale.ROOT);
                if (!SEVERITIES.contains(severity)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> thresholdRule = thresholdResult.get("rule") instanceof Map<?, ?> map
                        ? (Map<String, Object>) map
                        : Map.of();
                Map<String, Object> alarmRule = findAlarmRule(alarmRules, metric.getKey(), technology, severity);
                alarms.add(buildAlarm(fileId, record, metric.getKey(), value, severity, thresholdRule, alarmRule, now));
            }
        }

        jdbcTemplate.update("DELETE FROM `kpi_alarms` WHERE `fileId` = ?", fileId);
        insertAlarms(alarms);

        Map<String, Object> summary = summary(fileId);
        summary.put("generated", alarms.size());
        summary.put("fileId", fileId);
        return summary;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(Long fileId) {
        if (fileId == null) {
            throw new IllegalArgumentException("fileId is required.");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileId", fileId);
        response.put("total", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `kpi_alarms` WHERE `fileId` = ?", Long.class, fileId));
        response.put("severityCounts", groupedCounts("severity", fileId));
        response.put("statusCounts", groupedCounts("status", fileId));
        response.put("generatedAt", Instant.now().toString());
        return response;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listAlarms(Long fileId, String severity, String status, Integer page, Integer limit) {
        if (fileId == null) {
            throw new IllegalArgumentException("fileId is required.");
        }
        int safePage = page == null ? 1 : Math.max(1, page);
        int safeLimit = limit == null ? 50 : Math.max(1, Math.min(limit, 500));
        int offset = (safePage - 1) * safeLimit;
        StringBuilder where = new StringBuilder("WHERE `fileId` = ?");
        List<Object> args = new ArrayList<>();
        args.add(fileId);
        if (!stringValue(severity).isBlank()) {
            where.append(" AND `severity` = ?");
            args.add(severity.toUpperCase(Locale.ROOT));
        }
        if (!stringValue(status).isBlank()) {
            where.append(" AND `status` = ?");
            args.add(status.toUpperCase(Locale.ROOT));
        }
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `kpi_alarms` " + where, Long.class, args.toArray());
        args.add(safeLimit);
        args.add(offset);
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT `id`, `fileId`, `cellName`, `site`, `technology`, `band`, `sectorId`, `metricName`, `metricValue`,
                       `thresholdValue`, `severity`, `status`, `message`, `recommendation`, `createdAt`, `updatedAt`,
                       `acknowledgedBy`, `acknowledgedAt`, `resolvedBy`, `resolvedAt`, `remarks`
                FROM `kpi_alarms`
                """ + where + " ORDER BY FIELD(`severity`, 'CRITICAL', 'MAJOR', 'MINOR', 'WARNING'), `id` ASC LIMIT ? OFFSET ?",
                (rs, rowNum) -> alarmPayload(
                        rs.getLong("id"),
                        rs.getLong("fileId"),
                        rs.getString("cellName"),
                        rs.getString("site"),
                        rs.getString("technology"),
                        rs.getString("band"),
                        rs.getString("sectorId"),
                        rs.getString("metricName"),
                        rs.getObject("metricValue"),
                        rs.getObject("thresholdValue"),
                        rs.getString("severity"),
                        rs.getString("status"),
                        rs.getString("message"),
                        rs.getString("recommendation"),
                        rs.getObject("createdAt"),
                        rs.getObject("updatedAt"),
                        rs.getString("acknowledgedBy"),
                        rs.getObject("acknowledgedAt"),
                        rs.getString("resolvedBy"),
                        rs.getObject("resolvedAt"),
                        rs.getString("remarks")
                ), args.toArray());
        return Map.of("items", rows, "total", total == null ? 0 : total, "page", safePage, "limit", safeLimit);
    }

    @Transactional
    public Map<String, Object> acknowledge(Long alarmId, Map<String, Object> request) {
        return updateStatus(alarmId, "ACKNOWLEDGED", stringValue(request.get("user")), stringValue(request.get("remarks")));
    }

    @Transactional
    public Map<String, Object> resolve(Long alarmId, Map<String, Object> request) {
        return updateStatus(alarmId, "RESOLVED", stringValue(request.get("user")), stringValue(request.get("remarks")));
    }

    private Map<String, Object> updateStatus(Long alarmId, String status, String user, String remarks) {
        if (alarmId == null) {
            throw new IllegalArgumentException("alarmId is required.");
        }
        LocalDateTime now = LocalDateTime.now();
        String sql = "ACKNOWLEDGED".equals(status)
                ? "UPDATE `kpi_alarms` SET `status` = ?, `acknowledgedBy` = ?, `acknowledgedAt` = ?, `remarks` = ?, `updatedAt` = ? WHERE `id` = ?"
                : "UPDATE `kpi_alarms` SET `status` = ?, `resolvedBy` = ?, `resolvedAt` = ?, `remarks` = ?, `updatedAt` = ? WHERE `id` = ?";
        int updated = jdbcTemplate.update(sql, status, blankToNull(user), Timestamp.valueOf(now), blankToNull(remarks), Timestamp.valueOf(now), alarmId);
        if (updated == 0) {
            throw new IllegalArgumentException("Alarm not found for id " + alarmId + ".");
        }
        jdbcTemplate.update("""
                INSERT INTO `kpi_alarm_history` (`alarmId`, `action`, `actionBy`, `remarks`, `createdAt`)
                VALUES (?, ?, ?, ?, ?)
                """, alarmId, status, blankToNull(user), blankToNull(remarks), Timestamp.valueOf(now));
        return getAlarm(alarmId);
    }

    private Map<String, Object> getAlarm(Long alarmId) {
        List<Map<String, Object>> alarms = jdbcTemplate.query("""
                SELECT `id`, `fileId`, `cellName`, `site`, `technology`, `band`, `sectorId`, `metricName`, `metricValue`,
                       `thresholdValue`, `severity`, `status`, `message`, `recommendation`, `createdAt`, `updatedAt`,
                       `acknowledgedBy`, `acknowledgedAt`, `resolvedBy`, `resolvedAt`, `remarks`
                FROM `kpi_alarms`
                WHERE `id` = ?
                """, (rs, rowNum) -> alarmPayload(
                rs.getLong("id"), rs.getLong("fileId"), rs.getString("cellName"), rs.getString("site"),
                rs.getString("technology"), rs.getString("band"), rs.getString("sectorId"), rs.getString("metricName"),
                rs.getObject("metricValue"), rs.getObject("thresholdValue"), rs.getString("severity"), rs.getString("status"),
                rs.getString("message"), rs.getString("recommendation"), rs.getObject("createdAt"), rs.getObject("updatedAt"),
                rs.getString("acknowledgedBy"), rs.getObject("acknowledgedAt"), rs.getString("resolvedBy"), rs.getObject("resolvedAt"),
                rs.getString("remarks")), alarmId);
        if (alarms.isEmpty()) {
            throw new IllegalArgumentException("Alarm not found for id " + alarmId + ".");
        }
        return alarms.get(0);
    }

    private void insertAlarms(List<GeneratedAlarm> alarms) {
        if (alarms.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO `kpi_alarms`
                (`fileId`, `cellName`, `site`, `technology`, `band`, `sectorId`, `metricName`, `metricValue`, `thresholdValue`,
                 `severity`, `status`, `message`, `recommendation`, `createdAt`, `updatedAt`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        for (int start = 0; start < alarms.size(); start += BATCH_SIZE) {
            List<GeneratedAlarm> chunk = alarms.subList(start, Math.min(start + BATCH_SIZE, alarms.size()));
            jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    GeneratedAlarm alarm = chunk.get(i);
                    ps.setLong(1, alarm.fileId());
                    ps.setString(2, alarm.cellName());
                    ps.setString(3, alarm.site());
                    ps.setString(4, alarm.technology());
                    ps.setString(5, alarm.band());
                    ps.setString(6, alarm.sectorId());
                    ps.setString(7, alarm.metricName());
                    ps.setObject(8, alarm.metricValue());
                    ps.setObject(9, alarm.thresholdValue());
                    ps.setString(10, alarm.severity());
                    ps.setString(11, "OPEN");
                    ps.setString(12, alarm.message());
                    ps.setString(13, alarm.recommendation());
                    ps.setTimestamp(14, Timestamp.valueOf(alarm.createdAt()));
                    ps.setTimestamp(15, Timestamp.valueOf(alarm.createdAt()));
                }

                @Override
                public int getBatchSize() {
                    return chunk.size();
                }
            });
        }
    }

    private GeneratedAlarm buildAlarm(Long fileId,
                                      Map<String, Object> record,
                                      String metricName,
                                      Double value,
                                      String severity,
                                      Map<String, Object> thresholdRule,
                                      Map<String, Object> alarmRule,
                                      LocalDateTime now) {
        Double thresholdValue = thresholdValue(thresholdRule, severity);
        String technology = firstNonBlank(record.get("tech"), dimension(record, "tech", "technology", "rat"));
        String cell = firstNonBlank(record.get("cellName"), dimension(record, "cellid", "cell", "shortname", "short_name"));
        String site = firstNonBlank(record.get("site"), dimension(record, "siteid", "site_id", "sitename", "site_name"));
        String messageTemplate = firstNonBlank(alarmRule.get("messageTemplate"), severity + " breach on {metricName}");
        String recommendation = firstNonBlank(alarmRule.get("recommendation"), defaultRecommendation(metricName, severity));
        String message = messageTemplate
                .replace("{severity}", severity)
                .replace("{metricName}", metricName)
                .replace("{value}", String.valueOf(value))
                .replace("{threshold}", thresholdValue == null ? "-" : String.valueOf(thresholdValue))
                .replace("{cell}", cell == null ? "-" : cell)
                .replace("{site}", site == null ? "-" : site);
        return new GeneratedAlarm(
                fileId,
                cell,
                site,
                technology,
                firstNonBlank(record.get("band"), dimension(record, "band", "frequencyband", "frequency_band", "layer", "carrier")),
                firstNonBlank(record.get("sectorid"), dimension(record, "sectorid", "sector_id", "secid", "sec_id", "sec")),
                metricName,
                value,
                thresholdValue,
                severity,
                message,
                recommendation,
                now
        );
    }

    private Map<String, Object> findAlarmRule(List<Map<String, Object>> rules, String metricName, String technology, String severity) {
        String metricKey = normalize(metricName);
        String techKey = normalize(technology);
        return rules.stream()
                .filter(rule -> {
                    String ruleSeverity = stringValue(rule.get("severity")).toUpperCase(Locale.ROOT);
                    return ruleSeverity.isBlank() || ruleSeverity.equals(severity);
                })
                .filter(rule -> {
                    String ruleTech = normalize(stringValue(rule.get("technology")));
                    return ruleTech.isBlank() || ruleTech.equals(techKey);
                })
                .filter(rule -> {
                    String ruleMetric = normalize(stringValue(rule.get("metricName")));
                    return !ruleMetric.isBlank() && (metricKey.contains(ruleMetric) || ruleMetric.contains(metricKey));
                })
                .findFirst()
                .orElse(Map.of());
    }

    private Map<String, Integer> groupedCounts(String column, Long fileId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        String safeColumn = "status".equals(column) ? "status" : "severity";
        jdbcTemplate.query("SELECT `" + safeColumn + "` AS label, COUNT(*) AS total FROM `kpi_alarms` WHERE `fileId` = ? GROUP BY `" + safeColumn + "`",
                rs -> {
                    counts.put(rs.getString("label"), rs.getInt("total"));
                }, fileId);
        return counts;
    }

    private Map<String, Object> getRule(Long id) {
        List<Map<String, Object>> rules = jdbcTemplate.query("""
                SELECT `id`, `metricName`, `technology`, `severity`, `messageTemplate`, `recommendation`, `enabled`, `createdAt`, `updatedAt`
                FROM `kpi_alarm_rules`
                WHERE `id` = ?
                """, (rs, rowNum) -> {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("id", rs.getLong("id"));
            rule.put("metricName", rs.getString("metricName"));
            rule.put("technology", rs.getString("technology"));
            rule.put("severity", rs.getString("severity"));
            rule.put("messageTemplate", rs.getString("messageTemplate"));
            rule.put("recommendation", rs.getString("recommendation"));
            rule.put("enabled", rs.getBoolean("enabled"));
            rule.put("createdAt", rs.getObject("createdAt"));
            rule.put("updatedAt", rs.getObject("updatedAt"));
            return rule;
        }, id);
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("Alarm rule not found for id " + id + ".");
        }
        return rules.get(0);
    }

    private Map<String, Object> alarmPayload(Long id, Long fileId, String cellName, String site, String technology, String band, String sectorId,
                                             String metricName, Object metricValue, Object thresholdValue, String severity, String status,
                                             String message, String recommendation, Object createdAt, Object updatedAt, String acknowledgedBy,
                                             Object acknowledgedAt, String resolvedBy, Object resolvedAt, String remarks) {
        Map<String, Object> alarm = new LinkedHashMap<>();
        alarm.put("id", id);
        alarm.put("fileId", fileId);
        alarm.put("cellName", cellName);
        alarm.put("site", site);
        alarm.put("technology", technology);
        alarm.put("band", band);
        alarm.put("sectorId", sectorId);
        alarm.put("metricName", metricName);
        alarm.put("metricValue", metricValue);
        alarm.put("thresholdValue", thresholdValue);
        alarm.put("severity", severity);
        alarm.put("status", status);
        alarm.put("message", message);
        alarm.put("recommendation", recommendation);
        alarm.put("createdAt", createdAt);
        alarm.put("updatedAt", updatedAt);
        alarm.put("acknowledgedBy", acknowledgedBy);
        alarm.put("acknowledgedAt", acknowledgedAt);
        alarm.put("resolvedBy", resolvedBy);
        alarm.put("resolvedAt", resolvedAt);
        alarm.put("remarks", remarks);
        return alarm;
    }

    private Double thresholdValue(Map<String, Object> thresholdRule, String severity) {
        if (thresholdRule == null || thresholdRule.isEmpty()) {
            return null;
        }
        String key = switch (severity) {
            case "CRITICAL" -> "criticalValue";
            case "MAJOR" -> "majorValue";
            case "MINOR" -> "minorValue";
            case "WARNING" -> "warningValue";
            default -> "";
        };
        return parseNumber(thresholdRule.get(key));
    }

    private String defaultRecommendation(String metricName, String severity) {
        return severity + " KPI breach detected for " + metricName + ". Check related alarms, recent changes, radio quality, capacity, and site health.";
    }

    private String optionalSeverity(String severity) {
        String parsed = stringValue(severity).toUpperCase(Locale.ROOT);
        if (parsed.isBlank()) {
            return null;
        }
        if (!SEVERITIES.contains(parsed)) {
            throw new IllegalArgumentException("severity must be CRITICAL, MAJOR, MINOR, or WARNING.");
        }
        return parsed;
    }

    private String requiredString(Map<String, Object> request, String key) {
        String value = request == null ? "" : stringValue(request.get(key));
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " is required.");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private String dimension(Map<String, Object> record, String... keys) {
        Map<String, Object> dimensions = objectMap(record.get("dimensions"));
        for (String key : keys) {
            String value = stringValue(dimensions.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Double parseNumber(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = stringValue(value).replace(",", "").replace("%", "").trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            String parsed = stringValue(value);
            if (!parsed.isBlank()) {
                return parsed;
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        String parsed = stringValue(value);
        return parsed.isBlank() ? null : parsed;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String normalize(String value) {
        return stringValue(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private record GeneratedAlarm(Long fileId,
                                  String cellName,
                                  String site,
                                  String technology,
                                  String band,
                                  String sectorId,
                                  String metricName,
                                  Double metricValue,
                                  Double thresholdValue,
                                  String severity,
                                  String message,
                                  String recommendation,
                                  LocalDateTime createdAt) {
    }
}

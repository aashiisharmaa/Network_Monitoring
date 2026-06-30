package com.network.monitoring.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class KpiDynamicDataService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    public KpiDynamicDataService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        ensureTable();
    }

    @Transactional
    public void replaceRecords(long fileId, List<Map<String, Object>> records) {
        ensureTable();
        deleteByFileId(fileId);
        insertRecords(fileId, records);
    }

    @Transactional
    public void deleteByFileId(long fileId) {
        ensureTable();
        jdbcTemplate.update("DELETE FROM `kpidynamicdata` WHERE `fileId` = ?", fileId);
    }

    public List<Map<String, Object>> findRecordsByFileId(long fileId) {
        return findRecords(fileId);
    }

    private List<Map<String, Object>> findRecords(Long fileId) {
        String sql = "SELECT `dateValue`, `cellName`, `site`, `band`, `tech`, `sectorId`, `sectorName`, `groupsName`, `dimensionsJson`, `metricsJson` " +
                "FROM `kpidynamicdata` ";
        List<Object> args = new ArrayList<>();
        if (fileId != null) {
            sql += "WHERE `fileId` = ? ";
            args.add(fileId);
        }
        sql += "ORDER BY `rowIndex` ASC, `id` ASC";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("date", rs.getString("dateValue"));
            record.put("cellName", rs.getString("cellName"));
            record.put("site", rs.getString("site"));
            record.put("band", rs.getString("band"));
            record.put("tech", rs.getString("tech"));
            record.put("sectorid", rs.getString("sectorId"));
            record.put("sectorname", rs.getString("sectorName"));
            record.put("groups", rs.getString("groupsName"));
            record.put("dimensions", readJsonMap(rs.getString("dimensionsJson")));
            record.put("metrics", readJsonMap(rs.getString("metricsJson")));
            return record;
        }, args.toArray());
    }

    public List<String> listDistinctDimensionValues(String dimension, Long fileId) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Map<String, Object> record : findRecords(fileId)) {
            addNonBlank(values, dimensionValue(record, dimension));
        }
        return values.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public List<Map<String, Object>> listDistinctSectorSummaries(Long fileId) {
        Map<String, Map<String, Object>> sectors = new LinkedHashMap<>();
        for (Map<String, Object> record : findRecords(fileId)) {
            String id = firstNonBlank(
                    record.get("sectorid"),
                    dimension(record, "sectorid", "sector_id", "secid", "sec_id", "sec"),
                    deriveSectorFromCell(record)
            );
            if (id == null || id.isBlank()) {
                continue;
            }
            String name = firstNonBlank(record.get("sectorname"), dimension(record, "sectorname", "sector_name", "sector"));
            sectors.computeIfAbsent(id, ignored -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", id);
                item.put("name", name);
                return item;
            });
        }
        return sectors.values().stream()
                .sorted((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(String.valueOf(a.get("id")), String.valueOf(b.get("id"))))
                .toList();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getRecordsFromCachePayload(Map<String, Object> cachePayload) {
        if (cachePayload == null || !(cachePayload.get("records") instanceof List<?> records)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object record : records) {
            if (record instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null) {
                        row.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                result.add(row);
            }
        }
        return result;
    }

    private void ensureTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `kpidynamicdata` (
                    `id` BIGINT NOT NULL AUTO_INCREMENT,
                    `fileId` BIGINT NOT NULL,
                    `rowIndex` INT NOT NULL,
                    `rowKey` VARCHAR(191) NULL,
                    `dateValue` VARCHAR(64) NULL,
                    `cellName` VARCHAR(255) NULL,
                    `site` VARCHAR(255) NULL,
                    `band` VARCHAR(255) NULL,
                    `tech` VARCHAR(255) NULL,
                    `sectorId` VARCHAR(255) NULL,
                    `sectorName` VARCHAR(255) NULL,
                    `groupsName` VARCHAR(255) NULL,
                    `dimensionsJson` LONGTEXT NULL,
                    `metricsJson` LONGTEXT NULL,
                    `createdAt` DATETIME NULL,
                    PRIMARY KEY (`id`),
                    INDEX `idx_kpidynamicdata_fileId` (`fileId`),
                    INDEX `idx_kpidynamicdata_fileId_rowKey` (`fileId`, `rowKey`)
                )
                """);
    }

    private void insertRecords(long fileId, List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }

        String sql = "INSERT INTO `kpidynamicdata` " +
                "(`fileId`, `rowIndex`, `rowKey`, `dateValue`, `cellName`, `site`, `band`, `tech`, `sectorId`, `sectorName`, `groupsName`, `dimensionsJson`, `metricsJson`, `createdAt`) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        LocalDateTime createdAt = LocalDateTime.now(ZoneId.of("UTC"));
        List<Object[]> args = new ArrayList<>();
        Set<String> seenKeys = new LinkedHashSet<>();

        for (int index = 0; index < records.size(); index++) {
            Map<String, Object> record = records.get(index);
            String rowKey = dynamicRecordKey(record);
            if (!seenKeys.add(rowKey)) {
                continue;
            }
            args.add(new Object[]{
                    fileId,
                    index,
                    rowKey,
                    safeString(record.get("date")),
                    safeString(record.get("cellName")),
                    safeString(record.get("site")),
                    safeString(record.get("band")),
                    safeString(record.get("tech")),
                    safeString(record.get("sectorid")),
                    safeString(record.get("sectorname")),
                    safeString(record.get("groups")),
                    writeJson(getObjectMap(record.get("dimensions"))),
                    writeJson(getObjectMap(record.get("metrics"))),
                    Timestamp.valueOf(createdAt)
            });
        }

        jdbcTemplate.batchUpdate(sql, args);
    }

    private Map<String, Object> readJsonMap(String value) {
        if (value == null || value.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(value, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to serialize dynamic KPI row", ex);
        }
    }

    private Map<String, Object> getObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private String dynamicRecordKey(Map<String, Object> record) {
        String raw = String.join("|",
                keyPart(record.get("date")),
                keyPart(record.get("cellName")),
                keyPart(record.get("site")),
                keyPart(firstNonBlank(record.get("band"), dimension(record, "band", "frequencyband", "layer", "carrier"), record.get("tech"))),
                keyPart(record.get("tech")),
                keyPart(firstNonBlank(record.get("sectorid"), dimension(record, "sectorid", "sector_id", "secid", "sec_id", "sec"), deriveSectorFromCell(record))),
                keyPart(record.get("sectorname")),
                keyPart(record.get("groups")),
                getObjectMap(record.get("metrics")).toString()
        );
        return sha256Hex(raw);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (Exception ex) {
            return String.valueOf(value == null ? "" : value.hashCode());
        }
    }

    private String safeString(Object value) {
        if (value == null) {
            return null;
        }
        String parsed = String.valueOf(value).trim();
        return parsed.isEmpty() ? null : parsed;
    }

    private String keyPart(Object value) {
        String parsed = safeString(value);
        return parsed == null ? "" : parsed.toLowerCase();
    }

    private String dimensionValue(Map<String, Object> record, String dimension) {
        return switch (dimension) {
            case "cellName" -> firstNonBlank(record.get("cellName"), dimension(record, "cellname", "cellid", "cell", "shortname", "short_name"));
            case "site" -> firstNonBlank(record.get("site"), dimension(record, "site", "siteid", "site_id", "sitename", "site_name"));
            case "band" -> firstNonBlank(record.get("band"), dimension(record, "band", "frequencyband", "frequency_band", "layer", "carrier"), record.get("tech"));
            case "tech" -> firstNonBlank(record.get("tech"), dimension(record, "tech", "technology"));
            case "groups" -> firstNonBlank(record.get("groups"), dimension(record, "groups", "group", "region", "cluster"));
            default -> throw new IllegalArgumentException("Unsupported dynamic KPI dimension: " + dimension);
        };
    }

    private String dimension(Map<String, Object> record, String... keys) {
        Map<String, Object> dimensions = getObjectMap(record.get("dimensions"));
        for (String key : keys) {
            String value = safeString(dimensions.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String deriveSectorFromCell(Map<String, Object> record) {
        String shortName = firstNonBlank(dimension(record, "shortname", "short_name"), record.get("cellName"));
        if (shortName != null) {
            String[] parts = shortName.split("_");
            if (parts.length > 0) {
                String last = parts[parts.length - 1].trim();
                if (last.matches("[A-Za-z0-9]{1,3}")) {
                    return last.toUpperCase();
                }
            }
        }

        String cell = firstNonBlank(record.get("cellName"), dimension(record, "cellid", "cellname"));
        if (cell == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("([A-Za-z])$").matcher(cell.trim());
        return matcher.find() ? matcher.group(1).toUpperCase() : null;
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            String parsed = safeString(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private void addNonBlank(Set<String> values, String value) {
        String parsed = safeString(value);
        if (parsed != null) {
            values.add(parsed);
        }
    }
}

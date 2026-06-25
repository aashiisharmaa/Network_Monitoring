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
        ensureTable();
        String sql = "SELECT `dateValue`, `cellName`, `site`, `band`, `tech`, `sectorId`, `sectorName`, `groupsName`, `dimensionsJson`, `metricsJson` " +
                "FROM `kpidynamicdata` WHERE `fileId` = ? ORDER BY `rowIndex` ASC, `id` ASC";
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
        }, fileId);
    }

    public List<String> listDistinctDimensionValues(String dimension, Long fileId) {
        ensureTable();
        String column = switch (dimension) {
            case "cellName" -> "cellName";
            case "site" -> "site";
            case "band" -> "band";
            case "tech" -> "tech";
            case "groups" -> "groupsName";
            default -> throw new IllegalArgumentException("Unsupported dynamic KPI dimension: " + dimension);
        };

        StringBuilder sql = new StringBuilder("SELECT DISTINCT `")
                .append(column)
                .append("` FROM `kpidynamicdata` WHERE `")
                .append(column)
                .append("` IS NOT NULL AND TRIM(`")
                .append(column)
                .append("`) <> ''");
        List<Object> args = new ArrayList<>();
        if (fileId != null) {
            sql.append(" AND `fileId` = ?");
            args.add(fileId);
        }
        sql.append(" ORDER BY `").append(column).append("` ASC");

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> rs.getString(1), args.toArray());
    }

    public List<Map<String, Object>> listDistinctSectorSummaries(Long fileId) {
        ensureTable();
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT `sectorId`, MIN(`sectorName`) AS `sectorName` ");
        sql.append("FROM `kpidynamicdata` ");
        sql.append("WHERE `sectorId` IS NOT NULL AND TRIM(`sectorId`) <> ''");
        List<Object> args = new ArrayList<>();
        if (fileId != null) {
            sql.append(" AND `fileId` = ?");
            args.add(fileId);
        }
        sql.append(" GROUP BY `sectorId` ORDER BY `sectorId` ASC");

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getString("sectorId"));
            item.put("name", rs.getString("sectorName"));
            return item;
        }, args.toArray());
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
                safeString(record.get("date")),
                safeString(record.get("cellName")),
                safeString(record.get("site")),
                safeString(record.get("band")),
                safeString(record.get("tech")),
                safeString(record.get("sectorid")),
                safeString(record.get("sectorname")),
                safeString(record.get("groups")),
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
}

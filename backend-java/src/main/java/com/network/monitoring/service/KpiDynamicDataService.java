package com.network.monitoring.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(KpiDynamicDataService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectWriter JSON_WRITER = MAPPER.writer();
    private static final int INSERT_BATCH_SIZE = 1000;

    private final JdbcTemplate jdbcTemplate;

    public KpiDynamicDataService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        ensureTable();
    }

    @Transactional
    public void replaceRecords(long fileId, List<Map<String, Object>> records) {
        replaceRecords(fileId, null, records);
    }

    @Transactional
    public void replaceRecords(long fileId, Long companyId, List<Map<String, Object>> records) {
        deleteByFileId(fileId);
        insertRecords(fileId, companyId, records);
    }

    @Transactional
    public void deleteByFileId(long fileId) {
        jdbcTemplate.update("DELETE FROM `kpi_metric_values` WHERE `fileId` = ?", fileId);
        jdbcTemplate.update("DELETE FROM `kpidynamicdata` WHERE `fileId` = ?", fileId);
    }

    public List<Map<String, Object>> findRecordsByFileId(long fileId) {
        return findRecords(fileId);
    }

    @Transactional
    public void appendRecords(long fileId, Long companyId, List<Map<String, Object>> records) {
        Integer next = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(rowIndex),-1)+1 FROM kpidynamicdata WHERE fileId=?", Integer.class, fileId);
        insertRecords(fileId, companyId, records, next == null ? 0 : next);
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
        return listDistinctDimensionValues(dimension, fileId, null);
    }

    public List<String> listDistinctDimensionValues(String dimension, Long fileId, Long companyId) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        String column = dimensionColumn(dimension);
        if (column == null) {
            return List.of();
        }

        StringBuilder sql = new StringBuilder("SELECT DISTINCT `")
                .append(column)
                .append("` FROM `kpidynamicdata` WHERE `")
                .append(column)
                .append("` IS NOT NULL");
        List<Object> args = new ArrayList<>();
        if (fileId != null) {
            sql.append(" AND `fileId` = ?");
            args.add(fileId);
        }
        if (companyId != null) {
            sql.append(" AND `companyId` = ?");
            args.add(companyId);
        }
        sql.append(" ORDER BY `").append(column).append("` ASC");

        for (String value : jdbcTemplate.queryForList(sql.toString(), String.class, args.toArray())) {
            addNonBlank(values, value);
        }
        return values.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public List<Map<String, Object>> listDistinctSectorSummaries(Long fileId) {
        return listDistinctSectorSummaries(fileId, null);
    }

    public List<Map<String, Object>> listDistinctSectorSummaries(Long fileId, Long companyId) {
        Map<String, Map<String, Object>> sectors = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("""
                SELECT `sectorId` AS id, MIN(`sectorName`) AS name
                FROM `kpidynamicdata`
                WHERE `sectorId` IS NOT NULL
                """);
        List<Object> args = new ArrayList<>();
        if (fileId != null) {
            sql.append(" AND `fileId` = ?");
            args.add(fileId);
        }
        if (companyId != null) {
            sql.append(" AND `companyId` = ?");
            args.add(companyId);
        }
        sql.append(" GROUP BY `sectorId` ORDER BY `sectorId` ASC");

        jdbcTemplate.query(sql.toString(), rs -> {
            String id = rs.getString("id");
            if (id == null || id.isBlank()) {
                return;
            }
            String name = rs.getString("name");
            sectors.computeIfAbsent(id, ignored -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", id);
                item.put("name", name);
                return item;
            });
        }, args.toArray());
        return sectors.values().stream()
                .sorted((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(String.valueOf(a.get("id")), String.valueOf(b.get("id"))))
                .toList();
    }

    private String dimensionColumn(String dimension) {
        if (dimension == null) {
            return null;
        }
        return switch (dimension.toLowerCase()) {
            case "cellname", "cell_name", "cell" -> "cellName";
            case "site" -> "site";
            case "band" -> "band";
            case "tech", "technology" -> "tech";
            case "groups", "group", "groupsname" -> "groupsName";
            case "sectorid", "sector_id" -> "sectorId";
            case "sectorname", "sector_name" -> "sectorName";
            default -> null;
        };
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
                    `companyId` BIGINT NULL,
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
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `kpi_metric_values` (
                    `id` BIGINT NOT NULL AUTO_INCREMENT,
                    `fileId` BIGINT NOT NULL,
                    `companyId` BIGINT NULL,
                    `rowIndex` INT NOT NULL,
                    `rowKey` VARCHAR(191) NULL,
                    `metricKey` VARCHAR(191) NOT NULL,
                    `metricValue` DOUBLE NULL,
                    `cellName` VARCHAR(255) NULL,
                    `site` VARCHAR(255) NULL,
                    `band` VARCHAR(255) NULL,
                    `tech` VARCHAR(255) NULL,
                    `sectorId` VARCHAR(255) NULL,
                    `dateValue` VARCHAR(64) NULL,
                    `createdAt` DATETIME NULL,
                    PRIMARY KEY (`id`),
                    INDEX `idx_kpi_metric_values_file_metric` (`fileId`, `metricKey`),
                    INDEX `idx_kpi_metric_values_file_cell` (`fileId`, `cellName`),
                    INDEX `idx_kpi_metric_values_file_site` (`fileId`, `site`)
                )
                """);
        addColumnIfMissing("kpidynamicdata", "companyId", "ALTER TABLE `kpidynamicdata` ADD COLUMN `companyId` BIGINT NULL AFTER `fileId`");
        addColumnIfMissing("kpidynamicdata", "rowKey", "ALTER TABLE `kpidynamicdata` ADD COLUMN `rowKey` VARCHAR(191) NULL AFTER `rowIndex`");
        addColumnIfMissing("kpidynamicdata", "dateValue", "ALTER TABLE `kpidynamicdata` ADD COLUMN `dateValue` VARCHAR(64) NULL");
        addColumnIfMissing("kpidynamicdata", "cellName", "ALTER TABLE `kpidynamicdata` ADD COLUMN `cellName` VARCHAR(255) NULL");
        addColumnIfMissing("kpidynamicdata", "site", "ALTER TABLE `kpidynamicdata` ADD COLUMN `site` VARCHAR(255) NULL");
        addColumnIfMissing("kpidynamicdata", "band", "ALTER TABLE `kpidynamicdata` ADD COLUMN `band` VARCHAR(255) NULL");
        addColumnIfMissing("kpidynamicdata", "tech", "ALTER TABLE `kpidynamicdata` ADD COLUMN `tech` VARCHAR(255) NULL");
        addColumnIfMissing("kpidynamicdata", "sectorId", "ALTER TABLE `kpidynamicdata` ADD COLUMN `sectorId` VARCHAR(255) NULL");
        addColumnIfMissing("kpidynamicdata", "sectorName", "ALTER TABLE `kpidynamicdata` ADD COLUMN `sectorName` VARCHAR(255) NULL");
        addColumnIfMissing("kpidynamicdata", "groupsName", "ALTER TABLE `kpidynamicdata` ADD COLUMN `groupsName` VARCHAR(255) NULL");
        addColumnIfMissing("kpidynamicdata", "dimensionsJson", "ALTER TABLE `kpidynamicdata` ADD COLUMN `dimensionsJson` LONGTEXT NULL");
        addColumnIfMissing("kpidynamicdata", "metricsJson", "ALTER TABLE `kpidynamicdata` ADD COLUMN `metricsJson` LONGTEXT NULL");
        addColumnIfMissing("kpidynamicdata", "createdAt", "ALTER TABLE `kpidynamicdata` ADD COLUMN `createdAt` DATETIME NULL");

        addColumnIfMissing("kpi_metric_values", "companyId", "ALTER TABLE `kpi_metric_values` ADD COLUMN `companyId` BIGINT NULL AFTER `fileId`");
        addColumnIfMissing("kpi_metric_values", "rowKey", "ALTER TABLE `kpi_metric_values` ADD COLUMN `rowKey` VARCHAR(191) NULL AFTER `rowIndex`");
        addColumnIfMissing("kpi_metric_values", "metricValue", "ALTER TABLE `kpi_metric_values` ADD COLUMN `metricValue` DOUBLE NULL");
        addColumnIfMissing("kpi_metric_values", "cellName", "ALTER TABLE `kpi_metric_values` ADD COLUMN `cellName` VARCHAR(255) NULL");
        addColumnIfMissing("kpi_metric_values", "site", "ALTER TABLE `kpi_metric_values` ADD COLUMN `site` VARCHAR(255) NULL");
        addColumnIfMissing("kpi_metric_values", "band", "ALTER TABLE `kpi_metric_values` ADD COLUMN `band` VARCHAR(255) NULL");
        addColumnIfMissing("kpi_metric_values", "tech", "ALTER TABLE `kpi_metric_values` ADD COLUMN `tech` VARCHAR(255) NULL");
        addColumnIfMissing("kpi_metric_values", "sectorId", "ALTER TABLE `kpi_metric_values` ADD COLUMN `sectorId` VARCHAR(255) NULL");
        addColumnIfMissing("kpi_metric_values", "dateValue", "ALTER TABLE `kpi_metric_values` ADD COLUMN `dateValue` VARCHAR(64) NULL");
        addColumnIfMissing("kpi_metric_values", "createdAt", "ALTER TABLE `kpi_metric_values` ADD COLUMN `createdAt` DATETIME NULL");

        addIndexIfMissing("kpidynamicdata", "idx_kpidynamicdata_file_cell", "CREATE INDEX `idx_kpidynamicdata_file_cell` ON `kpidynamicdata` (`fileId`, `cellName`)");
        addIndexIfMissing("kpidynamicdata", "idx_kpidynamicdata_company_file", "CREATE INDEX `idx_kpidynamicdata_company_file` ON `kpidynamicdata` (`companyId`, `fileId`)");
        addIndexIfMissing("kpidynamicdata", "idx_kpidynamicdata_file_site", "CREATE INDEX `idx_kpidynamicdata_file_site` ON `kpidynamicdata` (`fileId`, `site`)");
        addIndexIfMissing("kpidynamicdata", "idx_kpidynamicdata_file_band", "CREATE INDEX `idx_kpidynamicdata_file_band` ON `kpidynamicdata` (`fileId`, `band`)");
        addIndexIfMissing("kpidynamicdata", "idx_kpidynamicdata_file_sector", "CREATE INDEX `idx_kpidynamicdata_file_sector` ON `kpidynamicdata` (`fileId`, `sectorId`)");
        addIndexIfMissing("kpidynamicdata", "idx_kpidynamicdata_file_tech", "CREATE INDEX `idx_kpidynamicdata_file_tech` ON `kpidynamicdata` (`fileId`, `tech`)");
    }

    private void insertRecords(long fileId, Long companyId, List<Map<String, Object>> records) {
        insertRecords(fileId, companyId, records, 0);
    }

    private void insertRecords(long fileId, Long companyId, List<Map<String, Object>> records, int offset) {
        if (records == null || records.isEmpty()) {
            return;
        }

        String sql = "INSERT INTO `kpidynamicdata` " +
                "(`fileId`, `companyId`, `rowIndex`, `rowKey`, `dateValue`, `cellName`, `site`, `band`, `tech`, `sectorId`, `sectorName`, `groupsName`, `dimensionsJson`, `metricsJson`, `createdAt`) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        LocalDateTime createdAt = LocalDateTime.now(ZoneId.of("UTC"));
        List<Object[]> args = new ArrayList<>();
        List<Object[]> metricArgs = new ArrayList<>();
        Set<String> seenKeys = new LinkedHashSet<>();

        for (int index = 0; index < records.size(); index++) {
            Map<String, Object> record = records.get(index);
            String rowKey = dynamicRecordKey(record);
            if (!seenKeys.add(rowKey)) {
                continue;
            }
            args.add(new Object[]{
                    fileId,
                    companyId,
                    index + offset,
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
            appendMetricValues(metricArgs, fileId, companyId, index + offset, rowKey, record, createdAt);

            if (args.size() >= INSERT_BATCH_SIZE) {
                jdbcTemplate.batchUpdate(sql, args);
                args.clear();
            }
            if (metricArgs.size() >= INSERT_BATCH_SIZE) {
                insertMetricValues(metricArgs);
                metricArgs.clear();
            }
        }

        if (!args.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, args);
        }
        if (!metricArgs.isEmpty()) {
            insertMetricValues(metricArgs);
        }
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
            return JSON_WRITER.writeValueAsString(value == null ? Map.of() : value);
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
                keyPart(firstNonBlank(record.get("band"), dimension(record, "band", "frequencyband", "frequency_band", "carrier"))),
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
            char[] chars = new char[hash.length * 2];
            char[] hex = "0123456789abcdef".toCharArray();
            for (int index = 0; index < hash.length; index++) {
                int item = hash[index] & 0xff;
                chars[index * 2] = hex[item >>> 4];
                chars[index * 2 + 1] = hex[item & 0x0f];
            }
            return new String(chars);
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
            case "band" -> firstNonBlank(record.get("band"), dimension(record, "band", "frequencyband", "frequency_band", "carrier"));
            case "tech" -> firstNonBlank(record.get("tech"), dimension(record, "tech", "technology"));
            case "groups" -> firstNonBlank(record.get("groups"), dimension(record, "groups", "group", "region", "cluster"));
            default -> throw new IllegalArgumentException("Unsupported dynamic KPI dimension: " + dimension);
        };
    }

    private void appendMetricValues(List<Object[]> metricArgs,
                                    long fileId,
                                    Long companyId,
                                    int rowIndex,
                                    String rowKey,
                                    Map<String, Object> record,
                                    LocalDateTime createdAt) {
        Map<String, Object> metrics = getObjectMap(record.get("metrics"));
        if (metrics.isEmpty()) {
            return;
        }
        String cellName = safeString(record.get("cellName"));
        String site = safeString(record.get("site"));
        String band = safeString(record.get("band"));
        String tech = safeString(record.get("tech"));
        String sectorId = safeString(record.get("sectorid"));
        String date = safeString(record.get("date"));
        for (Map.Entry<String, Object> entry : metrics.entrySet()) {
            Double value = toDouble(entry.getValue());
            if (entry.getKey() == null || entry.getKey().isBlank() || value == null) {
                continue;
            }
            metricArgs.add(new Object[]{
                    fileId,
                    companyId,
                    rowIndex,
                    rowKey,
                    entry.getKey(),
                    value,
                    cellName,
                    site,
                    band,
                    tech,
                    sectorId,
                    date,
                    Timestamp.valueOf(createdAt)
            });
        }
    }

    private void insertMetricValues(List<Object[]> metricArgs) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO `kpi_metric_values`
                (`fileId`, `companyId`, `rowIndex`, `rowKey`, `metricKey`, `metricValue`, `cellName`, `site`, `band`, `tech`, `sectorId`, `dateValue`, `createdAt`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, metricArgs);
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String parsed = safeString(value);
        if (parsed == null) {
            return null;
        }
        try {
            return Double.parseDouble(parsed.replace(",", ""));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void addIndexIfMissing(String tableName, String indexName, String ddl) {
        try {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM INFORMATION_SCHEMA.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = ?
                      AND INDEX_NAME = ?
                    """, Integer.class, tableName, indexName);
            if (count == null || count == 0) {
                jdbcTemplate.execute(ddl);
            }
        } catch (Exception ex) {
            log.warn("Skipping dynamic KPI index {} on {}: {}", indexName, tableName, ex.getMessage());
        }
    }

    private void addColumnIfMissing(String tableName, String columnName, String ddl) {
        try {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = ?
                      AND COLUMN_NAME = ?
                    """, Integer.class, tableName, columnName);
            if (count == null || count == 0) {
                jdbcTemplate.execute(ddl);
            }
        } catch (Exception ex) {
            log.warn("Skipping dynamic KPI column {}.{}: {}", tableName, columnName, ex.getMessage());
        }
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

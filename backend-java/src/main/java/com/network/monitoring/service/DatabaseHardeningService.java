package com.network.monitoring.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DatabaseHardeningService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseHardeningService.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseHardeningService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void hardenDatabase() {
        addColumnIfTableExists("uploadhistory", "companyId", "ALTER TABLE `uploadhistory` ADD COLUMN `companyId` BIGINT NULL");
        addColumnIfTableExists("uploaddata", "companyId", "ALTER TABLE `uploaddata` ADD COLUMN `companyId` BIGINT NULL");
        addColumnIfTableExists("kpidynamicdata", "companyId", "ALTER TABLE `kpidynamicdata` ADD COLUMN `companyId` BIGINT NULL");
        addColumnIfTableExists("kpi_metric_values", "companyId", "ALTER TABLE `kpi_metric_values` ADD COLUMN `companyId` BIGINT NULL");
        addColumnIfTableExists("sitedata", "companyId", "ALTER TABLE `sitedata` ADD COLUMN `companyId` BIGINT NULL");
        addColumnIfTableExists("alarmdata", "companyId", "ALTER TABLE `alarmdata` ADD COLUMN `companyId` BIGINT NULL");
        addColumnIfTableExists("threshold", "companyId", "ALTER TABLE `threshold` ADD COLUMN `companyId` BIGINT NULL");
        addColumnIfTableExists("kpi_alarms", "companyId", "ALTER TABLE `kpi_alarms` ADD COLUMN `companyId` BIGINT NULL");
        addColumnIfTableExists("kpi_threshold_results", "companyId", "ALTER TABLE `kpi_threshold_results` ADD COLUMN `companyId` BIGINT NULL");
        addColumnIfTableExists("recommendation_jobs", "companyId", "ALTER TABLE `recommendation_jobs` ADD COLUMN `companyId` BIGINT NULL");
        addColumnIfTableExists("site_prediction_results", "companyId", "ALTER TABLE `site_prediction_results` ADD COLUMN `companyId` BIGINT NULL");

        addIndexIfTableExists("uploadhistory", "idx_uploadhistory_company", "CREATE INDEX `idx_uploadhistory_company` ON `uploadhistory` (`companyId`)");
        addIndexIfTableExists("uploaddata", "idx_uploaddata_file_cell", "CREATE INDEX `idx_uploaddata_file_cell` ON `uploaddata` (`fileId`, `Cell_Name`)");
        addIndexIfTableExists("uploaddata", "idx_uploaddata_company_file", "CREATE INDEX `idx_uploaddata_company_file` ON `uploaddata` (`companyId`, `fileId`)");
        addIndexIfTableExists("uploaddata", "idx_uploaddata_file_site", "CREATE INDEX `idx_uploaddata_file_site` ON `uploaddata` (`fileId`, `Site`)");
        addIndexIfTableExists("uploaddata", "idx_uploaddata_file_band", "CREATE INDEX `idx_uploaddata_file_band` ON `uploaddata` (`fileId`, `Band`)");
        addIndexIfTableExists("uploaddata", "idx_uploaddata_file_sector", "CREATE INDEX `idx_uploaddata_file_sector` ON `uploaddata` (`fileId`, `Sector_ID`)");
        addIndexIfTableExists("uploaddata", "idx_uploaddata_file_tech", "CREATE INDEX `idx_uploaddata_file_tech` ON `uploaddata` (`fileId`, `Tech`)");

        addIndexIfTableExists("sitedata", "idx_sitedata_file_cellid", "CREATE INDEX `idx_sitedata_file_cellid` ON `sitedata` (`fileId`, `Cell ID`)");
        addIndexIfTableExists("sitedata", "idx_sitedata_company_file", "CREATE INDEX `idx_sitedata_company_file` ON `sitedata` (`companyId`, `fileId`)");
        addIndexIfTableExists("sitedata", "idx_sitedata_file_cellname", "CREATE INDEX `idx_sitedata_file_cellname` ON `sitedata` (`fileId`, `Cell Name`)");
        addIndexIfTableExists("sitedata", "idx_sitedata_site_cell", "CREATE INDEX `idx_sitedata_site_cell` ON `sitedata` (`SITEID`, `Cell ID`)");

        addIndexIfTableExists("alarmdata", "idx_alarmdata_company_file", "CREATE INDEX `idx_alarmdata_company_file` ON `alarmdata` (`companyId`, `fileId`)");
        addIndexIfTableExists("kpi_alarms", "idx_kpi_alarms_file_status_severity", "CREATE INDEX `idx_kpi_alarms_file_status_severity` ON `kpi_alarms` (`fileId`, `status`, `severity`)");
        addIndexIfTableExists("kpi_alarms", "idx_kpi_alarms_company_file", "CREATE INDEX `idx_kpi_alarms_company_file` ON `kpi_alarms` (`companyId`, `fileId`)");
        addIndexIfTableExists("kpi_threshold_results", "idx_kpi_threshold_results_file", "CREATE INDEX `idx_kpi_threshold_results_file` ON `kpi_threshold_results` (`fileId`)");
        addIndexIfTableExists("kpi_threshold_results", "idx_kpi_threshold_results_company_file", "CREATE INDEX `idx_kpi_threshold_results_company_file` ON `kpi_threshold_results` (`companyId`, `fileId`)");
        addIndexIfTableExists("recommendation_jobs", "idx_recommendation_jobs_file_status", "CREATE INDEX `idx_recommendation_jobs_file_status` ON `recommendation_jobs` (`fileId`, `status`)");
        addIndexIfTableExists("recommendation_jobs", "idx_recommendation_jobs_company_file", "CREATE INDEX `idx_recommendation_jobs_company_file` ON `recommendation_jobs` (`companyId`, `fileId`)");
    }

    private void addColumnIfTableExists(String tableName, String columnName, String ddl) {
        try {
            if (!tableExists(tableName) || columnExists(tableName, columnName)) {
                return;
            }
            jdbcTemplate.execute(ddl);
        } catch (Exception ex) {
            log.warn("Skipping database column {}.{}: {}", tableName, columnName, ex.getMessage());
        }
    }

    private void addIndexIfTableExists(String tableName, String indexName, String ddl) {
        try {
            if (!tableExists(tableName) || indexExists(tableName, indexName)) {
                return;
            }
            jdbcTemplate.execute(ddl);
        } catch (Exception ex) {
            log.warn("Skipping database index {} on {}: {}", indexName, tableName, ex.getMessage());
        }
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                """, Integer.class, tableName);
        return count != null && count > 0;
    }

    private boolean indexExists(String tableName, String indexName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND INDEX_NAME = ?
                """, Integer.class, tableName, indexName);
        return count != null && count > 0;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """, Integer.class, tableName, columnName);
        return count != null && count > 0;
    }
}

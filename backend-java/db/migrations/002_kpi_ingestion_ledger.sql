-- Additive foundation for daily import. Apply once before starting this build.
CREATE TABLE IF NOT EXISTS kpi_dataset_snapshots (
    fileId BIGINT NOT NULL PRIMARY KEY,
    snapshotName VARCHAR(255) NOT NULL,
    revision VARCHAR(64) NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    identityJson TEXT NULL,
    updatedAt DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS kpi_ingestion_runs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    importKey VARCHAR(64) NOT NULL,
    fileId BIGINT NOT NULL,
    companyId BIGINT NULL,
    sourceName VARCHAR(128) NOT NULL,
    fileName VARCHAR(512) NOT NULL,
    sourcePath VARCHAR(1024) NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    configJson LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    resultJson LONGTEXT NULL,
    message TEXT NULL,
    createdAt DATETIME NOT NULL,
    updatedAt DATETIME NOT NULL,
    UNIQUE KEY uq_kpi_ingestion_import (importKey),
    KEY idx_kpi_ingestion_file (fileId, id),
    KEY idx_kpi_ingestion_status (status, updatedAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Rollback requires exporting the active immutable snapshots to legacy <fileId>.json
-- before reverting code. Do not drop these tables while their snapshot pointers are in use.

-- Daily analysis is opt-in per dataset; no metric units or aggregation rules are guessed.
CREATE TABLE IF NOT EXISTS kpi_analysis_profiles (
    fileId BIGINT NOT NULL PRIMARY KEY,
    profileHash VARCHAR(64) NOT NULL,
    configJson LONGTEXT NOT NULL,
    updatedAt DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS kpi_analysis_snapshots (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    requestKey VARCHAR(64) NOT NULL,
    fileId BIGINT NOT NULL,
    sourceName VARCHAR(255) NOT NULL,
    sourceFingerprint VARCHAR(64) NOT NULL,
    datasetVersion VARCHAR(96) NOT NULL,
    profileHash VARCHAR(64) NOT NULL,
    configJson LONGTEXT NOT NULL,
    requestedDate DATE NULL,
    dataDate DATE NULL,
    status VARCHAR(32) NOT NULL,
    summaryJson LONGTEXT NULL,
    message TEXT NULL,
    createdAt DATETIME NOT NULL,
    updatedAt DATETIME NOT NULL,
    UNIQUE KEY uq_analysis_request (requestKey),
    KEY idx_analysis_file (fileId,id),
    KEY idx_analysis_status (status,updatedAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS kpi_daily_groups (
    snapshotId BIGINT NOT NULL,
    dataDate DATE NOT NULL,
    groupHash VARCHAR(64) NOT NULL,
    dimensionsJson TEXT NOT NULL,
    metricsJson LONGTEXT NOT NULL,
    recordCount INT NOT NULL,
    PRIMARY KEY (snapshotId,dataDate,groupHash),
    KEY idx_daily_group_page (snapshotId,groupHash,dataDate)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

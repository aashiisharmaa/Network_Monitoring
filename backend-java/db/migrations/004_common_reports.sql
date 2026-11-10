CREATE TABLE IF NOT EXISTS kpi_common_reports (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    requestKey VARCHAR(64) NOT NULL,
    snapshotId BIGINT NOT NULL,
    fileId BIGINT NOT NULL,
    configJson LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    summaryJson LONGTEXT NULL,
    artifactName VARCHAR(255) NULL,
    checksumsJson TEXT NULL,
    message TEXT NULL,
    createdAt DATETIME NOT NULL,
    updatedAt DATETIME NOT NULL,
    UNIQUE KEY uq_common_report_request (requestKey),
    KEY idx_common_report_file (fileId,id),
    KEY idx_common_report_status (status,updatedAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

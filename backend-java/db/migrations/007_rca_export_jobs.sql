CREATE TABLE IF NOT EXISTS kpi_rca_exports (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    fileId BIGINT NOT NULL,
    cacheKey VARCHAR(64) NULL,
    artifactKey VARCHAR(64) NOT NULL,
    requestJson LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    progressPercent INT NOT NULL DEFAULT 5,
    message VARCHAR(2000) NULL,
    createdAt DATETIME NOT NULL,
    updatedAt DATETIME NOT NULL,
    KEY idx_rca_export_request (fileId,cacheKey,status,id),
    KEY idx_rca_export_status (status,updatedAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

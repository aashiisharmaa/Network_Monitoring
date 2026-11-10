CREATE TABLE IF NOT EXISTS kpi_nightly_profiles (
    fileId BIGINT NOT NULL PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    configJson LONGTEXT NOT NULL,
    updatedBy BIGINT NULL,
    updatedAt DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS kpi_nightly_runs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    fileId BIGINT NOT NULL,
    dataDate DATE NOT NULL,
    configJson LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    attempts INT NOT NULL DEFAULT 1,
    retryPending BOOLEAN NOT NULL DEFAULT FALSE,
    allowLate BOOLEAN NOT NULL DEFAULT FALSE,
    observedSize BIGINT NULL,
    observedModified BIGINT NULL,
    observedAt DATETIME NULL,
    sourceChecksum VARCHAR(64) NULL,
    ingestionId BIGINT NULL,
    analysisId BIGINT NULL,
    reportId BIGINT NULL,
    deliveryIdsJson TEXT NULL,
    cutoffAt DATETIME NOT NULL,
    deadlineAt DATETIME NOT NULL,
    deadlineStatus VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    deadlineMissedAt DATETIME NULL,
    completionMode VARCHAR(32) NULL,
    message VARCHAR(2000) NULL,
    createdAt DATETIME NOT NULL,
    updatedAt DATETIME NOT NULL,
    finishedAt DATETIME NULL,
    UNIQUE KEY uq_nightly_day (fileId,dataDate),
    KEY idx_nightly_active (status,id),
    KEY idx_nightly_deadline (deadlineStatus,deadlineAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS kpi_nightly_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    runId BIGINT NOT NULL,
    event VARCHAR(40) NOT NULL,
    actorId BIGINT NULL,
    details TEXT NULL,
    createdAt DATETIME NOT NULL,
    KEY idx_nightly_event (runId,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

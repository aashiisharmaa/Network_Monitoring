CREATE TABLE IF NOT EXISTS kpi_mail_profiles (
    fileId BIGINT NOT NULL PRIMARY KEY,
    profileHash VARCHAR(64) NOT NULL,
    configJson TEXT NOT NULL,
    updatedBy BIGINT NULL,
    updatedAt DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS kpi_mail_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    deliveryKey VARCHAR(64) NOT NULL,
    reportId BIGINT NOT NULL,
    fileId BIGINT NOT NULL,
    recipient VARCHAR(254) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    envelopeJson LONGTEXT NOT NULL,
    messageId VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    mimeChecksum VARCHAR(64) NULL,
    providerResponse VARCHAR(1000) NULL,
    message VARCHAR(1000) NULL,
    acceptedAt DATETIME NULL,
    capturedAt DATETIME NULL,
    createdAt DATETIME NOT NULL,
    updatedAt DATETIME NOT NULL,
    UNIQUE KEY uq_mail_delivery (deliveryKey),
    KEY idx_mail_report (reportId,id),
    KEY idx_mail_file (fileId,id),
    KEY idx_mail_status (status,updatedAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS kpi_mail_attempts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    deliveryId BIGINT NOT NULL,
    attemptNo INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    message VARCHAR(1000) NULL,
    startedAt DATETIME NOT NULL,
    finishedAt DATETIME NULL,
    UNIQUE KEY uq_mail_attempt (deliveryId,attemptNo)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS kpi_mail_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    deliveryId BIGINT NOT NULL,
    event VARCHAR(40) NOT NULL,
    actorId BIGINT NULL,
    evidence VARCHAR(1000) NULL,
    createdAt DATETIME NOT NULL,
    KEY idx_mail_event (deliveryId,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

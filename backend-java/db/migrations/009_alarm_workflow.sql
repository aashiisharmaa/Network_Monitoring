-- Apply after 008. Additive schema; no catalogue, recipients or business defaults seeded.
SET @alarm_workflow_index_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
           AND table_name='alarm_source_feed_profiles' AND index_name='uq_alarm_feed_tenant'),
    'SELECT 1', 'ALTER TABLE alarm_source_feed_profiles ADD UNIQUE KEY uq_alarm_feed_tenant (id,company_id)');
PREPARE alarm_workflow_index_stmt FROM @alarm_workflow_index_sql;
EXECUTE alarm_workflow_index_stmt;
DEALLOCATE PREPARE alarm_workflow_index_stmt;

CREATE TABLE IF NOT EXISTS alarm_catalog_rules (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    oem VARCHAR(16) NOT NULL,
    technology VARCHAR(2) NOT NULL,
    match_field VARCHAR(8) NOT NULL,
    match_value VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    classification VARCHAR(16) NOT NULL,
    included BOOLEAN NOT NULL,
    service_affecting BOOLEAN NOT NULL,
    site_down BOOLEAN NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_alarm_catalog_match (company_id,oem,technology,match_field,match_value),
    CONSTRAINT fk_alarm_catalog_company FOREIGN KEY (company_id) REFERENCES companies(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS alarm_site_objects (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    feed_id BIGINT NOT NULL,
    circle_id BIGINT NOT NULL,
    object_key VARCHAR(512) COLLATE utf8mb4_bin NOT NULL,
    site_code VARCHAR(128) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_alarm_site_object (company_id,feed_id,object_key),
    CONSTRAINT fk_alarm_site_feed FOREIGN KEY (feed_id,company_id) REFERENCES alarm_source_feed_profiles(id,company_id),
    CONSTRAINT fk_alarm_site_circle FOREIGN KEY (circle_id,company_id) REFERENCES alarm_circles(id,company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS alarm_ingestion_runs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    feed_id BIGINT NOT NULL,
    snapshot_at DATETIME(6) NOT NULL,
    revision INT NOT NULL DEFAULT 1,
    supersedes_id BIGINT NULL,
    checksum VARCHAR(64) NOT NULL,
    source_key VARCHAR(64) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    config_json LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    attempts INT NOT NULL DEFAULT 0,
    lease_token VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    result_json LONGTEXT NULL,
    mail_planned BOOLEAN NOT NULL DEFAULT FALSE,
    message VARCHAR(2000) NULL,
    created_by BIGINT NOT NULL,
    retried_by BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_alarm_import_slot (company_id,feed_id,snapshot_at,revision),
    UNIQUE KEY uq_alarm_run_tenant (id,company_id),
    KEY idx_alarm_run_queue (status,lease_until,id),
    KEY idx_alarm_run_feed_time (company_id,feed_id,status,snapshot_at),
    CONSTRAINT fk_alarm_run_feed FOREIGN KEY (feed_id,company_id) REFERENCES alarm_source_feed_profiles(id,company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS alarm_observations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    run_id BIGINT NOT NULL,
    identity_key VARCHAR(64) NOT NULL,
    circle_id BIGINT NOT NULL,
    circle_name VARCHAR(160) NOT NULL,
    zone_name VARCHAR(160) NOT NULL,
    site_code VARCHAR(128) NULL,
    oem VARCHAR(16) NOT NULL,
    technology VARCHAR(2) NOT NULL,
    resource_name VARCHAR(512) NOT NULL,
    alarm_code VARCHAR(255) NOT NULL,
    alarm_text TEXT NOT NULL,
    source_severity VARCHAR(128) NOT NULL,
    classification VARCHAR(16) NOT NULL,
    included BOOLEAN NULL,
    service_affecting BOOLEAN NULL,
    site_down BOOLEAN NULL,
    raised_at DATETIME(6) NOT NULL,
    row_json LONGTEXT NOT NULL,
    UNIQUE KEY uq_alarm_observation (run_id,identity_key),
    KEY idx_alarm_observation_scope (company_id,run_id,circle_id,classification),
    CONSTRAINT fk_alarm_observation_run FOREIGN KEY (run_id,company_id) REFERENCES alarm_ingestion_runs(id,company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Configuration below is opt-in. No recipient, source directory or cadence is implied by the BRD.
CREATE TABLE IF NOT EXISTS alarm_automation_settings (
    company_id BIGINT NOT NULL PRIMARY KEY,
    settings_json LONGTEXT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    updated_by BIGINT NOT NULL,
    last_poll_at DATETIME(6) NULL,
    last_poll_message VARCHAR(1000) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_alarm_automation_company FOREIGN KEY (company_id) REFERENCES companies(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS alarm_delivery_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    run_id BIGINT NOT NULL,
    delivery_key VARCHAR(64) NOT NULL,
    mode VARCHAR(8) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    message_json LONGTEXT NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    mime_key VARCHAR(64) NULL,
    next_attempt_at DATETIME(6) NULL,
    lease_token VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    message VARCHAR(2000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_alarm_delivery (company_id,delivery_key),
    KEY idx_alarm_delivery_queue (status,id),
    CONSTRAINT fk_alarm_delivery_run FOREIGN KEY (run_id,company_id) REFERENCES alarm_ingestion_runs(id,company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS alarm_operator_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    feed_id BIGINT NOT NULL,
    identity_key VARCHAR(64) NOT NULL,
    action VARCHAR(16) NOT NULL,
    note VARCHAR(2000) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_alarm_operator_history (company_id,feed_id,identity_key,id),
    CONSTRAINT fk_alarm_operator_feed FOREIGN KEY (feed_id,company_id) REFERENCES alarm_source_feed_profiles(id,company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS alarm_escalation_decisions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    feed_id BIGINT NOT NULL,
    run_id BIGINT NOT NULL,
    identity_key VARCHAR(64) NOT NULL,
    policy_key VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_alarm_escalation_decision (company_id,feed_id,identity_key,policy_key),
    CONSTRAINT fk_alarm_escalation_run FOREIGN KEY (run_id,company_id) REFERENCES alarm_ingestion_runs(id,company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

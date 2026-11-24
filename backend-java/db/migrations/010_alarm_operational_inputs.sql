-- Apply after 008/009. Append-only operational inputs; no customer values seeded.
-- Enable ALARM_OPERATIONAL_ENABLED only after this migration is installed.
CREATE TABLE IF NOT EXISTS alarm_operational_snapshots (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    effective_at DATETIME(6) NOT NULL,
    revision INT NOT NULL,
    supersedes_id BIGINT NULL,
    based_on_id BIGINT NULL,
    schema_version VARCHAR(32) NOT NULL,
    source_reference VARCHAR(500) NOT NULL,
    input_json LONGTEXT NOT NULL,
    input_sha256 VARCHAR(64) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_alarm_operational_slot (company_id,effective_at,revision),
    UNIQUE KEY uq_alarm_operational_tenant (id,company_id),
    KEY idx_alarm_operational_asof (company_id,effective_at,revision),
    CONSTRAINT fk_alarm_operational_company FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_alarm_operational_previous FOREIGN KEY (supersedes_id,company_id)
        REFERENCES alarm_operational_snapshots(id,company_id),
    CONSTRAINT fk_alarm_operational_base FOREIGN KEY (based_on_id,company_id)
        REFERENCES alarm_operational_snapshots(id,company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

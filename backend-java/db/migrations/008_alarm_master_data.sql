-- Additive migration. Apply after the existing companies table has been created.
-- No customer mappings are seeded. Re-running preserves existing configuration.
CREATE TABLE IF NOT EXISTS alarm_zones (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(160) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version INT NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_alarm_zone_code (company_id, code),
    UNIQUE KEY uq_alarm_zone_tenant (id, company_id),
    CONSTRAINT fk_alarm_zone_company FOREIGN KEY (company_id) REFERENCES companies(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS alarm_circles (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    zone_id BIGINT NOT NULL,
    code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(160) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version INT NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_alarm_circle_code (company_id, code),
    UNIQUE KEY uq_alarm_circle_tenant (id, company_id),
    KEY idx_alarm_circle_zone (zone_id, company_id),
    CONSTRAINT fk_alarm_circle_company FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_alarm_circle_zone FOREIGN KEY (zone_id, company_id) REFERENCES alarm_zones(id, company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Canonical circle codes occupy this same namespace as aliases, preventing ambiguity.
CREATE TABLE IF NOT EXISTS alarm_circle_aliases (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    circle_id BIGINT NOT NULL,
    alias_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    canonical BOOLEAN NOT NULL DEFAULT FALSE,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_alarm_circle_alias (company_id, alias_key),
    KEY idx_alarm_alias_circle (circle_id, company_id),
    CONSTRAINT fk_alarm_alias_company FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_alarm_alias_circle FOREIGN KEY (circle_id, company_id) REFERENCES alarm_circles(id, company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS alarm_source_feed_profiles (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    source_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(160) NOT NULL,
    oem VARCHAR(16) NOT NULL,
    technology VARCHAR(2) NOT NULL,
    file_format VARCHAR(8) NOT NULL,
    sheet_name VARCHAR(128) NULL,
    source_timezone VARCHAR(64) NOT NULL,
    dump_type VARCHAR(8) NOT NULL,
    circle_id BIGINT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    version INT NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_alarm_feed_key (company_id, source_key),
    KEY idx_alarm_feed_circle (circle_id, company_id),
    CONSTRAINT fk_alarm_feed_company FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_alarm_feed_circle FOREIGN KEY (circle_id, company_id) REFERENCES alarm_circles(id, company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- MySQL DDL commits implicitly. Back up first; apply in order and verify all four
-- tables/constraints before deployment. Roll back application code without dropping
-- these additive tables. Destructive rollback is intentionally not automated.

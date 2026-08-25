package com.network.monitoring.repository;

import com.network.monitoring.entity.LicenseAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LicenseAuditLogRepository extends JpaRepository<LicenseAuditLog, Long> {
    List<LicenseAuditLog> findByLicenseIdOrderByCreatedAtDesc(Long licenseId);
}

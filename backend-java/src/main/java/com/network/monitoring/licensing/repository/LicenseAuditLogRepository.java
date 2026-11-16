package com.network.monitoring.licensing.repository;

import com.network.monitoring.licensing.entity.LicenseAuditLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LicenseAuditLogRepository extends JpaRepository<LicenseAuditLog, Long> {
    List<LicenseAuditLog> findByLicenseIdOrderByCreatedAtDesc(Long licenseId);
}

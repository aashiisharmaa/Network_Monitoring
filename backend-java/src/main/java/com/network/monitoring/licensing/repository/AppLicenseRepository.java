package com.network.monitoring.licensing.repository;

import com.network.monitoring.licensing.entity.AppLicense;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppLicenseRepository extends JpaRepository<AppLicense, Long> {
    Optional<AppLicense> findByLicenseKey(String licenseKey);
    List<AppLicense> findByCompanyId(Long companyId);
}

package com.network.monitoring.repository;

import com.network.monitoring.entity.AppLicense;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppLicenseRepository extends JpaRepository<AppLicense, Long> {
    Optional<AppLicense> findByLicenseKey(String licenseKey);
    List<AppLicense> findByCompanyId(Long companyId);
}

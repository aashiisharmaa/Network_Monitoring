package com.network.monitoring.service;

import com.network.monitoring.entity.AppLicense;
import com.network.monitoring.entity.Company;
import com.network.monitoring.entity.LicenseAuditLog;
import com.network.monitoring.repository.AppLicenseRepository;
import com.network.monitoring.repository.CompanyRepository;
import com.network.monitoring.repository.LicenseAuditLogRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LicenseService {

    private final AppLicenseRepository licenseRepository;
    private final CompanyRepository companyRepository;
    private final LicenseAuditLogRepository auditLogRepository;
    private final LicenseFileService licenseFileService;
    private final RoleAuthorizationService authorizationService;

    public LicenseService(AppLicenseRepository licenseRepository,
                          CompanyRepository companyRepository,
                          LicenseAuditLogRepository auditLogRepository,
                          LicenseFileService licenseFileService,
                          RoleAuthorizationService authorizationService) {
        this.licenseRepository = licenseRepository;
        this.companyRepository = companyRepository;
        this.auditLogRepository = auditLogRepository;
        this.licenseFileService = licenseFileService;
        this.authorizationService = authorizationService;
    }

    public List<AppLicense> listLicenses() {
        authorizationService.requireSuperAdmin();
        return licenseRepository.findAll();
    }

    public AppLicense getLicense(Long id) {
        authorizationService.requireSuperAdmin();
        return licenseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("License not found."));
    }

    public AppLicense createLicense(Map<String, Object> body) {
        authorizationService.requireSuperAdmin();
        Long companyId = longValue(body.get("companyId"), body.get("company_id"));
        if (companyId == null) {
            throw new IllegalArgumentException("Company id is required.");
        }
        companyRepository.findById(companyId)
                .orElseThrow(() -> new IllegalArgumentException("Company not found."));

        AppLicense license = new AppLicense();
        license.setCompanyId(companyId);
        license.setLicenseKey("LIC-" + UUID.randomUUID());
        license.setDeviceId(requiredString(body.get("deviceId"), body.get("device_id")));
        license.setPlan(firstNonBlank(string(body.get("plan")), "BASIC").toUpperCase());
        license.setMaxUsers(firstNonNull(intValue(body.get("maxUsers"), body.get("max_users")), 1));
        license.setValidFrom(firstNonNull(dateValue(body.get("validFrom"), body.get("valid_from")), LocalDate.now(ZoneOffset.UTC)));
        license.setExpiresAt(firstNonNull(dateValue(body.get("expiresAt"), body.get("expires_at")), LocalDate.now(ZoneOffset.UTC).plusYears(1)));
        license.setStatus(firstNonBlank(string(body.get("status")), "ACTIVE").toUpperCase());
        license.setIssuedBy(authorizationService.currentUserId());
        license.setCreatedAt(now());
        license.setUpdatedAt(now());
        AppLicense saved = licenseRepository.save(license);
        audit(saved, "CREATED", "License created.");
        return saved;
    }

    public AppLicense renewLicense(Long id, Map<String, Object> body) {
        authorizationService.requireSuperAdmin();
        AppLicense license = getExisting(id);
        LocalDate expiresAt = dateValue(body.get("expiresAt"), body.get("expires_at"));
        if (expiresAt == null) {
            throw new IllegalArgumentException("New expiry date is required.");
        }
        license.setExpiresAt(expiresAt);
        license.setStatus("ACTIVE");
        license.setUpdatedAt(now());
        AppLicense saved = licenseRepository.save(license);
        audit(saved, "RENEWED", "License renewed until " + expiresAt + ".");
        return saved;
    }

    public AppLicense blockLicense(Long id) {
        authorizationService.requireSuperAdmin();
        AppLicense license = getExisting(id);
        license.setStatus("BLOCKED");
        license.setUpdatedAt(now());
        AppLicense saved = licenseRepository.save(license);
        audit(saved, "BLOCKED", "License blocked.");
        return saved;
    }

    public byte[] downloadLicenseFile(Long id) {
        authorizationService.requireSuperAdmin();
        AppLicense license = getExisting(id);
        Company company = companyRepository.findById(license.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found."));
        return licenseFileService.createLicenseFile(payload(license, company));
    }

    public Map<String, Object> payload(AppLicense license, Company company) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", 1);
        payload.put("licenseKey", license.getLicenseKey());
        payload.put("companyId", license.getCompanyId());
        payload.put("companyName", company.getCompanyName());
        payload.put("deviceId", license.getDeviceId());
        payload.put("plan", license.getPlan());
        payload.put("maxUsers", license.getMaxUsers());
        payload.put("validFrom", String.valueOf(license.getValidFrom()));
        payload.put("expiresAt", String.valueOf(license.getExpiresAt()));
        payload.put("status", license.getStatus());
        return payload;
    }

    private AppLicense getExisting(Long id) {
        return licenseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("License not found."));
    }

    private void audit(AppLicense license, String action, String remarks) {
        LicenseAuditLog log = new LicenseAuditLog();
        log.setLicenseId(license.getId());
        log.setCompanyId(license.getCompanyId());
        log.setAction(action);
        log.setPerformedBy(authorizationService.currentUserId());
        log.setRemarks(remarks);
        log.setCreatedAt(now());
        auditLogRepository.save(log);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private String requiredString(Object... values) {
        String value = firstNonBlank(values);
        if (value == null) {
            throw new IllegalArgumentException("Device id is required.");
        }
        return value;
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            String parsed = string(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    @SafeVarargs
    private <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String string(Object value) {
        if (value == null) {
            return null;
        }
        String parsed = String.valueOf(value).trim();
        return parsed.isEmpty() ? null : parsed;
    }

    private Long longValue(Object... values) {
        String value = firstNonBlank(values);
        if (value == null) {
            return null;
        }
        return Long.parseLong(value);
    }

    private Integer intValue(Object... values) {
        String value = firstNonBlank(values);
        if (value == null) {
            return null;
        }
        return Integer.parseInt(value);
    }

    private LocalDate dateValue(Object... values) {
        String value = firstNonBlank(values);
        if (value == null) {
            return null;
        }
        return LocalDate.parse(value);
    }
}

package com.network.monitoring.reporting.service;

import com.network.monitoring.identity.service.RoleAuthorizationService;
import com.network.monitoring.reporting.repository.ReportDatasetRepository;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** Validates paging and resolves tenant scope before any dataset query. */
@Service
public class ReportDatasetService {
    private final ReportDatasetRepository repository;
    private final RoleAuthorizationService role;

    public ReportDatasetService(ReportDatasetRepository repository, RoleAuthorizationService role) {
        this.repository = repository;
        this.role = role;
    }

    public Map<String, Object> list(int page, int limit) {
        if (page < 1 || page > 100000 || limit < 1 || limit > 100)
            throw new IllegalArgumentException("Invalid dataset page or limit.");
        boolean admin = role.isCurrentUserSuperAdmin();
        Long company = role.currentUserCompanyIdOrNull();
        if (!admin && company == null)
            throw new AccessDeniedException("A company assignment is required.");
        return repository.page(admin ? null : company, page, limit);
    }
}

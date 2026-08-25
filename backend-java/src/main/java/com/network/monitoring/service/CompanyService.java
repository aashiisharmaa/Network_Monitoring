package com.network.monitoring.service;

import com.network.monitoring.entity.Company;
import com.network.monitoring.repository.CompanyRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@Service
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final RoleAuthorizationService authorizationService;

    public CompanyService(CompanyRepository companyRepository,
                          RoleAuthorizationService authorizationService) {
        this.companyRepository = companyRepository;
        this.authorizationService = authorizationService;
    }

    public List<Company> listCompanies() {
        authorizationService.requireSuperAdmin();
        return companyRepository.findAll();
    }

    public Company getCompany(Long id) {
        authorizationService.requireSuperAdmin();
        return companyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Company not found."));
    }

    public Company createCompany(Map<String, Object> body) {
        authorizationService.requireSuperAdmin();
        Company company = new Company();
        apply(company, body);
        company.setStatus(firstNonBlank(string(body.get("status")), "ACTIVE"));
        company.setCreatedAt(now());
        company.setUpdatedAt(now());
        return companyRepository.save(company);
    }

    public Company updateCompany(Long id, Map<String, Object> body) {
        authorizationService.requireSuperAdmin();
        Company company = companyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Company not found."));
        apply(company, body);
        if (body.containsKey("status")) {
            company.setStatus(firstNonBlank(string(body.get("status")), company.getStatus()));
        }
        company.setUpdatedAt(now());
        return companyRepository.save(company);
    }

    public Company blockCompany(Long id) {
        authorizationService.requireSuperAdmin();
        Company company = companyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Company not found."));
        company.setStatus("BLOCKED");
        company.setUpdatedAt(now());
        return companyRepository.save(company);
    }

    private void apply(Company company, Map<String, Object> body) {
        String companyName = firstNonBlank(string(body.get("companyName")), string(body.get("company_name")), company.getCompanyName());
        if (companyName == null || companyName.isBlank()) {
            throw new IllegalArgumentException("Company name is required.");
        }
        company.setCompanyName(companyName);
        company.setContactName(firstNonBlank(string(body.get("contactName")), string(body.get("contact_name")), company.getContactName()));
        company.setEmail(firstNonBlank(string(body.get("email")), company.getEmail()));
        company.setPhone(firstNonBlank(string(body.get("phone")), company.getPhone()));
        company.setAddress(firstNonBlank(string(body.get("address")), company.getAddress()));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private String string(Object value) {
        if (value == null) {
            return null;
        }
        String parsed = String.valueOf(value).trim();
        return parsed.isEmpty() ? null : parsed;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}

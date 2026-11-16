package com.network.monitoring.alarm.config;

import com.network.monitoring.repository.CompanyRepository;
import com.network.monitoring.repository.UserRepository;
import com.network.monitoring.service.RoleAuthorizationService;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Component
public class AlarmTenantAccess {
    private final RoleAuthorizationService authorization;
    private final UserRepository users;
    private final CompanyRepository companies;

    public AlarmTenantAccess(RoleAuthorizationService authorization, UserRepository users, CompanyRepository companies) {
        this.authorization = authorization;
        this.users = users;
        this.companies = companies;
    }

    /** Returns the persisted actor ID, never a user/tenant supplied in a request body. */
    public long require(long companyId, boolean write) {
        Long userId = authorization.currentUserId();
        if (userId == null) throw new AlarmConfigurationException(401, "AUTHENTICATION_REQUIRED", "Authentication is required.");
        var user = users.findById(userId).orElseThrow(AlarmTenantAccess::denied);
        String role = user.getRole() == null ? "" : user.getRole().trim().toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus()) || !Set.of("SUPER_ADMIN", "ADMIN", "USER").contains(role)) throw denied();
        if (!role.equals("SUPER_ADMIN") && !Objects.equals(user.getCompanyId(), companyId)) throw denied();
        if (write && !Set.of("SUPER_ADMIN", "ADMIN").contains(role)) throw denied();
        if (companyId <= 0) throw AlarmConfigurationException.invalid("Company ID must be positive.");
        var company = companies.findById(companyId).orElseThrow(AlarmConfigurationException::missing);
        if (!"ACTIVE".equalsIgnoreCase(company.getStatus())) throw denied();
        return userId;
    }

    private static AlarmConfigurationException denied() {
        return new AlarmConfigurationException(403, "ALARM_CONFIGURATION_FORBIDDEN", "Access to this company configuration is denied.");
    }
}

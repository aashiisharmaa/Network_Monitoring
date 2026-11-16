package com.network.monitoring.alarm.config;

import com.network.monitoring.entity.Company;
import com.network.monitoring.entity.User;
import com.network.monitoring.repository.CompanyRepository;
import com.network.monitoring.repository.UserRepository;
import com.network.monitoring.service.RoleAuthorizationService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AlarmTenantAccessTest {
    final RoleAuthorizationService authorization = mock(RoleAuthorizationService.class);
    final UserRepository users = mock(UserRepository.class);
    final CompanyRepository companies = mock(CompanyRepository.class);
    final AlarmTenantAccess access = new AlarmTenantAccess(authorization, users, companies);

    @Test void rejectsAnonymousMissingTenantAndLegacyBlankRoleWithoutLookingUpOtherCompany() {
        when(authorization.currentUserId()).thenReturn(null);
        assertEquals(401, assertThrows(AlarmConfigurationException.class, () -> access.require(100, false)).status());
        verifyNoInteractions(users, companies);
        user("USER", null);
        assertEquals(403, assertThrows(AlarmConfigurationException.class, () -> access.require(100, false)).status());
        user("", 100L);
        assertEquals(403, assertThrows(AlarmConfigurationException.class, () -> access.require(100, true)).status());
        verifyNoInteractions(companies);
    }
    @Test void userReadsOnlyOwnTenantAndCannotWriteOrProbeAnotherTenant() {
        user("USER", 100L); company(100L);
        assertEquals(9, access.require(100, false));
        assertEquals(403, assertThrows(AlarmConfigurationException.class, () -> access.require(100, true)).status());
        assertEquals(403, assertThrows(AlarmConfigurationException.class, () -> access.require(200, false)).status());
        verify(companies, never()).findById(200L);
    }
    @Test void adminWritesOnlyOwnTenantAndSuperAdminUsesExplicitTenant() {
        user("ADMIN", 100L); company(100L); company(200L);
        assertEquals(9, access.require(100, true));
        assertEquals(403, assertThrows(AlarmConfigurationException.class, () -> access.require(200, true)).status());
        user("SUPER_ADMIN", null);
        assertEquals(9, access.require(200, true));
        assertEquals(404, assertThrows(AlarmConfigurationException.class, () -> access.require(300, false)).status());
    }
    @Test void blockedUsersAndCompaniesFailClosed() {
        var user = user("ADMIN", 100L); var company = company(100L);
        user.setStatus("BLOCKED");
        assertEquals(403, assertThrows(AlarmConfigurationException.class, () -> access.require(100, false)).status());
        user.setStatus("ACTIVE"); company.setStatus("BLOCKED");
        assertEquals(403, assertThrows(AlarmConfigurationException.class, () -> access.require(100, false)).status());
    }
    private User user(String role, Long companyId) {
        var user = new User(); user.setId(9L); user.setRole(role); user.setCompanyId(companyId);
        when(authorization.currentUserId()).thenReturn(9L);
        when(users.findById(9L)).thenReturn(Optional.of(user)); return user;
    }
    private Company company(long id) {
        var company = new Company(); company.setId(id);
        when(companies.findById(id)).thenReturn(Optional.of(company)); return company;
    }
}

package com.network.monitoring.controller;

import com.network.monitoring.service.RoleAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportDatasetControllerTest {
    @Test void requiresCompanyAndRejectsInvalidPagesBeforeQuerying() {
        var jdbc = mock(JdbcTemplate.class); var role = mock(RoleAuthorizationService.class); var controller = new ReportDatasetController(jdbc, role);
        when(role.currentUserCompanyIdOrNull()).thenReturn(null);
        assertEquals(403, controller.list(1, 50).getStatusCode().value());
        assertEquals(400, controller.list(0, 50).getStatusCode().value()); verifyNoInteractions(jdbc);
    }
    @Test void companyFilterIsAppliedToCountsAndPagesInSql() {
        var jdbc = mock(JdbcTemplate.class); var role = mock(RoleAuthorizationService.class); when(role.currentUserCompanyIdOrNull()).thenReturn(10L);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        assertEquals(200, new ReportDatasetController(jdbc, role).list(2, 50).getStatusCode().value());
        verify(jdbc).queryForObject(contains("AND companyId=?"), eq(Long.class), eq(10L));
        verify(jdbc).queryForList(contains("AND companyId=?"), eq(10L), eq(50), eq(50));
    }
}

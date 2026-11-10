package com.network.monitoring.controller;

import com.network.monitoring.entity.UploadHistory;
import com.network.monitoring.repository.UploadHistoryRepository;
import com.network.monitoring.service.DailyAnalysisService;
import com.network.monitoring.service.RoleAuthorizationService;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DailyAnalysisControllerTest {
    @Test void companyReadAccessIsScopedAndMutationRequiresAdmin() {
        var analysis = mock(DailyAnalysisService.class);
        var role = mock(RoleAuthorizationService.class);
        var history = mock(UploadHistoryRepository.class);
        var target = new UploadHistory(); target.setCompanyId(10L);
        when(history.findById(1L)).thenReturn(Optional.of(target));
        when(role.currentUserCompanyIdOrNull()).thenReturn(20L);
        var controller = new DailyAnalysisController(analysis, role, history);
        assertEquals(403, controller.profile(1).getStatusCode().value());
        verifyNoInteractions(analysis);
        when(role.currentUserCompanyIdOrNull()).thenReturn(10L);
        when(analysis.profile(1)).thenReturn(Map.of("status", "CONFIGURATION_REQUIRED"));
        assertEquals(200, controller.profile(1).getStatusCode().value());
        doThrow(new IllegalArgumentException("Admin required")).when(role).requireSuperAdmin();
        assertEquals(403, controller.build(1, null).getStatusCode().value());
    }
}

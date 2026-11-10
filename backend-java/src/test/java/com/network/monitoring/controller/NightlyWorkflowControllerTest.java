package com.network.monitoring.controller;

import com.network.monitoring.entity.UploadHistory;
import com.network.monitoring.repository.UploadHistoryRepository;
import com.network.monitoring.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NightlyWorkflowControllerTest {
    @Test void historyIsCompanyScopedAndConfigurationRequiresAdministrator() {
        var nightly=mock(NightlyWorkflowService.class);var role=mock(RoleAuthorizationService.class);var history=mock(UploadHistoryRepository.class);
        var dataset=new UploadHistory();dataset.setCompanyId(10L);when(history.findById(500L)).thenReturn(Optional.of(dataset));when(nightly.fileId(1)).thenReturn(500L);when(role.currentUserCompanyIdOrNull()).thenReturn(20L);
        var controller=new NightlyWorkflowController(nightly,role,history);
        assertEquals(403,controller.list(500,20).getStatusCode().value());verify(nightly,never()).list(500,20);
        when(role.currentUserCompanyIdOrNull()).thenReturn(10L);when(nightly.list(500,20)).thenReturn(List.of());assertEquals(200,controller.list(500,20).getStatusCode().value());
        doThrow(new IllegalArgumentException("Admin required")).when(role).requireSuperAdmin();assertEquals(403,controller.profile(500).getStatusCode().value());assertEquals(403,controller.create(500,"2026-11-04",false).getStatusCode().value());
    }
}

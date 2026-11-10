package com.network.monitoring.controller;

import com.network.monitoring.entity.UploadHistory;
import com.network.monitoring.repository.UploadHistoryRepository;
import com.network.monitoring.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportMailControllerTest {
    @Test void mailHistoryAndCapturedContentAreScopedAndSendRequiresAdministrator() {
        var mail = mock(ReportMailOutboxService.class); var reports = mock(CommonReportService.class);
        var role = mock(RoleAuthorizationService.class); var history = mock(UploadHistoryRepository.class);
        var dataset = new UploadHistory(); dataset.setCompanyId(10L); when(history.findById(500L)).thenReturn(Optional.of(dataset));
        when(mail.fileId(1)).thenReturn(500L); when(role.currentUserCompanyIdOrNull()).thenReturn(20L);
        var controller = new ReportMailController(mail, reports, role, history);
        assertEquals(403, controller.get(1).getStatusCode().value()); assertEquals(403, controller.captured(1).getStatusCode().value());
        verify(mail, never()).get(1);
        doThrow(new IllegalArgumentException("Admin required")).when(role).requireSuperAdmin();
        assertEquals(403, controller.submit(1, null).getStatusCode().value()); verifyNoInteractions(reports);
        when(role.currentUserCompanyIdOrNull()).thenReturn(10L); when(mail.get(1)).thenReturn(Map.of("status", "CAPTURED"));
        assertEquals(200, controller.get(1).getStatusCode().value());
    }
}

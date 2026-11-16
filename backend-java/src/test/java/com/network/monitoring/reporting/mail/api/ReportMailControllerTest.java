package com.network.monitoring.reporting.mail.api;

import com.network.monitoring.identity.service.RoleAuthorizationService;
import com.network.monitoring.ingestion.entity.UploadHistory;
import com.network.monitoring.ingestion.repository.UploadHistoryRepository;
import com.network.monitoring.reporting.mail.service.ReportMailOutboxService;
import com.network.monitoring.reporting.service.CommonReportService;
import java.util.*;
import org.junit.jupiter.api.Test;
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

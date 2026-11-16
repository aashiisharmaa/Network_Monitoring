package com.network.monitoring.reporting.api;

import com.network.monitoring.identity.service.RoleAuthorizationService;
import com.network.monitoring.ingestion.entity.UploadHistory;
import com.network.monitoring.ingestion.repository.UploadHistoryRepository;
import com.network.monitoring.reporting.service.CommonReportService;
import com.network.monitoring.reporting.service.DailyAnalysisService;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommonReportControllerTest {
    @Test void reportsAndDownloadsAreCompanyScopedAndBuildRequiresAdmin() {
        var reports = mock(CommonReportService.class); var analysis = mock(DailyAnalysisService.class);
        var role = mock(RoleAuthorizationService.class); var history = mock(UploadHistoryRepository.class);
        var target = new UploadHistory(); target.setCompanyId(10L);
        when(history.findById(500L)).thenReturn(Optional.of(target)); when(reports.fileId(1)).thenReturn(500L);
        when(role.currentUserCompanyIdOrNull()).thenReturn(20L);
        var controller = new CommonReportController(reports, analysis, role, history);
        assertEquals(403, controller.get(1).getStatusCode().value()); assertEquals(403, controller.download(1, "pdf").getStatusCode().value());
        verify(reports, never()).get(1);
        when(role.currentUserCompanyIdOrNull()).thenReturn(10L); when(reports.get(1)).thenReturn(Map.of("status", "PARTIAL"));
        assertEquals(200, controller.get(1).getStatusCode().value());
        doThrow(new IllegalArgumentException("Admin required")).when(role).requireSuperAdmin();
        assertEquals(403, controller.create(1, null).getStatusCode().value()); verifyNoInteractions(analysis);
    }
}

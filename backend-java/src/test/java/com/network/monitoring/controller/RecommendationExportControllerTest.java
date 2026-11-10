package com.network.monitoring.controller;
import com.network.monitoring.service.*;
import com.network.monitoring.repository.UploadHistoryRepository;
import com.network.monitoring.entity.UploadHistory;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RecommendationExportControllerTest{
    @Test void exportCreationStatusAndDownloadsAreScopedToDatasetCompany(){
        var service=mock(RecommendationExportService.class);var role=mock(RoleAuthorizationService.class);var history=mock(UploadHistoryRepository.class);var file=new UploadHistory();file.setCompanyId(10L);when(history.findById(500L)).thenReturn(Optional.of(file));when(role.currentUserCompanyIdOrNull()).thenReturn(20L);when(service.fileId(1)).thenReturn(500L);
        var controller=new RecommendationExportController(service,role,history);assertEquals(403,controller.start(500,Map.of()).getStatusCode().value());assertEquals(403,controller.get(1).getStatusCode().value());assertEquals(403,controller.download(1).getStatusCode().value());verify(service,never()).start(500,Map.of());
        when(role.currentUserCompanyIdOrNull()).thenReturn(10L);when(service.get(1)).thenReturn(Map.of("status","QUEUED"));assertEquals(200,controller.get(1).getStatusCode().value());
    }
}

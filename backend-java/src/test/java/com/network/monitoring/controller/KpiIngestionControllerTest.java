package com.network.monitoring.controller;

import com.network.monitoring.service.KpiIngestionService;
import com.network.monitoring.service.RoleAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KpiIngestionControllerTest {
    @Test void unauthorizedUserCannotInspectOrImportAndBadConfigurationIs400() {
        var ingestion = mock(KpiIngestionService.class);
        var authorization = mock(RoleAuthorizationService.class);
        var controller = new KpiIngestionController(ingestion, authorization);
        doThrow(new IllegalArgumentException("Super admin required")).when(authorization).requireSuperAdmin();
        assertEquals(403, controller.list(20).getStatusCode().value());
        assertEquals(403, controller.retry(1).getStatusCode().value());
        verifyNoInteractions(ingestion);
        doNothing().when(authorization).requireSuperAdmin();
        assertEquals(400, controller.start(new MockMultipartFile("file", new byte[]{1}),
                1, "not-json", "common", null).getStatusCode().value());
        verifyNoInteractions(ingestion);
    }
}

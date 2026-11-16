package com.network.monitoring.alarm.api;

import com.network.monitoring.alarm.ingestion.AlarmImportPreviewService;
import com.network.monitoring.identity.service.RoleAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AlarmImportPreviewControllerTest {
    @Test void capacityRejectionProvidesRetryAfter() throws Exception {
        var service = mock(AlarmImportPreviewService.class);
        when(service.preview(any(), any(), any(), any(), any())).thenThrow(new AlarmImportPreviewService.PreviewBusyException());
        var mvc = MockMvcBuilders.standaloneSetup(new AlarmImportPreviewController(service, mock(RoleAuthorizationService.class))).build();
        mvc.perform(multipart("/api/v1/alarm-ingestion/preview")
                .file(new MockMultipartFile("file", "alarms.csv", "text/csv", new byte[]{1}))
                .param("sourceTimezone", "UTC")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"));
    }

    @Test void deniedUserCannotParseAFile() throws Exception {
        var service = mock(AlarmImportPreviewService.class);
        var role = mock(RoleAuthorizationService.class);
        doThrow(new IllegalArgumentException("forbidden")).when(role).requireSuperAdmin();
        var mvc = MockMvcBuilders.standaloneSetup(new AlarmImportPreviewController(service, role)).build();
        mvc.perform(multipart("/api/v1/alarm-ingestion/preview")
                .file(new MockMultipartFile("file", "alarms.csv", "text/csv", new byte[]{1}))
                .param("sourceTimezone", "UTC")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void servesRealValidationAndReturnsBadRequestForInvalidSchema() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new AlarmImportPreviewController(
                new AlarmImportPreviewService(), mock(RoleAuthorizationService.class))).build();
        mvc.perform(multipart("/api/v1/alarm-ingestion/preview")
                .file(new MockMultipartFile("file", "alarms.csv", "text/csv",
                        "Circle,Name,Alarm Text,Alarm Time\nMU,node,Alarm,2026-11-01 09:30:00\n".getBytes()))
                .param("sourceTimezone", "Asia/Kolkata").param("oem", "Nokia").param("technology", "4G"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.validRows").value(1))
                .andExpect(jsonPath("$.data.sample[0].customerClassification").value("UNMAPPED"));
        mvc.perform(multipart("/api/v1/alarm-ingestion/preview")
                .file(new MockMultipartFile("file", "alarms.csv", "text/csv", "foo\nbar\n".getBytes()))
                .param("sourceTimezone", "UTC")).andExpect(status().isBadRequest());
    }
}

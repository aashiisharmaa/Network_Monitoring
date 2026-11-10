package com.network.monitoring.controller;

import com.network.monitoring.service.JobCapacityException;
import com.network.monitoring.service.RecommendationJobService;
import com.network.monitoring.service.UploadJobService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobCapacityControllerTest {
    @Test
    void rcaAndUploadReturn503WithRetryAfterAndPersistentJobId() throws Exception {
        var rca = mock(RecommendationJobService.class);
        when(rca.startJob(eq(1L), anyMap())).thenThrow(new JobCapacityException(12L, "Busy", null));
        var rcaResponse = new RecommendationController(null, rca, null).startRecommendationJob(1L, Map.of());
        assertEquals(503, rcaResponse.getStatusCode().value());
        assertEquals("30", rcaResponse.getHeaders().getFirst("Retry-After"));
        assertEquals(12L, rcaResponse.getBody().get("jobId"));
        assertEquals("JOB_CAPACITY", rcaResponse.getBody().get("code"));

        var upload = mock(UploadJobService.class);
        when(upload.startKpiUploadJob(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new JobCapacityException(13L, "Busy", null));
        var controller = new UploadController(null, null, upload);
        var uploadResponse = controller.uploadKpiData(new MockMultipartFile("file", new byte[]{1}),
                null, null, null, null, null, null, false);
        assertEquals(503, uploadResponse.getStatusCode().value());
        assertEquals(13L, uploadResponse.getBody().get("jobId"));
        when(upload.retryJob(13L)).thenThrow(new JobCapacityException(13L, "Busy", null));
        assertEquals(503, controller.retryUploadJob(13L).getStatusCode().value());
    }
}

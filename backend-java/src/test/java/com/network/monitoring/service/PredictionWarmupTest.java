package com.network.monitoring.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PredictionWarmupTest {
    @Test
    void repeatedWarmupForSameFileIsCoalescedWhilePending() {
        TaskExecutor executor = mock(TaskExecutor.class);
        var service = new SitePredictionService(null, null, null, null, mock(JdbcTemplate.class), executor);
        service.warmFileAsync(1L);
        service.warmFileAsync(1L);
        service.warmFileAsync(null);
        verify(executor, times(1)).execute(any());
    }

    @Test
    void fullOptionalWarmupQueueDoesNotFailUploadOrPermanentlyMarkFilePending() {
        TaskExecutor executor = mock(TaskExecutor.class);
        doThrow(new RejectedExecutionException("full")).when(executor).execute(any());
        var service = new SitePredictionService(null, null, null, null, mock(JdbcTemplate.class), executor);
        assertDoesNotThrow(() -> service.warmFileAsync(1L));
        assertDoesNotThrow(() -> service.warmFileAsync(1L));
        verify(executor, times(2)).execute(any());
    }
}

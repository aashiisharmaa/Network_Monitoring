package com.network.monitoring.kpi.service;

import com.network.monitoring.ingestion.entity.UploadHistory;
import com.network.monitoring.ingestion.repository.UploadHistoryRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

class DashboardServiceTest {
    @Test
    void latestUploadUsesLimitedQueryAndPreservesExplicitFileLookup() {
        var repository = mock(UploadHistoryRepository.class);
        var latest = new UploadHistory();
        var explicit = new UploadHistory();
        when(repository.findFirstByOrderByCreatedAtDesc()).thenReturn(Optional.of(latest));
        when(repository.findById(17L)).thenReturn(Optional.of(explicit));
        var service = new DashboardService(null, repository, null, null, null);
        assertSame(latest, service.getLatestUpload(null));
        assertSame(explicit, service.getLatestUpload(17L));
        verify(repository).findFirstByOrderByCreatedAtDesc();
        verify(repository).findById(17L);
        verifyNoMoreInteractions(repository);
    }
}

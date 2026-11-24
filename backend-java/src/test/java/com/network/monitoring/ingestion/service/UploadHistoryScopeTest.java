package com.network.monitoring.ingestion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.network.monitoring.identity.service.RoleAuthorizationService;
import com.network.monitoring.ingestion.entity.UploadHistory;
import com.network.monitoring.ingestion.repository.UploadHistoryRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class UploadHistoryScopeTest {
    private final UploadHistoryRepository repository = mock(UploadHistoryRepository.class);
    private final RoleAuthorizationService authorization = mock(RoleAuthorizationService.class);
    private final UploadService service = new UploadService(null, null, repository, null, null,
            null, null, null, null, null, null, null, authorization, null, null, null);

    @Test
    void companyUserCannotChooseAnotherCompany() {
        when(authorization.currentUserCompanyIdOrNull()).thenReturn(1L);
        UploadHistory own = new UploadHistory();
        own.setId(106L);
        when(repository.findAllByCompanyId(eq(1L), any(Sort.class))).thenReturn(List.of(own));
        List<?> data = (List<?>) service.getUploadHistorySlim(2L).get("data");
        assertEquals(106L, ((Map<?, ?>) data.get(0)).get("id"));
        verify(repository, never()).findAllByCompanyId(eq(2L), any(Sort.class));
    }

    @Test
    void companyUserWithoutCompanySeesNothing() {
        when(authorization.currentUserCompanyIdOrNull()).thenReturn(null);
        assertEquals(List.of(), service.getUploadHistorySlim(null).get("data"));
        verifyNoInteractions(repository);
    }

    @Test
    void superAdminCanSeeAllOrSelectACompany() {
        when(authorization.isCurrentUserSuperAdmin()).thenReturn(true);
        service.getUploadHistorySlim(null);
        service.getUploadHistorySlim(1L);
        verify(repository).findAll(any(Sort.class));
        verify(repository).findAllByCompanyId(eq(1L), any(Sort.class));
    }
}

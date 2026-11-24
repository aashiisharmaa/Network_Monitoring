package com.network.monitoring.alarm.service;

import com.network.monitoring.alarm.exception.AlarmConfigurationException;
import com.network.monitoring.alarm.repository.AlarmReportRepository;
import com.network.monitoring.alarm.security.AlarmTenantAccess;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AlarmReportBatchTest {
    private final AlarmReportRepository repository=mock(AlarmReportRepository.class);
    private final AlarmReportService reports=new AlarmReportService(mock(AlarmWorkflowService.class),mock(AlarmTenantAccess.class),repository,mock(AlarmOperationalService.class));
    private Map<String,Object> source(long id) {
        return Map.of("id",id,"feed_id",id,"status","SUCCEEDED","snapshot_at",Timestamp.from(Instant.parse("2026-11-01T06:00:00Z")));
    }
    @Test void thirtySourcesUseOneMetadataReadAndOneObservationRead() {
        List<Long> ids=java.util.stream.LongStream.rangeClosed(1,30).boxed().toList();
        when(repository.sources(100,ids)).thenReturn(ids.stream().map(this::source).toList());
        when(repository.observations(100,ids,null,true)).thenReturn(List.of());
        assertTrue(reports.cohortPdf(100,ids,null).length>0);
        verify(repository).sources(100,ids);verify(repository).observations(100,ids,null,true);
        verifyNoMoreInteractions(repository);
    }
    @Test void missingTenantSourceFailsBeforeReadingObservations() {
        when(repository.sources(100,List.of(1L,2L))).thenReturn(List.of(source(1)));
        assertThrows(AlarmConfigurationException.class,()->reports.cohortPdf(100,List.of(1L,2L),null));
        verify(repository).sources(100,List.of(1L,2L));verifyNoMoreInteractions(repository);
    }
    @Test void observationBudgetsStillRejectOversizedCohortsAndSources() {
        var ids=List.of(1L);when(repository.sources(100,ids)).thenReturn(List.of(source(1)));
        when(repository.observations(100,ids,null,true)).thenReturn(Collections.nCopies(50001,Map.of("run_id",1L)));
        assertThrows(AlarmConfigurationException.class,()->reports.cohortPdf(100,ids,null));
        when(repository.observations(100,ids,null,true)).thenReturn(Collections.nCopies(5001,Map.of("run_id",1L)));
        assertThrows(IllegalStateException.class,()->reports.cohortPdf(100,ids,null));
    }
}

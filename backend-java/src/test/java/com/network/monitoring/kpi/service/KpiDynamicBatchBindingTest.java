package com.network.monitoring.kpi.service;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class KpiDynamicBatchBindingTest {
    @Test void globalRecordsBindNullCompanyWithExplicitSqlTypesForBothTables() {
        var jdbc=mock(JdbcTemplate.class);var service=new KpiDynamicDataService(jdbc);clearInvocations(jdbc);
        service.appendRecords(500,null,List.of(Map.of("date","2026-11-19","cellName","DEMO_CELL","metrics",Map.of("rrc",99d))));
        var types=ArgumentCaptor.forClass(int[].class);
        verify(jdbc,times(2)).batchUpdate(anyString(),anyList(),types.capture());
        assertEquals(15,types.getAllValues().get(0).length);assertEquals(13,types.getAllValues().get(1).length);
        for(int[] bindings:types.getAllValues()) assertEquals(java.sql.Types.BIGINT,bindings[1]);
        verify(jdbc,never()).batchUpdate(anyString(),anyList());
    }
}

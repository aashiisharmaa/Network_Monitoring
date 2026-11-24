package com.network.monitoring.alarm.service;

import com.network.monitoring.alarm.repository.*;
import com.network.monitoring.alarm.security.AlarmTenantAccess;
import com.network.monitoring.alarm.storage.AlarmInputFolders;
import com.network.monitoring.platform.mail.config.ReportMailSettings;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AlarmReadinessTest {
    @Test void missingTablesReturnReadinessFactsWithoutQueryingMissingFeatureTablesOrSendingMail() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),eq(String.class))).thenReturn(List.of("companies"));
        var access=mock(AlarmTenantAccess.class);
        var service=new AlarmReadinessService(access,new AlarmReadinessRepository(jdbc),mock(AlarmWorkflowService.class),
                mock(AlarmOperationalService.class),new AlarmInputFolders(""),new ReportMailSettings());
        var result=service.inspect(100);
        assertEquals(java.util.Map.of("008",false,"009",false,"010",false),result.get("schema"));
        assertNull(result.get("activeFeeds"));assertEquals(false,result.get("smtpOutboundEnabled"));
        verify(access).require(100,false);verify(jdbc).queryForList(anyString(),eq(String.class));verifyNoMoreInteractions(jdbc);
    }
}

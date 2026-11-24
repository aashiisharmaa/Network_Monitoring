package com.network.monitoring.alarm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.monitoring.alarm.dto.AlarmOperational.*;
import com.network.monitoring.alarm.dto.AlarmAutomation.Envelope;
import com.network.monitoring.alarm.repository.*;
import com.network.monitoring.alarm.security.AlarmTenantAccess;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AlarmOperationalContractTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @Test void absentCountsRemainUnknownAndExplicitZerosAreRetained() throws Exception {
        var input=json.readValue("{\"circleId\":10,\"technology\":\"4G\",\"ms2Pending\":0}",CircleInput.class);
        assertEquals(0,input.ms2Pending());assertNull(input.lrPending());assertTrue(input.sites().isEmpty());
        assertEquals(0,json.readTree(json.writeValueAsString(input)).get("ms2Pending").asInt());
        assertTrue(json.readTree(json.writeValueAsString(input)).get("lrPending").isNull());
    }
    @Test void decimalsNumericStringsAndOversizedIntegersCannotBeSilentlyCoerced() {
        for(String value:List.of("1.5","\"2\"","2147483648","true"))
            assertThrows(Exception.class,()->json.readValue("{\"circleId\":10,\"technology\":\"4G\",\"ms2Pending\":"+value+"}",CircleInput.class));
        assertThrows(Exception.class,()->json.readValue("{\"circleId\":10.5,\"technology\":\"4G\"}",CircleInput.class));
    }
    @Test void disabledFeatureDoesNotReadOptionalSchemaAndOldOutboxHasNoImplicitContext() throws Exception {
        var access=mock(AlarmTenantAccess.class);var repo=mock(AlarmOperationalRepository.class);
        var service=new AlarmOperationalService(access,mock(AlarmConfigurationRepository.class),repo,json,mock(PlatformTransactionManager.class),false);
        assertNull(((Snapshot)service.current(100,"2026-11-17T00:00:00Z").get("snapshot")).id());
        assertNull(service.pinned(100,null).id());assertTrue(service.history(100,50).isEmpty());
        assertThrows(Exception.class,()->service.capture(100,new Capture("2026-11-17T00:00:00Z",0,"Reference",List.of())));
        verifyNoInteractions(repo);
        var legacy=json.readValue("{\"subject\":\"Old\",\"recipients\":[],\"mode\":\"CAPTURE\",\"circleId\":null,\"identities\":null,\"sourceRunIds\":[1]}",Envelope.class);
        assertNull(legacy.operationalSnapshotId());
    }
}

package com.network.monitoring.alarm.architecture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.monitoring.alarm.dto.AlarmAutomation;
import com.network.monitoring.alarm.dto.AlarmModels;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Old stored snapshots must survive a Java package move without a data migration. */
class AlarmPersistedContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test void frozenSnapshotRemainsReadableWithSameJsonShape() throws Exception {
        String previousJson = """
            {"feed":{"id":20,"companyId":100,"sourceKey":"oss-test","name":"Synthetic source",
            "oem":"NOKIA","technology":"4G","fileFormat":"CSV","sheet":null,
            "sourceTimezone":"Asia/Kolkata","dumpType":"SNAPSHOT","circleId":10,"active":true,"version":1},
            "circles":{"MU":{"circleId":10,"circleName":"Synthetic Circle","zoneName":"West"}},
            "catalog":[{"id":1,"oem":"NOKIA","technology":"4G","matchField":"CODE","matchValue":"100",
            "classification":"MAJOR","included":true,"serviceAffecting":false,"siteDown":false}],
            "sites":[{"id":1,"circleId":10,"objectKey":"NODE-A","siteCode":"SITE-A"}],
            "automationJson":"{}","adapterVersion":"oss-snapshot-v1"}
            """;
        var frozen = mapper.readValue(previousJson, AlarmModels.Frozen.class);
        assertEquals("oss-snapshot-v1", frozen.adapterVersion());
        assertEquals(10, frozen.circles().get("MU").circleId());
        assertEquals(mapper.readTree(previousJson), mapper.readTree(mapper.writeValueAsString(frozen)));
    }

    @Test void olderWatchWithoutTimeWindowKeepsDefaults() throws Exception {
        var settings = mapper.readValue("""
            {"version":1,"enabled":false,"freshnessMinutes":60,"reports":[],"escalations":[],
            "watches":[{"feedId":20,"folder":"incoming","timestampSource":"SIDECAR"}]}
            """, AlarmAutomation.Settings.class);
        var watch = settings.watches().get(0);
        assertNull(watch.windowStart());
        assertNull(watch.windowEnd());
        assertNull(watch.timezone());
        assertTrue(com.network.monitoring.alarm.scheduling.AlarmSchedulePolicy.withinWindow(watch, java.time.Instant.EPOCH));
    }
}

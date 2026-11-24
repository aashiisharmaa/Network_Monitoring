package com.network.monitoring.ingestion.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KpiUploadDateTest {
    private final UploadService uploads = mock(UploadService.class, CALLS_REAL_METHODS);

    @Test void csvHourlyDatesReachCanonicalCacheAndLegacyRows() {
        String timestamp = "2026-11-19 23:00:00";
        assertEquals(LocalDateTime.of(2026, 11, 19, 23, 0),
                ReflectionTestUtils.invokeMethod(uploads, "parseDateSafe", timestamp));
        Map<?, ?> payload = ReflectionTestUtils.invokeMethod(uploads, "buildKpiDynamicCachePayload",
                List.of(Map.of("date", timestamp, "cell_name", "DEMO_L18_A", "dl_prb_utilization_rate", 96)));
        var record = (Map<?, ?>) ((List<?>) payload.get("records")).get(0);
        assertEquals("2026-11-19", record.get("date"));
        assertEquals(timestamp, ((Map<?, ?>) record.get("dimensions")).get("date"));
    }

    @Test void dateOnlyAndIsoDatesRemainSupportedAndInvalidTimestampsStayInvalid() {
        assertEquals(LocalDateTime.of(2026, 11, 19, 0, 0),
                ReflectionTestUtils.invokeMethod(uploads, "parseDateSafe", "2026-11-19"));
        assertEquals(LocalDateTime.of(2026, 11, 19, 12, 30),
                ReflectionTestUtils.invokeMethod(uploads, "parseDateSafe", "2026-11-19T12:30:00"));
        for (String invalid : List.of("2026-02-30 12:00:00", "2026-11-19 25:00:00", "bad")) {
            assertNull(ReflectionTestUtils.invokeMethod(uploads, "parseDateSafe", invalid));
        }
    }
}

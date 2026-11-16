package com.network.monitoring.alarm.workflow;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class AlarmScheduleTest {
    @Test void agreedOneToTwoAmWindowUsesExplicitTimezoneAndExclusiveDeadline() {
        var watch=new AlarmAutomationService.Watch(1,"feed","SIDECAR","01:00","02:00","Asia/Kolkata");
        assertFalse(AlarmAutomationService.withinWindow(watch,Instant.parse("2026-11-01T19:29:59Z")));
        assertTrue(AlarmAutomationService.withinWindow(watch,Instant.parse("2026-11-01T19:30:00Z")));
        assertTrue(AlarmAutomationService.withinWindow(watch,Instant.parse("2026-11-01T20:29:59Z")));
        assertFalse(AlarmAutomationService.withinWindow(watch,Instant.parse("2026-11-01T20:30:00Z")));
    }
    @Test void midnightCrossingWindowAndUnscheduledEventModeAreDistinct() {
        var watch=new AlarmAutomationService.Watch(1,"feed","SIDECAR","23:00","02:00","UTC");
        assertTrue(AlarmAutomationService.withinWindow(watch,Instant.parse("2026-11-01T23:30:00Z")));
        assertTrue(AlarmAutomationService.withinWindow(watch,Instant.parse("2026-11-02T00:30:00Z")));
        assertFalse(AlarmAutomationService.withinWindow(watch,Instant.parse("2026-11-02T12:00:00Z")));
        assertTrue(AlarmAutomationService.withinWindow(new AlarmAutomationService.Watch(1,"feed","SIDECAR"),Instant.now()));
    }
}

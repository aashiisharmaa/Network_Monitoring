package com.network.monitoring.alarm.scheduling;

import com.network.monitoring.alarm.dto.AlarmAutomation;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AlarmScheduleTest {
    @Test void agreedOneToTwoAmWindowUsesExplicitTimezoneAndExclusiveDeadline() {
        var watch=new AlarmAutomation.Watch(1,"feed","SIDECAR","01:00","02:00","Asia/Kolkata");
        assertFalse(AlarmSchedulePolicy.withinWindow(watch,Instant.parse("2026-11-01T19:29:59Z")));
        assertTrue(AlarmSchedulePolicy.withinWindow(watch,Instant.parse("2026-11-01T19:30:00Z")));
        assertTrue(AlarmSchedulePolicy.withinWindow(watch,Instant.parse("2026-11-01T20:29:59Z")));
        assertFalse(AlarmSchedulePolicy.withinWindow(watch,Instant.parse("2026-11-01T20:30:00Z")));
    }
    @Test void midnightCrossingWindowAndUnscheduledEventModeAreDistinct() {
        var watch=new AlarmAutomation.Watch(1,"feed","SIDECAR","23:00","02:00","UTC");
        assertTrue(AlarmSchedulePolicy.withinWindow(watch,Instant.parse("2026-11-01T23:30:00Z")));
        assertTrue(AlarmSchedulePolicy.withinWindow(watch,Instant.parse("2026-11-02T00:30:00Z")));
        assertFalse(AlarmSchedulePolicy.withinWindow(watch,Instant.parse("2026-11-02T12:00:00Z")));
        assertTrue(AlarmSchedulePolicy.withinWindow(new AlarmAutomation.Watch(1,"feed","SIDECAR"),Instant.now()));
    }
}

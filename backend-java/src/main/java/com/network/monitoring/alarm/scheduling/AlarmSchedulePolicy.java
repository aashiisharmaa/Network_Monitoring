package com.network.monitoring.alarm.scheduling;
import com.network.monitoring.alarm.dto.AlarmAutomation.Watch;
import java.time.*;
/** Local-time scheduling policy, independently testable without Spring/DB/mail. */
public final class AlarmSchedulePolicy {
    private AlarmSchedulePolicy() {}
    public static boolean hasWindow(Watch watch) {return watch.windowStart()!=null&&!watch.windowStart().isBlank()||watch.windowEnd()!=null&&!watch.windowEnd().isBlank()||watch.timezone()!=null&&!watch.timezone().isBlank();}
    public static boolean withinWindow(Watch watch,Instant at) {
        if(!hasWindow(watch)) return true;
        LocalTime now=at.atZone(ZoneId.of(watch.timezone())).toLocalTime();
        LocalTime start=LocalTime.parse(watch.windowStart()),end=LocalTime.parse(watch.windowEnd());
        return start.isBefore(end)?!now.isBefore(start)&&now.isBefore(end):!now.isBefore(start)||now.isBefore(end);
    }
}

package com.network.monitoring.alarm.dto;

import java.util.*;

/** Stable JSON records, independent of service implementations. */
public final class AlarmAutomation {
    private AlarmAutomation() {}
    public record Route(String key, Long circleId, List<String> recipients, String mode) {}
    public record Policy(String key, Long circleId, String classification, int minAgeHours, List<String> recipients, String mode) {}
    public record Watch(long feedId, String folder, String timestampSource, String windowStart, String windowEnd, String timezone) {
        public Watch(long feedId,String folder,String timestampSource) {this(feedId,folder,timestampSource,null,null,null);}
    }
    public record Settings(int version, boolean enabled, int freshnessMinutes, List<Route> reports, List<Policy> escalations, List<Watch> watches) {}
    public record Envelope(String subject, List<String> recipients, String mode, Long circleId, Set<String> identities, List<Long> sourceRunIds, Long operationalSnapshotId) {
        public Envelope(String subject,List<String> recipients,String mode,Long circleId,Set<String> identities,List<Long> sourceRunIds) {
            this(subject,recipients,mode,circleId,identities,sourceRunIds,null);
        }
    }
}

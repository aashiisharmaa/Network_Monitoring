package com.network.monitoring.alarm.dto;

import java.util.List;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.network.monitoring.alarm.support.StrictNumbers;

/** Explicit customer-reported inputs. Null counts mean unknown, while zero is a reported zero. */
public final class AlarmOperational {
    private AlarmOperational() {}
    public record SiteUpdate(String siteCode, String state, String observedAt, Boolean lrPending,
                             String owner, String recoveryReason, String responsibility) {}
    public record CircleInput(@JsonDeserialize(using=StrictNumbers.LongValue.class) long circleId, String technology,
                              @JsonDeserialize(using=StrictNumbers.IntegerValue.class) Integer ms2Pending,
                              @JsonDeserialize(using=StrictNumbers.IntegerValue.class) Integer lrPending,
                              @JsonDeserialize(using=StrictNumbers.IntegerValue.class) Integer siteInventoryTotal,
                              @JsonDeserialize(using=StrictNumbers.IntegerValue.class) Integer siteUp,
                              @JsonDeserialize(using=StrictNumbers.IntegerValue.class) Integer gpsAlarms,
                              @JsonDeserialize(using=StrictNumbers.IntegerValue.class) Integer vswrAlarms,
                              String zoneOwner, String circleOwner, String majorReason, List<SiteUpdate> sites) {
        public CircleInput { sites=sites==null?List.of():List.copyOf(sites); }
    }
    public record Capture(String effectiveAt,@JsonDeserialize(using=StrictNumbers.IntegerValue.class) int expectedRevision, String sourceReference, List<CircleInput> circles,
                          @JsonDeserialize(using=StrictNumbers.LongValue.class) Long expectedBaseSnapshotId) {
        public Capture(String effectiveAt,int expectedRevision,String sourceReference,List<CircleInput> circles) {
            this(effectiveAt,expectedRevision,sourceReference,circles,null);
        }
    }
    public record CircleContext(String circleName, String zoneName, CircleInput input) {}
    public record Snapshot(Long id, String effectiveAt, int revision, Long supersedesId, String sourceReference,
                           String checksum, long createdBy, String createdAt, List<CircleContext> circles,Long basedOnId) {
        public Snapshot(Long id,String effectiveAt,int revision,Long supersedesId,String sourceReference,String checksum,long createdBy,String createdAt,List<CircleContext> circles) {
            this(id,effectiveAt,revision,supersedesId,sourceReference,checksum,createdBy,createdAt,circles,null);
        }
        public static Snapshot none() { return new Snapshot(null,null,0,null,null,null,0,null,List.of()); }
    }
}

package com.network.monitoring.alarm.reporting;

import com.network.monitoring.alarm.dto.AlarmOperational.*;
import com.network.monitoring.alarm.dto.AlarmReport.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/** Report joins use persisted circle IDs/RAT; customer-reported metrics remain separate from OSS counts. */
public final class AlarmOperationalContext {
    private AlarmOperationalContext() {}
    public record Values(Long ms2Pending,Long lrPending,Long inventory,Long siteUp,Long gps,Long vswr,
                         String zoneOwner,String circleOwner,String majorReason) {}

    public static Snapshot scope(Snapshot snapshot,Long circle) {
        if(circle==null||snapshot.id()==null) return snapshot;
        return new Snapshot(snapshot.id(),snapshot.effectiveAt(),snapshot.revision(),snapshot.supersedesId(),snapshot.sourceReference(),
                snapshot.checksum(),snapshot.createdBy(),snapshot.createdAt(),snapshot.circles().stream().filter(c->c.input().circleId()==circle).toList(),snapshot.basedOnId());
    }
    public static Values values(Snapshot snapshot,Group group,Summary summary) {
        List<CircleInput> inputs=new ArrayList<>();
        for(Group expected:summary.circles()) {
            if(!Objects.equals(expected.technology(),group.technology())||!Objects.equals(expected.zone(),group.zone())
                    ||group.circleId()!=null&&!Objects.equals(group.circleId(),expected.circleId())) continue;
            var input=find(snapshot,expected.circleId(),expected.technology(),expected.zone());
            if(input==null) return unknown();
            inputs.add(input);
        }
        if(inputs.isEmpty()) return unknown();
        return new Values(sum(inputs,CircleInput::ms2Pending),sum(inputs,CircleInput::lrPending),sum(inputs,CircleInput::siteInventoryTotal),
                sum(inputs,CircleInput::siteUp),sum(inputs,CircleInput::gpsAlarms),sum(inputs,CircleInput::vswrAlarms),
                text(inputs,CircleInput::zoneOwner),group.circleId()==null?null:text(inputs,CircleInput::circleOwner),text(inputs,CircleInput::majorReason));
    }
    public static Summary summary(Summary base,Snapshot snapshot) {
        if(snapshot.id()==null) return base;
        List<String> pending=new ArrayList<>();
        if(!complete(base,snapshot,i->i.ms2Pending()!=null)) pending.add("MS2 pending 2G/4G");
        if(!complete(base,snapshot,i->i.lrPending()!=null)) pending.add("LR pending status/count");
        if(!complete(base,snapshot,i->i.siteInventoryTotal()!=null&&i.siteUp()!=null)) pending.add("Reported site inventory/up counts");
        if(!complete(base,snapshot,i->i.zoneOwner()!=null&&i.circleOwner()!=null&&i.majorReason()!=null)) pending.add("Zone/circle owners and major reasons");
        if(!complete(base,snapshot,i->!i.sites().isEmpty())) pending.add("Site recovery and circle remarks");
        if(!complete(base,snapshot,i->i.gpsAlarms()!=null&&i.vswrAlarms()!=null)) pending.add("GPS/VSWR reported counts");
        pending.add("Approved responsibility category rules");
        List<String> notes=new ArrayList<>(base.notes());
        notes.add("Operational counts are customer-reported inputs, separate from detected OSS observations. Null is unknown; zero is an explicit reported zero.");
        notes.add("Zone operational aggregates cover only circles/RATs represented by the selected OSS snapshots; incomplete represented inputs remain blank. Other reported circles stay in Operational Inputs.");
        notes.add("Operational inputs whose captured zone differs from the OSS snapshot are shown separately, without merging their counts or owners into that source summary.");
        notes.add("Operational snapshot #"+snapshot.id()+", effective "+snapshot.effectiveAt()+", revision "+snapshot.revision()+". Reference: "+snapshot.sourceReference());
        return new Summary(base.contractVersion(),base.zones(),base.circles(),base.observedRecords(),base.includedRecords(),base.unmappedCatalogue(),base.knownDownSites(),List.copyOf(pending),List.copyOf(notes));
    }
    private static CircleInput find(Snapshot snapshot,Long circle,String rat,String zone) {
        return snapshot.circles().stream().filter(context->Objects.equals(context.zoneName(),zone)).map(CircleContext::input).filter(i->Objects.equals(i.circleId(),circle)&&i.technology().equals(rat)).findFirst().orElse(null);
    }
    private static boolean complete(Summary summary,Snapshot snapshot,Predicate<CircleInput> supplied) {
        return !summary.circles().isEmpty()&&summary.circles().stream().allMatch(group->{var input=find(snapshot,group.circleId(),group.technology(),group.zone());return input!=null&&supplied.test(input);});
    }
    private static Long sum(List<CircleInput> inputs,Function<CircleInput,Integer> field) {
        if(inputs.stream().anyMatch(i->field.apply(i)==null)) return null;
        return inputs.stream().map(field).mapToLong(Integer::longValue).sum();
    }
    private static String text(List<CircleInput> inputs,Function<CircleInput,String> field) {
        Set<String> values=new HashSet<>();for(var input:inputs){var value=field.apply(input);if(value==null)return null;values.add(value);}
        return values.size()==1?values.iterator().next():null;
    }
    private static Values unknown(){return new Values(null,null,null,null,null,null,null,null,null);}
}

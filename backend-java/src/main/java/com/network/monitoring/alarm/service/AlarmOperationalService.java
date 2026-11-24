package com.network.monitoring.alarm.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.monitoring.alarm.dto.AlarmOperational.*;
import com.network.monitoring.alarm.exception.AlarmConfigurationException;
import com.network.monitoring.alarm.repository.AlarmConfigurationRepository;
import com.network.monitoring.alarm.repository.AlarmOperationalRepository;
import com.network.monitoring.alarm.security.AlarmTenantAccess;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.network.monitoring.alarm.exception.AlarmConfigurationException.*;
import static com.network.monitoring.alarm.support.AlarmValues.*;

@Service
public class AlarmOperationalService {
    private final AlarmTenantAccess access;
    private final AlarmConfigurationRepository master;
    private final AlarmOperationalRepository repository;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public AlarmOperationalService(AlarmTenantAccess access,AlarmConfigurationRepository master,
            AlarmOperationalRepository repository,ObjectMapper json,PlatformTransactionManager transactions,
            @Value("${app.alarm-operational.enabled:false}") boolean enabled) {
        this.access=access;this.master=master;this.repository=repository;this.json=json;
        this.tx=new TransactionTemplate(transactions);this.enabled=enabled;
    }
    public boolean enabled() { return enabled; }
    public Map<String,Object> current(long company,String at) {
        access.require(company,false);
        return Map.of("enabled",enabled,"snapshot",at(company,instant(at)));
    }
    public List<Map<String,Object>> history(long company,int limit) {
        access.require(company,false);
        if(limit<1||limit>100) throw invalid("History limit must be between 1 and 100.");
        return enabled?repository.history(company,limit):List.of();
    }
    // Internal report/mail reads run only after their existing persisted tenant/actor checks.
    Snapshot at(long company,Instant at) {
        return enabled?repository.latest(company,timestamp(at)).map(this::decode).orElseGet(Snapshot::none):Snapshot.none();
    }
    Snapshot pinned(long company,Long id) {
        if(id==null) return Snapshot.none(); // Old outbox entries must not adopt later operational edits.
        if(!enabled) throw new AlarmConfigurationException(503,"ALARM_OPERATIONAL_DISABLED","Enable operational inputs before rendering the pinned report.");
        return decode(repository.find(company,id).orElseThrow(AlarmConfigurationException::missing));
    }
    public Snapshot capture(long company,Capture request) {
        long actor=access.require(company,true);
        if(!enabled) throw new AlarmConfigurationException(503,"ALARM_OPERATIONAL_DISABLED","Operational inputs are disabled. Install migration 010 and enable the feature before capturing inputs.");
        if(request==null||request.expectedRevision()<0||request.circles()==null||request.circles().isEmpty()||request.circles().size()>500)
            throw invalid("Supply 1–500 circle/technology entries and a nonnegative expected revision.");
        Instant at=instant(request.effectiveAt());
        if(at.getNano()!=0||at.isAfter(Instant.now().plusSeconds(300))) throw invalid("Effective time must use whole seconds and cannot be in the future.");
        String reference=required(request.sourceReference(),500);
        return tx.execute(status->{
            master.lockCompany(company);
            Long base=repository.latest(company,timestamp(at)).map(row->number(row,"id")).orElse(null);
            if(!Objects.equals(base,request.expectedBaseSnapshotId())) throw conflict("The operational snapshot used as your starting point changed; reload before saving.");
            var previous=repository.slot(company,timestamp(at));
            int revision=previous.map(row->((Number)row.get("revision")).intValue()).orElse(0);
            if(revision!=request.expectedRevision()) throw conflict("Operational inputs changed at this effective time; reload before saving.");
            Set<String> scopes=new HashSet<>(); List<CircleContext> contexts=new ArrayList<>(); int siteCount=0;
            for(CircleInput raw:request.circles()) {
                if(raw==null) throw invalid("Circle entries cannot be null.");
                String rat=required(raw.technology(),2).toUpperCase(Locale.ROOT);
                if(!Set.of("2G","4G").contains(rat)||!scopes.add(raw.circleId()+"|"+rat)) throw invalid("Use a distinct circle and explicit 2G/4G technology for each entry.");
                var circle=master.circle(company,raw.circleId()).orElseThrow(AlarmConfigurationException::missing);
                var zone=master.zone(company,circle.zoneId()).orElseThrow(AlarmConfigurationException::missing);
                if(!circle.active()||!zone.active()) throw invalid("Operational inputs require active circle/zone mappings.");
                for(Integer count:Arrays.asList(raw.ms2Pending(),raw.lrPending(),raw.siteInventoryTotal(),raw.siteUp(),raw.gpsAlarms(),raw.vswrAlarms()))
                    if(count!=null&&(count<0||count>10000000)) throw invalid("Counts must be unknown (null) or between zero and 10000000.");
                if(raw.siteUp()!=null&&raw.siteInventoryTotal()!=null&&raw.siteUp()>raw.siteInventoryTotal()) throw invalid("Reported site-up count cannot exceed the supplied inventory total.");
                List<SiteUpdate> sites=new ArrayList<>(); Set<String> codes=new HashSet<>();
                for(SiteUpdate site:raw.sites()==null?List.<SiteUpdate>of():raw.sites()) {
                    if(site==null||++siteCount>10000) throw invalid("Supply at most 10000 non-null site updates in one snapshot.");
                    String code=required(site.siteCode(),128),state=required(site.state(),16).toUpperCase(Locale.ROOT);
                    if(!codes.add(code)||!Set.of("UP","DOWN","UNKNOWN").contains(state)) throw invalid("Site codes must be distinct within a circle/RAT and state must be UP, DOWN or UNKNOWN.");
                    Instant observed=instant(site.observedAt());
                    if(observed.isAfter(at)) throw invalid("Site observation time cannot be after the operational snapshot.");
                    sites.add(new SiteUpdate(code,state,observed.toString(),site.lrPending(),optional(site.owner(),160),optional(site.recoveryReason(),2000),optional(site.responsibility(),160)));
                }
                var input=new CircleInput(raw.circleId(),rat,raw.ms2Pending(),raw.lrPending(),raw.siteInventoryTotal(),raw.siteUp(),raw.gpsAlarms(),raw.vswrAlarms(),
                        optional(raw.zoneOwner(),160),optional(raw.circleOwner(),160),optional(raw.majorReason(),2000),List.copyOf(sites));
                contexts.add(new CircleContext(circle.name(),zone.name(),input));
            }
            String payload=encode(contexts);
            byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);
            if(bytes.length>2*1024*1024) throw invalid("Operational snapshot exceeds the 2 MiB input budget.");
            Long prior=previous.map(row->number(row,"id")).orElse(null);
            long id=repository.insert(company,timestamp(at),revision+1,prior,base,reference,payload,digest(bytes),actor);
            return decode(repository.find(company,id).orElseThrow());
        });
    }
    private Snapshot decode(Map<String,Object> row) {
        if(!"alarm-operational-v1".equals(string(row,"schema_version"))) throw new IllegalStateException("Unsupported operational snapshot version.");
        String payload=string(row,"input_json"),hash=string(row,"input_sha256");
        if(!digest(payload.getBytes(StandardCharsets.UTF_8)).equals(hash)) throw new IllegalStateException("Operational snapshot checksum mismatch.");
        try {
            List<CircleContext> circles=json.readValue(payload,new TypeReference<>(){});
            return new Snapshot(number(row,"id"),jdbcInstant(row.get("effective_at")).toString(),((Number)row.get("revision")).intValue(),
                    row.get("supersedes_id")==null?null:number(row,"supersedes_id"),string(row,"source_reference"),hash,number(row,"created_by"),jdbcInstant(row.get("created_at")).toString(),List.copyOf(circles),row.get("based_on_id")==null?null:number(row,"based_on_id"));
        } catch(java.io.IOException error) {throw new IllegalStateException("Invalid persisted operational inputs",error);}
    }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception error) {throw invalid("Invalid operational inputs.");} }
    private static String optional(String value,int limit) { return value==null||value.isBlank()?null:required(value,limit); }
}

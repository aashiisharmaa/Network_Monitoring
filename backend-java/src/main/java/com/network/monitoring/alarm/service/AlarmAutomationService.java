package com.network.monitoring.alarm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.monitoring.alarm.exception.AlarmConfigurationException;
import com.network.monitoring.alarm.repository.AlarmConfigurationRepository;
import com.network.monitoring.alarm.scheduling.AlarmFolderWatcher;
import com.network.monitoring.alarm.scheduling.AlarmSchedulePolicy;
import com.network.monitoring.alarm.security.AlarmTenantAccess;
import com.network.monitoring.alarm.storage.AlarmInputFolders;
import com.network.monitoring.platform.mail.config.ReportMailSettings;
import jakarta.mail.internet.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.network.monitoring.alarm.dto.AlarmAutomation.*;
import static com.network.monitoring.alarm.dto.AlarmModels.*;
import static com.network.monitoring.alarm.exception.AlarmConfigurationException.*;
import static com.network.monitoring.alarm.support.AlarmValues.*;

@Service
public class AlarmAutomationService {
    private final JdbcTemplate jdbc;
    private final AlarmTenantAccess access;
    private final AlarmConfigurationRepository master;
    private final AlarmWorkflowService workflow;
    private final AlarmReportService reports;
    private final AlarmMailDeliveryService delivery;
    private final AlarmFolderWatcher folders;
    private final AlarmInputFolders inputFolders;
    private final ReportMailSettings mail;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public AlarmAutomationService(JdbcTemplate jdbc,AlarmTenantAccess access,AlarmConfigurationRepository master,
            AlarmWorkflowService workflow,AlarmReportService reports,ReportMailSettings mail,ObjectMapper json,
            PlatformTransactionManager transactions,AlarmInputFolders inputFolders,AlarmFolderWatcher folders,AlarmMailDeliveryService delivery) {
        this.jdbc=jdbc;this.access=access;this.master=master;this.workflow=workflow;this.reports=reports;
        this.mail=mail;this.json=json;this.tx=new TransactionTemplate(transactions);
        this.inputFolders=inputFolders;this.folders=folders;this.delivery=delivery;
    }
    public Settings settings(long company) {
        access.require(company,false);
        var rows=jdbc.queryForList("SELECT settings_json FROM alarm_automation_settings WHERE company_id=?",company);
        return rows.isEmpty()?new Settings(0,false,60,List.of(),List.of(),List.of()):decode(string(rows.get(0),"settings_json"),Settings.class);
    }
    public Settings save(long company,Settings request) {
        long actor=access.require(company,true);
        if(request.version()<0||request.freshnessMinutes()<1||request.freshnessMinutes()>10080) throw invalid("Version and explicit freshness (1–10080 minutes) are required.");
        if(request.reports()==null||request.escalations()==null||request.watches()==null||request.reports().size()>10||request.escalations().size()>10||request.watches().size()>20)
            throw invalid("Supply reports/escalations/watches arrays within the 10/10/20 limits.");
        if(request.reports().stream().anyMatch(Objects::isNull)||request.escalations().stream().anyMatch(Objects::isNull)||request.watches().stream().anyMatch(Objects::isNull)) throw invalid("Configuration entries cannot be null.");
        Set<String> keys=new HashSet<>();
        for(var route:request.reports()) {key(keys,"report:",route.key());scope(company,route.circleId());recipients(route.recipients());mode(route.mode());}
        for(var policy:request.escalations()) {
            key(keys,"escalation:",policy.key());scope(company,policy.circleId());recipients(policy.recipients());mode(policy.mode());
            if(policy.classification()==null||!Set.of("CRITICAL","MAJOR","MINOR").contains(policy.classification())||policy.minAgeHours()<0||policy.minAgeHours()>8760) throw invalid("Escalation requires an explicit classification and age from 0 to 8760 hours.");
        }
        Set<Long> feeds=new HashSet<>();
        for(var watch:request.watches()) {
            master.feed(company,watch.feedId()).orElseThrow(AlarmConfigurationException::missing);
            if(!feeds.add(watch.feedId())||watch.timestampSource()==null||!Set.of("FILE_MODIFIED_UTC","SIDECAR").contains(watch.timestampSource())) throw invalid("Each watch needs a distinct feed and explicit timestamp source.");
            inputFolders.resolve(watch.folder());
            if(AlarmSchedulePolicy.hasWindow(watch)) {
                try {LocalTime.parse(watch.windowStart());LocalTime.parse(watch.windowEnd());ZoneId.of(watch.timezone());}
                catch(Exception error) {throw invalid("A schedule needs start/end local times and an explicit timezone.");}
                if(watch.windowStart().equals(watch.windowEnd())) throw invalid("Schedule start and deadline must differ.");
            }
        }
        var saved=new Settings(request.version()+1,request.enabled(),request.freshnessMinutes(),List.copyOf(request.reports()),List.copyOf(request.escalations()),List.copyOf(request.watches()));
        return tx.execute(status->{
            master.lockCompany(company);
            int current=jdbc.queryForList("SELECT version FROM alarm_automation_settings WHERE company_id=?",Integer.class,company).stream().findFirst().orElse(0);
            if(current!=request.version()) throw conflict("Automation settings changed; reload before saving.");
            if(current==0) jdbc.update("INSERT INTO alarm_automation_settings(company_id,settings_json,version,updated_by) VALUES(?,?,?,?)",company,workflow.encode(saved),saved.version(),actor);
            else jdbc.update("UPDATE alarm_automation_settings SET settings_json=?,version=?,updated_by=?,updated_at=UTC_TIMESTAMP(6) WHERE company_id=?",workflow.encode(saved),saved.version(),actor,company);
            return saved;
        });
    }
    private void scope(long company,Long circle) {if(circle!=null) master.circle(company,circle).orElseThrow(AlarmConfigurationException::missing);}
    private static void key(Set<String> keys,String prefix,String key) {if(key==null||!key.matches("[A-Za-z0-9_.-]{1,64}")||!keys.add(prefix+key)) throw invalid("Route/policy keys must be distinct ASCII identifiers.");}
    private void mode(String value) {if(value==null||!Set.of("CAPTURE","SMTP").contains(value)) throw invalid("Delivery mode must be CAPTURE or SMTP.");if(value.equals("SMTP")&&!mail.isOutboundEnabled()) throw invalid("Configure and explicitly enable outbound SMTP before selecting SMTP routes.");}
    private static void recipients(List<String> values) {
        if(values==null||values.isEmpty()||values.size()>30) throw invalid("Provide 1–30 explicit recipient addresses.");
        for(String value:values) try {
            if(value==null||value.length()>254||value.contains("\r")||value.contains("\n")) throw new IllegalArgumentException();
            var address=new InternetAddress(value,true);address.validate();
            if(!address.getAddress().equals(value)||!value.contains("@")) throw new IllegalArgumentException();
        } catch(Exception e) {throw invalid("A recipient address is invalid. Supply addresses without display names or header characters.");}
    }

    /** Enqueue reports and once-per-identity/policy escalation groups atomically. */
    public void plan() {
        for(long id:jdbc.queryForList("SELECT id FROM alarm_ingestion_runs WHERE status='SUCCEEDED' AND mail_planned=FALSE ORDER BY id LIMIT 2",Long.class))
            tx.executeWithoutResult(status->{
                var rows=jdbc.queryForList("SELECT * FROM alarm_ingestion_runs WHERE id=? AND mail_planned=FALSE FOR UPDATE",id);
                if(rows.isEmpty()) return;
                var run=rows.get(0);long company=number(run,"company_id"),feed=number(run,"feed_id");
                var activeFeed=jdbc.queryForList("SELECT active FROM alarm_source_feed_profiles WHERE company_id=? AND id=? FOR UPDATE",company,feed);
                if(activeFeed.isEmpty()||!bool(activeFeed.get(0),"active")) {
                    jdbc.update("UPDATE alarm_ingestion_runs SET mail_planned=TRUE,message='Import completed; delivery suppressed because source was deactivated' WHERE id=?",id);return;
                }
                var frozen=decode(string(run,"config_json"),Frozen.class);
                if(frozen.automationJson()!=null&&!frozen.automationJson().isBlank()) {
                    var settings=decode(frozen.automationJson(),Settings.class);
                    boolean stale=jdbcInstant(run.get("snapshot_at")).isBefore(Instant.now().minusSeconds(settings.freshnessMinutes()*60L));
                    if(settings.enabled()) {
                        List<Long> sourceIds=reports.sourceIds(company,jdbcInstant(run.get("snapshot_at")).toString());
                        boolean incomplete=jdbc.queryForObject("SELECT COUNT(*) FROM alarm_source_feed_profiles f WHERE f.company_id=? AND f.active=TRUE AND NOT EXISTS (SELECT 1 FROM alarm_ingestion_runs r WHERE r.company_id=f.company_id AND r.feed_id=f.id AND r.status='SUCCEEDED' AND r.snapshot_at<=?)",Integer.class,company,run.get("snapshot_at"))>0;
                        for(var route:settings.reports()) enqueue(company,id,"report:"+route.key(),new Envelope("Alarm snapshot | "+route.key(),route.recipients(),route.mode(),route.circleId(),null,sourceIds,reports.operationalSnapshotId(company,jdbcInstant(run.get("snapshot_at")).toString())),stale||incomplete);
                        for(var policy:settings.escalations()) {
                            String policyKey=digest(workflow.encode(policy).getBytes(StandardCharsets.UTF_8));
                            Set<String> previous=new HashSet<>(jdbc.queryForList("SELECT identity_key FROM alarm_escalation_decisions WHERE company_id=? AND feed_id=? AND policy_key=?",String.class,company,feed,policyKey));
                            Set<String> eligible=new LinkedHashSet<>();
                            Instant at=jdbcInstant(run.get("snapshot_at"));
                            for(var observation:reports.rows(company,id,policy.circleId(),null))
                                if(bool(observation,"included")&&policy.classification().equals(observation.get("classification"))&&Duration.between(jdbcInstant(observation.get("raised_at")),at).toSeconds()>=policy.minAgeHours()*3600L&&!previous.contains(string(observation,"identity_key"))) eligible.add(string(observation,"identity_key"));
                            if(!eligible.isEmpty()) {
                                // Stale SMTP decisions are recorded in the outbox, but do not consume escalation stages.
                                if(!stale||policy.mode().equals("CAPTURE")) {
                                    List<Object[]> batch=eligible.stream().map(identity->new Object[]{company,feed,id,identity,policyKey}).toList();
                                    for(int offset=0;offset<batch.size();offset+=250) jdbc.batchUpdate("INSERT INTO alarm_escalation_decisions(company_id,feed_id,run_id,identity_key,policy_key) VALUES(?,?,?,?,?)",batch.subList(offset,Math.min(offset+250,batch.size())));
                                }
                                enqueue(company,id,"escalation:"+policy.key(),new Envelope("Alarm escalation | "+policy.key(),policy.recipients(),policy.mode(),policy.circleId(),Set.copyOf(eligible),List.of(id),reports.operationalSnapshotId(company,jdbcInstant(run.get("snapshot_at")).toString())),stale);
                            }
                        }
                    }
                }
                jdbc.update("UPDATE alarm_ingestion_runs SET mail_planned=TRUE WHERE id=?",id);
            });
    }
    private void enqueue(long company,long run,String kind,Envelope message,boolean stale) {
        String key=digest((company+"|"+kind+"|"+(message.identities()==null?workflow.encode(message):run)).getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT IGNORE INTO alarm_delivery_outbox(company_id,run_id,delivery_key,mode,status,message_json,message) VALUES(?,?,?,?,?,?,?)",company,run,key,message.mode(),stale&&message.mode().equals("SMTP")?"SKIPPED_STALE":"QUEUED",workflow.encode(message),stale?"Historical/stale source: SMTP is suppressed; CAPTURE is a local preview":"Snapshot report queued");
    }

    public Map<String,Object> status(long company) {
        access.require(company,false);
        return Map.of("workflowEnabled",workflow.enabled(),"inputRootConfigured",inputFolders.configured(),"smtpEnabled",mail.isOutboundEnabled(),
            "folderStatus",jdbc.queryForList("SELECT last_poll_at,last_poll_message FROM alarm_automation_settings WHERE company_id=?",company),
            "imports",jdbc.queryForList("SELECT status,COUNT(*) total FROM alarm_ingestion_runs WHERE company_id=? GROUP BY status",company),
            "deliveries",jdbc.queryForList("SELECT status,COUNT(*) total FROM alarm_delivery_outbox WHERE company_id=? GROUP BY status",company));
    }

    // Preserve the feature facade while execution concerns have dedicated owners.
    public List<Map<String,Object>> deliveries(long company) {return delivery.deliveries(company);}
    public void retry(long company,long id) {delivery.retry(company,id);}
    public void reconcile(long company,long id,String outcome,String note) {delivery.reconcile(company,id,outcome,note);}
    public byte[] captured(long company,long id) {return delivery.captured(company,id);}
    public void deliverNext() {delivery.deliverNext();}
    public void pollSources() {folders.pollSources();}
    public static boolean withinWindow(Watch watch,Instant at) {return AlarmSchedulePolicy.withinWindow(watch,at);}

    private <T>T decode(String value,Class<T> type) {try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException("Invalid persisted alarm configuration",e);}}
}

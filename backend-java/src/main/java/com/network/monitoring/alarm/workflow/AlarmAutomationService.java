package com.network.monitoring.alarm.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.monitoring.alarm.config.*;
import com.network.monitoring.service.ReportMailSettings;
import com.network.monitoring.service.ReportMailTransport;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.mail.util.ByteArrayDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.network.monitoring.alarm.workflow.AlarmWorkflowService.*;
import static com.network.monitoring.alarm.config.AlarmConfigurationException.*;

@Service
public class AlarmAutomationService {
    public record Route(String key, Long circleId, List<String> recipients, String mode) {}
    public record Policy(String key, Long circleId, String classification, int minAgeHours, List<String> recipients, String mode) {}
    public record Watch(long feedId, String folder, String timestampSource, String windowStart, String windowEnd, String timezone) {
        public Watch(long feedId,String folder,String timestampSource) {this(feedId,folder,timestampSource,null,null,null);}
    }
    public record Settings(int version, boolean enabled, int freshnessMinutes, List<Route> reports, List<Policy> escalations, List<Watch> watches) {}
    public record Envelope(String subject, List<String> recipients, String mode, Long circleId, Set<String> identities, List<Long> sourceRunIds) {}
    private final JdbcTemplate jdbc;
    private final AlarmTenantAccess access;
    private final AlarmConfigurationRepository master;
    private final AlarmWorkflowService workflow;
    private final AlarmReportService reports;
    private final ReportMailTransport transport;
    private final ReportMailSettings mail;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final Path spool;
    private final Path inputRoot;
    private final java.util.concurrent.atomic.AtomicLong lastFolderPoll = new java.util.concurrent.atomic.AtomicLong();

    public AlarmAutomationService(JdbcTemplate jdbc, AlarmTenantAccess access, AlarmConfigurationRepository master,
            AlarmWorkflowService workflow, AlarmReportService reports, ReportMailTransport transport, ReportMailSettings mail,
            ObjectMapper json, PlatformTransactionManager transactions,
            @Value("${app.alarm-workflow.storage-path:uploads/alarm-workflow}") String storage,
            @Value("${app.alarm-workflow.input-root:}") String inputRoot) {
        this.jdbc=jdbc;this.access=access;this.master=master;this.workflow=workflow;this.reports=reports;
        this.transport=transport;this.mail=mail;this.json=json;this.tx=new TransactionTemplate(transactions);
        this.spool=Path.of(storage).toAbsolutePath().normalize().resolve("mail");
        this.inputRoot=inputRoot==null||inputRoot.isBlank()?null:Path.of(inputRoot).toAbsolutePath().normalize();
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
            folder(watch.folder());
            if(hasWindow(watch)) {
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
    private Path folder(String relative) {
        if(inputRoot==null) throw invalid("Configure ALARM_INPUT_ROOT before enabling folder watches.");
        if(relative==null||relative.isBlank()||Path.of(relative).isAbsolute()) throw invalid("Watch folder must be relative to ALARM_INPUT_ROOT.");
        Path candidate=inputRoot.resolve(relative).normalize();
        try {if(!candidate.startsWith(inputRoot)||!candidate.toRealPath().startsWith(inputRoot.toRealPath())||!Files.isDirectory(candidate)) throw invalid("Folder is outside the approved input root or is unavailable.");}
        catch(java.io.IOException e) {throw invalid("Approved input folder is unavailable.");}
        return candidate;
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
                        for(var route:settings.reports()) enqueue(company,id,"report:"+route.key(),new Envelope("Alarm snapshot | "+route.key(),route.recipients(),route.mode(),route.circleId(),null,sourceIds),stale||incomplete);
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
                                enqueue(company,id,"escalation:"+policy.key(),new Envelope("Alarm escalation | "+policy.key(),policy.recipients(),policy.mode(),policy.circleId(),Set.copyOf(eligible),List.of(id)),stale);
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
    public List<Map<String,Object>> deliveries(long company) {access.require(company,false);return jdbc.queryForList("SELECT id,run_id,mode,status,attempts,message,created_at,updated_at FROM alarm_delivery_outbox WHERE company_id=? ORDER BY id DESC LIMIT 100",company);}
    public Map<String,Object> status(long company) {
        access.require(company,false);
        return Map.of("workflowEnabled",workflow.enabled(),"inputRootConfigured",inputRoot!=null,"smtpEnabled",mail.isOutboundEnabled(),
            "folderStatus",jdbc.queryForList("SELECT last_poll_at,last_poll_message FROM alarm_automation_settings WHERE company_id=?",company),
            "imports",jdbc.queryForList("SELECT status,COUNT(*) total FROM alarm_ingestion_runs WHERE company_id=? GROUP BY status",company),
            "deliveries",jdbc.queryForList("SELECT status,COUNT(*) total FROM alarm_delivery_outbox WHERE company_id=? GROUP BY status",company));
    }
    public void retry(long company,long id) {
        access.require(company,true);
        if(jdbc.update("UPDATE alarm_delivery_outbox SET status='QUEUED',next_attempt_at=NULL,message='Manual retry queued',updated_at=UTC_TIMESTAMP(6) WHERE company_id=? AND id=? AND status='FAILED'",company,id)!=1) throw conflict("Only FAILED deliveries can be retried. UNKNOWN outcomes require provider reconciliation.");
    }
    public void reconcile(long company,long id,String outcome,String note) {
        long actor=access.require(company,true);
        if(outcome==null||!Set.of("CONFIRMED_SENT","CONFIRMED_NOT_SENT").contains(outcome)) throw invalid("Provider reconciliation must confirm SENT or NOT_SENT.");
        String evidence=required(note,1500);
        if(jdbc.update("UPDATE alarm_delivery_outbox SET status=?,message=?,updated_at=UTC_TIMESTAMP(6) WHERE company_id=? AND id=? AND status='UNKNOWN'",outcome.equals("CONFIRMED_SENT")?"ACCEPTED":"FAILED","Provider reconciliation by user "+actor+": "+evidence,company,id)!=1) throw conflict("Only UNKNOWN deliveries can be reconciled.");
    }
    public byte[] captured(long company,long id) {
        access.require(company,false);var rows=jdbc.queryForList("SELECT mime_key FROM alarm_delivery_outbox WHERE company_id=? AND id=? AND status='CAPTURED'",company,id);
        if(rows.isEmpty()) throw missing();
        try {return Files.readAllBytes(mime(string(rows.get(0),"mime_key")));} catch(java.io.IOException e){throw new IllegalStateException("Captured message unavailable",e);}
    }
    public void deliverNext() {
        jdbc.update("UPDATE alarm_delivery_outbox SET status='UNKNOWN',lease_token=NULL,message='Sending worker disappeared; reconcile before any resend',updated_at=UTC_TIMESTAMP(6) WHERE status='SENDING' AND lease_until<UTC_TIMESTAMP(6)");
        jdbc.update("UPDATE alarm_delivery_outbox SET status='QUEUED',lease_token=NULL,message='Recovered before sending started' WHERE status='PREPARING' AND lease_until<UTC_TIMESTAMP(6)");
        for(long id:jdbc.queryForList("SELECT id FROM alarm_delivery_outbox WHERE status='QUEUED' AND (next_attempt_at IS NULL OR next_attempt_at<=UTC_TIMESTAMP(6)) ORDER BY id LIMIT 2",Long.class)) deliver(id);
    }
    private void deliver(long id) {
        String token=UUID.randomUUID().toString();
        if(jdbc.update("UPDATE alarm_delivery_outbox SET status='PREPARING',attempts=attempts+1,lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE),updated_at=UTC_TIMESTAMP(6) WHERE id=? AND status='QUEUED' AND (next_attempt_at IS NULL OR next_attempt_at<=UTC_TIMESTAMP(6))",token,id)!=1) return;
        try {
            var delivery=jdbc.queryForMap("SELECT * FROM alarm_delivery_outbox WHERE id=?",id);
            long company=number(delivery,"company_id"),run=number(delivery,"run_id");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM companies WHERE id=? AND status='ACTIVE'",Integer.class,company)!=1) throw new IllegalStateException("Company is inactive");
            Envelope envelope=decode(string(delivery,"message_json"),Envelope.class);recipients(envelope.recipients());
            var source=jdbc.queryForMap("SELECT snapshot_at,config_json FROM alarm_ingestion_runs WHERE company_id=? AND id=?",company,run);
            if(envelope.mode().equals("SMTP")) {
                if(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_source_feed_profiles WHERE company_id=? AND id=(SELECT feed_id FROM alarm_ingestion_runs WHERE company_id=? AND id=?) AND active=TRUE",Integer.class,company,company,run)!=1) {
                    jdbc.update("UPDATE alarm_delivery_outbox SET status='CANCELLED',message='Source deactivated before SMTP sending',updated_at=UTC_TIMESTAMP(6) WHERE id=? AND lease_token=?",id,token);return;
                }
                var frozen=decode(string(source,"config_json"),Frozen.class);
                var settings=decode(frozen.automationJson(),Settings.class);
                for(var watch:settings.watches()) if(watch.feedId()==frozen.feed().id()&&!withinWindow(watch,Instant.now())) {
                    jdbc.update("UPDATE alarm_delivery_outbox SET status='SKIPPED_DEADLINE',message='Configured sending window/deadline has passed',updated_at=UTC_TIMESTAMP(6) WHERE id=? AND lease_token=?",id,token);return;
                }
                if(jdbcInstant(source.get("snapshot_at")).isBefore(Instant.now().minusSeconds(settings.freshnessMinutes()*60L))) {
                    jdbc.update("UPDATE alarm_delivery_outbox SET status='SKIPPED_STALE',message='Source became stale before sending',updated_at=UTC_TIMESTAMP(6) WHERE id=? AND lease_token=?",id,token);return;
                }
                for(long sourceId:envelope.sourceRunIds()) {
                    var snapshot=workflow.completed(company,sourceId);
                    if(jdbcInstant(snapshot.get("snapshot_at")).isBefore(Instant.now().minusSeconds(settings.freshnessMinutes()*60L))) {
                        jdbc.update("UPDATE alarm_delivery_outbox SET status='SKIPPED_STALE',message='Source cohort contains stale snapshots',updated_at=UTC_TIMESTAMP(6) WHERE id=? AND lease_token=?",id,token);return;
                    }
                }
            }
            var message=new MimeMessage(Session.getInstance(new Properties()));
            message.setFrom(new InternetAddress(mail.getFrom(),true));
            message.setRecipients(Message.RecipientType.TO,InternetAddress.parse(String.join(",",envelope.recipients()),true));
            message.setSubject(envelope.subject(),"UTF-8");message.setSentDate(new Date());
            var multipart=new MimeMultipart();var body=new MimeBodyPart();
            body.setText("Alarm monitoring snapshot report.\nImport: "+run+"\nScope: "+(envelope.circleId()==null?"All circles in the source":"Circle "+envelope.circleId())+"\nSee the attached summary PDF and complete CSV.\nCustomer classification follows the captured catalogue; UNMAPPED is reported separately.\nMissing records are not confirmed cleared. CAPTURE mode does not send external email.","UTF-8");multipart.addBodyPart(body);
            attach(multipart,"alarm-report.pdf","application/pdf",envelope.identities()==null?reports.cohortPdf(company,envelope.sourceRunIds(),envelope.circleId()):reports.pdf(company,run,envelope.circleId(),envelope.identities()));
            attach(multipart,"alarm-details.csv","text/csv; charset=UTF-8",envelope.identities()==null?reports.cohortCsv(company,envelope.sourceRunIds(),envelope.circleId()):reports.csv(company,run,envelope.circleId(),envelope.identities()));
            message.setContent(multipart);message.saveChanges();message.setHeader("Message-ID","<oss-alarm-"+id+"@network-monitor.local>");
            String mimeKey=UUID.randomUUID().toString();Path path=mime(mimeKey);Files.createDirectories(spool);
            try(var output=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW)){message.writeTo(output);}
            if(Files.size(path)>mail.getMaxMessageBytes()) throw new IllegalStateException("Message exceeds configured size budget");
            jdbc.update("UPDATE alarm_delivery_outbox SET mime_key=? WHERE id=? AND lease_token=? AND status='PREPARING'",mimeKey,id,token);
            var receipt=transport.deliver(envelope.mode(),path,()->{
                if(jdbc.update("UPDATE alarm_delivery_outbox SET status='SENDING',updated_at=UTC_TIMESTAMP(6) WHERE id=? AND lease_token=? AND lease_until>UTC_TIMESTAMP(6) AND status='PREPARING'",id,token)!=1) throw new IllegalStateException("Delivery claim lost");
            });
            jdbc.update("UPDATE alarm_delivery_outbox SET status=?,message=?,updated_at=UTC_TIMESTAMP(6) WHERE id=? AND lease_token=? AND status='SENDING'",receipt.status(),receipt.response(),id,token);
        } catch(Exception error) {
            // Once SENDING was committed, any unrecorded result can be an accepted message. Never auto-resend it.
            jdbc.update("UPDATE alarm_delivery_outbox SET status=CASE WHEN status='SENDING' THEN 'UNKNOWN' WHEN attempts<? THEN 'QUEUED' ELSE 'FAILED' END,next_attempt_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 SECOND),message='Pre-send failures retry after 30s within the attempt budget. UNKNOWN requires provider reconciliation; FAILED requires review.',updated_at=UTC_TIMESTAMP(6) WHERE id=? AND lease_token=? AND status IN ('PREPARING','SENDING')",Math.max(1,Math.min(5,mail.getMaxAttempts())),id,token);
        }
    }
    private void attach(MimeMultipart multipart,String name,String type,byte[] data) throws Exception {var part=new MimeBodyPart();part.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(data,type)));part.setFileName(name);multipart.addBodyPart(part);}
    private Path mime(String key) {if(!key.matches("[a-f0-9-]{36}")||Files.isSymbolicLink(spool)) throw invalid("Invalid MIME key");Path path=spool.resolve(key+".eml");if(Files.isSymbolicLink(path)) throw invalid("Invalid MIME file");return path;}

    public void pollSources() {
        if(inputRoot==null) return;
        long now=System.currentTimeMillis(),previous=lastFolderPoll.get();
        if(now-previous<60000||!lastFolderPoll.compareAndSet(previous,now)) return;
        for(var row:jdbc.queryForList("SELECT a.company_id,a.settings_json,a.updated_by FROM alarm_automation_settings a JOIN companies c ON c.id=a.company_id AND c.status='ACTIVE' ORDER BY a.company_id LIMIT 200")) {
            Settings settings=decode(string(row,"settings_json"),Settings.class);if(!settings.enabled()) continue;
            for(var watch:settings.watches()) try {
                if(!withinWindow(watch,Instant.now())) {
                    jdbc.update("UPDATE alarm_automation_settings SET last_poll_at=UTC_TIMESTAMP(6),last_poll_message=? WHERE company_id=?","Feed "+watch.feedId()+": waiting for configured local processing window",number(row,"company_id"));continue;
                }
                Path directory=folder(watch.folder());
                var feed=master.feed(number(row,"company_id"),watch.feedId()).orElseThrow(AlarmConfigurationException::missing);
                if(!feed.active()) continue;
                try(var entries=Files.list(directory)) {
                    int staged=0;
                    for(Path file:entries.filter(p->p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith("."+feed.fileFormat().toLowerCase(Locale.ROOT)))
                            .sorted(Comparator.comparingLong((Path p)->p.toFile().lastModified()).reversed()).limit(200).toList()) {
                        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file)||Files.size(file)>10*1024*1024||Files.getLastModifiedTime(file).toInstant().isAfter(Instant.now().minusSeconds(60))) continue;
                        if(staged>=2) break;
                        Instant at=Files.getLastModifiedTime(file).toInstant().truncatedTo(ChronoUnit.SECONDS);
                        String expectedChecksum=null;
                        if(watch.timestampSource().equals("SIDECAR")) {
                            Path ready=file.resolveSibling(file.getFileName()+".ready.json");if(!Files.isRegularFile(ready)||Files.isSymbolicLink(ready)||Files.size(ready)>4096) continue;
                            var metadata=json.readTree(Files.readString(ready));
                            expectedChecksum=metadata.path("checksum").asText();at=instant(metadata.path("snapshotAt").asText());
                        }
                        var known=jdbc.queryForList("SELECT checksum FROM alarm_ingestion_runs WHERE company_id=? AND feed_id=? AND snapshot_at=?",number(row,"company_id"),feed.id(),timestamp(at));
                        if(!known.isEmpty()) {
                            if(expectedChecksum!=null&&!expectedChecksum.equals(string(known.get(0),"checksum"))) throw conflict("Sidecar changed content for an existing snapshot slot.");
                            continue;
                        }
                        byte[] bytes;try(var input=Files.newInputStream(file)){bytes=input.readNBytes(10*1024*1024+1);}
                        if(bytes.length>10*1024*1024) continue;
                        if(watch.timestampSource().equals("FILE_MODIFIED_UTC") && (!Files.getLastModifiedTime(file).toInstant().truncatedTo(ChronoUnit.SECONDS).equals(at) || Files.size(file)!=bytes.length)) continue;
                        if(expectedChecksum!=null&&!digest(bytes).equals(expectedChecksum)) throw invalid("Ready sidecar checksum does not match source bytes.");
                        workflow.submitWatched(number(row,"company_id"),feed.id(),at.toString(),new AlarmWorkflowService.MemoryFile(file.getFileName().toString(),bytes),number(row,"updated_by"));
                        staged++;
                    }
                } catch(java.io.IOException error) {throw new IllegalStateException("An alarm watch folder could not be read",error);}
                jdbc.update("UPDATE alarm_automation_settings SET last_poll_at=UTC_TIMESTAMP(6),last_poll_message=? WHERE company_id=?","Feed "+watch.feedId()+": scanned up to 200 newest files; queued up to 2 new snapshots",number(row,"company_id"));
            } catch(Exception error) {
                jdbc.update("UPDATE alarm_automation_settings SET last_poll_at=UTC_TIMESTAMP(6),last_poll_message=? WHERE company_id=?","Feed "+watch.feedId()+": folder processing needs review ("+error.getClass().getSimpleName()+")",number(row,"company_id"));
            }
        }
    }
    private static boolean hasWindow(Watch watch) {return watch.windowStart()!=null&&!watch.windowStart().isBlank()||watch.windowEnd()!=null&&!watch.windowEnd().isBlank()||watch.timezone()!=null&&!watch.timezone().isBlank();}
    public static boolean withinWindow(Watch watch,Instant at) {
        if(!hasWindow(watch)) return true;
        LocalTime now=at.atZone(ZoneId.of(watch.timezone())).toLocalTime();
        LocalTime start=LocalTime.parse(watch.windowStart()),end=LocalTime.parse(watch.windowEnd());
        return start.isBefore(end)?!now.isBefore(start)&&now.isBefore(end):!now.isBefore(start)||now.isBefore(end);
    }
    private <T>T decode(String value,Class<T> type) {try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException("Invalid persisted alarm configuration",e);}}
}

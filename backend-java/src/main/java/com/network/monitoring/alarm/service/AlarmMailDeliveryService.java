package com.network.monitoring.alarm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.monitoring.alarm.security.AlarmTenantAccess;
import com.network.monitoring.platform.mail.ReportMailTransport;
import com.network.monitoring.platform.mail.config.ReportMailSettings;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.mail.util.ByteArrayDataSource;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.network.monitoring.alarm.dto.AlarmAutomation.*;
import static com.network.monitoring.alarm.dto.AlarmModels.*;
import static com.network.monitoring.alarm.exception.AlarmConfigurationException.*;
import static com.network.monitoring.alarm.scheduling.AlarmSchedulePolicy.withinWindow;
import static com.network.monitoring.alarm.support.AlarmValues.*;

/** Outbox claims, MIME artifacts and SMTP outcomes; independent of folder discovery and import policy. */
@Service
public class AlarmMailDeliveryService {
    private final JdbcTemplate jdbc;
    private final AlarmTenantAccess access;
    private final AlarmWorkflowService workflow;
    private final AlarmReportService reports;
    private final ReportMailTransport transport;
    private final ReportMailSettings mail;
    private final ObjectMapper json;
    private final Path spool;
    public AlarmMailDeliveryService(JdbcTemplate jdbc,AlarmTenantAccess access,AlarmWorkflowService workflow,
            AlarmReportService reports,ReportMailTransport transport,ReportMailSettings mail,ObjectMapper json,
            @Value("${app.alarm-workflow.storage-path:uploads/alarm-workflow}") String storage) {
        this.jdbc=jdbc;this.access=access;this.workflow=workflow;this.reports=reports;
        this.transport=transport;this.mail=mail;this.json=json;
        this.spool=Path.of(storage).toAbsolutePath().normalize().resolve("mail");
    }
    public List<Map<String,Object>> deliveries(long company) {access.require(company,false);return jdbc.queryForList("SELECT id,run_id,mode,status,attempts,message,created_at,updated_at FROM alarm_delivery_outbox WHERE company_id=? ORDER BY id DESC LIMIT 100",company);}

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

    private static void recipients(List<String> values) {
        if(values==null||values.isEmpty()||values.size()>30) throw invalid("Provide 1–30 explicit recipient addresses.");
        for(String value:values) try {
            if(value==null||value.length()>254||value.contains("\r")||value.contains("\n")) throw new IllegalArgumentException();
            var address=new InternetAddress(value,true);address.validate();
            if(!address.getAddress().equals(value)||!value.contains("@")) throw new IllegalArgumentException();
        } catch(Exception e) {throw invalid("A recipient address is invalid. Supply addresses without display names or header characters.");}
    }

    private <T>T decode(String value,Class<T> type) {try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException("Invalid persisted alarm configuration",e);}}
}

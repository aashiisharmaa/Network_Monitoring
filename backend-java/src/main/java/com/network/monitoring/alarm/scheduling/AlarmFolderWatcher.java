package com.network.monitoring.alarm.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.monitoring.alarm.exception.AlarmConfigurationException;
import com.network.monitoring.alarm.ingestion.AlarmMemoryFile;
import com.network.monitoring.alarm.repository.AlarmConfigurationRepository;
import com.network.monitoring.alarm.service.AlarmWorkflowService;
import com.network.monitoring.alarm.storage.AlarmInputFolders;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.network.monitoring.alarm.dto.AlarmAutomation.*;
import static com.network.monitoring.alarm.dto.AlarmModels.*;
import static com.network.monitoring.alarm.exception.AlarmConfigurationException.*;
import static com.network.monitoring.alarm.scheduling.AlarmSchedulePolicy.withinWindow;
import static com.network.monitoring.alarm.support.AlarmValues.*;

/** Discovers ready files and submits durable imports; no MIME creation or SMTP calls. */
@Service
public class AlarmFolderWatcher {
    private final JdbcTemplate jdbc;
    private final AlarmConfigurationRepository master;
    private final AlarmWorkflowService workflow;
    private final ObjectMapper json;
    private final AlarmInputFolders inputFolders;
    private final java.util.concurrent.atomic.AtomicLong lastFolderPoll = new java.util.concurrent.atomic.AtomicLong();
    public AlarmFolderWatcher(JdbcTemplate jdbc,AlarmConfigurationRepository master,AlarmWorkflowService workflow,ObjectMapper json,AlarmInputFolders inputFolders) {
        this.jdbc=jdbc;this.master=master;this.workflow=workflow;this.json=json;this.inputFolders=inputFolders;
    }
    public void pollSources() {
        if(!inputFolders.configured()) return;
        long now=System.currentTimeMillis(),previous=lastFolderPoll.get();
        if(now-previous<60000||!lastFolderPoll.compareAndSet(previous,now)) return;
        for(var row:jdbc.queryForList("SELECT a.company_id,a.settings_json,a.updated_by FROM alarm_automation_settings a JOIN companies c ON c.id=a.company_id AND c.status='ACTIVE' ORDER BY a.company_id LIMIT 200")) {
            Settings settings=decode(string(row,"settings_json"),Settings.class);if(!settings.enabled()) continue;
            for(var watch:settings.watches()) try {
                if(!withinWindow(watch,Instant.now())) {
                    jdbc.update("UPDATE alarm_automation_settings SET last_poll_at=UTC_TIMESTAMP(6),last_poll_message=? WHERE company_id=?","Feed "+watch.feedId()+": waiting for configured local processing window",number(row,"company_id"));continue;
                }
                Path directory=inputFolders.resolve(watch.folder());
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
                        workflow.submitWatched(number(row,"company_id"),feed.id(),at.toString(),new AlarmMemoryFile(file.getFileName().toString(),bytes),number(row,"updated_by"));
                        staged++;
                    }
                } catch(java.io.IOException error) {throw new IllegalStateException("An alarm watch folder could not be read",error);}
                jdbc.update("UPDATE alarm_automation_settings SET last_poll_at=UTC_TIMESTAMP(6),last_poll_message=? WHERE company_id=?","Feed "+watch.feedId()+": scanned up to 200 newest files; queued up to 2 new snapshots",number(row,"company_id"));
            } catch(Exception error) {
                jdbc.update("UPDATE alarm_automation_settings SET last_poll_at=UTC_TIMESTAMP(6),last_poll_message=? WHERE company_id=?","Feed "+watch.feedId()+": folder processing needs review ("+error.getClass().getSimpleName()+")",number(row,"company_id"));
            }
        }
    }
    private <T>T decode(String value,Class<T> type) {try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException("Invalid persisted alarm configuration",e);}}
}

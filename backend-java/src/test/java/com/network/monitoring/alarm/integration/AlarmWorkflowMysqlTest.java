package com.network.monitoring.alarm.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.monitoring.alarm.dto.AlarmAutomation;
import com.network.monitoring.alarm.dto.AlarmModels;
import com.network.monitoring.alarm.exception.AlarmConfigurationException;
import com.network.monitoring.alarm.ingestion.AlarmImportPreviewService;
import com.network.monitoring.alarm.ingestion.AlarmObservationNormalizer;
import com.network.monitoring.alarm.repository.AlarmConfigurationRepository;
import com.network.monitoring.alarm.repository.AlarmDefinitionRepository;
import com.network.monitoring.alarm.security.AlarmTenantAccess;
import com.network.monitoring.alarm.service.AlarmAutomationService;
import com.network.monitoring.alarm.service.AlarmConfigurationService;
import com.network.monitoring.alarm.service.AlarmDefinitionService;
import com.network.monitoring.alarm.service.AlarmReportService;
import com.network.monitoring.alarm.service.AlarmWorkflowService;
import com.network.monitoring.alarm.storage.AlarmSourceStorage;
import com.network.monitoring.alarm.support.AlarmValues;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockMultipartFile;
import static com.network.monitoring.alarm.dto.AlarmConfiguration.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="ALARM_CONFIG_TEST_DB_URL", matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AlarmWorkflowMysqlTest {
    @TempDir Path files;
    JdbcTemplate jdbc; DriverManagerDataSource datasource;
    AlarmWorkflowService workflow; AlarmConfigurationService config;
    AlarmConfigurationRepository master;
    AlarmTenantAccess access;
    long circle, feed;
    AlarmAutomationService automation; AlarmReportService reports;
    static final String HEADER="Circle,Resource,Alarm Number,Alarm Text,Severity,Alarm Time\n";
    @BeforeAll void setup() throws Exception {
        String url=System.getenv("ALARM_CONFIG_TEST_DB_URL");
        if(!url.startsWith("jdbc:mysql://127.0.0.1:13316/alarm_config_test?")) throw new IllegalStateException("Disposable DB required");
        datasource=new DriverManagerDataSource(url,"root",""); jdbc=new JdbcTemplate(datasource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS companies (id BIGINT NOT NULL PRIMARY KEY,company_name VARCHAR(255) NOT NULL,status VARCHAR(32) NOT NULL) ENGINE=InnoDB");
        jdbc.update("INSERT IGNORE INTO companies VALUES(100,'Synthetic A','ACTIVE'),(200,'Synthetic B','ACTIVE')");
        migrate("008_alarm_master_data.sql"); migrate("009_alarm_workflow.sql");
    }
    @BeforeEach void fixture() {
        for(String table:List.of("alarm_escalation_decisions","alarm_operator_events","alarm_delivery_outbox","alarm_observations","alarm_ingestion_runs","alarm_automation_settings","alarm_site_objects","alarm_catalog_rules","alarm_source_feed_profiles","alarm_circle_aliases","alarm_circles","alarm_zones")) jdbc.update("DELETE FROM "+table);
        access=mock(AlarmTenantAccess.class); when(access.require(anyLong(),anyBoolean())).thenReturn(9L);
        master=new AlarmConfigurationRepository(jdbc); config=new AlarmConfigurationService(access,master);
        var zone=config.createZone(100,new ZoneCreate("WEST","West"));
        circle=config.createCircle(100,new CircleCreate(zone.id(),"MU","Mumbai")).id();
        var draft=config.createFeed(100,new FeedCreate("TEST","Test feed","NOKIA","4G","CSV",null,"Asia/Kolkata","SNAPSHOT",circle));
        feed=config.updateFeed(100,draft.id(),new FeedUpdate(draft.name(),draft.oem(),draft.technology(),draft.fileFormat(),null,draft.sourceTimezone(),draft.dumpType(),circle,true,1)).id();
        workflow=new AlarmWorkflowService(jdbc,access,master,new AlarmImportPreviewService(),new ObjectMapper().findAndRegisterModules(),new DataSourceTransactionManager(datasource),new AlarmSourceStorage(files.toString()),new AlarmDefinitionService(access,master,new AlarmDefinitionRepository(jdbc),new DataSourceTransactionManager(datasource)),new AlarmObservationNormalizer(new ObjectMapper().findAndRegisterModules()),true);
        reports=new AlarmReportService(workflow,access,jdbc);
        var mail=new com.network.monitoring.platform.mail.config.ReportMailSettings();
        automation=newAutomation(mail,new com.network.monitoring.platform.mail.ReportMailTransport(mail),"");
    }
    @Test void importsAllRowsBeyondPreviewAndDeduplicatesByFeedSlotNotGloballyByHash() {
        StringBuilder csv=new StringBuilder(HEADER);
        for(int i=0;i<65;i++) csv.append("MU,node-").append(i).append(",100,Alarm,Major,2026-11-01 09:30:00\n");
        long first=submit("2026-11-01T05:00:00Z",csv.toString()); assertTrue(workflow.process(first));
        assertEquals("SUCCEEDED",workflow.run(100,first).get("status"));
        assertEquals(65,count("alarm_observations"));
        assertEquals(first,submit("2026-11-01T05:00:00Z",csv.toString()));
        assertFalse(workflow.process(first)); assertEquals(1,count("alarm_ingestion_runs"));
        long next=submit("2026-11-02T05:00:00Z",csv.toString()); workflow.process(next);
        assertEquals(130,count("alarm_observations"));
        assertTrue(workflow.run(100,next).get("result_json").toString().contains("\"continuingAlarms\":65"));
        assertThrows(AlarmConfigurationException.class,()->submit("2026-11-01T05:00:00Z",csv+"MU,new,100,Alarm,Major,2026-11-01 09:30:00\n"));
        assertEquals(LocalDateTime.of(2026,11,1,5,0),jdbc.queryForObject("SELECT snapshot_at FROM alarm_ingestion_runs WHERE id=?",LocalDateTime.class,first));
    }
    @Test void freezesCatalogueAliasesAndSiteMappingBeforeBackgroundWorkAndRetry() {
        workflow.addCatalog(100,new AlarmModels.Catalog(0,"NOKIA","4G","CODE","100","CRITICAL",true,true,true));
        workflow.addSite(100,feed,new AlarmModels.Site(0,circle,"node","SITE-1"));
        long id=submit("2026-11-01T05:00:00Z",row("MU","100"));
        jdbc.update("DELETE FROM alarm_catalog_rules"); jdbc.update("DELETE FROM alarm_site_objects");
        jdbc.update("UPDATE alarm_circles SET name='Renamed' WHERE id=?",circle);
        workflow.process(id);
        var observation=workflow.observations(100,id,0,100).get(0);
        assertEquals("CRITICAL",observation.get("classification"));
        assertEquals("Mumbai",observation.get("circle_name")); assertEquals("SITE-1",observation.get("site_code"));
        assertEquals(1,((Number)((Map<?,?>)((List<?>)workflow.dashboard(100,"2026-11-02T00:00:00Z").get("circles")).get(0)).get("down_sites")).intValue());
    }
    @Test void unmappedCircleFailsAtomicallyWhileMissingCatalogueRemainsExplicit() {
        long invalid=submit("2026-11-01T05:00:00Z",row("UNKNOWN","100")); workflow.process(invalid);
        assertEquals("FAILED",workflow.run(100,invalid).get("status")); assertEquals(0,count("alarm_observations"));
        long valid=submit("2026-11-02T05:00:00Z",row("MU","100")); workflow.process(valid);
        assertEquals("UNMAPPED",workflow.observations(100,valid,0,10).get(0).get("classification"));
        assertNull(workflow.observations(100,valid,0,10).get(0).get("included"));
        assertThrows(AlarmConfigurationException.class,()->workflow.run(200,valid));
    }
    @Test void tamperedSourceCannotImportAndRetryRetainsOriginalConfiguration() throws Exception {
        String csv=row("MU","100"); long id=submit("2026-11-01T05:00:00Z",csv);
        String key=jdbc.queryForObject("SELECT source_key FROM alarm_ingestion_runs WHERE id=?",String.class,id);
        Files.writeString(workflow.source(key),"tampered"); workflow.process(id);
        assertEquals("FAILED",workflow.run(100,id).get("status")); assertEquals(0,count("alarm_observations"));
        Files.writeString(workflow.source(key),csv); workflow.retry(100,id); workflow.process(id);
        assertEquals("SUCCEEDED",workflow.run(100,id).get("status"));
        assertEquals(2,((Number)workflow.run(100,id).get("attempts")).intValue());
    }
    @Test void emptySnapshotAndLateSnapshotNeverInventClearedAlarms() {
        long first=submit("2026-11-02T05:00:00Z",row("MU","100")); workflow.process(first);
        long empty=submit("2026-11-03T05:00:00Z",HEADER); workflow.process(empty);
        String result=workflow.run(100,empty).get("result_json").toString();
        assertTrue(result.contains("\"notObservedAgain\":1")); assertTrue(result.contains("\"automaticClearingEnabled\":false"));
        long late=submit("2026-11-01T05:00:00Z",row("MU","100")); workflow.process(late);
        var latest=(Map<?,?>)((List<?>)workflow.dashboard(100,"2026-11-04T00:00:00Z").get("sources")).get(0);
        assertEquals(empty,((Number)latest.get("id")).longValue());
    }
    @Test void duplicateRowsCollapseButConflictingIdentityFailsTheWholeBatch() {
        String one="MU,node,100,Alarm,Major,2026-11-01 09:30:00\n";
        long id=submit("2026-11-01T05:00:00Z",HEADER+one+one); workflow.process(id);
        assertEquals(1,count("alarm_observations"));
        long conflict=submit("2026-11-02T05:00:00Z",HEADER+one+one.replace("Major","Minor")); workflow.process(conflict);
        assertEquals("FAILED",workflow.run(100,conflict).get("status")); assertEquals(1,count("alarm_observations"));
    }
    @Test void crashedLeaseCanRecoverAndTwoWorkersCannotCommitTheSameRun() throws Exception {
        long id=submit("2026-11-01T05:00:00Z",row("MU","100"));
        jdbc.update("UPDATE alarm_ingestion_runs SET status='RUNNING',lease_token='old',lease_until=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 MINUTE),attempts=1 WHERE id=?",id);
        assertTrue(workflow.pending().contains(id));
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->workflow.process(id)); var b=pool.submit(()->workflow.process(id));
            assertTrue(a.get(10,TimeUnit.SECONDS)^b.get(10,TimeUnit.SECONDS)); assertEquals(1,count("alarm_observations"));
        } finally {pool.shutdownNow();}
    }
    @Test void rendersFrozenPdfCsvAndCapturesMailWithoutExternalDelivery() throws Exception {
        automation.save(100,new AlarmAutomation.Settings(0,true,60,List.of(new AlarmAutomation.Route("national",null,List.of("qa@example.invalid"),"CAPTURE")),List.of(),List.of()));
        long id=submit("2026-11-01T05:00:00Z",row("MU","100"));workflow.process(id);
        var csv=new String(reports.download(100,id,null,"csv"),StandardCharsets.UTF_8);
        assertTrue(csv.contains("Mumbai"));assertTrue(csv.contains("UNMAPPED"));assertEquals("\"'=HYPERLINK(test)\"",AlarmReportService.cell("=HYPERLINK(test)"));
        byte[] pdf=reports.download(100,id,null,"pdf");
        try(var doc=org.apache.pdfbox.Loader.loadPDF(pdf)) {assertTrue(new org.apache.pdfbox.text.PDFTextStripper().getText(doc).contains("ALARM MONITORING"));}
        automation.plan();automation.plan();assertEquals(1,count("alarm_delivery_outbox"));
        automation.deliverNext();var outbox=automation.deliveries(100).get(0);assertEquals("CAPTURED",outbox.get("status"));
        String message=new String(automation.captured(100,((Number)outbox.get("id")).longValue()),StandardCharsets.UTF_8);
        assertTrue(message.contains("alarm-report.pdf"));assertTrue(message.contains("alarm-details.csv"));
        assertThrows(AlarmConfigurationException.class,()->automation.captured(200,((Number)outbox.get("id")).longValue()));
        String preview=System.getenv("ALARM_REPORT_PREVIEW_DIR");
        if(preview!=null) {Files.createDirectories(Path.of(preview));Files.write(Path.of(preview,"sample-oss-alarm-report.pdf"),pdf);Files.writeString(Path.of(preview,"sample-oss-alarm-report.csv"),csv,StandardCharsets.UTF_8);}
    }
    @Test void escalatesOncePerIdentityAndPolicyAndNeverAutomaticallyResendsUnknownOutcome() {
        workflow.addCatalog(100,new AlarmModels.Catalog(0,"NOKIA","4G","CODE","100","CRITICAL",true,true,true));
        var route=new AlarmAutomation.Route("summary",null,List.of("qa@example.invalid"),"CAPTURE");
        var policy=new AlarmAutomation.Policy("critical-12h",circle,"CRITICAL",12,List.of("qa@example.invalid"),"CAPTURE");
        automation.save(100,new AlarmAutomation.Settings(0,true,60,List.of(route),List.of(policy),List.of()));
        long first=submit("2026-11-01T18:00:00Z",row("MU","100"));workflow.process(first);automation.plan();
        assertEquals(2,count("alarm_delivery_outbox"));assertEquals(1,count("alarm_escalation_decisions"));
        long next=submit("2026-11-02T18:00:00Z",row("MU","100"));workflow.process(next);automation.plan();
        assertEquals(3,count("alarm_delivery_outbox"));assertEquals(1,count("alarm_escalation_decisions"));
        long delivery=((Number)automation.deliveries(100).get(0).get("id")).longValue();
        jdbc.update("UPDATE alarm_delivery_outbox SET status='SENDING',lease_until=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 MINUTE) WHERE id=?",delivery);
        automation.deliverNext();
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT status FROM alarm_delivery_outbox WHERE id=?",String.class,delivery));
        assertThrows(AlarmConfigurationException.class,()->automation.retry(100,delivery));
    }
    @Test void snapshotHistoryAndOperatorActionsRemainTenantScoped() {
        long id=submit("2026-11-01T05:00:00Z",row("MU","100"));workflow.process(id);
        String key=workflow.observations(100,id,0,10).get(0).get("identity_key").toString();
        workflow.operatorEvent(100,feed,key,"ACKNOWLEDGED","Synthetic operator review");
        assertEquals(1,((List<?>)workflow.history(100,feed,key).get("operatorEvents")).size());
        assertThrows(AlarmConfigurationException.class,()->workflow.history(200,feed,key));
    }
    @Test void watchesVerifiedSidecarsInsideApprovedRootWithoutReimportingOldFiles() throws Exception {
        Path input=files.resolve("input");Path directory=Files.createDirectories(input.resolve("feed"));
        jdbc.execute("CREATE TABLE IF NOT EXISTS `user`(id BIGINT PRIMARY KEY,role VARCHAR(32),company_id BIGINT,status VARCHAR(32))");
        jdbc.update("INSERT INTO `user` VALUES(9,'ADMIN',100,'ACTIVE') ON DUPLICATE KEY UPDATE role='ADMIN',company_id=100,status='ACTIVE'");
        var mail=new com.network.monitoring.platform.mail.config.ReportMailSettings();
        automation=newAutomation(mail,new com.network.monitoring.platform.mail.ReportMailTransport(mail),input.toString());
        var settings=new AlarmAutomation.Settings(0,true,60,List.of(),List.of(),List.of(new AlarmAutomation.Watch(feed,"feed","SIDECAR")));
        automation.save(100,settings);
        byte[] source=row("MU","100").getBytes(StandardCharsets.UTF_8);Path file=directory.resolve("daily.csv");Files.write(file,source);
        Files.setLastModifiedTime(file,java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(120)));
        Files.writeString(directory.resolve("daily.csv.ready.json"),"{\"snapshotAt\":\"2026-11-01T05:00:00Z\",\"checksum\":\""+AlarmValues.digest(source)+"\"}");
        automation.pollSources();assertEquals(1,count("alarm_ingestion_runs"));
        automation.pollSources();assertEquals(1,count("alarm_ingestion_runs"));
        assertThrows(AlarmConfigurationException.class,()->automation.save(100,new AlarmAutomation.Settings(1,true,60,List.of(),List.of(),List.of(new AlarmAutomation.Watch(feed,"..","SIDECAR")))));
    }
    @Test void replacesOnlyFailedLatestRevisionWithNewFrozenConfiguration() {
        String csv=row("ALIAS","100");long original=submit("2026-11-01T05:00:00Z",csv);workflow.process(original);
        assertEquals("FAILED",workflow.run(100,original).get("status"));
        config.createAlias(100,new AliasCreate(circle,"ALIAS"));
        workflow.retry(100,original);workflow.process(original);
        assertEquals("FAILED",workflow.run(100,original).get("status"));
        long corrected=((Number)workflow.replace(100,original,new MockMultipartFile("file","alarms.csv","text/csv",csv.getBytes(StandardCharsets.UTF_8))).get("id")).longValue();
        workflow.process(corrected);assertEquals("SUCCEEDED",workflow.run(100,corrected).get("status"));
        assertEquals("SUPERSEDED",workflow.run(100,original).get("status"));
        assertEquals(2,((Number)workflow.run(100,corrected).get("revision")).intValue());
        assertThrows(AlarmConfigurationException.class,()->workflow.retry(100,original));
        assertThrows(AlarmConfigurationException.class,()->workflow.replace(100,corrected,new MockMultipartFile("file","alarms.csv","text/csv",csv.getBytes(StandardCharsets.UTF_8))));
    }
    @Test void nationalCohortIncludesMultipleOemsAndCircleReportCannotLeakAnotherCircle() throws Exception {
        workflow.addCatalog(100,new AlarmModels.Catalog(0,"NOKIA","4G","CODE","100","CRITICAL",true,true,true));
        workflow.addCatalog(100,new AlarmModels.Catalog(0,"ERICSSON","2G","CODE","200","MAJOR",true,true,false));
        var north=config.createZone(100,new ZoneCreate("NORTH","North"));
        var delhi=config.createCircle(100,new CircleCreate(north.id(),"DL","Synthetic Delhi"));
        var draft=config.createFeed(100,new FeedCreate("ERICSSON-2G","Synthetic Ericsson 2G","ERICSSON","2G","CSV",null,"Asia/Kolkata","SNAPSHOT",delhi.id()));
        var second=config.updateFeed(100,draft.id(),new FeedUpdate(draft.name(),draft.oem(),draft.technology(),draft.fileFormat(),null,draft.sourceTimezone(),draft.dumpType(),delhi.id(),true,1));
        long first=submit("2026-11-01T18:00:00Z",row("MU","100"));workflow.process(first);
        long other=((Number)workflow.submit(100,second.id(),"2026-11-01T18:00:00Z",new MockMultipartFile("file","ericsson.csv","text/csv",row("DL","200").getBytes(StandardCharsets.UTF_8))).get("id")).longValue();workflow.process(other);
        byte[] pdf=reports.nationalDownload(100,"2026-11-02T00:00:00Z",null,"pdf");
        String national=new String(reports.nationalDownload(100,"2026-11-02T00:00:00Z",null,"csv"),StandardCharsets.UTF_8);
        assertTrue(national.contains("ERICSSON"));assertTrue(national.contains("NOKIA"));
        String scoped=new String(reports.nationalDownload(100,"2026-11-02T00:00:00Z",circle,"csv"),StandardCharsets.UTF_8);
        assertFalse(scoped.contains("Synthetic Delhi"));assertFalse(scoped.contains("ERICSSON"));
        try(var doc=org.apache.pdfbox.Loader.loadPDF(pdf)) {String text=new org.apache.pdfbox.text.PDFTextStripper().getText(doc);assertTrue(text.contains("NATIONAL / CIRCLE"));assertTrue(text.contains("Synthetic Delhi"));}
        String preview=System.getenv("ALARM_REPORT_PREVIEW_DIR");
        if(preview!=null) {Files.createDirectories(Path.of(preview));Files.write(Path.of(preview,"sample-oss-national-report.pdf"),pdf);Files.writeString(Path.of(preview,"sample-oss-national-report.csv"),national,StandardCharsets.UTF_8);}
    }
    @Test void deltaChangesAreNeverPresentedAsACompleteSourceSnapshot() {
        var source=master.feed(100,feed).orElseThrow();
        config.updateFeed(100,feed,new FeedUpdate(source.name(),source.oem(),source.technology(),source.fileFormat(),source.sheet(),source.sourceTimezone(),"DELTA",circle,true,source.version()));
        assertThrows(AlarmConfigurationException.class,()->submit("2026-11-01T05:00:00Z",row("MU","100")));
        assertEquals(0,count("alarm_ingestion_runs"));
    }
    @Test void deliveryFailuresBeforeSendingBackOffWithoutAnImmediateRetryLoop() {
        var mail=new com.network.monitoring.platform.mail.config.ReportMailSettings();mail.setMaxAttempts(2);
        var transport=mock(com.network.monitoring.platform.mail.ReportMailTransport.class);
        try {when(transport.deliver(anyString(),any(),any())).thenThrow(new java.io.IOException("Synthetic connection failure before sending"));}catch(Exception error){throw new AssertionError(error);}
        automation=newAutomation(mail,transport,"");
        automation.save(100,new AlarmAutomation.Settings(0,true,60,List.of(new AlarmAutomation.Route("preview",null,List.of("qa@example.invalid"),"CAPTURE")),List.of(),List.of()));
        long id=submit("2026-11-01T05:00:00Z",row("MU","100"));workflow.process(id);automation.plan();automation.deliverNext();
        long delivery=((Number)automation.deliveries(100).get(0).get("id")).longValue();
        assertEquals("QUEUED",automation.deliveries(100).get(0).get("status"));
        automation.deliverNext();assertEquals(1,((Number)automation.deliveries(100).get(0).get("attempts")).intValue());
        jdbc.update("UPDATE alarm_delivery_outbox SET next_attempt_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE id=?",delivery);
        automation.deliverNext();assertEquals("FAILED",automation.deliveries(100).get(0).get("status"));
    }
    @Test void deactivatedFeedsCannotCommitQueuedWorkAndAreRemovedFromCurrentCohorts() {
        long id=submit("2026-11-01T05:00:00Z",row("MU","100"));
        jdbc.update("UPDATE alarm_source_feed_profiles SET active=FALSE WHERE id=?",feed);workflow.process(id);
        assertEquals("FAILED",workflow.run(100,id).get("status"));assertEquals(0,count("alarm_observations"));
        jdbc.update("UPDATE alarm_source_feed_profiles SET active=TRUE WHERE id=?",feed);workflow.retry(100,id);workflow.process(id);
        assertEquals("SUCCEEDED",workflow.run(100,id).get("status"));
        jdbc.update("UPDATE alarm_source_feed_profiles SET active=FALSE WHERE id=?",feed);
        assertTrue(((List<?>)workflow.dashboard(100,"2026-11-02T00:00:00Z").get("sources")).isEmpty());
        assertEquals(1,workflow.observations(100,id,0,100).size());
    }

    private AlarmAutomationService newAutomation(com.network.monitoring.platform.mail.config.ReportMailSettings mail,
            com.network.monitoring.platform.mail.ReportMailTransport transport,String inputRoot) {
        var mapper=new ObjectMapper().findAndRegisterModules();
        var inputFolders=new com.network.monitoring.alarm.storage.AlarmInputFolders(inputRoot);
        var folders=new com.network.monitoring.alarm.scheduling.AlarmFolderWatcher(jdbc,master,workflow,mapper,inputFolders);
        var delivery=new com.network.monitoring.alarm.service.AlarmMailDeliveryService(jdbc,access,workflow,reports,transport,mail,mapper,files.toString());
        return new AlarmAutomationService(jdbc,access,master,workflow,reports,mail,mapper,new DataSourceTransactionManager(datasource),inputFolders,folders,delivery);
    }

    private long submit(String at,String data) {return ((Number)workflow.submit(100,feed,at,new MockMultipartFile("file","alarms.csv","text/csv",data.getBytes(StandardCharsets.UTF_8))).get("id")).longValue();}
    private String row(String circle,String code) {return HEADER+circle+",node,"+code+",Alarm,Major,2026-11-01 09:30:00\n";}
    private int count(String table) {return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    private void migrate(String file) throws Exception {try(var c=datasource.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource("db/migrations/"+file));}}
}

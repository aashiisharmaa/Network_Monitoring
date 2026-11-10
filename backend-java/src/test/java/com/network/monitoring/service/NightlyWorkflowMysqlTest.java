package com.network.monitoring.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="KPI_TEST_DB_URL", matches=".+")
class NightlyWorkflowMysqlTest {
    @TempDir Path directory;
    Connection connection; JdbcTemplate jdbc; KpiSnapshotStore store;
    KpiIngestionService ingestion; DailyAnalysisService analysis; CommonReportService reports; ReportMailOutboxService mail;
    NightlyWorkflowService service; ObjectProvider<NightlyWorkflowService> provider;
    MutableClock clock = new MutableClock(Instant.parse("2026-11-04T19:30:00Z"));
    LocalDate day = LocalDate.of(2026,11,4); Path source;
    static class MutableClock extends Clock {
        Instant value; MutableClock(Instant value) { this.value=value; }
        public ZoneId getZone() { return NightlyProfile.ZONE; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(value,zone); }
        public Instant instant() { return value; }
    }
    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        connection=DriverManager.getConnection(System.getenv("KPI_TEST_DB_URL"),System.getenv("KPI_TEST_DB_USER"),System.getenv("KPI_TEST_DB_PASSWORD"));
        var ds=new SingleConnectionDataSource(connection,true); jdbc=new JdbcTemplate(ds);
        for(String table:List.of("uploadhistory","kpi_nightly_profiles","kpi_nightly_runs","kpi_nightly_events")) {
            String ddl=jdbc.queryForMap("SHOW CREATE TABLE `"+table+"`").get("Create Table").toString().replaceFirst("CREATE TABLE","CREATE TEMPORARY TABLE");
            ddl=ddl.lines().filter(line->!line.stripLeading().startsWith("CONSTRAINT ")).collect(java.util.stream.Collectors.joining("\n")).replace(",\n)","\n)"); jdbc.execute(ddl);
        }
        jdbc.update("INSERT INTO uploadhistory (id,remarks) VALUES (500,'[kpi] nightly fixture')"); store=new KpiSnapshotStore(jdbc,new DataSourceTransactionManager(ds));
        ingestion=mock(KpiIngestionService.class); analysis=mock(DailyAnalysisService.class); reports=mock(CommonReportService.class); mail=mock(ReportMailOutboxService.class);
        when(analysis.profile(500)).thenReturn(Map.of("profileHash","profile-v1"));
        when(mail.profile(500)).thenReturn(Map.of("profileHash","mail-v1","outboundEnabled",false,"profile",Map.of("enabled",true,"mode","CAPTURE")));
        when(ingestion.startPath(any(),eq(500L),any(),eq(day),anyString(),anyLong())).thenReturn(Map.of("data",Map.of("id",10L)));
        when(ingestion.get(10)).thenReturn(Map.of("id",10L,"status","COMPLETED","result",Map.of("analysisSnapshotId",20L,"datasetRevision","revision-v1")));
        when(analysis.get(20)).thenReturn(snapshot("profile-v1"));
        when(reports.request(eq(20L),any())).thenReturn(Map.of("id",30L)); when(reports.get(30)).thenReturn(Map.of("id",30L,"status","READY"));
        when(reports.publishedAt(30)).thenAnswer(call->clock.instant());
        when(mail.request(30L,null,"mail-v1")).thenReturn(Map.of("deliveries",List.of(Map.of("id",40L)),"capacityLimited",false));
        when(mail.get(40)).thenReturn(Map.of("id",40L,"status","CAPTURED")); when(mail.completedAt(40)).thenAnswer(call->clock.instant());
        provider=mock(ObjectProvider.class); when(provider.getObject()).thenReturn(mock(NightlyWorkflowService.class));
        service=fresh(); service.configure(500,profile("CAPTURE"),1L);
        source=directory.resolve("kpi-2026-11-04.csv");
    }
    NightlyWorkflowService fresh() { var value=new NightlyWorkflowService(jdbc,store,ingestion,analysis,reports,mail,provider,clock); value.initialize(); return value; }
    Map<String,Object> snapshot(String hash) { return Map.of("id",20L,"profileHash",hash,"datasetVersion","revision-v1","status","COMPLETED","dataDate",day.toString(),"summary",Map.of("dataStatus","AVAILABLE")); }
    NightlyProfile profile(String mode) { return new NightlyProfile(true,directory.toString(),"kpi-{date}.csv",List.of("date","cellName"),Map.of(),mode,"01:30",new CommonReportModules.Options(List.of("worstCells"),Map.of())); }
    long start() { return ((Number)service.request(500,day,false,1L).get("id")).longValue(); }
    void writeSource(String text) throws Exception { Files.writeString(source,text); Files.setLastModifiedTime(source,FileTime.from(clock.instant().minusSeconds(120))); }
    void stable() { service.sweep(); clock.value=clock.value.plusSeconds(61); service.sweep(); }
    void pump(int count) { for(int i=0;i<count;i++)service.sweep(); }
    @AfterEach void close() throws Exception { if(connection!=null)connection.close(); }

    @Test void completesPinnedStagesCapturesMailAndDeduplicatesAcrossRestart() throws Exception {
        writeSource("synthetic daily input"); long id=start(); stable(); pump(10);
        var run=service.get(id); assertEquals("COMPLETED",run.get("status"),run.toString()); assertEquals("MAIL_CAPTURED",run.get("completionMode")); assertEquals("MET",run.get("deadlineStatus"));
        assertEquals("2026-11-04T20:30:00Z",run.get("deadlineAt")); assertFalse(run.containsKey("configJson"));
        assertEquals(id,((Number)fresh().request(500,day,false,1L).get("id")).longValue());
        verify(ingestion,times(1)).startPath(eq(source),eq(500L),any(),eq(day),eq(RecommendationCacheKey.fileDigest(source)),anyLong());
        verify(reports,times(1)).request(eq(20L),any()); verify(mail,times(1)).request(30L,null,"mail-v1");
    }
    @Test void missingInputFailsAtCutoffAndRecordsOnlyOneDeadlineAlert() {
        long id=start();service.sweep();assertEquals("ACTIVE",service.get(id).get("status"));
        clock.value=Instant.parse("2026-11-04T20:00:00Z");service.sweep();assertEquals("FAILED",service.get(id).get("status"));
        clock.value=Instant.parse("2026-11-04T20:31:00Z");service.deadlineTick();service.deadlineTick();assertEquals("MISSED",service.get(id).get("deadlineStatus"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM kpi_nightly_events WHERE runId=? AND event='DEADLINE_MISSED'",Integer.class,id));verifyNoInteractions(ingestion);
    }
    @Test void changingFileResetsStabilityAndCrashGapReusesTheChildRequest() throws Exception {
        writeSource("first");long id=start();service.sweep();clock.value=clock.value.plusSeconds(30);writeSource("changed and still copying");service.sweep();
        clock.value=clock.value.plusSeconds(40);service.sweep();assertEquals("WAITING_INPUT",service.get(id).get("stage"));
        clock.value=clock.value.plusSeconds(21);service.sweep();assertEquals("IMPORT",service.get(id).get("stage"));service.sweep();
        jdbc.update("UPDATE kpi_nightly_runs SET ingestionId=NULL WHERE id=?",id);service=fresh();service.sweep();
        assertEquals(10L,((Number)service.get(id).get("ingestionId")).longValue());verify(ingestion,times(2)).startPath(any(),anyLong(),any(),any(),anyString(),anyLong());
    }
    @Test void unknownDeliveryNeverResendsAndUsesActualAcceptanceTimeForTheDeadline() throws Exception {
        writeSource("input");long id=start();when(mail.get(40)).thenReturn(Map.of("id",40L,"status","UNKNOWN"));stable();pump(10);
        assertEquals("BLOCKED",service.get(id).get("status"));pump(3);verify(mail,never()).retry(anyLong(),any());
        clock.value=Instant.parse("2026-11-04T20:31:00Z");service.sweep();assertEquals("MISSED",service.get(id).get("deadlineStatus"));
        when(mail.get(40)).thenReturn(Map.of("id",40L,"status","CAPTURED"));when(mail.completedAt(40)).thenReturn(Instant.parse("2026-11-04T20:29:00Z"));service.sweep();
        assertEquals("COMPLETED",service.get(id).get("status"));assertEquals("MET",service.get(id).get("deadlineStatus"));verify(mail,times(1)).request(30L,null,"mail-v1");
    }
    @Test void explicitReportRetryKeepsCommittedInputAndCompletedAnalysis() throws Exception {
        writeSource("input");long id=start();when(reports.get(30)).thenReturn(Map.of("id",30L,"status","FAILED"));stable();pump(8);assertEquals("FAILED",service.get(id).get("status"));
        when(reports.retry(30)).thenAnswer(call->{when(reports.get(30)).thenReturn(Map.of("id",30L,"status","READY"));return Map.of("id",30L);});
        service.retry(id,false,false,1L);pump(5);assertEquals("COMPLETED",service.get(id).get("status"));verify(reports,times(1)).retry(30);verify(ingestion,never()).retry(anyLong());
        assertThrows(IllegalArgumentException.class,()->service.retry(id,true,false,1L));
    }
    @Test void cronCatchupCreatesOnePreviousDayRunAndNeverStartsBeforeOneIst() throws Exception {
        ReflectionTestUtils.setField(service,"schedulerEnabled",true);clock.value=Instant.parse("2026-11-04T19:29:00Z");service.sweep();assertTrue(service.list(500,20).isEmpty());
        clock.value=Instant.parse("2026-11-04T19:30:00Z");pump(3);assertEquals(1,service.list(500,20).size());assertEquals(day.toString(),service.list(500,20).get(0).get("dataDate"));
    }
    @Test void refusesAnalysisFromAnotherProfileOrDatasetVersion() throws Exception {
        writeSource("input");long id=start();when(analysis.get(20)).thenReturn(snapshot("changed-profile"));stable();pump(7);assertEquals("FAILED",service.get(id).get("status"));verifyNoInteractions(reports);
    }
    @Test void queueRejectionUsesBoundedKnownSafeRetriesWithoutAnotherImport() throws Exception {
        writeSource("input");long id=start();when(reports.get(30)).thenReturn(Map.of("id",30L,"status","FAILED","message","Report workers and queue are full. Retry this report."));
        when(reports.retry(30)).thenAnswer(call->{when(reports.get(30)).thenReturn(Map.of("id",30L,"status","READY"));return Map.of("id",30L);});
        stable();pump(12);assertEquals("COMPLETED",service.get(id).get("status"));assertEquals(2,service.get(id).get("attempts"));verify(reports,times(1)).retry(30);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM kpi_nightly_events WHERE event='AUTOMATIC_SAFE_RETRY'",Integer.class));
    }
    @Test void lateReplayKeepsTheOriginalDeadlineAndExplicitlyReportsMissedTarget() throws Exception {
        clock.value=Instant.parse("2026-11-04T20:35:00Z");writeSource("late input");long id=((Number)service.request(500,day,true,1L).get("id")).longValue();stable();pump(10);
        assertEquals("COMPLETED",service.get(id).get("status"));assertEquals("MISSED",service.get(id).get("deadlineStatus"));assertNotNull(service.get(id).get("deadlineMissedAt"));
    }
    @Test void blockedOldDeliveryDoesNotConsumeTheNextDaysActiveCapacity() throws Exception {
        ReflectionTestUtils.setField(service,"maxActive",1);writeSource("input");long id=start();when(mail.get(40)).thenReturn(Map.of("id",40L,"status","UNKNOWN"));stable();pump(10);assertEquals("BLOCKED",service.get(id).get("status"));
        ReflectionTestUtils.setField(service,"schedulerEnabled",true);clock.value=Instant.parse("2026-11-05T19:30:00Z");service.sweep();
        assertEquals(2,service.list(500,20).size());assertEquals("2026-11-05",service.list(500,20).get(0).get("dataDate"));assertEquals("ACTIVE",service.list(500,20).get(0).get("status"));
        verify(mail,never()).retry(anyLong(),any());
    }
}

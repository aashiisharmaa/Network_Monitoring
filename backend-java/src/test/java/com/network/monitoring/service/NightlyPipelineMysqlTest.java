package com.network.monitoring.service;

import com.network.monitoring.entity.UploadHistory;
import com.network.monitoring.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import static com.network.monitoring.service.DailyKpiCalculator.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NightlyPipelineMysqlTest {
    @TempDir Path directory;
    @Test @EnabledIfEnvironmentVariable(named="KPI_TEST_DB_URL",matches=".+") @SuppressWarnings("unchecked")
    void realCsvFlowsThroughAppendAnalysisPdfAndCapturedMimeWithoutTouchingLiveTables() throws Exception {
        try(var connection=DriverManager.getConnection(System.getenv("KPI_TEST_DB_URL"),System.getenv("KPI_TEST_DB_USER"),System.getenv("KPI_TEST_DB_PASSWORD"))) {
            var ds=new SingleConnectionDataSource(connection,true);var jdbc=new JdbcTemplate(ds);
            for(String table:List.of("uploadhistory","uploaddata","kpidynamicdata","kpi_metric_values","kpi_dataset_snapshots","kpi_ingestion_runs","site_prediction_results","kpi_analysis_profiles","kpi_analysis_snapshots","kpi_daily_groups","kpi_common_reports","kpi_mail_profiles","kpi_mail_outbox","kpi_mail_attempts","kpi_mail_events","kpi_nightly_profiles","kpi_nightly_runs","kpi_nightly_events")) {
                String ddl=jdbc.queryForMap("SHOW CREATE TABLE `"+table+"`").get("Create Table").toString().replaceFirst("CREATE TABLE","CREATE TEMPORARY TABLE");
                ddl=ddl.lines().filter(line->!line.stripLeading().startsWith("CONSTRAINT ")).collect(java.util.stream.Collectors.joining("\n")).replace(",\n)","\n)");jdbc.execute(ddl);
            }
            jdbc.update("INSERT INTO uploadhistory (id,remarks,companyId) VALUES (500,'[kpi] synthetic nightly pipeline',99)");
            var store=new KpiSnapshotStore(jdbc,new DataSourceTransactionManager(ds));var cache=new KpiCacheService(directory.resolve("cache").toString(),store);var dynamic=new KpiDynamicDataService(jdbc);
            var target=new UploadHistory();target.setId(500L);target.setCompanyId(99L);target.setRemarks("[kpi] synthetic nightly pipeline");
            var history=mock(UploadHistoryRepository.class);when(history.findById(500L)).thenReturn(Optional.of(target));var role=mock(RoleAuthorizationService.class);when(role.currentUserCompanyIdOrNull()).thenReturn(99L);
            var uploads=spy(new UploadService(new ParserService(),null,history,null,null,null,null,cache,dynamic,null,null,null,role,jdbc,null,store));doNothing().when(uploads).afterDailyCommit(anyLong());
            ObjectProvider<DailyAnalysisService> analysisProvider=mock(ObjectProvider.class);var analysis=new DailyAnalysisService(jdbc,store,cache,analysisProvider);when(analysisProvider.getObject()).thenReturn(analysis);analysis.initialize();
            analysis.saveProfile(500,new Profile(List.of("cellName"),List.of(new Metric("dlprbutilizationrate",Aggregation.MEAN,Direction.LOWER_IS_BETTER,"PERCENT",null,null,null,true)),7,1,true));
            ObjectProvider<KpiIngestionService> ingestionProvider=mock(ObjectProvider.class);var ingestion=new KpiIngestionService(jdbc,store,cache,dynamic,uploads,history,ingestionProvider,directory.toString(),analysis);when(ingestionProvider.getObject()).thenReturn(ingestion);
            var ruleRepository=mock(KpiThresholdRuleRepository.class);when(ruleRepository.findAllByEnabledTrue()).thenReturn(List.of());var alarms=mock(KpiAlarmService.class);when(alarms.listRules()).thenReturn(List.of());
            var modules=new CommonReportModules(ruleRepository,new KpiThresholdRuleService(ruleRepository,dynamic,jdbc),alarms,mock(RecommendationService.class),mock(SitePredictionService.class));
            ObjectProvider<CommonReportService> reportProvider=mock(ObjectProvider.class);var reports=new CommonReportService(jdbc,analysis,cache,store,modules,new CommonReportRenderer(),reportProvider,directory.resolve("reports").toString());when(reportProvider.getObject()).thenReturn(reports);reports.initialize();
            var settings=new ReportMailSettings();settings.setSpoolPath(directory.resolve("mail").toString());ObjectProvider<ReportMailOutboxService> mailProvider=mock(ObjectProvider.class);
            var mail=new ReportMailOutboxService(jdbc,store,reports,settings,new ReportMailTransport(settings),mailProvider);when(mailProvider.getObject()).thenReturn(mail);mail.initialize();
            mail.configure(500,new ReportMailProfile("CAPTURE",List.of("qa@example.test"),List.of("pdf"),false,true),1L);
            var clock=new NightlyWorkflowMysqlTest.MutableClock(Instant.now());ObjectProvider<NightlyWorkflowService> workflowProvider=mock(ObjectProvider.class);when(workflowProvider.getObject()).thenReturn(mock(NightlyWorkflowService.class));
            var nightly=new NightlyWorkflowService(jdbc,store,ingestion,analysis,reports,mail,workflowProvider,clock);nightly.initialize();
            LocalDate day=LocalDate.now(NightlyProfile.ZONE).minusDays(2);Path input=directory.resolve("kpi-"+day+".csv");
            Files.writeString(input,"date,cellname,site,tech,dlprbutilizationrate\n"+day+",DEMO_CELL,DEMO_SITE,LTE,80\n");Files.setLastModifiedTime(input,FileTime.from(clock.instant().minusSeconds(120)));
            nightly.configure(500,new NightlyProfile(false,directory.toString(),"kpi-{date}.csv",List.of("date","cellName"),Map.of(),"CAPTURE","01:30",new CommonReportModules.Options(List.of("worstCells"),Map.of())),1L);
            long id=((Number)nightly.request(500,day,true,1L).get("id")).longValue();nightly.sweep();clock.value=clock.value.plusSeconds(61);for(int i=0;i<15;i++)nightly.sweep();
            var run=nightly.get(id);assertEquals("COMPLETED",run.get("status"),run.toString());assertEquals("MAIL_CAPTURED",run.get("completionMode"));assertEquals("MISSED",run.get("deadlineStatus"));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM uploaddata",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM kpidynamicdata",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM kpi_analysis_snapshots WHERE status='COMPLETED'",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM kpi_common_reports WHERE status='READY'",Integer.class));
            long delivery=((Number)((List<?>)run.get("deliveryIds")).get(0)).longValue();assertEquals("CAPTURED",mail.get(delivery).get("status"));assertFalse(settings.isOutboundEnabled());
            long report=((Number)run.get("reportId")).longValue();assertTrue(Files.size(reports.download(report,"pdf"))>1000);assertTrue(Files.size(mail.captured(delivery))>1000);
            assertTrue(Files.exists(input)); // Input was never moved/deleted.
            String preview=System.getenv("KPI_REPORT_PREVIEW_DIR");if(preview!=null) {
                Files.copy(mail.captured(delivery),Path.of(preview).resolve("nightly-replay-sample.eml"),StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(Path.of(preview).resolve("nightly-replay-sample.json"),CommonReportModules.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(run));
            }
            nightly.request(500,day,true,1L);nightly.sweep();assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM kpi_ingestion_runs",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM kpi_mail_outbox",Integer.class));
        }
    }
}

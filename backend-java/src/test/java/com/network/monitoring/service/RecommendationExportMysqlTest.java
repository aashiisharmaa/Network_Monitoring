package com.network.monitoring.service;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
@EnabledIfEnvironmentVariable(named="KPI_TEST_DB_URL",matches=".+")
class RecommendationExportMysqlTest{
    @TempDir Path directory;Connection connection;JdbcTemplate jdbc;RcaArtifactStore artifacts;RecommendationService rca;RecommendationExportService exports,held;ObjectProvider<RecommendationExportService> provider;
    @BeforeEach @SuppressWarnings("unchecked") void setup()throws Exception{
        connection=DriverManager.getConnection(System.getenv("KPI_TEST_DB_URL"),System.getenv("KPI_TEST_DB_USER"),System.getenv("KPI_TEST_DB_PASSWORD"));var ds=new SingleConnectionDataSource(connection,true);jdbc=new JdbcTemplate(ds);
        for(String table:List.of("uploadhistory","kpi_rca_exports")){String ddl=jdbc.queryForMap("SHOW CREATE TABLE `"+table+"`").get("Create Table").toString().replaceFirst("CREATE TABLE","CREATE TEMPORARY TABLE");ddl=ddl.lines().filter(line->!line.stripLeading().startsWith("CONSTRAINT ")).collect(java.util.stream.Collectors.joining("\n")).replace(",\n)","\n)");jdbc.execute(ddl);}
        jdbc.update("INSERT INTO uploadhistory (id,remarks) VALUES (500,'[kpi] export fixture')");artifacts=new RcaArtifactStore(directory.resolve("artifacts").toString());Path source=directory.resolve("full-frames");Files.writeString(source,"captured frames");artifacts.publishAnalysis("a".repeat(64),source);
        rca=mock(RecommendationService.class);when(rca.currentCacheKey(anyLong(),anyMap())).thenReturn(Optional.of("a".repeat(64)));
        doAnswer(call->{Files.writeString(call.getArgument(1),"synthetic workbook bytes");return null;}).when(rca).renderStoredAnalysis(any(),any());
        provider=mock(ObjectProvider.class);held=mock(RecommendationExportService.class);when(provider.getObject()).thenReturn(held);
        exports=new RecommendationExportService(jdbc,new KpiSnapshotStore(jdbc,new DataSourceTransactionManager(ds)),rca,artifacts,provider);exports.initialize();
    }
    @AfterEach void close()throws Exception{if(connection!=null)connection.close();}
    @Test void concurrentRequestsCoalesceAndCompletedExportsReuseVerifiedBytes()throws Exception{
        long id=((Number)exports.start(500,Map.of()).get("id")).longValue();assertEquals(id,((Number)exports.start(500,Map.of()).get("id")).longValue());verify(held,times(1)).process(id);
        exports.process(id);assertEquals("COMPLETED",exports.get(id).get("status"));Path workbook=exports.download(id);assertEquals("synthetic workbook bytes",Files.readString(workbook));exports.process(id);exports.start(500,Map.of());
        verify(rca,times(1)).renderStoredAnalysis(any(),any());verify(rca,never()).runRecommendation(anyLong(),anyMap(),anyBoolean());
        Files.writeString(workbook,"tampered");assertThrows(java.io.IOException.class,()->exports.download(id));long rebuilt=((Number)exports.start(500,Map.of()).get("id")).longValue();assertNotEquals(id,rebuilt);exports.process(rebuilt);assertEquals("COMPLETED",exports.get(rebuilt).get("status"));
    }
    @Test void dataChangesAndRestartRecoveryCannotPublishStaleOrOrphanedJobs()throws Exception{
        long id=((Number)exports.start(500,Map.of()).get("id")).longValue();when(rca.currentCacheKey(anyLong(),anyMap())).thenReturn(Optional.of("b".repeat(64)));exports.process(id);assertEquals("FAILED",exports.get(id).get("status"));verify(rca,never()).renderStoredAnalysis(any(),any());
        long queued=((Number)exports.start(500,Map.of()).get("id")).longValue();exports.initialize();assertEquals("FAILED",exports.get(queued).get("status"));
        doThrow(new RejectedExecutionException()).when(held).process(anyLong());var rejected=assertThrows(JobCapacityException.class,()->exports.start(500,Map.of()));assertEquals("FAILED",exports.get(rejected.getJobId()).get("status"));
    }
}

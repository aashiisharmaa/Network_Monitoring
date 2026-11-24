package com.network.monitoring.ingestion.service;

import com.network.monitoring.identity.service.RoleAuthorizationService;
import com.network.monitoring.ingestion.entity.UploadHistory;
import com.network.monitoring.ingestion.repository.UploadHistoryRepository;
import com.network.monitoring.ingestion.storage.FileStorageService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="KPI_TEST_DB_URL",matches=".+")
class SiteUploadMysqlTest {
    @TempDir Path directory;
    @Test @SuppressWarnings("unchecked")
    void realSiteCsvPersistsValidValuesAndReportsDuplicatesAndInvalidCoordinates() throws Exception {
        String url=System.getenv("KPI_TEST_DB_URL");
        if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:13316/kpi_project_test(?:\\?.*)?"))
            throw new IllegalStateException("Only disposable project DB accepted.");
        try(var connection=DriverManager.getConnection(url,"root","")) {
            var jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            String ddl=jdbc.queryForMap("SHOW CREATE TABLE sitedata").get("Create Table").toString()
                    .replaceFirst("CREATE TABLE","CREATE TEMPORARY TABLE");
            jdbc.execute(ddl);
            var history=mock(UploadHistoryRepository.class);
            when(history.save(any())).thenAnswer(call->{UploadHistory value=call.getArgument(0);value.setId(500L);return value;});
            var role=mock(RoleAuthorizationService.class);when(role.currentUserCompanyIdOrNull()).thenReturn(99L);
            var uploads=new UploadService(new ParserService(),new FileStorageService(directory.toString()),history,
                    null,null,null,null,null,null,null,null,null,role,jdbc,null,null);
            String csv="siteid,cell_name,latitude,longitude,pci,tac,earfcn,azimuth\n"
                    +"DEMO_SITE,DEMO_SITE_L18_A,-1.95,30.05,21,43000,1650,120\n"
                    +"DEMO_SITE,DEMO_SITE_L18_A,-1.95,30.05,21,43000,1650,120\n"
                    +"BAD,BAD_L18_A,999,999,22,43000,1650,0\n"
                    +",,-1.95,30.05,23,43000,1650,0\n";
            var result=uploads.uploadSiteData(new MockMultipartFile("file","SYNTHETIC_sites.csv","text/csv",csv.getBytes(StandardCharsets.UTF_8)),"synthetic test","test-only");
            assertEquals(true,result.get("success"),result.toString());
            var data=(Map<String,Object>)result.get("data");
            assertEquals(1,data.get("insertedRows"));assertEquals(1,data.get("duplicateRows"));assertEquals(2,data.get("invalidRows"));
            var saved=jdbc.queryForMap("SELECT * FROM sitedata");
            assertEquals(99L,((Number)saved.get("companyId")).longValue());
            assertEquals(500L,((Number)saved.get("fileId")).longValue());
            assertEquals(21,((Number)saved.get("PCI")).intValue());
            assertEquals(43000,((Number)saved.get("TAC")).intValue());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sitedata",Integer.class));
            verify(role,times(1)).currentUserCompanyIdOrNull();
        }
    }
}

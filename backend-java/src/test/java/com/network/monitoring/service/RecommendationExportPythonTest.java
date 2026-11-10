package com.network.monitoring.service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class RecommendationExportPythonTest {
    @TempDir Path directory;
    @Test @EnabledIfEnvironmentVariable(named="KPI_RCA_EXPORT_BENCHMARK_CONFIG",matches=".+")
    void measuresCompletedAnalysisExportOnLocalReadOnlyDataset() throws Exception {
        var config=CommonReportModules.JSON.readTree(Files.readString(Path.of(System.getenv("KPI_RCA_EXPORT_BENCHMARK_CONFIG"))));
        long fileId=config.get("fileId").asLong();Path source=Path.of(config.get("cacheFile").asText());
        Path cachePath=directory.resolve("benchmark-cache");Files.createDirectories(cachePath);Files.copy(source,cachePath.resolve(fileId+".json"));
        Map<String,Object> body=CommonReportModules.JSON.convertValue(config.get("request"),new com.fasterxml.jackson.core.type.TypeReference<>(){});
        var artifacts=new RcaArtifactStore(directory.resolve("benchmark-artifacts").toString());
        var rca=spy(new RecommendationService(new KpiCacheService(cachePath.toString()),null,null,new RcaConcurrencyGate(1,30),artifacts));
        ReflectionTestUtils.setField(rca,"pythonServicePath",Path.of(System.getProperty("user.dir")).getParent().resolve("python service").toString());
        ReflectionTestUtils.setField(rca,"pythonBin",System.getenv("KPI_RCA_PYTHON"));
        long started=System.nanoTime();var analysis=rca.runRecommendation(fileId,body,false);
        try{rca.retainArtifacts(analysis,fileId,body);}finally{rca.cleanup(analysis.workDir);}
        long analysisMs=(System.nanoTime()-started)/1_000_000;clearInvocations(rca);
        var exports=new RecommendationExportService(null,null,rca,artifacts,null);
        started=System.nanoTime();Path first=exports.blocking(fileId,body);long firstMs=(System.nanoTime()-started)/1_000_000;
        started=System.nanoTime();Path second=exports.blocking(fileId,body);long repeatMs=(System.nanoTime()-started)/1_000_000;
        assertEquals(first,second);verify(rca,never()).runRecommendation(anyLong(),anyMap(),anyBoolean());verify(rca,times(1)).renderStoredAnalysis(any(),any());
        var metrics=Map.of("fileId",fileId,"sourceBytes",Files.size(source),"sourceSha256",RecommendationCacheKey.fileDigest(source),"analysisAndRetentionMs",analysisMs,"firstExcelFromCompletedAnalysisMs",firstMs,"repeatCachedExcelMs",repeatMs,"excelBytes",Files.size(first),"customerDatabaseWrites",0);
        Path output=Path.of(System.getenv("KPI_RCA_EXPORT_BENCHMARK_CONFIG")).resolveSibling("rca-export-benchmark.json");Files.writeString(output,CommonReportModules.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(metrics));
        System.out.println("[RCA-EXPORT-BENCHMARK] "+metrics);
    }
    @Test @EnabledIfEnvironmentVariable(named="KPI_RCA_PYTHON",matches=".+")
    void completedAnalysisBuildsExcelWithoutReanalysisAndRepeatedExportsReuseBytes()throws Exception{
        var cache=new KpiCacheService(directory.resolve("cache").toString());var artifacts=new RcaArtifactStore(directory.resolve("artifacts").toString());
        List<Map<String,Object>> records=new ArrayList<>();for(int i=0;i<12;i++)records.add(Map.of("date","2026-03-%02d".formatted(i/2+1),"cellName","CELL_"+i%2,"site","SITE","tech","LTE","metrics",Map.of("mvpuschsinr",i<10?12:-4,"mveutranaveragecqi",i<10?10:1)));
        cache.writeKpiCache(1,Map.of("records",records));var rca=spy(new RecommendationService(cache,null,null,new RcaConcurrencyGate(1,30),artifacts));
        ReflectionTestUtils.setField(rca,"pythonServicePath",Path.of(System.getProperty("user.dir")).getParent().resolve("python service").toString());ReflectionTestUtils.setField(rca,"pythonBin",System.getenv("KPI_RCA_PYTHON"));
        Map<String,Object> body=Map.of("vendor","ericsson","columnMapping",Map.of("Short name","cellname","Date","date","Cell I'd","cellname","Site I'd","site","Tech","tech"),"kpiMapping",Map.of("MV_PUSCH SINR","mvpuschsinr","MV_E-UTRAN Average CQI","mveutranaveragecqi"),"selectedKpis",List.of("MV_PUSCH SINR","MV_E-UTRAN Average CQI"),"responseRowLimit",1);
        var analysis=rca.runRecommendation(1,body,false);try{rca.retainArtifacts(analysis,1,body);}finally{rca.cleanup(analysis.workDir);}
        assertNotNull(artifacts.analysis(rca.currentCacheKey(1,body).orElseThrow()));clearInvocations(rca);
        var exports=new RecommendationExportService(null,null,rca,artifacts,null);Path first=exports.blocking(1,body);String checksum=RecommendationCacheKey.fileDigest(first);var modified=Files.getLastModifiedTime(first);
        Path second=exports.blocking(1,body);assertEquals(first,second);assertEquals(checksum,RecommendationCacheKey.fileDigest(second));assertEquals(modified,Files.getLastModifiedTime(second));
        verify(rca,never()).runRecommendation(anyLong(),anyMap(),anyBoolean());verify(rca,times(1)).renderStoredAnalysis(any(),any());
        try(var book=org.apache.poi.ss.usermodel.WorkbookFactory.create(first.toFile())){assertEquals(12,book.getSheet("All_Data_Scored").getLastRowNum());}
    }
}

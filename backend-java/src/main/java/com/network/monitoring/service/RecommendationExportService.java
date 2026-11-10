package com.network.monitoring.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;

@Service
public class RecommendationExportService {
    private final JdbcTemplate jdbc; private final KpiSnapshotStore transactions;
    private final RecommendationService rca; private final RcaArtifactStore artifacts;
    private final ObjectProvider<RecommendationExportService> self;
    @Value("${app.jobs.recover-on-startup:true}") private boolean recover = true;
    public RecommendationExportService(JdbcTemplate jdbc, KpiSnapshotStore transactions, RecommendationService rca, RcaArtifactStore artifacts, ObjectProvider<RecommendationExportService> self) {
        this.jdbc=jdbc;this.transactions=transactions;this.rca=rca;this.artifacts=artifacts;this.self=self;
    }
    @PostConstruct public void initialize() {
        try { jdbc.queryForList("SELECT id FROM kpi_rca_exports LIMIT 0"); }
        catch(org.springframework.dao.DataAccessException ex){throw new IllegalStateException("Apply backend-java/db/migrations/007_rca_export_jobs.sql before starting.",ex);}
        if(recover)jdbc.update("UPDATE kpi_rca_exports SET status='FAILED',progressPercent=100,message='Backend restarted before export completed. Request the export again.',updatedAt=NOW() WHERE status IN ('QUEUED','PROCESSING')");
    }
    private record Submission(long id,boolean created){}
    public Map<String,Object> start(long fileId,Map<String,Object> request) {
        Map<String,Object> body=CommonReportRenderer.map(request==null?Map.of():request);
        String key=rca.currentCacheKey(fileId,body).orElse(null);
        Submission submitted=transactions.locked(fileId,()->{
            if(key!=null){
                var rows=jdbc.queryForList("SELECT id,status FROM kpi_rca_exports WHERE fileId=? AND cacheKey=? AND status IN ('QUEUED','PROCESSING','COMPLETED') ORDER BY id DESC LIMIT 1",fileId,key);
                if(!rows.isEmpty()&&(!"COMPLETED".equals(rows.get(0).get("status"))||artifacts.excel(key)!=null))return new Submission(((Number)rows.get(0).get("id")).longValue(),false);
            }
            String artifact=key==null?RecommendationCacheKey.build(Map.of("nonce",UUID.randomUUID().toString()),"uncached-export",Map.of()):key;
            var keys=new GeneratedKeyHolder();
            jdbc.update(connection->{var statement=connection.prepareStatement("INSERT INTO kpi_rca_exports (fileId,cacheKey,artifactKey,requestJson,status,createdAt,updatedAt) VALUES (?,?,?,?,'QUEUED',NOW(),NOW())",Statement.RETURN_GENERATED_KEYS);statement.setLong(1,fileId);statement.setString(2,key);statement.setString(3,artifact);statement.setString(4,write(body));return statement;},keys);
            return new Submission(Objects.requireNonNull(keys.getKey()).longValue(),true);
        });
        try{verify(fileId,body,key);}catch(RuntimeException ex){if(submitted.created())failed(submitted.id(),ex.getMessage());throw ex;}
        if(submitted.created()){
            try{self.getObject().process(submitted.id());}
            catch(RejectedExecutionException ex){failed(submitted.id(),"Export workers and queue are full. Request export again shortly.");throw new JobCapacityException(submitted.id(),"RCA export queue is full.",ex);}
            catch(RuntimeException ex){failed(submitted.id(),"Export could not be scheduled.");throw ex;}
        }
        return get(submitted.id());
    }
    @Async("rcaExportExecutor") public void process(long id){
        if(jdbc.update("UPDATE kpi_rca_exports SET status='PROCESSING',progressPercent=10,message='Checking captured RCA analysis...',updatedAt=NOW() WHERE id=? AND status='QUEUED'",id)!=1)return;
        try{
            var row=row(id);long fileId=((Number)row.get("fileId")).longValue();var body=read(row.get("requestJson").toString());String key=(String)row.get("cacheKey");String artifact=row.get("artifactKey").toString();
            build(fileId,body,key,artifact,(percent,message)->jdbc.update("UPDATE kpi_rca_exports SET progressPercent=?,message=?,updatedAt=NOW() WHERE id=? AND status='PROCESSING'",percent,message,id));
            verify(fileId,body,key);
            jdbc.update("UPDATE kpi_rca_exports SET status='COMPLETED',progressPercent=100,message='Export ready. Download uses the saved workbook.',updatedAt=NOW() WHERE id=? AND status='PROCESSING'",id);
        }catch(Exception ex){failed(id,ex.getMessage()==null?"RCA export failed.":ex.getMessage());}
    }
    public Path blocking(long fileId,Map<String,Object> body)throws IOException{
        String key=rca.currentCacheKey(fileId,body).orElse(null);String artifact=key==null?RecommendationCacheKey.build(Map.of("nonce",UUID.randomUUID().toString()),"uncached-export",Map.of()):key;
        return build(fileId,body,key,artifact,(p,m)->{});
    }
    private Path build(long fileId,Map<String,Object> body,String key,String artifact,BiConsumer<Integer,String> progress)throws IOException{
        return artifacts.locked(artifact,()->{
            verify(fileId,body,key);Path excel=artifacts.excel(artifact);if(excel!=null){progress.accept(95,"Reusing saved Excel export...");return excel;}
            Path work=Files.createTempDirectory("rca-export-");
            try{
                if(key==null){
                    progress.accept(25,"No verifiable KPI snapshot; generating a fresh uncached export...");
                    var result=rca.runRecommendation(fileId,body,true);try{artifacts.publishExcel(artifact,result.excelPath);}finally{rca.cleanup(result.workDir);}
                }else{
                    Path analysis=artifacts.analysis(key);
                    if(analysis==null){
                        progress.accept(25,"Preparing full RCA analysis once for this data and settings...");
                        var result=rca.runRecommendation(fileId,body,false);try{
                            if(!key.equals(result.inputCacheKey))throw new IllegalStateException("KPI input changed. Request the export again.");
                            rca.retainArtifacts(result,fileId,body);
                        }finally{rca.cleanup(result.workDir);}
                        analysis=artifacts.analysis(key);
                        if(analysis==null)throw new IllegalStateException("Full RCA analysis could not be retained. Run recommendation again.");
                    }
                    progress.accept(65,"Reusing full scored analysis; writing Excel sheets in one pass...");
                    Path output=work.resolve("export.xlsx");rca.renderStoredAnalysis(analysis,output);verify(fileId,body,key);artifacts.publishExcel(artifact,output);
                }
                progress.accept(95,"Verifying saved workbook...");Path result=artifacts.excel(artifact);if(result==null)throw new IOException("Export artifact integrity validation failed.");return result;
            }finally{rca.cleanup(work);}
        });
    }
    private void verify(long fileId,Map<String,Object> body,String key){if(key!=null&&rca.currentCacheKey(fileId,body).filter(key::equals).isEmpty())throw new IllegalArgumentException("KPI data, settings or RCA engine changed. Request a new export.");}
    public long fileId(long id){return ((Number)row(id).get("fileId")).longValue();}
    public Map<String,Object> get(long id){var value=new LinkedHashMap<>(row(id));value.remove("requestJson");value.remove("artifactKey");value.put("cacheable",value.get("cacheKey")!=null);return value;}
    public Path download(long id)throws IOException{var row=row(id);if(!"COMPLETED".equals(row.get("status")))throw new IllegalArgumentException("RCA export is not ready.");Path file=artifacts.excel(row.get("artifactKey").toString());if(file==null)throw new IOException("Export is missing or changed. Request the export again.");return file;}
    private Map<String,Object> row(long id){var rows=jdbc.queryForList("SELECT * FROM kpi_rca_exports WHERE id=?",id);if(rows.isEmpty())throw new IllegalArgumentException("RCA export job was not found.");return rows.get(0);}
    private void failed(long id,String message){jdbc.update("UPDATE kpi_rca_exports SET status='FAILED',progressPercent=100,message=?,updatedAt=NOW() WHERE id=? AND status IN ('QUEUED','PROCESSING')",message.substring(0,Math.min(message.length(),2000)),id);}
    private static String write(Object value){try{return CommonReportModules.JSON.writeValueAsString(value);}catch(Exception ex){throw new IllegalArgumentException("Invalid export settings.",ex);}}
    private static Map<String,Object> read(String value){try{return CommonReportModules.JSON.readValue(value,new com.fasterxml.jackson.core.type.TypeReference<>(){});}catch(Exception ex){throw new IllegalStateException("Invalid stored export settings.",ex);}}
}

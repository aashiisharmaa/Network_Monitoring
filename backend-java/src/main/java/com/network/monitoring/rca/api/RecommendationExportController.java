package com.network.monitoring.rca.api;

import com.network.monitoring.identity.service.RoleAuthorizationService;
import com.network.monitoring.ingestion.repository.UploadHistoryRepository;
import com.network.monitoring.platform.jobs.exception.JobCapacityException;
import com.network.monitoring.rca.service.RecommendationExportService;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/rca-exports")
public class RecommendationExportController {
    private final RecommendationExportService exports;private final RoleAuthorizationService role;private final UploadHistoryRepository history;
    public RecommendationExportController(RecommendationExportService exports,RoleAuthorizationService role,UploadHistoryRepository history){this.exports=exports;this.role=role;this.history=history;}
    @PostMapping public ResponseEntity<?> start(@RequestParam("fileId")long fileId,@RequestBody(required=false)Map<String,Object> body){return execute(()->{access(fileId);return ResponseEntity.ok(Map.of("success",true,"data",exports.start(fileId,body==null?Map.of():body)));});}
    @GetMapping("/{id}") public ResponseEntity<?> get(@PathVariable("id")long id){return execute(()->{access(exports.fileId(id));return ResponseEntity.ok(Map.of("success",true,"data",exports.get(id)));});}
    @GetMapping("/{id}/download") public ResponseEntity<?> download(@PathVariable("id")long id){return execute(()->{access(exports.fileId(id));return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=rca-export-"+id+".xlsx").body(new FileSystemResource(exports.download(id)));});}
    private void access(long fileId){var file=history.findById(fileId).orElseThrow(()->new IllegalArgumentException("KPI dataset was not found."));if(!role.isCurrentUserSuperAdmin()&&(role.currentUserCompanyIdOrNull()==null||!Objects.equals(role.currentUserCompanyIdOrNull(),file.getCompanyId())))throw new AccessDeniedException("Dataset access denied.");}
    private ResponseEntity<?> execute(Action action){try{return action.run();}catch(AccessDeniedException ex){return ResponseEntity.status(403).body(Map.of("message",ex.getMessage()));}catch(JobCapacityException ex){return ResponseEntity.status(503).header("Retry-After","30").body(Map.of("message",ex.getMessage(),"jobId",ex.getJobId()));}catch(IllegalArgumentException ex){return ResponseEntity.badRequest().body(Map.of("message",ex.getMessage()));}catch(Exception ex){return ResponseEntity.internalServerError().body(Map.of("message","RCA export request failed. Check export job status."));}}
    @FunctionalInterface private interface Action{ResponseEntity<?> run()throws Exception;}
}

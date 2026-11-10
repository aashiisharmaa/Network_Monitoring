package com.network.monitoring.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.monitoring.service.JobCapacityException;
import com.network.monitoring.service.KpiIngestionService;
import com.network.monitoring.service.RoleAuthorizationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** Initial common-report workflow is managed by a super admin, not an anonymous folder-path endpoint. */
@RestController
@RequestMapping("/api/v1/ingestion")
public class KpiIngestionController {
    private final KpiIngestionService ingestion;
    private final RoleAuthorizationService authorization;
    private final ObjectMapper json = new ObjectMapper();

    public KpiIngestionController(KpiIngestionService ingestion, RoleAuthorizationService authorization) {
        this.ingestion = ingestion;
        this.authorization = authorization;
    }

    @PostMapping("/kpi")
    public ResponseEntity<?> start(@RequestParam("file") MultipartFile file, @RequestParam("fileId") long fileId,
                                   @RequestParam("identityFields") String identityFields,
                                   @RequestParam(value = "sourceName", defaultValue = "common") String sourceName,
                                   @RequestParam(value = "columnMapping", required = false) String columnMapping) {
        return execute(() -> ingestion.start(file, fileId, sourceName,
                json.readValue(identityFields, new TypeReference<List<String>>() { }),
                columnMapping == null || columnMapping.isBlank() ? Map.of()
                        : json.readValue(columnMapping, new TypeReference<Map<String, String>>() { })));
    }

    @GetMapping("/runs")
    public ResponseEntity<?> list(@RequestParam(value = "limit", defaultValue = "20") int limit) {
        return execute(() -> Map.of("success", true, "data", ingestion.list(limit)));
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<?> get(@PathVariable("id") long id) {
        return execute(() -> Map.of("success", true, "data", ingestion.get(id)));
    }

    @PostMapping("/runs/{id}/retry")
    public ResponseEntity<?> retry(@PathVariable("id") long id) {
        return execute(() -> Map.of("success", true, "data", ingestion.retry(id)));
    }

    private ResponseEntity<?> execute(Action action) {
        try {
            authorization.requireSuperAdmin();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(403).body(Map.of("success", false, "message", ex.getMessage()));
        }
        try {
            return ResponseEntity.ok(action.run());
        } catch (JobCapacityException ex) {
            return ResponseEntity.status(503).header("Retry-After", "30").body(Map.of("success", false,
                    "code", "JOB_CAPACITY", "runId", ex.getJobId(), "message", ex.getMessage()));
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "Ingestion request failed. Check the run history."));
        }
    }

    @FunctionalInterface private interface Action { Object run() throws Exception; }
}

package com.network.monitoring.controller;

import com.network.monitoring.service.RecommendationService;
import com.network.monitoring.service.RecommendationJobService;
import com.network.monitoring.service.JobCapacityException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/kpi/recommendation", "/api/v1/kpi/recommendation"})
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final RecommendationJobService recommendationJobService;
    private final com.network.monitoring.service.RecommendationExportService exports;

    public RecommendationController(RecommendationService recommendationService,
                                    RecommendationJobService recommendationJobService, com.network.monitoring.service.RecommendationExportService exports) {
        this.recommendationService = recommendationService;
        this.recommendationJobService = recommendationJobService;
        this.exports = exports;
    }

    @PostMapping("/jobs")
    public ResponseEntity<Map<String, Object>> startRecommendationJob(
            @RequestParam(value = "fileId", required = false) Long fileId,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            return ResponseEntity.ok(recommendationJobService.startJob(fileId, body == null ? Map.of() : body));
        } catch (JobCapacityException ex) {
            Map<String, Object> error = errorResponse(ex.getMessage());
            error.put("code", "JOB_CAPACITY");
            error.put("jobId", ex.getJobId());
            error.put("retryable", true);
            return ResponseEntity.status(503).header("Retry-After", "30").body(error);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(errorResponse(ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(errorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<Map<String, Object>> getRecommendationJob(@PathVariable("jobId") Long jobId) {
        try {
            return ResponseEntity.ok(recommendationJobService.getJob(jobId));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(errorResponse(ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(errorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runRecommendation(
            @RequestParam(value = "fileId", required = false) Long fileId,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            if (fileId == null || fileId <= 0) {
                return ResponseEntity.badRequest().body(errorResponse("fileId query parameter is required."));
            }
            RecommendationService.RecommendationResult result = recommendationService.runRecommendation(fileId, body == null ? Map.of() : body, false);
            try {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", true);
                response.put("fileId", fileId);
                recommendationService.retainArtifacts(result, fileId, body == null ? Map.of() : body);
                response.putAll(result.payload);
                return ResponseEntity.ok(response);
            } finally {
                recommendationService.cleanup(result.workDir);
            }
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(errorResponse(ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(errorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/export")
    public ResponseEntity<?> exportRecommendation(
            @RequestParam(value = "fileId", required = false) Long fileId,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            if (fileId == null || fileId <= 0) return ResponseEntity.badRequest().body(errorResponse("fileId query parameter is required."));
            var path = exports.blocking(fileId, body == null ? Map.of() : body);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=rca_recommendation_" + fileId + ".xlsx")
                    .body(new org.springframework.core.io.FileSystemResource(path));
        } catch (Exception ex) { return ResponseEntity.status(500).body(errorResponse(ex.getMessage())); }
    }

    @GetMapping("/presets")
    public ResponseEntity<Map<String, Object>> listPresets() {
        try {
            List<String> presets = recommendationService.listPresets();
            return ResponseEntity.ok(Map.of("success", true, "presets", presets));
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(errorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/presets")
    public ResponseEntity<Map<String, Object>> savePreset(@RequestBody Map<String, Object> body) {
        try {
            String name = body.get("name") == null ? "" : String.valueOf(body.get("name")).trim();
            Object config = body.get("config");
            if (name.isBlank()) {
                return ResponseEntity.badRequest().body(errorResponse("Preset name is required."));
            }
            if (!(config instanceof Map<?, ?> map)) {
                return ResponseEntity.badRequest().body(errorResponse("Preset config is required."));
            }
            recommendationService.savePreset(name, (Map<String, Object>) map);
            return ResponseEntity.ok(Map.of("success", true, "name", name));
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(errorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/presets/{name}")
    public ResponseEntity<Map<String, Object>> loadPreset(@PathVariable("name") String name) {
        try {
            if (name == null || name.isBlank()) {
                return ResponseEntity.badRequest().body(errorResponse("Preset name is required."));
            }
            Map<String, Object> config = recommendationService.loadPreset(name.trim());
            return ResponseEntity.ok(Map.of("success", true, "name", name.trim(), "config", config));
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(errorResponse(ex.getMessage()));
        }
    }

    private Map<String, Object> errorResponse(String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("success", false);
        error.put("message", message == null || message.isBlank() ? "Unexpected error" : message);
        return error;
    }
}

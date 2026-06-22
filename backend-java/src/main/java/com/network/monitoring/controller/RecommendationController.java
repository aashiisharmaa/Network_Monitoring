package com.network.monitoring.controller;

import com.network.monitoring.service.RecommendationService;
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
@RequestMapping("/kpi/recommendation")
public class RecommendationController {

    private final RecommendationService recommendationService;

    public RecommendationController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
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
        java.nio.file.Path workDir = null;
        try {
            if (fileId == null || fileId <= 0) {
                return ResponseEntity.badRequest().body(errorResponse("fileId query parameter is required."));
            }
            RecommendationService.RecommendationResult result = recommendationService.runRecommendation(fileId, body == null ? Map.of() : body, true);
            workDir = result.workDir;
            if (result.excelPath == null || !Files.exists(result.excelPath)) {
                throw new IllegalStateException("Excel output was not generated.");
            }
            byte[] buffer = Files.readAllBytes(result.excelPath);
            String fileName = "rca_recommendation_" + fileId + "_" + System.currentTimeMillis() + ".xlsx";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"");
            return new ResponseEntity<>(buffer, headers, HttpStatus.OK);
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(errorResponse(ex.getMessage()));
        } finally {
            if (workDir != null) {
                recommendationService.cleanup(workDir);
            }
        }
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

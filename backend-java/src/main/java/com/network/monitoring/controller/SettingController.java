package com.network.monitoring.controller;

import com.network.monitoring.service.SettingService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/setting")
public class SettingController {

    private final SettingService settingService;

    public SettingController(SettingService settingService) {
        this.settingService = settingService;
    }

    @GetMapping("/getsettings")
    public ResponseEntity<Map<String, Object>> getSettings(
            @RequestParam(value = "fileId", required = false) Long fileId) {
        try {
            return ResponseEntity.ok(response("Threshold retrieved successfully", true, settingService.getSettings(fileId)));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(response(ex.getMessage(), false, null));
        } catch (NoSuchElementException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response(ex.getMessage(), false, null));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response(ex.getMessage(), false, null));
        }
    }

    @PostMapping("/updateSetting")
    public ResponseEntity<Map<String, Object>> updateSettings(
            @RequestParam(value = "fileId", required = false) Long fileId,
            @RequestBody Map<String, Object> body) {
        try {
            Long scopedFileId = resolveScopedFileId(fileId, body);
            return ResponseEntity.ok(response("Threshold updated successfully", true, settingService.updateSettings(scopedFileId, body)));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(response(ex.getMessage(), false, null));
        } catch (NoSuchElementException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response(ex.getMessage(), false, null));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response(ex.getMessage(), false, null));
        }
    }

    @PostMapping("/thresholds/export")
    public ResponseEntity<?> exportThresholdWorkbook(
            @RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Number> thresholds = (Map<String, Number>) body.get("thresholds");
            @SuppressWarnings("unchecked")
            Map<String, String> operators = (Map<String, String>) body.get("operators");
            String scopeFileId = body.getOrDefault("scopeFileId", "global").toString();
            if (thresholds == null || thresholds.isEmpty()) {
                return ResponseEntity.badRequest().body(response("No valid thresholds provided for export.", false, null));
            }
            byte[] payload = settingService.exportThresholdWorkbook(thresholds == null ? Map.of() : thresholds,
                    operators == null ? Map.of() : operators,
                    scopeFileId);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            String timestamp = Instant.now().toString().replace(":", "-").replace(".", "-");
            String scopeSuffix = scopeFileId == null || scopeFileId.isBlank() ? "global" : "file_" + scopeFileId;
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"dashboard_thresholds_" + scopeSuffix + "_" + timestamp + ".xlsx\"");
            return new ResponseEntity<>(payload, headers, HttpStatus.OK);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response(ex.getMessage(), false, null));
        }
    }

    @PostMapping(value = "/thresholds/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> importThresholdWorkbook(
            @RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(response("Threshold file parsed successfully.", true, settingService.importThresholdWorkbook(file)));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(response(ex.getMessage(), false, null));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response(ex.getMessage(), false, null));
        }
    }

    private Long resolveScopedFileId(Long requestParamFileId, Map<String, Object> body) {
        if (requestParamFileId != null) {
            return requestParamFileId;
        }
        Object raw = body == null ? null : body.get("fileId");
        if (raw == null || String.valueOf(raw).trim().isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid fileId. It must be a positive integer.");
        }
    }

    private Map<String, Object> response(String message, boolean success, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", message);
        response.put("success", success);
        response.put("data", data);
        return response;
    }
}

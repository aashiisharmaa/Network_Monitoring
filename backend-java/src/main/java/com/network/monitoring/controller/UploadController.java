package com.network.monitoring.controller;

import com.network.monitoring.service.KpiUploadPreviewService;
import com.network.monitoring.service.UploadJobService;
import com.network.monitoring.service.UploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping({"/upload", "/api/v1/upload"})
public class UploadController {
    private static final Logger log = LoggerFactory.getLogger(UploadController.class);

    private final UploadService uploadService;
    private final KpiUploadPreviewService kpiUploadPreviewService;
    private final UploadJobService uploadJobService;

    public UploadController(UploadService uploadService, KpiUploadPreviewService kpiUploadPreviewService, UploadJobService uploadJobService) {
        this.uploadService = uploadService;
        this.kpiUploadPreviewService = kpiUploadPreviewService;
        this.uploadJobService = uploadJobService;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadAndParseFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "remarks", required = false) String remarks,
            @RequestParam(value = "uploadedBy", required = false) String uploadedBy,
            @RequestParam(value = "appendOption", required = false) String appendOption,
            @RequestParam(value = "fileId", required = false) Long fileId,
            @RequestParam(value = "targetFileId", required = false) Long targetFileId,
            @RequestParam(value = "columnMapping", required = false) String columnMapping,
            @RequestParam(value = "cleanKpiData", required = false) Boolean cleanKpiData) {
        return executeUpload(() -> uploadJobService.startKpiUploadJob(file, remarks, uploadedBy, appendOption, targetFileId != null ? targetFileId : fileId, columnMapping, Boolean.TRUE.equals(cleanKpiData)));
    }

    @PostMapping("/kpi")
    public ResponseEntity<Map<String, Object>> uploadKpiData(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "remarks", required = false) String remarks,
            @RequestParam(value = "uploadedBy", required = false) String uploadedBy,
            @RequestParam(value = "appendOption", required = false) String appendOption,
            @RequestParam(value = "fileId", required = false) Long fileId,
            @RequestParam(value = "targetFileId", required = false) Long targetFileId,
            @RequestParam(value = "columnMapping", required = false) String columnMapping,
            @RequestParam(value = "cleanKpiData", required = false) Boolean cleanKpiData) {
        return executeUpload(() -> uploadJobService.startKpiUploadJob(file, remarks, uploadedBy, appendOption, targetFileId != null ? targetFileId : fileId, columnMapping, Boolean.TRUE.equals(cleanKpiData)));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<Map<String, Object>> getUploadJob(@PathVariable("jobId") Long jobId) {
        try {
            return ResponseEntity.ok(uploadJobService.getJob(jobId));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(errorResponse(ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(errorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/jobs")
    public ResponseEntity<Map<String, Object>> listUploadJobs(@RequestParam(value = "limit", required = false) Integer limit) {
        try {
            return ResponseEntity.ok(uploadJobService.listJobs(limit));
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(errorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/jobs/{jobId}/retry")
    public ResponseEntity<Map<String, Object>> retryUploadJob(@PathVariable("jobId") Long jobId) {
        try {
            return ResponseEntity.ok(uploadJobService.retryJob(jobId));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(errorResponse(ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(errorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/kpi/preview")
    public ResponseEntity<Map<String, Object>> previewKpiData(@RequestParam("file") MultipartFile file) {
        return executeUpload(() -> kpiUploadPreviewService.preview(file));
    }

    @PostMapping("/site")
    public ResponseEntity<Map<String, Object>> uploadSiteData(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "remarks", required = false) String remarks,
            @RequestParam(value = "uploadedBy", required = false) String uploadedBy) {
        return executeUpload(() -> uploadService.uploadSiteData(file, remarks, uploadedBy));
    }

    @PostMapping("/alarm")
    public ResponseEntity<Map<String, Object>> uploadAlarmData(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "remarks", required = false) String remarks,
            @RequestParam(value = "uploadedBy", required = false) String uploadedBy) {
        return executeUpload(() -> uploadService.uploadAlarmData(file, remarks, uploadedBy));
    }

    @GetMapping("/uploads")
    public ResponseEntity<Map<String, Object>> getUploadHistory() {
        return ResponseEntity.ok(uploadService.getUploadHistory());
    }

    @DeleteMapping("/uploads/{id}")
    public ResponseEntity<Map<String, Object>> deleteUpload(@PathVariable("id") Long id) {
        try {
            return ResponseEntity.ok(uploadService.deleteUpload(id));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(errorResponse(ex.getMessage()));
        } catch (Exception ex) {
            log.error("Failed to delete upload {}", id, ex);
            return ResponseEntity.status(500).body(errorResponse("Internal server error occurred."));
        }
    }

    @GetMapping("/network-data")
    public ResponseEntity<Map<String, Object>> getNetworkData(@RequestParam(value = "page", required = false, defaultValue = "1") int page,
                                                              @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        return ResponseEntity.ok(uploadService.getNetworkData(page, limit));
    }

    @GetMapping("/uploads/history")
    public ResponseEntity<Map<String, Object>> getUploadHistorySlim() {
        return ResponseEntity.ok(uploadService.getUploadHistorySlim());
    }

    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> getUploadHistorySlimAlias() {
        return ResponseEntity.ok(uploadService.getUploadHistorySlim());
    }

    @GetMapping("/site-data")
    public ResponseEntity<Map<String, Object>> getSiteData(@RequestParam(value = "page", required = false, defaultValue = "1") int page,
                                                           @RequestParam(value = "limit", required = false, defaultValue = "10000") int limit,
                                                           @RequestParam(value = "fileId", required = false) Long fileId) {
        return ResponseEntity.ok(uploadService.getSiteData(page, limit, fileId));
    }

    @GetMapping("/alarm-data")
    public ResponseEntity<Map<String, Object>> getAlarmData(@RequestParam(value = "page", required = false, defaultValue = "1") int page,
                                                            @RequestParam(value = "limit", required = false, defaultValue = "10") int limit,
                                                            @RequestParam(value = "circle", required = false) String circle,
                                                            @RequestParam(value = "severity", required = false) String severity,
                                                            @RequestParam(value = "search", required = false) String search,
                                                            @RequestParam(value = "startDate", required = false) String startDate,
                                                            @RequestParam(value = "endDate", required = false) String endDate) {
        return ResponseEntity.ok(uploadService.getAlarmData(page, limit, circle, severity, search, startDate, endDate));
    }

    private ResponseEntity<Map<String, Object>> wrapUpload(Map<String, Object> payload) {
        Object success = payload.get("success");
        if (Boolean.FALSE.equals(success)) {
            return ResponseEntity.badRequest().body(payload);
        }
        return ResponseEntity.ok(payload);
    }

    private ResponseEntity<Map<String, Object>> executeUpload(ThrowingUploadSupplier supplier) {
        try {
            return wrapUpload(supplier.get());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(errorResponse(ex.getMessage()));
        } catch (IOException ex) {
            log.error("File upload IO failure", ex);
            return ResponseEntity.status(500).body(errorResponse("File processing failed."));
        } catch (Exception ex) {
            log.error("File upload failure", ex);
            return ResponseEntity.status(500).body(errorResponse("Internal server error occurred."));
        }
    }

    private Map<String, Object> errorResponse(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("message", message);
        return response;
    }

    @FunctionalInterface
    private interface ThrowingUploadSupplier {
        Map<String, Object> get() throws IOException;
    }
}

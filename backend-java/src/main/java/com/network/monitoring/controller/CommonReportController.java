package com.network.monitoring.controller;

import com.network.monitoring.repository.UploadHistoryRepository;
import com.network.monitoring.service.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/reports")
public class CommonReportController {
    private final CommonReportService reports; private final DailyAnalysisService analysis;
    private final RoleAuthorizationService authorization; private final UploadHistoryRepository history;
    public CommonReportController(CommonReportService reports, DailyAnalysisService analysis, RoleAuthorizationService authorization, UploadHistoryRepository history) {
        this.reports = reports; this.analysis = analysis; this.authorization = authorization; this.history = history;
    }
    @PostMapping public ResponseEntity<?> create(@RequestParam("snapshotId") long snapshotId, @RequestBody(required = false) CommonReportModules.Options options) {
        return execute(() -> { admin(); access(analysis.fileId(snapshotId)); return ok(reports.request(snapshotId, options == null ? new CommonReportModules.Options(null, null) : options)); });
    }
    @GetMapping public ResponseEntity<?> list(@RequestParam("fileId") long fileId, @RequestParam(value = "limit", defaultValue = "20") int limit) { return execute(() -> { access(fileId); return ok(reports.list(fileId, limit)); }); }
    @GetMapping("/{id}") public ResponseEntity<?> get(@PathVariable("id") long id) { return execute(() -> { access(reports.fileId(id)); return ok(reports.get(id)); }); }
    @PostMapping("/{id}/retry") public ResponseEntity<?> retry(@PathVariable("id") long id) { return execute(() -> { admin(); access(reports.fileId(id)); return ok(reports.retry(id)); }); }
    @GetMapping("/{id}/download/{format}") public ResponseEntity<?> download(@PathVariable("id") long id, @PathVariable("format") String format) {
        return execute(() -> { access(reports.fileId(id)); var file = reports.download(id, format);
            String type = switch (format) { case "pdf" -> "application/pdf"; case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; default -> "application/json"; };
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("common-report-" + id + "-" + file.getFileName()).build().toString()).body(new FileSystemResource(file)); });
    }
    private void admin() { try { authorization.requireSuperAdmin(); } catch (IllegalArgumentException ex) { throw new AccessDeniedException(ex.getMessage()); } }
    private void access(long id) {
        var dataset = history.findById(id).orElseThrow(() -> new IllegalArgumentException("KPI dataset was not found."));
        if (!authorization.isCurrentUserSuperAdmin() && (authorization.currentUserCompanyIdOrNull() == null || !Objects.equals(authorization.currentUserCompanyIdOrNull(), dataset.getCompanyId()))) throw new AccessDeniedException("Dataset access denied.");
    }
    private static ResponseEntity<?> ok(Object data) { return ResponseEntity.ok(Map.of("success", true, "data", data)); }
    private ResponseEntity<?> execute(Action action) {
        try { return action.run(); }
        catch (AccessDeniedException ex) { return ResponseEntity.status(403).body(Map.of("success", false, "message", ex.getMessage())); }
        catch (JobCapacityException ex) { return ResponseEntity.status(503).header("Retry-After", "30").body(Map.of("success", false, "reportId", ex.getJobId(), "message", ex.getMessage())); }
        catch (IllegalArgumentException ex) { return ResponseEntity.badRequest().body(Map.of("success", false, "message", ex.getMessage())); }
        catch (Exception ex) { return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "Report request failed. Check report history or backend logs.")); }
    }
    @FunctionalInterface private interface Action { ResponseEntity<?> run() throws Exception; }
}

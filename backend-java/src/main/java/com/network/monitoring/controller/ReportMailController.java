package com.network.monitoring.controller;

import com.network.monitoring.repository.UploadHistoryRepository;
import com.network.monitoring.service.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/report-mail")
public class ReportMailController {
    private final ReportMailOutboxService mail; private final CommonReportService reports;
    private final RoleAuthorizationService role; private final UploadHistoryRepository history;
    public ReportMailController(ReportMailOutboxService mail, CommonReportService reports, RoleAuthorizationService role, UploadHistoryRepository history) { this.mail = mail; this.reports = reports; this.role = role; this.history = history; }
    @PutMapping("/profile") public ResponseEntity<?> configure(@RequestParam("fileId") long fileId, @RequestBody ReportMailProfile profile) {
        return execute(() -> { admin(); access(fileId); return ok(mail.configure(fileId, profile, role.currentUserId())); });
    }
    @GetMapping("/profile") public ResponseEntity<?> profile(@RequestParam("fileId") long fileId) { return execute(() -> { admin(); access(fileId); return ok(mail.profile(fileId)); }); }
    @PostMapping("/deliveries") public ResponseEntity<?> submit(@RequestParam("reportId") long reportId, @RequestParam(value="expectedProfileHash",required=false) String expectedProfileHash) {
        return execute(() -> { admin(); access(reports.fileId(reportId)); var result = mail.request(reportId, role.currentUserId(), expectedProfileHash);
            return ResponseEntity.status(Boolean.TRUE.equals(result.get("capacityLimited")) ? 503 : 200).header("Retry-After", "30").body(Map.of("success", !Boolean.TRUE.equals(result.get("capacityLimited")), "data", result)); });
    }
    @GetMapping("/deliveries") public ResponseEntity<?> list(@RequestParam("fileId") long fileId, @RequestParam(value = "limit", defaultValue = "20") int limit) { return execute(() -> { access(fileId); return ok(mail.list(fileId, limit)); }); }
    @GetMapping("/deliveries/{id}") public ResponseEntity<?> get(@PathVariable("id") long id) { return execute(() -> { access(mail.fileId(id)); return ok(mail.get(id)); }); }
    @PostMapping("/deliveries/{id}/retry") public ResponseEntity<?> retry(@PathVariable("id") long id) { return execute(() -> { admin(); access(mail.fileId(id)); return ok(mail.retry(id, role.currentUserId())); }); }
    @PostMapping("/deliveries/{id}/cancel") public ResponseEntity<?> cancel(@PathVariable("id") long id) { return execute(() -> { admin(); access(mail.fileId(id)); return ok(mail.cancel(id, role.currentUserId())); }); }
    public record Resolution(String outcome, String evidence) { }
    @PostMapping("/deliveries/{id}/reconcile") public ResponseEntity<?> reconcile(@PathVariable("id") long id, @RequestBody Resolution resolution) { return execute(() -> { admin(); access(mail.fileId(id)); return ok(mail.reconcile(id, resolution.outcome(), resolution.evidence(), role.currentUserId())); }); }
    @GetMapping("/deliveries/{id}/message.eml") public ResponseEntity<?> captured(@PathVariable("id") long id) {
        return execute(() -> { access(mail.fileId(id)); return ResponseEntity.ok().contentType(MediaType.parseMediaType("message/rfc822")).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report-mail-" + id + ".eml").body(new FileSystemResource(mail.captured(id))); });
    }
    private void admin() { try { role.requireSuperAdmin(); } catch (IllegalArgumentException ex) { throw new AccessDeniedException(ex.getMessage()); } }
    private void access(long fileId) {
        var dataset = history.findById(fileId).orElseThrow(() -> new IllegalArgumentException("KPI dataset was not found."));
        if (!role.isCurrentUserSuperAdmin() && (role.currentUserCompanyIdOrNull() == null || !Objects.equals(role.currentUserCompanyIdOrNull(), dataset.getCompanyId()))) throw new AccessDeniedException("Dataset access denied.");
    }
    private static ResponseEntity<?> ok(Object data) { return ResponseEntity.ok(Map.of("success", true, "data", data)); }
    private ResponseEntity<?> execute(Action action) {
        try { return action.run(); }
        catch (AccessDeniedException ex) { return ResponseEntity.status(403).body(Map.of("success", false, "message", ex.getMessage())); }
        catch (JobCapacityException ex) { return ResponseEntity.status(503).header("Retry-After", "30").body(Map.of("success", false, "deliveryId", ex.getJobId(), "message", ex.getMessage())); }
        catch (IllegalArgumentException ex) { return ResponseEntity.badRequest().body(Map.of("success", false, "message", ex.getMessage())); }
        catch (Exception ex) { return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "Mail request failed. Check delivery history before retrying.")); }
    }
    @FunctionalInterface private interface Action { ResponseEntity<?> run() throws Exception; }
}

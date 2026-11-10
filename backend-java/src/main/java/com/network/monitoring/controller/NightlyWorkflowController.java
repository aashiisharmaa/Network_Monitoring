package com.network.monitoring.controller;

import com.network.monitoring.repository.UploadHistoryRepository;
import com.network.monitoring.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/nightly")
public class NightlyWorkflowController {
    private final NightlyWorkflowService nightly; private final RoleAuthorizationService role; private final UploadHistoryRepository history;
    public NightlyWorkflowController(NightlyWorkflowService nightly, RoleAuthorizationService role, UploadHistoryRepository history) { this.nightly = nightly; this.role = role; this.history = history; }
    @GetMapping("/profile") public ResponseEntity<?> profile(@RequestParam("fileId") long fileId) { return execute(() -> { admin(); access(fileId); return nightly.profile(fileId); }); }
    @PutMapping("/profile") public ResponseEntity<?> configure(@RequestParam("fileId") long fileId, @RequestBody NightlyProfile profile) { return execute(() -> { admin(); access(fileId); return nightly.configure(fileId, profile, role.currentUserId()); }); }
    @PostMapping("/runs") public ResponseEntity<?> create(@RequestParam("fileId") long fileId, @RequestParam("date") String date, @RequestParam(value="allowLate",defaultValue="false") boolean late) { return execute(() -> { admin(); access(fileId); return nightly.request(fileId, LocalDate.parse(date), late, role.currentUserId()); }); }
    @GetMapping("/runs") public ResponseEntity<?> list(@RequestParam("fileId") long fileId, @RequestParam(value="limit",defaultValue="20") int limit) { return execute(() -> { access(fileId); return nightly.list(fileId, limit); }); }
    @GetMapping("/runs/{id}") public ResponseEntity<?> get(@PathVariable("id") long id) { return execute(() -> { access(nightly.fileId(id)); return nightly.get(id); }); }
    @PostMapping("/runs/{id}/retry") public ResponseEntity<?> retry(@PathVariable("id") long id, @RequestParam(value="rediscoverInput",defaultValue="false") boolean rediscover, @RequestParam(value="refreshMailProfile",defaultValue="false") boolean refresh) { return execute(() -> { admin(); access(nightly.fileId(id)); return nightly.retry(id, rediscover, refresh, role.currentUserId()); }); }
    private void admin() { try { role.requireSuperAdmin(); } catch (IllegalArgumentException ex) { throw new AccessDeniedException(ex.getMessage()); } }
    private void access(long fileId) {
        var file = history.findById(fileId).orElseThrow(() -> new IllegalArgumentException("KPI dataset was not found."));
        if (!role.isCurrentUserSuperAdmin() && (role.currentUserCompanyIdOrNull() == null || !Objects.equals(role.currentUserCompanyIdOrNull(), file.getCompanyId()))) throw new AccessDeniedException("Dataset access denied.");
    }
    private ResponseEntity<?> execute(Action action) {
        try { return ResponseEntity.ok(Map.of("success", true, "data", action.run())); }
        catch (AccessDeniedException ex) { return ResponseEntity.status(403).body(Map.of("success", false, "message", ex.getMessage())); }
        catch (IllegalArgumentException | java.time.DateTimeException ex) { return ResponseEntity.badRequest().body(Map.of("success", false, "message", ex.getMessage())); }
        catch (Exception ex) { return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "Nightly workflow request failed. Review run history.")); }
    }
    @FunctionalInterface private interface Action { Object run(); }
}

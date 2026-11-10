package com.network.monitoring.controller;

import com.network.monitoring.repository.UploadHistoryRepository;
import com.network.monitoring.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/daily-analysis")
public class DailyAnalysisController {
    private final DailyAnalysisService analysis;
    private final RoleAuthorizationService authorization;
    private final UploadHistoryRepository history;

    public DailyAnalysisController(DailyAnalysisService analysis, RoleAuthorizationService authorization, UploadHistoryRepository history) {
        this.analysis = analysis; this.authorization = authorization; this.history = history;
    }

    @PutMapping("/profile")
    public ResponseEntity<?> configure(@RequestParam("fileId") long fileId, @RequestBody DailyKpiCalculator.Profile profile) {
        return execute(() -> { admin(); access(fileId); return analysis.saveProfile(fileId, profile); });
    }

    @GetMapping("/profile")
    public ResponseEntity<?> profile(@RequestParam("fileId") long fileId) {
        return execute(() -> { access(fileId); return analysis.profile(fileId); });
    }

    @PostMapping("/snapshots")
    public ResponseEntity<?> build(@RequestParam("fileId") long fileId,
                                   @RequestParam(value = "date", required = false) String date) {
        return execute(() -> {
            admin(); access(fileId);
            return analysis.request(fileId, date == null || date.isBlank() ? null : LocalDate.parse(date));
        });
    }

    @GetMapping("/snapshots")
    public ResponseEntity<?> list(@RequestParam("fileId") long fileId, @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return execute(() -> { access(fileId); return analysis.list(fileId, limit); });
    }

    @GetMapping("/snapshots/{id}")
    public ResponseEntity<?> get(@PathVariable("id") long id) {
        return execute(() -> { access(analysis.fileId(id)); return analysis.get(id); });
    }

    @GetMapping("/snapshots/{id}/comparisons")
    public ResponseEntity<?> compare(@PathVariable("id") long id,
                                     @RequestParam(value = "page", defaultValue = "1") int page,
                                     @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return execute(() -> { access(analysis.fileId(id)); return analysis.comparisons(id, page, limit); });
    }

    @PostMapping("/snapshots/{id}/retry")
    public ResponseEntity<?> retry(@PathVariable("id") long id) {
        return execute(() -> { admin(); access(analysis.fileId(id)); return analysis.retry(id); });
    }

    private void admin() {
        try { authorization.requireSuperAdmin(); }
        catch (IllegalArgumentException ex) { throw new AccessDeniedException(ex.getMessage()); }
    }

    private void access(long fileId) {
        var target = history.findById(fileId).orElseThrow(() -> new IllegalArgumentException("KPI dataset was not found."));
        if (authorization.isCurrentUserSuperAdmin()) return;
        Long company = authorization.currentUserCompanyIdOrNull();
        if (company == null || !Objects.equals(company, target.getCompanyId())) throw new AccessDeniedException("Dataset access denied.");
    }

    private ResponseEntity<?> execute(Action action) {
        try { return ResponseEntity.ok(Map.of("success", true, "data", action.run())); }
        catch (AccessDeniedException ex) { return ResponseEntity.status(403).body(Map.of("success", false, "message", ex.getMessage())); }
        catch (JobCapacityException ex) { return ResponseEntity.status(503).header("Retry-After", "30")
                .body(Map.of("success", false, "snapshotId", ex.getJobId(), "code", "JOB_CAPACITY", "message", ex.getMessage())); }
        catch (IllegalArgumentException | java.time.DateTimeException ex) { return ResponseEntity.badRequest().body(Map.of("success", false, "message", ex.getMessage())); }
        catch (Exception ex) { return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "Daily analysis request failed. Check snapshot history.")); }
    }
    @FunctionalInterface private interface Action { Object run(); }
}

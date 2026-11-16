package com.network.monitoring.reporting.api;

import com.network.monitoring.reporting.service.ReportDatasetService;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

/** Scoped, bounded dataset picker for the reporting workspace. */
@RestController
@RequestMapping("/api/v1/report-datasets")
public class ReportDatasetController {
    private final ReportDatasetService service;
    public ReportDatasetController(ReportDatasetService service) { this.service = service; }
    @GetMapping public ResponseEntity<?> list(@RequestParam(value="page",defaultValue="1") int page, @RequestParam(value="limit",defaultValue="50") int limit) {
        try {
            return ResponseEntity.ok(Map.of("success", true, "data", service.list(page, limit)));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        } catch (AccessDeniedException error) {
            return ResponseEntity.status(403).body(Map.of("message", error.getMessage()));
        }
    }
}

package com.network.monitoring.controller;

import com.network.monitoring.service.DashboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/dashboard", "/api/v1/dashboard"})
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getDashboardStats(
            @RequestParam(value = "fileId", required = false) Long fileId,
            @RequestParam(value = "companyId", required = false) Long companyId) {
        long startedAt = System.currentTimeMillis();
        try {
            Map<String, Object> data = dashboardService.getDashboardSummary(fileId, companyId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", data);
            response.put("meta", Map.of(
                    "executionTime", (System.currentTimeMillis() - startedAt) + "ms",
                    "timestamp", Instant.now().toString()
            ));
            return ResponseEntity.ok(response);
        } catch (Exception ex) {
            return internalServerError(ex);
        }
    }

    @GetMapping("/stats/cells")
    public ResponseEntity<Map<String, Object>> getTotalCells() {
        try {
            List<String> cells = dashboardService.listDistinctCells();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", Map.of(
                            "total", cells.size(),
                            "cells", cells
                    )
            ));
        } catch (Exception ex) {
            return internalServerError(ex);
        }
    }

    @GetMapping("/stats/sites")
    public ResponseEntity<Map<String, Object>> getTotalSites() {
        try {
            List<String> sites = dashboardService.listDistinctSites();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", Map.of(
                            "total", sites.size(),
                            "sites", sites
                    )
            ));
        } catch (Exception ex) {
            return internalServerError(ex);
        }
    }

    @GetMapping("/stats/bands")
    public ResponseEntity<Map<String, Object>> getTotalBands() {
        try {
            List<String> bands = dashboardService.listDistinctBands();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", Map.of(
                            "total", bands.size(),
                            "bands", bands
                    )
            ));
        } catch (Exception ex) {
            return internalServerError(ex);
        }
    }

    @GetMapping("/stats/tech")
    public ResponseEntity<Map<String, Object>> getTotalTech() {
        try {
            List<String> tech = dashboardService.listDistinctTech();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", Map.of(
                            "total", tech.size(),
                            "tech", tech
                    )
            ));
        } catch (Exception ex) {
            return internalServerError(ex);
        }
    }

    @GetMapping("/stats/sectors")
    public ResponseEntity<Map<String, Object>> getTotalSectors() {
        try {
            List<Map<String, Object>> sectors = dashboardService.listDistinctSectors();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", Map.of(
                            "total", sectors.size(),
                            "sectors", sectors
                    )
            ));
        } catch (Exception ex) {
            return internalServerError(ex);
        }
    }

    @GetMapping("/stats/groups")
    public ResponseEntity<Map<String, Object>> getTotalGroups() {
        try {
            List<String> groups = dashboardService.listDistinctGroups();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", Map.of(
                            "total", groups.size(),
                            "groups", groups
                    )
            ));
        } catch (Exception ex) {
            return internalServerError(ex);
        }
    }

    @GetMapping("/stats/detailed")
    public ResponseEntity<Map<String, Object>> getDetailedStats() {
        long startedAt = System.currentTimeMillis();
        try {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", dashboardService.getDetailedStats(),
                    "meta", Map.of(
                            "executionTime", (System.currentTimeMillis() - startedAt) + "ms",
                            "timestamp", Instant.now().toString()
                    )
            ));
        } catch (Exception ex) {
            return internalServerError(ex);
        }
    }

    @GetMapping("/stats/performance")
    public ResponseEntity<Map<String, Object>> getPerformanceMetrics(
            @RequestParam(value = "fileId", required = false) Long fileId,
            @RequestParam(value = "companyId", required = false) Long companyId) {
        try {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", dashboardService.getPerformanceMetrics(fileId, companyId)
            ));
        } catch (Exception ex) {
            return internalServerError(ex);
        }
    }

    private ResponseEntity<Map<String, Object>> internalServerError(Exception ex) {
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("message", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        return ResponseEntity.status(500).body(error);
    }
}

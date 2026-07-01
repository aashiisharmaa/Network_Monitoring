package com.network.monitoring.controller;

import com.network.monitoring.service.WorstPerformanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/kpi")
public class WorstPerformanceController {

    private final WorstPerformanceService worstPerformanceService;

    public WorstPerformanceController(WorstPerformanceService worstPerformanceService) {
        this.worstPerformanceService = worstPerformanceService;
    }

    @GetMapping("/worst-cells")
    public ResponseEntity<Map<String, Object>> worstCells(
            @RequestParam("fileId") Long fileId,
            @RequestParam("metric") String metric,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ResponseEntity.ok(response("Worst cells retrieved successfully.", worstPerformanceService.worstCells(fileId, metric, limit)));
    }

    @GetMapping("/worst-sites")
    public ResponseEntity<Map<String, Object>> worstSites(
            @RequestParam("fileId") Long fileId,
            @RequestParam("metric") String metric,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ResponseEntity.ok(response("Worst sites retrieved successfully.", worstPerformanceService.worstSites(fileId, metric, limit)));
    }

    private Map<String, Object> response(String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", message);
        response.put("data", data);
        return response;
    }
}

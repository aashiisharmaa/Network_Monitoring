package com.network.monitoring.controller;

import com.network.monitoring.service.KpiValidationReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/kpi")
public class KpiValidationReportController {

    private final KpiValidationReportService validationReportService;

    public KpiValidationReportController(KpiValidationReportService validationReportService) {
        this.validationReportService = validationReportService;
    }

    @GetMapping("/validation-report/{fileId}")
    public ResponseEntity<Map<String, Object>> getValidationReport(@PathVariable("fileId") Long fileId) {
        return ResponseEntity.ok(response("KPI validation report generated successfully.", validationReportService.buildReport(fileId)));
    }

    private Map<String, Object> response(String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", message);
        response.put("data", data);
        return response;
    }
}

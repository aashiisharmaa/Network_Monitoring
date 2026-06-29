package com.network.monitoring.controller;

import com.network.monitoring.dto.request.KpiThresholdRuleRequest;
import com.network.monitoring.service.KpiThresholdRuleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/threshold-rules")
public class KpiThresholdRuleController {

    private final KpiThresholdRuleService thresholdRuleService;

    public KpiThresholdRuleController(KpiThresholdRuleService thresholdRuleService) {
        this.thresholdRuleService = thresholdRuleService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listRules() {
        return ResponseEntity.ok(response("Threshold rules retrieved successfully.", true, thresholdRuleService.listRules()));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createRule(@RequestBody KpiThresholdRuleRequest request) {
        return ResponseEntity.ok(response("Threshold rule created successfully.", true, thresholdRuleService.createRule(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateRule(@PathVariable Long id, @RequestBody KpiThresholdRuleRequest request) {
        return ResponseEntity.ok(response("Threshold rule updated successfully.", true, thresholdRuleService.updateRule(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteRule(@PathVariable Long id) {
        thresholdRuleService.deleteRule(id);
        return ResponseEntity.ok(response("Threshold rule deleted successfully.", true, Map.of("id", id)));
    }

    @GetMapping("/evaluate")
    public ResponseEntity<Map<String, Object>> evaluate(
            @RequestParam("metricName") String metricName,
            @RequestParam("value") Double value,
            @RequestParam(value = "technology", required = false) String technology) {
        return ResponseEntity.ok(response("Threshold severity evaluated successfully.", true, thresholdRuleService.evaluate(metricName, value, technology)));
    }

    private Map<String, Object> response(String message, boolean success, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", success);
        response.put("message", message);
        response.put("data", data);
        return response;
    }
}

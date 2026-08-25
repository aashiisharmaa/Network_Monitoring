package com.network.monitoring.controller;

import com.network.monitoring.service.KpiAlarmService;
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
@RequestMapping({"/alarms", "/api/v1/alarms"})
public class KpiAlarmController {

    private final KpiAlarmService alarmService;

    public KpiAlarmController(KpiAlarmService alarmService) {
        this.alarmService = alarmService;
    }

    @GetMapping("/rules")
    public ResponseEntity<Map<String, Object>> listRules() {
        return ok("Alarm rules loaded.", alarmService.listRules());
    }

    @PostMapping("/rules")
    public ResponseEntity<Map<String, Object>> createRule(@RequestBody Map<String, Object> request) {
        return execute(() -> ok("Alarm rule created.", alarmService.createRule(request)));
    }

    @PutMapping("/rules/{id}")
    public ResponseEntity<Map<String, Object>> updateRule(@PathVariable("id") Long id, @RequestBody Map<String, Object> request) {
        return execute(() -> ok("Alarm rule updated.", alarmService.updateRule(id, request)));
    }

    @DeleteMapping("/rules/{id}")
    public ResponseEntity<Map<String, Object>> deleteRule(@PathVariable("id") Long id) {
        return execute(() -> {
            alarmService.deleteRule(id);
            return ok("Alarm rule deleted.", Map.of("id", id));
        });
    }

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(@RequestParam("fileId") Long fileId) {
        return execute(() -> ok("Alarms generated.", alarmService.generateAlarms(fileId)));
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(@RequestParam("fileId") Long fileId) {
        return execute(() -> ok("Alarm summary loaded.", alarmService.summary(fileId)));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listAlarms(@RequestParam("fileId") Long fileId,
                                                          @RequestParam(value = "severity", required = false) String severity,
                                                          @RequestParam(value = "status", required = false) String status,
                                                          @RequestParam(value = "page", required = false) Integer page,
                                                          @RequestParam(value = "limit", required = false) Integer limit) {
        return execute(() -> ok("Alarms loaded.", alarmService.listAlarms(fileId, severity, status, page, limit)));
    }

    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledge(@PathVariable("id") Long id,
                                                           @RequestBody(required = false) Map<String, Object> request) {
        return execute(() -> ok("Alarm acknowledged.", alarmService.acknowledge(id, request == null ? Map.of() : request)));
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<Map<String, Object>> resolve(@PathVariable("id") Long id,
                                                       @RequestBody(required = false) Map<String, Object> request) {
        return execute(() -> ok("Alarm resolved.", alarmService.resolve(id, request == null ? Map.of() : request)));
    }

    private ResponseEntity<Map<String, Object>> execute(ResponseSupplier supplier) {
        try {
            return supplier.get();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(error(ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(error(ex.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> ok(String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", message);
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("message", message == null ? "Request failed." : message);
        return response;
    }

    @FunctionalInterface
    private interface ResponseSupplier {
        ResponseEntity<Map<String, Object>> get();
    }
}

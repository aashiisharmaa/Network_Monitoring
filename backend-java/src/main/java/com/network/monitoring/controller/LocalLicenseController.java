package com.network.monitoring.controller;

import com.network.monitoring.service.LocalLicenseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping({"/local-license", "/api/v1/local-license"})
public class LocalLicenseController {

    private final LocalLicenseService localLicenseService;

    public LocalLicenseController(LocalLicenseService localLicenseService) {
        this.localLicenseService = localLicenseService;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadLicense(@RequestParam("file") MultipartFile file) throws IOException {
        return ok("License uploaded successfully.", localLicenseService.uploadLicense(file));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ok("License status retrieved successfully.", localLicenseService.status());
    }

    @GetMapping("/device-id")
    public ResponseEntity<Map<String, Object>> deviceId() {
        return ok("Device id retrieved successfully.", Map.of("deviceId", localLicenseService.currentDeviceId()));
    }

    private ResponseEntity<Map<String, Object>> ok(String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", message);
        response.put("data", data);
        return ResponseEntity.ok(response);
    }
}

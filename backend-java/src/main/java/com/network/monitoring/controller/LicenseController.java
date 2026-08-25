package com.network.monitoring.controller;

import com.network.monitoring.service.LicenseService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping({"/licenses", "/api/v1/licenses"})
public class LicenseController {

    private final LicenseService licenseService;

    public LicenseController(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listLicenses() {
        return ok("Licenses retrieved successfully.", licenseService.listLicenses());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getLicense(@PathVariable("id") Long id) {
        return ok("License retrieved successfully.", licenseService.getLicense(id));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createLicense(@RequestBody Map<String, Object> body) {
        return ok("License created successfully.", licenseService.createLicense(body));
    }

    @PatchMapping("/{id}/renew")
    public ResponseEntity<Map<String, Object>> renewLicense(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        return ok("License renewed successfully.", licenseService.renewLicense(id, body));
    }

    @PatchMapping("/{id}/block")
    public ResponseEntity<Map<String, Object>> blockLicense(@PathVariable("id") Long id) {
        return ok("License blocked successfully.", licenseService.blockLicense(id));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> downloadLicense(@PathVariable("id") Long id) {
        byte[] file = licenseService.downloadLicenseFile(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("license.dat").build().toString())
                .body(file);
    }

    private ResponseEntity<Map<String, Object>> ok(String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", message);
        response.put("data", data);
        return ResponseEntity.ok(response);
    }
}

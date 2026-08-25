package com.network.monitoring.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class LocalLicenseService {

    private final Path licenseFilePath;
    private final LicenseFileService licenseFileService;

    public LocalLicenseService(@Value("${app.license-file-path}") String licenseFilePath,
                               LicenseFileService licenseFileService) {
        this.licenseFilePath = Paths.get(licenseFilePath).toAbsolutePath().normalize();
        this.licenseFileService = licenseFileService;
    }

    public Map<String, Object> uploadLicense(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("License file is required.");
        }
        Map<String, Object> payload = licenseFileService.verifyLicenseFile(file.getBytes());
        Map<String, Object> validation = validatePayload(payload);
        if (!Boolean.TRUE.equals(validation.get("valid"))) {
            throw new IllegalArgumentException(String.valueOf(validation.get("message")));
        }
        Path parent = licenseFilePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(licenseFilePath, file.getBytes());
        return statusFromPayload(payload, true, "License uploaded successfully.");
    }

    public boolean hasValidLicense() {
        return Boolean.TRUE.equals(status().get("valid"));
    }

    public Map<String, Object> status() {
        if (!Files.exists(licenseFilePath)) {
            return invalid("License file is not installed.");
        }
        try {
            Map<String, Object> payload = licenseFileService.verifyLicenseFile(Files.readAllBytes(licenseFilePath));
            Map<String, Object> validation = validatePayload(payload);
            if (!Boolean.TRUE.equals(validation.get("valid"))) {
                return statusFromPayload(payload, false, String.valueOf(validation.get("message")));
            }
            return statusFromPayload(payload, true, "License is valid.");
        } catch (Exception ex) {
            return invalid(ex.getMessage() == null ? "License file is invalid." : ex.getMessage());
        }
    }

    public String currentDeviceId() {
        String configured = System.getenv("LICENSE_DEVICE_ID");
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        try {
            for (NetworkInterface item : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                byte[] mac = item.getHardwareAddress();
                if (mac == null || mac.length == 0 || item.isLoopback() || item.isVirtual()) {
                    continue;
                }
                StringBuilder builder = new StringBuilder();
                for (byte value : mac) {
                    if (!builder.isEmpty()) {
                        builder.append("-");
                    }
                    builder.append(String.format("%02X", value));
                }
                return builder.toString();
            }
        } catch (Exception ignored) {
        }
        return System.getenv().getOrDefault("COMPUTERNAME", "LOCAL_DEVICE");
    }

    private Map<String, Object> validatePayload(Map<String, Object> payload) {
        String status = string(payload.get("status"));
        if (!"ACTIVE".equalsIgnoreCase(status)) {
            return invalid("License is not active.");
        }
        String deviceId = string(payload.get("deviceId"));
        if (deviceId == null || !deviceId.equalsIgnoreCase(currentDeviceId())) {
            return invalid("License is not valid for this device. Current device id: " + currentDeviceId());
        }
        LocalDate validFrom = date(payload.get("validFrom"));
        LocalDate expiresAt = date(payload.get("expiresAt"));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (validFrom != null && today.isBefore(validFrom)) {
            return invalid("License is not active yet.");
        }
        if (expiresAt == null || today.isAfter(expiresAt)) {
            return invalid("License is expired.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", true);
        result.put("message", "License is valid.");
        return result;
    }

    private Map<String, Object> statusFromPayload(Map<String, Object> payload, boolean valid, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("valid", valid);
        response.put("message", message);
        response.put("deviceId", currentDeviceId());
        response.put("licensePath", licenseFilePath.toString());
        response.put("license", payload);
        return response;
    }

    private Map<String, Object> invalid(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("valid", false);
        response.put("message", message);
        response.put("deviceId", currentDeviceId());
        response.put("licensePath", licenseFilePath.toString());
        return response;
    }

    private String string(Object value) {
        if (value == null) {
            return null;
        }
        String parsed = String.valueOf(value).trim();
        return parsed.isEmpty() ? null : parsed;
    }

    private LocalDate date(Object value) {
        String parsed = string(value);
        return parsed == null ? null : LocalDate.parse(parsed);
    }
}

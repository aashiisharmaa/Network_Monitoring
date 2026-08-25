package com.network.monitoring.controller;

import com.network.monitoring.service.PciAnalysisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping({"/pci", "/api/v1/pci"})
public class PciAnalysisController {

    private final PciAnalysisService pciAnalysisService;

    public PciAnalysisController(PciAnalysisService pciAnalysisService) {
        this.pciAnalysisService = pciAnalysisService;
    }

    @GetMapping("/analysis")
    public ResponseEntity<Map<String, Object>> analysis(@RequestParam("fileId") Long fileId,
                                                        @RequestParam("pci") Integer pci,
                                                        @RequestParam(value = "sourceSiteId", required = false) String sourceSiteId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", "PCI analysis retrieved successfully.");
        response.put("data", pciAnalysisService.analyze(fileId, pci, sourceSiteId));
        return ResponseEntity.ok(response);
    }
}

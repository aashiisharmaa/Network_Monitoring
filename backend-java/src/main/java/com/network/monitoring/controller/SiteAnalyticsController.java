package com.network.monitoring.controller;

import com.network.monitoring.service.SiteAnalyticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping({"/sites", "/api/v1/sites"})
public class SiteAnalyticsController {

    private final SiteAnalyticsService siteAnalyticsService;

    public SiteAnalyticsController(SiteAnalyticsService siteAnalyticsService) {
        this.siteAnalyticsService = siteAnalyticsService;
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(@RequestParam("fileId") Long fileId) {
        return ResponseEntity.ok(response("Site summary retrieved successfully.", siteAnalyticsService.siteSummary(fileId)));
    }

    @GetMapping("/worst")
    public ResponseEntity<Map<String, Object>> worst(@RequestParam("fileId") Long fileId,
                                                     @RequestParam(value = "limit", required = false) Integer limit) {
        return ResponseEntity.ok(response("Worst sites retrieved successfully.", siteAnalyticsService.worstSites(fileId, limit)));
    }

    @GetMapping("/details")
    public ResponseEntity<Map<String, Object>> details(@RequestParam("fileId") Long fileId,
                                                       @RequestParam("site") String site) {
        return ResponseEntity.ok(response("Site details retrieved successfully.", siteAnalyticsService.siteDetails(fileId, site)));
    }

    @GetMapping({"/intelligence", "/site-intelligence"})
    public ResponseEntity<Map<String, Object>> intelligence(@RequestParam("fileId") Long fileId,
                                                            @RequestParam(value = "siteFileId", required = false) Long siteFileId,
                                                            @RequestParam(value = "limit", required = false) Integer limit) {
        return ResponseEntity.ok(response("Site intelligence retrieved successfully.", siteAnalyticsService.siteIntelligence(fileId, siteFileId, limit)));
    }

    private Map<String, Object> response(String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", message);
        response.put("data", data);
        return response;
    }
}

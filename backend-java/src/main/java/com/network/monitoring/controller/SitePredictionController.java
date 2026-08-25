package com.network.monitoring.controller;

import com.network.monitoring.service.SitePredictionService;
import com.network.monitoring.service.LbPredictionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping({"/site-prediction", "/api/v1/site-prediction"})
public class SitePredictionController {

    private final SitePredictionService sitePredictionService;
    private final LbPredictionService lbPredictionService;

    public SitePredictionController(SitePredictionService sitePredictionService,
                                    LbPredictionService lbPredictionService) {
        this.sitePredictionService = sitePredictionService;
        this.lbPredictionService = lbPredictionService;
    }

    @GetMapping("/recommendations")
    public ResponseEntity<Map<String, Object>> recommendations(
            @RequestParam("fileId") Long fileId,
            @RequestParam(value = "site", required = false) String site,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ResponseEntity.ok(response(
                "Site prediction recommendations retrieved successfully.",
                sitePredictionService.recommendations(fileId, site, limit)
        ));
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(@RequestParam("fileId") Long fileId) {
        return ResponseEntity.ok(response(
                "Site prediction summary retrieved successfully.",
                sitePredictionService.summary(fileId)
        ));
    }

    @PostMapping("/lb-wcf")
    public ResponseEntity<Map<String, Object>> lbWcfPrediction(
            @RequestParam("fileId") Long fileId,
            @RequestParam(value = "taFile", required = false) MultipartFile taFile,
            @RequestParam(value = "method", required = false) String method,
            @RequestParam(value = "quantile", required = false) Double quantile,
            @RequestParam(value = "mlMode", required = false) Integer mlMode) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("method", method);
            body.put("quantile", quantile);
            body.put("mlMode", mlMode);
            return ResponseEntity.ok(response(
                    "LB worst-cell prediction completed successfully.",
                    lbPredictionService.runWcf(fileId, taFile, body)
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(errorResponse(ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(errorResponse(ex.getMessage()));
        }
    }

    private Map<String, Object> response(String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", message);
        response.put("data", data);
        return response;
    }

    private Map<String, Object> errorResponse(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("message", message == null || message.isBlank() ? "Unexpected error" : message);
        return response;
    }

}

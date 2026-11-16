package com.network.monitoring.alarm.api;

import com.network.monitoring.alarm.ingestion.AlarmImportPreviewService;
import com.network.monitoring.identity.service.RoleAuthorizationService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/alarm-ingestion")
public class AlarmImportPreviewController {
    private final AlarmImportPreviewService service;
    private final RoleAuthorizationService authorization;

    public AlarmImportPreviewController(AlarmImportPreviewService service, RoleAuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @PostMapping(value = "/preview", consumes = "multipart/form-data")
    public ResponseEntity<?> preview(@RequestParam("file") MultipartFile file,
                                     @RequestParam(value = "sheet", required = false) String sheet,
                                     @RequestParam(value = "oem", required = false) String oem,
                                     @RequestParam(value = "technology", required = false) String technology,
                                     @RequestParam("sourceTimezone") String sourceTimezone) {
        try { authorization.requireSuperAdmin(); }
        catch (IllegalArgumentException e) {
            return ResponseEntity.status(403).body(Map.of("success", false, "message", "Super admin access is required."));
        }
        try {
            return ResponseEntity.ok(Map.of("success", true, "data", service.preview(file, sheet, oem, technology, sourceTimezone)));
        } catch (AlarmImportPreviewService.PreviewBusyException e) {
            return ResponseEntity.status(503).header("Retry-After", "5")
                    .body(Map.of("success", false, "code", "ALARM_PREVIEW_BUSY", "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }
}

package com.network.monitoring.alarm.api;

import com.network.monitoring.alarm.service.AlarmConfigurationService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.network.monitoring.alarm.dto.AlarmConfiguration.*;

@RestController
@RequestMapping("/api/v1/alarm-config/companies/{companyId}")
public class AlarmConfigurationController {
    private final AlarmConfigurationService service;
    public AlarmConfigurationController(AlarmConfigurationService service) { this.service = service; }

    @GetMapping("/zones")
    public Map<String, Object> zones(@PathVariable("companyId") long companyId, @RequestParam(value = "afterId", defaultValue = "0") long afterId,
                                     @RequestParam(value = "limit", defaultValue = "100") int limit) { return ok(service.zones(companyId, afterId, limit)); }
    @PostMapping("/zones")
    public ResponseEntity<?> createZone(@PathVariable("companyId") long companyId, @Valid @RequestBody ZoneCreate body) {
        return ResponseEntity.status(201).body(ok(service.createZone(companyId, body)));
    }
    @PutMapping("/zones/{id}")
    public Map<String, Object> updateZone(@PathVariable("companyId") long companyId, @PathVariable("id") long id, @Valid @RequestBody ZoneUpdate body) {
        return ok(service.updateZone(companyId, id, body));
    }
    @GetMapping("/circles")
    public Map<String, Object> circles(@PathVariable("companyId") long companyId, @RequestParam(value = "afterId", defaultValue = "0") long afterId,
                                      @RequestParam(value = "limit", defaultValue = "100") int limit) { return ok(service.circles(companyId, afterId, limit)); }
    @PostMapping("/circles")
    public ResponseEntity<?> createCircle(@PathVariable("companyId") long companyId, @Valid @RequestBody CircleCreate body) {
        return ResponseEntity.status(201).body(ok(service.createCircle(companyId, body)));
    }
    @PutMapping("/circles/{id}")
    public Map<String, Object> updateCircle(@PathVariable("companyId") long companyId, @PathVariable("id") long id, @Valid @RequestBody CircleUpdate body) {
        return ok(service.updateCircle(companyId, id, body));
    }
    @GetMapping("/circle-aliases")
    public Map<String, Object> aliases(@PathVariable("companyId") long companyId, @RequestParam(value = "afterId", defaultValue = "0") long afterId,
                                      @RequestParam(value = "limit", defaultValue = "100") int limit) { return ok(service.aliases(companyId, afterId, limit)); }
    @PostMapping("/circle-aliases")
    public ResponseEntity<?> createAlias(@PathVariable("companyId") long companyId, @Valid @RequestBody AliasCreate body) {
        return ResponseEntity.status(201).body(ok(service.createAlias(companyId, body)));
    }
    @DeleteMapping("/circle-aliases/{id}")
    public ResponseEntity<Void> deleteAlias(@PathVariable("companyId") long companyId, @PathVariable("id") long id) {
        service.deleteAlias(companyId, id); return ResponseEntity.noContent().build();
    }
    @GetMapping("/circle-resolution")
    public Map<String, Object> resolve(@PathVariable("companyId") long companyId, @RequestParam("value") String value) { return ok(service.resolveCircle(companyId, value)); }
    @GetMapping("/feeds")
    public Map<String, Object> feeds(@PathVariable("companyId") long companyId, @RequestParam(value = "afterId", defaultValue = "0") long afterId,
                                    @RequestParam(value = "limit", defaultValue = "100") int limit) { return ok(service.feeds(companyId, afterId, limit)); }
    @GetMapping("/feeds/{id}")
    public Map<String, Object> feed(@PathVariable("companyId") long companyId, @PathVariable("id") long id) { return ok(service.feed(companyId, id)); }
    @PostMapping("/feeds")
    public ResponseEntity<?> createFeed(@PathVariable("companyId") long companyId, @Valid @RequestBody FeedCreate body) {
        return ResponseEntity.status(201).body(ok(service.createFeed(companyId, body)));
    }
    @PutMapping("/feeds/{id}")
    public Map<String, Object> updateFeed(@PathVariable("companyId") long companyId, @PathVariable("id") long id, @Valid @RequestBody FeedUpdate body) {
        return ok(service.updateFeed(companyId, id, body));
    }
    private static Map<String, Object> ok(Object value) { return Map.of("success", true, "data", value); }
}

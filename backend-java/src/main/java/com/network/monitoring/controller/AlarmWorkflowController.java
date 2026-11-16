package com.network.monitoring.controller;

import com.network.monitoring.alarm.workflow.AlarmWorkflowService;
import com.network.monitoring.alarm.workflow.AlarmAutomationService;
import com.network.monitoring.alarm.workflow.AlarmReportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/alarm-workflow/companies/{companyId}")
public class AlarmWorkflowController {
    private final AlarmWorkflowService service;
    private final AlarmReportService reports;
    private final AlarmAutomationService automation;
    public AlarmWorkflowController(AlarmWorkflowService service,AlarmReportService reports,AlarmAutomationService automation) { this.service = service; this.reports=reports;this.automation=automation; }
    private Map<String, Object> ok(Object value) { return Map.of("success", true, "data", value); }
    @PostMapping(value = "/feeds/{feedId}/imports", consumes = "multipart/form-data")
    public ResponseEntity<?> submit(@PathVariable("companyId") long company, @PathVariable("feedId") long feed,
            @RequestParam("snapshotAt") String at, @RequestParam("file") MultipartFile file) {
        return ResponseEntity.accepted().body(ok(service.submit(company, feed, at, file)));
    }
    @GetMapping("/imports") public Map<String, Object> runs(@PathVariable("companyId") long company,
            @RequestParam(value = "limit", defaultValue = "50") int limit) { return ok(service.runs(company, limit)); }
    @GetMapping("/imports/{id}") public Map<String, Object> run(@PathVariable("companyId") long company, @PathVariable("id") long id) { return ok(service.run(company, id)); }
    @PostMapping("/imports/{id}/retry") public Map<String, Object> retry(@PathVariable("companyId") long company, @PathVariable("id") long id) { return ok(service.retry(company, id)); }
    @PostMapping(value="/imports/{id}/replace",consumes="multipart/form-data") public ResponseEntity<?> replace(@PathVariable("companyId") long company,@PathVariable("id") long id,@RequestParam("file") MultipartFile file) {return ResponseEntity.accepted().body(ok(service.replace(company,id,file)));}
    @GetMapping("/imports/{id}/observations") public Map<String, Object> observations(@PathVariable("companyId") long company, @PathVariable("id") long id,
            @RequestParam(value = "afterId", defaultValue = "0") long after, @RequestParam(value = "limit", defaultValue = "100") int limit) { return ok(service.observations(company, id, after, limit)); }
    @GetMapping("/dashboard") public Map<String, Object> dashboard(@PathVariable("companyId") long company,
            @RequestParam(value = "asOf", required = false) String at) { return ok(service.dashboard(company, at == null ? Instant.now().toString() : at)); }
    @GetMapping("/catalogue") public Map<String, Object> catalog(@PathVariable("companyId") long company) { return ok(service.catalogs(company)); }
    @PostMapping("/catalogue") public ResponseEntity<?> catalog(@PathVariable("companyId") long company, @RequestBody AlarmWorkflowService.Catalog rule) { return ResponseEntity.status(201).body(ok(service.addCatalog(company, rule))); }
    @DeleteMapping("/catalogue/{id}") public ResponseEntity<Void> removeCatalog(@PathVariable("companyId") long company, @PathVariable("id") long id) { service.removeCatalog(company, id); return ResponseEntity.noContent().build(); }
    @GetMapping("/feeds/{id}/sites") public Map<String, Object> sites(@PathVariable("companyId") long company, @PathVariable("id") long id) { return ok(service.sites(company, id)); }
    @PostMapping("/feeds/{id}/sites") public ResponseEntity<?> site(@PathVariable("companyId") long company, @PathVariable("id") long id, @RequestBody AlarmWorkflowService.Site site) { return ResponseEntity.status(201).body(ok(service.addSite(company, id, site))); }
    @DeleteMapping("/feeds/{id}/sites/{siteId}") public ResponseEntity<Void> removeSite(@PathVariable("companyId") long company, @PathVariable("id") long id, @PathVariable("siteId") long site) { service.removeSite(company, id, site); return ResponseEntity.noContent().build(); }
    @GetMapping("/imports/{id}/report/{format}") public ResponseEntity<byte[]> report(@PathVariable("companyId") long company,@PathVariable("id") long id,@PathVariable("format") String format,@RequestParam(value="circleId",required=false) Long circle) {
        if(!java.util.Set.of("csv","pdf").contains(format)) throw com.network.monitoring.alarm.config.AlarmConfigurationException.invalid("Report format must be csv or pdf.");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=alarm-report-"+id+"."+format)
                .contentType(format.equals("pdf")?MediaType.APPLICATION_PDF:MediaType.parseMediaType("text/csv; charset=UTF-8")).body(reports.download(company,id,circle,format));
    }
    @GetMapping("/dashboard/report/{format}") public ResponseEntity<byte[]> national(@PathVariable("companyId") long company,@PathVariable("format") String format,@RequestParam(value="asOf") String at,@RequestParam(value="circleId",required=false) Long circle) {
        if(!java.util.Set.of("csv","pdf").contains(format)) throw com.network.monitoring.alarm.config.AlarmConfigurationException.invalid("Report format must be csv or pdf.");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=alarm-national-report."+format).contentType(format.equals("pdf")?MediaType.APPLICATION_PDF:MediaType.parseMediaType("text/csv; charset=UTF-8")).body(reports.nationalDownload(company,at,circle,format));
    }
    @GetMapping("/feeds/{id}/alarms/{key}/history") public Map<String,Object> history(@PathVariable("companyId") long company,@PathVariable("id") long id,@PathVariable("key") String key) {return ok(service.history(company,id,key));}
    @PostMapping("/feeds/{id}/alarms/{key}/events") public Map<String,Object> event(@PathVariable("companyId") long company,@PathVariable("id") long id,@PathVariable("key") String key,@RequestBody Map<String,String> body) {service.operatorEvent(company,id,key,body.get("action"),body.get("note"));return ok(Map.of("recorded",true));}
    @GetMapping("/automation") public Map<String,Object> settings(@PathVariable("companyId") long company) {return ok(automation.settings(company));}
    @PutMapping("/automation") public Map<String,Object> settings(@PathVariable("companyId") long company,@RequestBody AlarmAutomationService.Settings body) {return ok(automation.save(company,body));}
    @GetMapping("/status") public Map<String,Object> status(@PathVariable("companyId") long company) {return ok(automation.status(company));}
    @GetMapping("/deliveries") public Map<String,Object> deliveries(@PathVariable("companyId") long company) {return ok(automation.deliveries(company));}
    @PostMapping("/deliveries/{id}/retry") public Map<String,Object> retryDelivery(@PathVariable("companyId") long company,@PathVariable("id") long id) {automation.retry(company,id);return ok(Map.of("queued",true));}
    @PostMapping("/deliveries/{id}/reconcile") public Map<String,Object> reconcile(@PathVariable("companyId") long company,@PathVariable("id") long id,@RequestBody Map<String,String> body) {automation.reconcile(company,id,body.get("outcome"),body.get("note"));return ok(Map.of("recorded",true));}
    @GetMapping("/deliveries/{id}/capture") public ResponseEntity<byte[]> capture(@PathVariable("companyId") long company,@PathVariable("id") long id) {return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=alarm-email-"+id+".eml").contentType(MediaType.parseMediaType("message/rfc822")).body(automation.captured(company,id));}
}

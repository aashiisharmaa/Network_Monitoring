package com.network.monitoring.controller;

import com.network.monitoring.service.CompanyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping({"/companies", "/api/v1/companies"})
public class CompanyController {

    private final CompanyService companyService;

    public CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listCompanies() {
        return ok("Companies retrieved successfully.", companyService.listCompanies());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getCompany(@PathVariable Long id) {
        return ok("Company retrieved successfully.", companyService.getCompany(id));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createCompany(@RequestBody Map<String, Object> body) {
        return ok("Company created successfully.", companyService.createCompany(body));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateCompany(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ok("Company updated successfully.", companyService.updateCompany(id, body));
    }

    @PatchMapping("/{id}/block")
    public ResponseEntity<Map<String, Object>> blockCompany(@PathVariable Long id) {
        return ok("Company blocked successfully.", companyService.blockCompany(id));
    }

    private ResponseEntity<Map<String, Object>> ok(String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", message);
        response.put("data", data);
        return ResponseEntity.ok(response);
    }
}

package com.network.monitoring.controller;

import com.network.monitoring.service.RoleAuthorizationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Scoped, bounded dataset picker for the reporting workspace. */
@RestController
@RequestMapping("/api/v1/report-datasets")
public class ReportDatasetController {
    private final JdbcTemplate jdbc; private final RoleAuthorizationService role;
    public ReportDatasetController(JdbcTemplate jdbc, RoleAuthorizationService role) { this.jdbc = jdbc; this.role = role; }
    @GetMapping public ResponseEntity<?> list(@RequestParam(value="page",defaultValue="1") int page, @RequestParam(value="limit",defaultValue="50") int limit) {
        if (page < 1 || page > 100000 || limit < 1 || limit > 100) return ResponseEntity.badRequest().body(Map.of("message", "Invalid dataset page or limit."));
        boolean admin = role.isCurrentUserSuperAdmin(); Long company = role.currentUserCompanyIdOrNull();
        if (!admin && company == null) return ResponseEntity.status(403).body(Map.of("message", "A company assignment is required."));
        String where = " WHERE (LOWER(COALESCE(remarks,'')) LIKE '%kpi%' OR LOWER(COALESCE(fileName,'')) LIKE '%kpi%' OR LOWER(COALESCE(fileType,''))='kpi')" + (admin ? "" : " AND companyId=?");
        List<Object> args = new ArrayList<>(); if (!admin) args.add(company);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM uploadhistory" + where, Long.class, args.toArray());
        args.add(limit); args.add((page - 1) * limit);
        var items = jdbc.queryForList("SELECT id,fileName,companyId,createdAt FROM uploadhistory" + where + " ORDER BY id DESC LIMIT ? OFFSET ?", args.toArray());
        return ResponseEntity.ok(Map.of("success", true, "data", Map.of("items", items, "total", total == null ? 0 : total, "page", page, "limit", limit)));
    }
}

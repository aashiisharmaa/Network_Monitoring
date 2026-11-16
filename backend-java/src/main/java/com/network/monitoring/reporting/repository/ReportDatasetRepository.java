package com.network.monitoring.reporting.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Bounded dataset queries; a null company is reserved for authorized global reads. */
@Repository
public class ReportDatasetRepository {
    private final JdbcTemplate jdbc;

    public ReportDatasetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> page(Long company, int page, int limit) {
        String where = " WHERE (LOWER(COALESCE(remarks,'')) LIKE '%kpi%'"
                + " OR LOWER(COALESCE(fileName,'')) LIKE '%kpi%' OR LOWER(COALESCE(fileType,''))='kpi')"
                + (company == null ? "" : " AND companyId=?");
        List<Object> args = new ArrayList<>();
        if (company != null) args.add(company);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM uploadhistory" + where, Long.class, args.toArray());
        args.add(limit);
        args.add((page - 1) * limit);
        var items = jdbc.queryForList("SELECT id,fileName,companyId,createdAt FROM uploadhistory"
                + where + " ORDER BY id DESC LIMIT ? OFFSET ?", args.toArray());
        return Map.of("items", items, "total", total == null ? 0 : total, "page", page, "limit", limit);
    }
}

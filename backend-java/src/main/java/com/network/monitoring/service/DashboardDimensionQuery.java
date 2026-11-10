package com.network.monitoring.service;

import java.util.ArrayList;
import java.util.List;

/** Builds dimension counts without changing the existing normalization/UNION semantics. */
final class DashboardDimensionQuery {
    enum Dimension {
        CELLS("totalCells", "Cell_Name", "cellName"),
        SITES("totalSites", "Site", "site"),
        BANDS("totalBands", "Band", "band"),
        TECHNOLOGIES("totalTech", "Tech", "tech"),
        SECTORS("totalSectors", "Sector_ID", "sectorId"),
        GROUPS("totalGroups", "Groups", "groupsName");

        final String outputKey;
        final String uploadColumn;
        final String dynamicColumn;

        Dimension(String outputKey, String uploadColumn, String dynamicColumn) {
            this.outputKey = outputKey;
            this.uploadColumn = uploadColumn;
            this.dynamicColumn = dynamicColumn;
        }
    }

    record Statement(String sql, List<Object> parameters) { }

    static Statement build(Dimension dimension, Long fileId, Long companyId,
                           boolean preaggregateUpload, boolean preaggregateDynamic) {
        List<Object> parameters = new ArrayList<>();
        String upload = source("uploaddata", dimension.uploadColumn, fileId, companyId,
                preaggregateUpload, parameters);
        String dynamic = source("kpidynamicdata", dimension.dynamicColumn, fileId, companyId,
                preaggregateDynamic, parameters);
        return new Statement("SELECT COUNT(*) FROM (" + upload + " UNION " + dynamic + ") merged",
                List.copyOf(parameters));
    }

    private static String source(String table, String column, Long fileId, Long companyId,
                                 boolean preaggregate, List<Object> parameters) {
        String quoted = "`" + column + "`";
        StringBuilder scope = new StringBuilder(quoted + " IS NOT NULL");
        if (fileId != null) {
            scope.append(" AND `fileId` = ?");
            parameters.add(fileId);
        }
        if (companyId != null) {
            scope.append(" AND `companyId` = ?");
            parameters.add(companyId);
        }
        if (preaggregate) {
            // Including the leading fileId allows MySQL's loose index scan on (fileId, dimension).
            // Only safe when the source collation equals the final UNION collation.
            String normalized = normalize("raw_value");
            return "SELECT " + normalized + " AS value FROM (SELECT DISTINCT `fileId`, "
                    + quoted + " AS raw_value FROM `" + table + "` WHERE " + scope
                    + ") raw_values WHERE " + normalized + " <> ''";
        }
        String normalized = normalize(quoted);
        return "SELECT " + normalized + " AS value FROM `" + table + "` WHERE "
                + scope + " AND " + normalized + " <> ''";
    }

    private static String normalize(String expression) {
        return "TRIM(CONVERT(" + expression + " USING utf8mb4)) COLLATE utf8mb4_unicode_ci";
    }
}

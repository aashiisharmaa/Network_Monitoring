package com.network.monitoring.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DashboardDimensionQueryTest {
    @Test
    void scopesBothSourcesAndBindsValuesInsteadOfInterpolating() {
        var statement = DashboardDimensionQuery.build(DashboardDimensionQuery.Dimension.CELLS,
                17L, 42L, true, true);
        assertEquals(List.of(17L, 42L, 17L, 42L), statement.parameters());
        assertEquals(4, statement.sql().chars().filter(c -> c == '?').count());
        assertFalse(statement.sql().contains("42"));
    }

    @Test
    void fallsBackIndependentlyForSourcesWithDifferentCollation() {
        var statement = DashboardDimensionQuery.build(DashboardDimensionQuery.Dimension.CELLS,
                null, null, true, false);
        assertEquals(1, statement.sql().split("SELECT DISTINCT", -1).length - 1);
        assertTrue(statement.parameters().isEmpty());
        assertTrue(statement.sql().contains("FROM `kpidynamicdata` WHERE `cellName` IS NOT NULL"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KPI_TEST_DB_URL", matches = ".+")
    void mysqlCountsMatchOriginalAcrossUnicodeDuplicatesAndAllScopes() throws Exception {
        // TEMPORARY tables shadow production names only on this connection and disappear on close.
        try (Connection connection = DriverManager.getConnection(System.getenv("KPI_TEST_DB_URL"),
                System.getenv("KPI_TEST_DB_USER"), System.getenv("KPI_TEST_DB_PASSWORD"))) {
            createTemporaryTable(connection, "uploaddata", true);
            createTemporaryTable(connection, "kpidynamicdata", false);
            String[] values = {null, "", "   ", "Alpha", " alpha ", "ALPHA", "Café", "CAFE",
                    "\u00a0", "ß", "ss", "a\u0301", "á", "x", "x ", "İ", "i"};
            for (int i = 0; i < values.length; i++) {
                insert(connection, "uploaddata", true, i % 3 + 1L, i % 2 + 10L, values[i]);
                insert(connection, "kpidynamicdata", false, i % 3 + 1L, i % 2 + 10L, values[i]);
            }
            // Also exercise duplicates across different files and missing file/company IDs.
            insert(connection, "kpidynamicdata", false, 4L, 10L, " alpha ");
            insert(connection, "uploaddata", true, null, null, "Unscoped");
            for (Long file : new Long[]{null, 1L, 4L, 999L}) {
                for (Long company : new Long[]{null, 10L, 11L, 999L}) {
                    for (var dimension : DashboardDimensionQuery.Dimension.values()) {
                        long original = count(connection, DashboardDimensionQuery.build(dimension, file, company, false, false));
                        for (boolean upload : new boolean[]{false, true}) {
                            for (boolean dynamic : new boolean[]{false, true}) {
                                assertEquals(original, count(connection, DashboardDimensionQuery.build(
                                        dimension, file, company, upload, dynamic)),
                                        dimension + " file=" + file + " company=" + company);
                            }
                        }
                    }
                }
            }
            assertEquals(1, count(connection, DashboardDimensionQuery.build(
                    DashboardDimensionQuery.Dimension.CELLS, 4L, 10L, true, true)));
            assertEquals(0, count(connection, DashboardDimensionQuery.build(
                    DashboardDimensionQuery.Dimension.CELLS, 999L, null, true, true)));
        }
    }

    private void createTemporaryTable(Connection connection, String table, boolean upload) throws SQLException {
        StringBuilder columns = new StringBuilder("fileId BIGINT NULL, companyId BIGINT NULL");
        for (var dimension : DashboardDimensionQuery.Dimension.values()) {
            columns.append(", `").append(upload ? dimension.uploadColumn : dimension.dynamicColumn)
                    .append("` VARCHAR(255) COLLATE utf8mb4_unicode_ci NULL");
        }
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TEMPORARY TABLE `" + table + "` (" + columns
                    + ") CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private void insert(Connection connection, String table, boolean upload, Long file, Long company,
                        String value) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO `" + table + "` VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setObject(1, file);
            statement.setObject(2, company);
            for (int index = 3; index <= 8; index++) statement.setString(index, value);
            statement.executeUpdate();
        }
    }

    private long count(Connection connection, DashboardDimensionQuery.Statement query) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
            for (int i = 0; i < query.parameters().size(); i++) statement.setObject(i + 1, query.parameters().get(i));
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }
}

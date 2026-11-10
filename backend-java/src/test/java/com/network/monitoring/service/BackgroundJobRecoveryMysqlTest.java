package com.network.monitoring.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BackgroundJobRecoveryMysqlTest {
    @TempDir Path directory;

    @Test
    @EnabledIfEnvironmentVariable(named = "KPI_TEST_DB_URL", matches = ".+")
    @SuppressWarnings("unchecked")
    void recoveryOnlyFailsUnfinishedRowsAndDistinguishesSafeQueuedUploadFromPartialWrites() throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv("KPI_TEST_DB_URL"),
                System.getenv("KPI_TEST_DB_USER"), System.getenv("KPI_TEST_DB_PASSWORD"))) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("""
                    CREATE TEMPORARY TABLE upload_jobs (
                      id BIGINT PRIMARY KEY, fileName VARCHAR(255), status VARCHAR(32), progressPercent INT,
                      message TEXT, resultJson LONGTEXT, filePath VARCHAR(1024), createdAt DATETIME, updatedAt DATETIME
                    )
                    """);
            jdbc.execute("""
                    CREATE TEMPORARY TABLE recommendation_jobs (
                      id BIGINT PRIMARY KEY, fileId BIGINT, configHash VARCHAR(64), status VARCHAR(32), progressPercent INT,
                      message TEXT, resultJson LONGTEXT, createdAt DATETIME, updatedAt DATETIME
                    )
                    """);
            Path staged = Files.writeString(directory.resolve("daily.csv"), "a,b\n1,2");
            String[] states = {"QUEUED", "PROCESSING", "COMPLETED", "FAILED"};
            for (int i = 0; i < states.length; i++) {
                jdbc.update("INSERT INTO upload_jobs (id,status,filePath,progressPercent,updatedAt) VALUES (?,?,?,15,NOW())",
                        i + 1, states[i], staged.toString());
                jdbc.update("INSERT INTO recommendation_jobs (id,status,progressPercent,updatedAt) VALUES (?,?,15,NOW())", i + 1, states[i]);
            }
            var uploads = new UploadJobService(jdbc, null, mock(ObjectProvider.class), directory.toString(), 30);
            var rca = new RecommendationJobService(jdbc, null, mock(ObjectProvider.class));
            uploads.recoverInterruptedJobs();
            rca.recoverInterruptedJobs();
            for (String table : new String[]{"upload_jobs", "recommendation_jobs"}) {
                assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE status='FAILED'", Integer.class));
                assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM " + table + " WHERE id=3", String.class));
            }
            var queued = (Map<String, Object>) uploads.getJob(1L).get("data");
            var partial = (Map<String, Object>) uploads.getJob(2L).get("data");
            assertEquals(true, queued.get("retryable"));
            assertEquals(false, partial.get("retryable"));
            String messageBefore = jdbc.queryForObject("SELECT message FROM upload_jobs WHERE id=2", String.class);
            uploads.recoverInterruptedJobs();
            assertEquals(messageBefore, jdbc.queryForObject("SELECT message FROM upload_jobs WHERE id=2", String.class));

            jdbc.update("UPDATE upload_jobs SET status='PROCESSING' WHERE id=2");
            ReflectionTestUtils.setField(uploads, "recoverOnStartup", false);
            uploads.recoverInterruptedJobs();
            assertEquals("PROCESSING", jdbc.queryForObject("SELECT status FROM upload_jobs WHERE id=2", String.class));
        }
    }
}

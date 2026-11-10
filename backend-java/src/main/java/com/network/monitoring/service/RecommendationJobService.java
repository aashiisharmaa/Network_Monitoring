package com.network.monitoring.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

@Service
public class RecommendationJobService {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final RecommendationService recommendationService;
    private final ObjectProvider<RecommendationJobService> selfProvider;
    @Value("${app.jobs.recover-on-startup:true}")
    private boolean recoverOnStartup = true;

    public RecommendationJobService(JdbcTemplate jdbcTemplate,
                                    RecommendationService recommendationService,
                                    ObjectProvider<RecommendationJobService> selfProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.recommendationService = recommendationService;
        this.selfProvider = selfProvider;
    }

    @PostConstruct
    public void ensureTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `recommendation_jobs` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT,
                  `fileId` BIGINT NOT NULL,
                  `configHash` VARCHAR(64) NOT NULL,
                  `status` VARCHAR(32) NOT NULL,
                  `progressPercent` INT NOT NULL DEFAULT 0,
                  `message` VARCHAR(512) NULL,
                  `requestJson` LONGTEXT NULL,
                  `resultJson` LONGTEXT NULL,
                  `createdAt` DATETIME NULL,
                  `updatedAt` DATETIME NULL,
                  PRIMARY KEY (`id`),
                  INDEX `idx_recommendation_jobs_file_hash` (`fileId`, `configHash`),
                  INDEX `idx_recommendation_jobs_status` (`status`),
                  INDEX `idx_recommendation_jobs_updated` (`updatedAt`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        recoverInterruptedJobs();
    }

    public void recoverInterruptedJobs() {
        if (recoverOnStartup) markStaleProcessingJobs("Backend restarted before recommendation finished. Please run recommendation again.");
    }

    public Map<String, Object> startJob(Long fileId, Map<String, Object> request) {
        if (fileId == null || fileId <= 0) {
            throw new IllegalArgumentException("fileId query parameter is required.");
        }
        // Detach nested settings from the caller before asynchronous processing.
        Map<String, Object> safeRequest = request == null ? Map.of() : MAPPER.convertValue(request, MAP_TYPE);
        String cacheKey = recommendationService.currentCacheKey(fileId, safeRequest).orElse(null);
        String configHash = cacheKey == null
                ? RecommendationCacheKey.build(safeRequest, UUID.randomUUID().toString(), Map.of("cache", "disabled"))
                : cacheKey;

        Map<String, Object> cached = cacheKey == null ? null : findLatestReusableJob(fileId, configHash);
        if (cached != null && recommendationService.currentCacheKey(fileId, safeRequest).filter(cacheKey::equals).isPresent()) {
            return Map.of("success", true, "message", "Recommendation result loaded from cache.", "data", cached);
        }

        Long jobId = createJob(fileId, configHash, safeRequest);
        try {
            selfProvider.getObject().processJob(jobId, fileId, safeRequest, cacheKey);
        } catch (RejectedExecutionException ex) {
            String message = "RCA workers and queue are full. Please try again shortly.";
            updateJob(jobId, "FAILED", 100, message,
                    Map.of("success", false, "code", "JOB_CAPACITY", "retryable", true, "message", message));
            throw new JobCapacityException(jobId, message, ex);
        } catch (RuntimeException ex) {
            String message = "RCA job could not be scheduled. Please run recommendation again.";
            updateJob(jobId, "FAILED", 100, message,
                    Map.of("success", false, "code", "JOB_SUBMISSION_FAILED", "message", message));
            throw ex;
        }
        return Map.of("success", true, "message", "Recommendation job started.", "data", getJobData(jobId));
    }

    public Map<String, Object> getJob(Long jobId) {
        return Map.of("success", true, "message", "Recommendation job loaded.", "data", getJobData(jobId));
    }

    @Async("rcaJobExecutor")
    public void processJob(Long jobId, Long fileId, Map<String, Object> request, String expectedCacheKey) {
        RecommendationService.RecommendationResult result = null;
        try {
            int claimed = jdbcTemplate.update("""
                    UPDATE `recommendation_jobs`
                    SET `status` = ?, `progressPercent` = ?, `message` = ?, `updatedAt` = ?
                    WHERE `id` = ? AND `status` = 'QUEUED'
                    """, "PROCESSING", 20, "Running RCA recommendation...", Timestamp.valueOf(LocalDateTime.now()), jobId);
            if (claimed == 0) return;
            verifyCacheKey(fileId, request, expectedCacheKey);
            result = recommendationService.runRecommendation(
                    fileId,
                    request,
                    false,
                    (progressPercent, message) -> updateJob(jobId, "PROCESSING", progressPercent, message, null)
            );
            if (expectedCacheKey != null && !expectedCacheKey.equals(result.inputCacheKey)) {
                throw new IllegalStateException("KPI data or RCA settings changed before input was loaded. Please run recommendation again.");
            }
            verifyCacheKey(fileId, request, expectedCacheKey);
            Map<String, Object> payload = new LinkedHashMap<>();
            recommendationService.retainArtifacts(result, fileId, request);
            payload.put("success", true);
            payload.put("fileId", fileId);
            payload.putAll(result.payload);
            updateJob(jobId, "COMPLETED", 100, "Recommendation complete.", payload);
        } catch (Throwable ex) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("success", false);
            payload.put("message", failureMessage(ex));
            updateJob(jobId, "FAILED", 100, String.valueOf(payload.get("message")), payload);
        } finally {
            if (result != null) {
                recommendationService.cleanup(result.workDir);
            }
        }
    }

    private void verifyCacheKey(Long fileId, Map<String, Object> request, String expected) {
        if (expected != null && recommendationService.currentCacheKey(fileId, request).filter(expected::equals).isEmpty()) {
            throw new IllegalStateException("KPI data or RCA engine changed during this job. Please run recommendation again.");
        }
    }

    private Long createJob(Long fileId, String configHash, Map<String, Object> request) {
        LocalDateTime now = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update((PreparedStatementCreator) connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO `recommendation_jobs`
                    (`fileId`, `configHash`, `status`, `progressPercent`, `message`, `requestJson`, `createdAt`, `updatedAt`)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, fileId);
            statement.setString(2, configHash);
            statement.setString(3, "QUEUED");
            statement.setInt(4, 5);
            statement.setString(5, "Recommendation queued.");
            statement.setString(6, writeJson(request));
            statement.setTimestamp(7, Timestamp.valueOf(now));
            statement.setTimestamp(8, Timestamp.valueOf(now));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Unable to create recommendation job.");
        }
        return key.longValue();
    }

    private Map<String, Object> findLatestReusableJob(Long fileId, String configHash) {
        List<Map<String, Object>> jobs = jdbcTemplate.query("""
                SELECT `id`, `fileId`, `configHash`, `status`, `progressPercent`, `message`, `resultJson`, `createdAt`, `updatedAt`
                FROM `recommendation_jobs`
                WHERE `fileId` = ? AND `configHash` = ? AND `status` = 'COMPLETED' AND `resultJson` IS NOT NULL
                ORDER BY `updatedAt` DESC, `id` DESC
                LIMIT 1
                """, (rs, rowNum) -> jobPayload(
                rs.getLong("id"),
                rs.getLong("fileId"),
                rs.getString("configHash"),
                rs.getString("status"),
                rs.getInt("progressPercent"),
                rs.getString("message"),
                rs.getString("resultJson"),
                rs.getObject("createdAt"),
                rs.getObject("updatedAt")
        ), fileId, configHash);
        return jobs.isEmpty() ? null : jobs.get(0);
    }

    private Map<String, Object> getJobData(Long jobId) {
        if (jobId == null) {
            throw new IllegalArgumentException("jobId is required.");
        }
        List<Map<String, Object>> jobs = jdbcTemplate.query("""
                SELECT `id`, `fileId`, `configHash`, `status`, `progressPercent`, `message`, `resultJson`, `createdAt`, `updatedAt`
                FROM `recommendation_jobs`
                WHERE `id` = ?
                """, (rs, rowNum) -> jobPayload(
                rs.getLong("id"),
                rs.getLong("fileId"),
                rs.getString("configHash"),
                rs.getString("status"),
                rs.getInt("progressPercent"),
                rs.getString("message"),
                rs.getString("resultJson"),
                rs.getObject("createdAt"),
                rs.getObject("updatedAt")
        ), jobId);
        if (jobs.isEmpty()) {
            throw new IllegalArgumentException("Recommendation job not found for id " + jobId + ".");
        }
        return jobs.get(0);
    }

    private void markStaleProcessingJobs(String reason) {
        LocalDateTime now = LocalDateTime.now();
        String message = reason == null || reason.isBlank()
                ? "Recommendation job stopped before completion. Please run recommendation again."
                : reason;
        jdbcTemplate.update("""
                UPDATE `recommendation_jobs`
                SET `status` = 'FAILED',
                    `progressPercent` = 100,
                    `message` = ?,
                    `resultJson` = ?,
                    `updatedAt` = ?
                WHERE `status` IN ('QUEUED', 'PROCESSING')
                """,
                message,
                writeJson(Map.of("success", false, "code", "BACKEND_RESTARTED", "retryable", true, "message", message)),
                Timestamp.valueOf(now));
    }

    private Map<String, Object> jobPayload(Long id,
                                           Long fileId,
                                           String configHash,
                                           String status,
                                           int progressPercent,
                                           String message,
                                           String resultJson,
                                           Object createdAt,
                                           Object updatedAt) {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("jobId", id);
        job.put("fileId", fileId);
        job.put("configHash", configHash);
        job.put("status", status);
        job.put("progressPercent", progressPercent);
        job.put("message", message);
        job.put("result", readJsonMap(resultJson));
        job.put("createdAt", createdAt);
        job.put("updatedAt", updatedAt);
        return job;
    }

    private void updateJob(Long jobId, String status, int progressPercent, String message, Map<String, Object> result) {
        jdbcTemplate.update("""
                UPDATE `recommendation_jobs`
                SET `status` = ?, `progressPercent` = ?, `message` = ?, `resultJson` = ?, `updatedAt` = ?
                WHERE `id` = ?
                """, status, Math.max(0, Math.min(100, progressPercent)), message, writeJson(result), Timestamp.valueOf(LocalDateTime.now()), jobId);
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String failureMessage(Throwable ex) {
        if (ex == null) {
            return "Recommendation failed.";
        }
        if (ex instanceof OutOfMemoryError) {
            return "Recommendation result was too large to finalize. Please select a smaller date range or fewer KPIs.";
        }
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.toString() : message;
    }

    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            return Map.of("message", json);
        }
    }
}

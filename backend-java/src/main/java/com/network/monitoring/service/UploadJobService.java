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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class UploadJobService {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final UploadService uploadService;
    private final ObjectProvider<UploadJobService> selfProvider;
    private final Path jobFileBasePath;
    private final int retentionDays;

    public UploadJobService(JdbcTemplate jdbcTemplate,
                            UploadService uploadService,
                            ObjectProvider<UploadJobService> selfProvider,
                            @Value("${app.upload-base-path:uploads}") String uploadBasePath,
                            @Value("${app.upload-job-retention-days:30}") int retentionDays) {
        this.jdbcTemplate = jdbcTemplate;
        this.uploadService = uploadService;
        this.selfProvider = selfProvider;
        Path configured = Paths.get(uploadBasePath == null || uploadBasePath.isBlank() ? "uploads" : uploadBasePath.trim());
        Path uploadRoot = configured.isAbsolute()
                ? configured.toAbsolutePath().normalize()
                : resolveWorkspaceRoot().resolve(configured).toAbsolutePath().normalize();
        this.jobFileBasePath = uploadRoot.resolve("upload-jobs").toAbsolutePath().normalize();
        this.retentionDays = Math.max(1, retentionDays);
    }

    @PostConstruct
    public void ensureTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `upload_jobs` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT,
                  `fileName` VARCHAR(512) NULL,
                  `status` VARCHAR(32) NOT NULL,
                  `progressPercent` INT NOT NULL DEFAULT 0,
                  `message` TEXT NULL,
                  `resultJson` LONGTEXT NULL,
                  `filePath` VARCHAR(1024) NULL,
                  `contentType` VARCHAR(255) NULL,
                  `remarks` TEXT NULL,
                  `uploadedBy` VARCHAR(255) NULL,
                  `appendOption` VARCHAR(32) NULL,
                  `targetFileId` BIGINT NULL,
                  `columnMapping` LONGTEXT NULL,
                  `cleanKpiData` BIT(1) NOT NULL DEFAULT b'0',
                  `createdAt` DATETIME NULL,
                  `updatedAt` DATETIME NULL,
                  PRIMARY KEY (`id`),
                  INDEX `idx_upload_jobs_status` (`status`),
                  INDEX `idx_upload_jobs_updated` (`updatedAt`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        addColumnIfMissing("filePath", "ALTER TABLE `upload_jobs` ADD COLUMN `filePath` VARCHAR(1024) NULL");
        addColumnIfMissing("contentType", "ALTER TABLE `upload_jobs` ADD COLUMN `contentType` VARCHAR(255) NULL");
        addColumnIfMissing("remarks", "ALTER TABLE `upload_jobs` ADD COLUMN `remarks` TEXT NULL");
        addColumnIfMissing("uploadedBy", "ALTER TABLE `upload_jobs` ADD COLUMN `uploadedBy` VARCHAR(255) NULL");
        addColumnIfMissing("appendOption", "ALTER TABLE `upload_jobs` ADD COLUMN `appendOption` VARCHAR(32) NULL");
        addColumnIfMissing("targetFileId", "ALTER TABLE `upload_jobs` ADD COLUMN `targetFileId` BIGINT NULL");
        addColumnIfMissing("columnMapping", "ALTER TABLE `upload_jobs` ADD COLUMN `columnMapping` LONGTEXT NULL");
        addColumnIfMissing("cleanKpiData", "ALTER TABLE `upload_jobs` ADD COLUMN `cleanKpiData` BIT(1) NOT NULL DEFAULT b'0'");
        jdbcTemplate.execute("ALTER TABLE `upload_jobs` MODIFY COLUMN `message` TEXT NULL");
        try {
            Files.createDirectories(jobFileBasePath);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to create upload job file directory", ex);
        }
    }

    public Map<String, Object> startKpiUploadJob(MultipartFile file,
                                                 String remarks,
                                                 String uploadedBy,
                                                 String appendOption,
                                                 Long targetFileId,
                                                 String columnMapping,
                                                 boolean cleanKpiData) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file uploaded.");
        }

        Long jobId = createJob(file.getOriginalFilename(), file.getContentType(), remarks, uploadedBy, appendOption, targetFileId, columnMapping, cleanKpiData);
        Path stagedFile = createStagedUploadFile(jobId, file);
        updateJobFilePath(jobId, stagedFile);
        selfProvider.getObject().processKpiUploadJob(jobId, stagedFile, file.getOriginalFilename(), file.getContentType(), remarks, uploadedBy, appendOption, targetFileId, columnMapping, cleanKpiData);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobId", jobId);
        data.put("status", "QUEUED");
        data.put("progressPercent", 5);

        return Map.of(
                "success", true,
                "message", "KPI upload job started.",
                "data", data
        );
    }

    @Async
    public void processKpiUploadJob(Long jobId,
                                    Path tempFile,
                                    String originalFilename,
                                    String contentType,
                                    String remarks,
                                    String uploadedBy,
                                    String appendOption,
                                    Long targetFileId,
                                    String columnMapping,
                                    boolean cleanKpiData) {
        updateJob(jobId, "PROCESSING", 15, "Processing KPI file...", null);
        try {
            MultipartFile file = new StoredFileMultipartFile(tempFile, originalFilename, contentType);
            Map<String, Object> result = uploadService.uploadKpiData(file, remarks, uploadedBy, appendOption, targetFileId, columnMapping, cleanKpiData);
            if (Boolean.FALSE.equals(result.get("success"))) {
                updateJob(jobId, "FAILED", 100, String.valueOf(result.getOrDefault("message", "KPI upload failed.")), result);
                return;
            }
            updateJob(jobId, "COMPLETED", 100, String.valueOf(result.getOrDefault("message", "KPI upload complete.")), result);
            deleteStagedFile(tempFile);
        } catch (Exception ex) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", ex.getMessage() == null ? ex.toString() : ex.getMessage());
            updateJob(jobId, "FAILED", 100, String.valueOf(result.get("message")), result);
        }
    }

    public Map<String, Object> listJobs(Integer limit) {
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        List<Map<String, Object>> jobs = jdbcTemplate.query("""
                SELECT `id`, `fileName`, `status`, `progressPercent`, `message`, `resultJson`, `filePath`, `createdAt`, `updatedAt`
                FROM `upload_jobs`
                ORDER BY `updatedAt` DESC, `id` DESC
                LIMIT ?
                """, (rs, rowNum) -> {
            Map<String, Object> job = baseJobPayload(
                    rs.getLong("id"),
                    rs.getString("fileName"),
                    rs.getString("status"),
                    rs.getInt("progressPercent"),
                    rs.getString("message"),
                    rs.getString("resultJson"),
                    rs.getString("filePath"),
                    rs.getObject("createdAt"),
                    rs.getObject("updatedAt")
            );
            return job;
        }, safeLimit);
        return Map.of("success", true, "message", "Upload jobs loaded.", "data", jobs);
    }

    public Map<String, Object> retryJob(Long jobId) {
        Map<String, Object> job = getJobRecord(jobId);
        String status = stringValue(job.get("status")).toUpperCase(Locale.ROOT);
        if (!"FAILED".equals(status)) {
            throw new IllegalArgumentException("Only failed upload jobs can be retried.");
        }

        String filePath = stringValue(job.get("filePath"));
        if (filePath.isBlank() || !Files.exists(Paths.get(filePath))) {
            throw new IllegalArgumentException("Stored upload file is not available for retry. Please upload the file again.");
        }

        jdbcTemplate.update("""
                UPDATE `upload_jobs`
                SET `status` = ?, `progressPercent` = ?, `message` = ?, `resultJson` = NULL, `updatedAt` = ?
                WHERE `id` = ?
                """, "QUEUED", 5, "Retry queued.", Timestamp.valueOf(LocalDateTime.now()), jobId);

        selfProvider.getObject().processKpiUploadJob(
                jobId,
                Paths.get(filePath),
                stringValue(job.get("fileName")),
                stringValue(job.get("contentType")),
                stringValue(job.get("remarks")),
                stringValue(job.get("uploadedBy")),
                stringValue(job.get("appendOption")),
                longValue(job.get("targetFileId")),
                stringValue(job.get("columnMapping")),
                Boolean.TRUE.equals(job.get("cleanKpiData"))
        );

        return getJob(jobId);
    }

    public Map<String, Object> getJob(Long jobId) {
        if (jobId == null) {
            throw new IllegalArgumentException("jobId is required.");
        }
        List<Map<String, Object>> jobs = jdbcTemplate.query("""
                SELECT `id`, `fileName`, `status`, `progressPercent`, `message`, `resultJson`, `filePath`, `createdAt`, `updatedAt`
                FROM `upload_jobs`
                WHERE `id` = ?
                """, (rs, rowNum) -> {
            return baseJobPayload(
                    rs.getLong("id"),
                    rs.getString("fileName"),
                    rs.getString("status"),
                    rs.getInt("progressPercent"),
                    rs.getString("message"),
                    rs.getString("resultJson"),
                    rs.getString("filePath"),
                    rs.getObject("createdAt"),
                    rs.getObject("updatedAt")
            );
        }, jobId);

        if (jobs.isEmpty()) {
            throw new IllegalArgumentException("Upload job not found for id " + jobId + ".");
        }

        return Map.of(
                "success", true,
                "message", "Upload job loaded.",
                "data", jobs.get(0)
        );
    }

    private Path createStagedUploadFile(Long jobId, MultipartFile file) throws IOException {
        String original = file.getOriginalFilename() == null ? "upload.tmp" : file.getOriginalFilename();
        String suffix = ".tmp";
        int dot = original.lastIndexOf('.');
        if (dot >= 0 && dot < original.length() - 1) {
            suffix = original.substring(dot);
        }
        Files.createDirectories(jobFileBasePath);
        Path tempFile = jobFileBasePath.resolve("job-" + jobId + suffix).toAbsolutePath().normalize();
        if (!tempFile.startsWith(jobFileBasePath)) {
            throw new IllegalStateException("Invalid upload job file path.");
        }
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return tempFile;
    }

    private Long createJob(String fileName,
                           String contentType,
                           String remarks,
                           String uploadedBy,
                           String appendOption,
                           Long targetFileId,
                           String columnMapping,
                           boolean cleanKpiData) {
        LocalDateTime now = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update((PreparedStatementCreator) connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO `upload_jobs`
                    (`fileName`, `status`, `progressPercent`, `message`, `contentType`, `remarks`, `uploadedBy`,
                     `appendOption`, `targetFileId`, `columnMapping`, `cleanKpiData`, `createdAt`, `updatedAt`)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, fileName);
            statement.setString(2, "QUEUED");
            statement.setInt(3, 5);
            statement.setString(4, "KPI upload queued.");
            statement.setString(5, contentType);
            statement.setString(6, remarks);
            statement.setString(7, uploadedBy);
            statement.setString(8, appendOption);
            if (targetFileId == null) {
                statement.setObject(9, null);
            } else {
                statement.setLong(9, targetFileId);
            }
            statement.setString(10, columnMapping);
            statement.setBoolean(11, cleanKpiData);
            statement.setTimestamp(12, Timestamp.valueOf(now));
            statement.setTimestamp(13, Timestamp.valueOf(now));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Unable to create upload job.");
        }
        return key.longValue();
    }

    private void updateJobFilePath(Long jobId, Path filePath) {
        jdbcTemplate.update("UPDATE `upload_jobs` SET `filePath` = ?, `updatedAt` = ? WHERE `id` = ?",
                filePath.toString(),
                Timestamp.valueOf(LocalDateTime.now()),
                jobId);
    }

    private void updateJob(Long jobId, String status, int progressPercent, String message, Map<String, Object> result) {
        jdbcTemplate.update("""
                UPDATE `upload_jobs`
                SET `status` = ?, `progressPercent` = ?, `message` = ?, `resultJson` = ?, `updatedAt` = ?
                WHERE `id` = ?
                """, status, Math.max(0, Math.min(100, progressPercent)), compactMessage(message), writeJson(result), Timestamp.valueOf(LocalDateTime.now()), jobId);
    }

    @Scheduled(cron = "0 20 2 * * *")
    public void cleanupOldJobs() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        List<String> filePaths = jdbcTemplate.query("""
                SELECT `filePath`
                FROM `upload_jobs`
                WHERE `updatedAt` < ? AND `status` IN ('COMPLETED', 'FAILED')
                """, (rs, rowNum) -> rs.getString("filePath"), Timestamp.valueOf(cutoff));
        for (String filePath : filePaths) {
            deleteStagedFile(filePath == null || filePath.isBlank() ? null : Paths.get(filePath));
        }
        jdbcTemplate.update("""
                DELETE FROM `upload_jobs`
                WHERE `updatedAt` < ? AND `status` IN ('COMPLETED', 'FAILED')
                """, Timestamp.valueOf(cutoff));
    }

    private Map<String, Object> getJobRecord(Long jobId) {
        if (jobId == null) {
            throw new IllegalArgumentException("jobId is required.");
        }
        List<Map<String, Object>> jobs = jdbcTemplate.query("""
                SELECT `id`, `fileName`, `status`, `progressPercent`, `message`, `resultJson`, `filePath`,
                       `contentType`, `remarks`, `uploadedBy`, `appendOption`, `targetFileId`, `columnMapping`, `cleanKpiData`,
                       `createdAt`, `updatedAt`
                FROM `upload_jobs`
                WHERE `id` = ?
                """, (rs, rowNum) -> {
            Map<String, Object> job = new LinkedHashMap<>();
            job.put("jobId", rs.getLong("id"));
            job.put("fileName", rs.getString("fileName"));
            job.put("status", rs.getString("status"));
            job.put("progressPercent", rs.getInt("progressPercent"));
            job.put("message", rs.getString("message"));
            job.put("result", readJsonMap(rs.getString("resultJson")));
            job.put("filePath", rs.getString("filePath"));
            job.put("contentType", rs.getString("contentType"));
            job.put("remarks", rs.getString("remarks"));
            job.put("uploadedBy", rs.getString("uploadedBy"));
            job.put("appendOption", rs.getString("appendOption"));
            long targetFileId = rs.getLong("targetFileId");
            job.put("targetFileId", rs.wasNull() ? null : targetFileId);
            job.put("columnMapping", rs.getString("columnMapping"));
            job.put("cleanKpiData", rs.getBoolean("cleanKpiData"));
            job.put("createdAt", rs.getObject("createdAt"));
            job.put("updatedAt", rs.getObject("updatedAt"));
            return job;
        }, jobId);
        if (jobs.isEmpty()) {
            throw new IllegalArgumentException("Upload job not found for id " + jobId + ".");
        }
        return jobs.get(0);
    }

    private Map<String, Object> baseJobPayload(Long id,
                                               String fileName,
                                               String status,
                                               int progressPercent,
                                               String message,
                                               String resultJson,
                                               String filePath,
                                               Object createdAt,
                                               Object updatedAt) {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("jobId", id);
        job.put("fileName", fileName);
        job.put("status", status);
        job.put("progressPercent", progressPercent);
        job.put("message", message);
        job.put("result", readJsonMap(resultJson));
        job.put("retryable", "FAILED".equalsIgnoreCase(status) && filePath != null && !filePath.isBlank() && Files.exists(Paths.get(filePath)));
        job.put("createdAt", createdAt);
        job.put("updatedAt", updatedAt);
        return job;
    }

    private void addColumnIfMissing(String columnName, String sql) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'upload_jobs'
                  AND COLUMN_NAME = ?
                """, Integer.class, columnName);
        if (count == null || count == 0) {
            jdbcTemplate.execute(sql);
        }
    }

    private void deleteStagedFile(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private Path resolveWorkspaceRoot() {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path name = cwd.getFileName();
        String current = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        if ("backend-java".equals(current) || "backend".equals(current)) {
            Path parent = cwd.getParent();
            if (parent != null) {
                return parent;
            }
        }
        return cwd;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String parsed = stringValue(value);
        if (parsed.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(parsed);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String writeJson(Map<String, Object> result) {
        if (result == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String compactMessage(String message) {
        if (message == null) {
            return null;
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 2000 ? normalized : normalized.substring(0, 2000);
    }

    private Map<String, Object> readJsonMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(value, MAP_TYPE);
        } catch (Exception ex) {
            return Map.of();
        }
    }
}

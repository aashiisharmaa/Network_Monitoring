package com.network.monitoring.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.monitoring.entity.AlarmData;
import com.network.monitoring.entity.SiteData;
import com.network.monitoring.entity.Threshold;
import com.network.monitoring.entity.UploadData;
import com.network.monitoring.entity.UploadHistory;
import com.network.monitoring.repository.AlarmDataRepository;
import com.network.monitoring.repository.SiteDataRepository;
import com.network.monitoring.repository.ThresholdRepository;
import com.network.monitoring.repository.UploadDataRepository;
import com.network.monitoring.repository.UploadHistoryRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UploadService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".xlsx", ".xls", ".csv");
    private static final int INSERT_CHUNK_SIZE = 2000;

    private final ParserService parserService;
    private final FileStorageService fileStorageService;
    private final UploadHistoryRepository uploadHistoryRepository;
    private final UploadDataRepository uploadDataRepository;
    private final SiteDataRepository siteDataRepository;
    private final AlarmDataRepository alarmDataRepository;
    private final ThresholdRepository thresholdRepository;
    private final KpiCacheService kpiCacheService;
    private final KpiDynamicDataService kpiDynamicDataService;
    private final KpiThresholdRuleService kpiThresholdRuleService;
    private final KpiAlarmService kpiAlarmService;
    private final SitePredictionService sitePredictionService;
    private final RoleAuthorizationService roleAuthorizationService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ObjectProvider<RedisService.RedisCacheService> redisCacheServiceProvider;

    @Value("${app.perf-logs-enabled:false}")
    private String perfLogsEnabledRaw;

    @Value("${app.kpi-debug-logs-enabled:false}")
    private String kpiDebugLogsEnabledRaw;

    @Value("${app.kpi-post-upload-analysis-enabled:false}")
    private String kpiPostUploadAnalysisEnabledRaw;

    public UploadService(ParserService parserService,
                         FileStorageService fileStorageService,
                         UploadHistoryRepository uploadHistoryRepository,
                         UploadDataRepository uploadDataRepository,
                         SiteDataRepository siteDataRepository,
                         AlarmDataRepository alarmDataRepository,
                         ThresholdRepository thresholdRepository,
                         KpiCacheService kpiCacheService,
                         KpiDynamicDataService kpiDynamicDataService,
                         KpiThresholdRuleService kpiThresholdRuleService,
                         KpiAlarmService kpiAlarmService,
                         SitePredictionService sitePredictionService,
                         RoleAuthorizationService roleAuthorizationService,
                         JdbcTemplate jdbcTemplate,
                         ObjectProvider<RedisService.RedisCacheService> redisCacheServiceProvider) {
        this.parserService = parserService;
        this.fileStorageService = fileStorageService;
        this.uploadHistoryRepository = uploadHistoryRepository;
        this.uploadDataRepository = uploadDataRepository;
        this.siteDataRepository = siteDataRepository;
        this.alarmDataRepository = alarmDataRepository;
        this.thresholdRepository = thresholdRepository;
        this.kpiCacheService = kpiCacheService;
        this.kpiDynamicDataService = kpiDynamicDataService;
        this.kpiThresholdRuleService = kpiThresholdRuleService;
        this.kpiAlarmService = kpiAlarmService;
        this.sitePredictionService = sitePredictionService;
        this.roleAuthorizationService = roleAuthorizationService;
        this.jdbcTemplate = jdbcTemplate;
        this.redisCacheServiceProvider = redisCacheServiceProvider;
    }

    @PostConstruct
    public void ensureSiteDataSchema() {
        addSiteDataColumnIfMissing("fileId", "ALTER TABLE `sitedata` ADD COLUMN `fileId` BIGINT NULL");
        addSiteDataColumnIfMissing("companyId", "ALTER TABLE `sitedata` ADD COLUMN `companyId` BIGINT NULL");
        addSiteDataColumnIfMissing("Sector", "ALTER TABLE `sitedata` ADD COLUMN `Sector` VARCHAR(50) NULL");
        addSiteDataColumnIfMissing("Band", "ALTER TABLE `sitedata` ADD COLUMN `Band` VARCHAR(50) NULL");
        addSiteDataColumnIfMissing("Technology", "ALTER TABLE `sitedata` ADD COLUMN `Technology` VARCHAR(50) NULL");
        addSiteDataColumnIfMissing("EARFCN", "ALTER TABLE `sitedata` ADD COLUMN `EARFCN` INT NULL");
        addSiteDataIndexIfMissing("idx_sitedata_fileId", "CREATE INDEX `idx_sitedata_fileId` ON `sitedata` (`fileId`)");
    }

    public Map<String, Object> uploadKpiData(MultipartFile file, String remarks, String uploadedBy) throws IOException {
        return uploadKpiData(file, remarks, uploadedBy, null, null);
    }

    public Map<String, Object> uploadKpiData(MultipartFile file, String remarks, String uploadedBy, String appendOption, Long targetFileId) throws IOException {
        return uploadKpiData(file, remarks, uploadedBy, appendOption, targetFileId, null);
    }

    public Map<String, Object> uploadKpiData(MultipartFile file, String remarks, String uploadedBy, String appendOption, Long targetFileId, String columnMappingJson) throws IOException {
        return uploadKpiData(file, remarks, uploadedBy, appendOption, targetFileId, columnMappingJson, false);
    }

    public Map<String, Object> uploadKpiData(MultipartFile file, String remarks, String uploadedBy, String appendOption, Long targetFileId, String columnMappingJson, boolean cleanKpiData) throws IOException {
        return runUpload(file, remarks, uploadedBy, DatasetType.KPI, parseAppendOption(appendOption), targetFileId, parseColumnMapping(columnMappingJson), cleanKpiData);
    }

    public Map<String, Object> uploadSiteData(MultipartFile file, String remarks, String uploadedBy) throws IOException {
        return runUpload(file, remarks, uploadedBy, DatasetType.SITE, AppendOption.APPEND, null, Map.of(), false);
    }

    public Map<String, Object> uploadAlarmData(MultipartFile file, String remarks, String uploadedBy) throws IOException {
        return runUpload(file, remarks, uploadedBy, DatasetType.ALARM, AppendOption.APPEND, null, Map.of(), false);
    }

    public Map<String, Object> getUploadHistory() {
        List<Map<String, Object>> uploads = uploadHistoryRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream()
                .map(this::toUploadHistoryPayloadWithCounts)
                .toList();
        return Map.of("success", true, "data", uploads);
    }

    public Map<String, Object> getUploadHistorySlim() {
        List<Map<String, Object>> uploads = uploadHistoryRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream()
                .map(this::toUploadHistoryPayloadWithoutCounts)
                .toList();
        return Map.of("success", true, "data", uploads);
    }

    @Transactional
    public Map<String, Object> deleteUpload(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Upload id is required.");
        }
        alarmDataRepository.findAllByFileId(id).forEach(alarmDataRepository::delete);
        uploadDataRepository.findAllByFileId(id).forEach(uploadDataRepository::delete);
        siteDataRepository.deleteByFileId(id);
        kpiDynamicDataService.deleteByFileId(id);
        alarmDataRepository.flush();
        uploadDataRepository.flush();
        uploadHistoryRepository.findById(id).ifPresent(uploadHistoryRepository::delete);
        uploadHistoryRepository.flush();
        kpiCacheService.deleteKpiCache(id);
        sitePredictionService.invalidateFile(id);
        clearRedisKpiCaches(id);
        return Map.of("success", true, "message", "Upload and related KPI/alarm data deleted successfully");
    }

    public Map<String, Object> getNetworkData(int page, int limit) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 1) - 1, Math.max(limit, 1), Sort.by(Sort.Direction.DESC, "id"));
        Page<UploadData> result = uploadDataRepository.findAll(pageRequest);

        List<Map<String, Object>> data = result.getContent().stream()
                .map(this::toNetworkDataPayload)
                .toList();

        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("page", page);
        pagination.put("limit", limit);
        pagination.put("total", result.getTotalElements());
        pagination.put("totalPages", result.getTotalPages());

        return Map.of("success", true, "data", data, "pagination", pagination);
    }

    public Map<String, Object> getSiteData(int page, int limit) {
        return getSiteData(page, limit, null);
    }

    public Map<String, Object> getSiteData(int page, int limit, Long fileId) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 1) - 1, Math.max(limit, 1), Sort.by(Sort.Direction.ASC, "id"));
        Page<SiteData> result = fileId == null
                ? siteDataRepository.findAll(pageRequest)
                : siteDataRepository.findAllByFileId(fileId, pageRequest);

        List<Map<String, Object>> data = result.getContent().stream()
                .map(this::toSiteDataPayload)
                .toList();

        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("page", page);
        pagination.put("limit", limit);
        pagination.put("fileId", fileId);
        pagination.put("totalCount", result.getTotalElements());
        pagination.put("totalPages", result.getTotalPages());
        pagination.put("hasNextPage", result.hasNext());
        pagination.put("hasPrevPage", result.hasPrevious());

        return Map.of("success", true, "data", data, "pagination", pagination);
    }

    public Map<String, Object> getAlarmData(int page, int limit, Long fileId, String circle, String severity, String search, String startDate, String endDate) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 1) - 1, Math.max(limit, 1), Sort.by(Sort.Direction.DESC, "id"));

        List<AlarmData> filtered = alarmDataRepository.findAll((root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            predicates.add(hasAlarmDetailPredicate(root, cb));
            if (fileId != null && fileId > 0) {
                predicates.add(cb.equal(root.get("fileId"), fileId));
            }
            if (circle != null && !circle.isBlank()) {
                predicates.add(cb.or(
                        cb.like(root.get("subNetworkId"), "%" + circle + "%"),
                        cb.like(root.get("subNetwork2Id"), "%" + circle + "%")
                ));
            }
            if (severity != null && !severity.isBlank()) {
                predicates.add(cb.like(root.get("perceivedSeverity"), "%" + severity + "%"));
            }
            if (search != null && !search.isBlank()) {
                predicates.add(cb.or(
                        cb.like(root.get("userLabel"), "%" + search + "%"),
                        cb.like(root.get("managedElementId"), "%" + search + "%"),
                        cb.like(root.get("meContextId"), "%" + search + "%"),
                        cb.like(root.get("alarmSlogan"), "%" + search + "%"),
                        cb.like(root.get("alarmPortId"), "%" + search + "%")
                ));
            }
            if ((startDate != null && !startDate.isBlank()) || (endDate != null && !endDate.isBlank())) {
                jakarta.persistence.criteria.Predicate datePredicate = cb.conjunction();
                if (startDate != null && !startDate.isBlank()) {
                    LocalDateTime parsed = parseDateTimeLoose(startDate);
                    if (parsed != null) {
                        datePredicate = cb.and(datePredicate, cb.greaterThanOrEqualTo(root.get("datetime"), parsed));
                    }
                }
                if (endDate != null && !endDate.isBlank()) {
                    LocalDateTime parsed = parseDateTimeLoose(endDate);
                    if (parsed != null) {
                        datePredicate = cb.and(datePredicate, cb.lessThanOrEqualTo(root.get("datetime"), parsed));
                    }
                }
                predicates.add(datePredicate);
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        }, pageRequest).getContent();

        long totalCount = alarmDataRepository.count((root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            predicates.add(hasAlarmDetailPredicate(root, cb));
            if (fileId != null && fileId > 0) {
                predicates.add(cb.equal(root.get("fileId"), fileId));
            }
            if (circle != null && !circle.isBlank()) {
                predicates.add(cb.or(
                        cb.like(root.get("subNetworkId"), "%" + circle + "%"),
                        cb.like(root.get("subNetwork2Id"), "%" + circle + "%")
                ));
            }
            if (severity != null && !severity.isBlank()) {
                predicates.add(cb.like(root.get("perceivedSeverity"), "%" + severity + "%"));
            }
            if (search != null && !search.isBlank()) {
                predicates.add(cb.or(
                        cb.like(root.get("userLabel"), "%" + search + "%"),
                        cb.like(root.get("managedElementId"), "%" + search + "%"),
                        cb.like(root.get("meContextId"), "%" + search + "%"),
                        cb.like(root.get("alarmSlogan"), "%" + search + "%"),
                        cb.like(root.get("alarmPortId"), "%" + search + "%")
                ));
            }
            if ((startDate != null && !startDate.isBlank()) || (endDate != null && !endDate.isBlank())) {
                jakarta.persistence.criteria.Predicate datePredicate = cb.conjunction();
                if (startDate != null && !startDate.isBlank()) {
                    LocalDateTime parsed = parseDateTimeLoose(startDate);
                    if (parsed != null) {
                        datePredicate = cb.and(datePredicate, cb.greaterThanOrEqualTo(root.get("datetime"), parsed));
                    }
                }
                if (endDate != null && !endDate.isBlank()) {
                    LocalDateTime parsed = parseDateTimeLoose(endDate);
                    if (parsed != null) {
                        datePredicate = cb.and(datePredicate, cb.lessThanOrEqualTo(root.get("datetime"), parsed));
                    }
                }
                predicates.add(datePredicate);
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });

        List<Map<String, Object>> data = filtered.stream().map(this::toAlarmSummaryPayload).toList();
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("page", page);
        pagination.put("limit", limit);
        pagination.put("fileId", fileId);
        pagination.put("totalCount", totalCount);
        pagination.put("totalPages", (int) Math.ceil(totalCount / (double) Math.max(limit, 1)));
        pagination.put("hasNextPage", page * limit < totalCount);
        pagination.put("hasPrevPage", page > 1);

        return Map.of("success", true, "data", data, "pagination", pagination);
    }

    public UploadHistory createUploadHistory(String fileName, String fileType, String uploadedBy, String remarks) {
        UploadHistory uploadHistory = new UploadHistory();
        uploadHistory.setFileName(fileName);
        uploadHistory.setFileType(fileType);
        uploadHistory.setUploadedBy(uploadedBy == null || uploadedBy.isBlank() ? "anonymous" : uploadedBy);
        uploadHistory.setCompanyId(roleAuthorizationService.currentUserCompanyIdOrNull());
        uploadHistory.setRemarks(remarks);
        uploadHistory.setCreatedAt(LocalDateTime.now(ZoneId.of("UTC")));
        return uploadHistoryRepository.save(uploadHistory);
    }

    @Transactional
    private Map<String, Object> runUpload(MultipartFile file, String remarks, String uploadedBy, DatasetType datasetType, AppendOption appendOption, Long targetFileId, Map<String, String> columnMapping, boolean cleanKpiData) throws IOException {
        long requestStartedAt = System.currentTimeMillis();
        if (file == null || file.isEmpty()) {
            return Map.of("success", false, "message", "No file uploaded.");
        }

        String originalName = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String ext = getExtension(originalName);
        if (!ALLOWED_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT))) {
            return Map.of("success", false, "message", "Invalid file type. Only .xlsx, .xls, and .csv files are supported.");
        }

        Path storedPath = fileStorageService.storeFile(file);
        long uploadRecordId = -1;
        try {
            UploadHistory uploadRecord = datasetType == DatasetType.KPI && targetFileId != null
                    ? uploadHistoryRepository.findById(targetFileId)
                            .orElseThrow(() -> new IllegalArgumentException("No upload found for fileId " + targetFileId + "."))
                    : createUploadHistory(
                            originalName,
                            ext.replace(".", ""),
                            uploadedBy,
                            remarks == null || remarks.isBlank() ? "[" + datasetType.name().toLowerCase(Locale.ROOT) + "] file upload" : "[" + datasetType.name().toLowerCase(Locale.ROOT) + "] " + remarks
                    );
            uploadRecordId = uploadRecord.getId();

            if (datasetType == DatasetType.KPI) {
                ensureKpiThreshold(uploadRecord.getId());
            }

            List<Map<String, Object>> parsedRows = parserService.parseFile(storedPath);
            if (datasetType == DatasetType.KPI && columnMapping != null && !columnMapping.isEmpty()) {
                parsedRows = applyColumnMapping(parsedRows, columnMapping);
            }
            if (parsedRows.isEmpty()) {
                return Map.of(
                        "success", true,
                        "message", datasetType.name() + " file uploaded, but no rows were found.",
                        "data", Map.of(
                                "uploadId", uploadRecord.getId(),
                                "datasetType", datasetType.name().toLowerCase(Locale.ROOT),
                                "totalRows", 0,
                                "insertedRows", 0,
                                "skippedRows", 0
                        )
                );
            }

            List<Map<String, Object>> normalizedRows = parsedRows.stream().map(this::normalizeRow).toList();
            int insertedRows;
            List<String> detectedHeaders = new ArrayList<>(parsedRows.get(0).keySet());

            if (datasetType == DatasetType.KPI) {
                List<UploadData> uploadDataRows = normalizedRows.stream()
                        .map(row -> mapKpiRow(row, uploadRecord.getId()))
                        .toList();
                int replacedRows = 0;
                int mergedSkippedRows = 0;
                boolean legacyKpiInsertEnabled = hasLegacyKpiMetrics(uploadDataRows);
                if (legacyKpiInsertEnabled) {
                    if (appendOption == AppendOption.OVERWRITE) {
                        replacedRows = jdbcTemplate.update("DELETE FROM `uploaddata` WHERE `fileId` = ?", uploadRecord.getId());
                    } else if (appendOption == AppendOption.MERGE) {
                        Set<String> knownKeys = uploadDataRepository.findAllByFileId(uploadRecord.getId()).stream()
                                .map(this::kpiBusinessKey)
                                .collect(Collectors.toCollection(LinkedHashSet::new));
                        List<UploadData> mergedRows = new ArrayList<>();
                        for (UploadData row : uploadDataRows) {
                            if (knownKeys.add(kpiBusinessKey(row))) {
                                mergedRows.add(row);
                            } else {
                                mergedSkippedRows++;
                            }
                        }
                        uploadDataRows = mergedRows;
                    }
                    insertedRows = insertInChunks(uploadDataRows, UploadData.class);
                } else {
                    insertedRows = 0;
                }
                Map<String, Object> incomingKpiCachePayload = buildKpiDynamicCachePayload(parsedRows);
                Map<String, Object> existingKpiCachePayload = appendOption == AppendOption.OVERWRITE
                        ? null
                        : kpiCacheService.readKpiCache(uploadRecord.getId());
                Map<String, Object> kpiCachePayload = mergeKpiDynamicCachePayloads(
                        existingKpiCachePayload,
                        incomingKpiCachePayload,
                        appendOption == AppendOption.MERGE
                );
                List<Map<String, Object>> mergedDynamicRecords = kpiDynamicDataService.getRecordsFromCachePayload(kpiCachePayload);
                CleanKpiRecordsResult cleanResult = cleanKpiData
                        ? cleanKpiRecords(mergedDynamicRecords)
                        : CleanKpiRecordsResult.unchanged(mergedDynamicRecords);
                kpiCachePayload.put("records", cleanResult.records());
                kpiCachePayload.put("cleaningStats", cleanResult.stats());
                List<Map<String, Object>> dynamicRecords = cleanResult.records();
                kpiCacheService.writeKpiCache(uploadRecord.getId(), kpiCachePayload);
                kpiDynamicDataService.replaceRecords(uploadRecord.getId(), uploadRecord.getCompanyId(), dynamicRecords);
                sitePredictionService.invalidateFile(uploadRecord.getId());
                sitePredictionService.warmFileAsync(uploadRecord.getId());
                Map<String, Object> thresholdSummary = Map.of(
                        "status", "SKIPPED",
                        "message", "Post-upload threshold analysis skipped for faster upload. Use Threshold Rules recheck to generate it."
                );
                Map<String, Object> alarmSummary = Map.of(
                        "status", "SKIPPED",
                        "message", "Post-upload alarm generation skipped for faster upload. Use Alarms generate to create alarms."
                );
                if (isTruthy(kpiPostUploadAnalysisEnabledRaw)) {
                    thresholdSummary = kpiThresholdRuleService.evaluateUploadedRecords(uploadRecord.getId(), dynamicRecords);
                    kpiThresholdRuleService.saveEvaluationResult(uploadRecord.getId(), thresholdSummary);
                    alarmSummary = kpiAlarmService.generateAlarms(uploadRecord.getId());
                }
                clearRedisKpiCaches(uploadRecord.getId());
                Map<String, Object> uploadData = new LinkedHashMap<>();
                uploadData.put("uploadId", uploadRecord.getId());
                uploadData.put("datasetType", "kpi");
                uploadData.put("appendOption", appendOption.name().toLowerCase(Locale.ROOT));
                uploadData.put("totalRows", parsedRows.size());
                uploadData.put("insertedRows", insertedRows);
                uploadData.put("dynamicRowsStored", dynamicRecords.size());
                uploadData.put("cleanKpiData", cleanKpiData);
                uploadData.put("cleaningStats", cleanResult.stats());
                uploadData.put("legacyKpiRowsStored", legacyKpiInsertEnabled);
                uploadData.put("replacedRows", replacedRows);
                uploadData.put("skippedRows", parsedRows.size() - insertedRows);
                uploadData.put("duplicateRows", mergedSkippedRows);
                uploadData.put("appliedColumnMapping", columnMapping == null ? Map.of() : columnMapping);
                uploadData.put("detectedHeaders", detectedHeaders);
                uploadData.put("thresholdSummary", thresholdSummary);
                uploadData.put("alarmSummary", alarmSummary);
                return Map.of(
                        "success", true,
                        "message", "KPI upload complete. Stored " + dynamicRecords.size() + " dynamic row(s). Analysis can be run from Threshold Rules or Alarms.",
                        "data", uploadData
                );
            }

            if (datasetType == DatasetType.SITE) {
                long nextGeneratedSiteId = nextSiteDataId();
                List<SiteData> rows = new ArrayList<>();
                for (Map<String, Object> row : normalizedRows) {
                    SiteData mapped = mapSiteRow(row, nextGeneratedSiteId, uploadRecord.getId());
                    if (mapped != null && mapped.getId() != null) {
                        rows.add(mapped);
                        nextGeneratedSiteId = Math.max(nextGeneratedSiteId + 1, mapped.getId() + 1);
                    }
                }
                if (rows.isEmpty()) {
                    return Map.of(
                            "success", false,
                            "message", "No valid site rows found. Ensure the file contains site/cell columns such as siteid, site_name, cell_id, cell_name, or short_name.",
                            "data", Map.of(
                                    "uploadId", uploadRecord.getId(),
                                    "datasetType", "site",
                                    "totalRows", parsedRows.size(),
                                    "insertedRows", 0,
                                    "skippedRows", parsedRows.size(),
                                    "detectedHeaders", detectedHeaders
                            )
                    );
                }
                insertedRows = insertInChunks(rows, SiteData.class);
                return Map.of(
                        "success", true,
                        "message", "Site upload complete. Inserted " + insertedRows + " row(s).",
                        "data", Map.of(
                                "uploadId", uploadRecord.getId(),
                                "datasetType", "site",
                                "totalRows", parsedRows.size(),
                                "insertedRows", insertedRows,
                                "skippedRows", rows.size() - insertedRows + (parsedRows.size() - rows.size()),
                                "detectedHeaders", detectedHeaders
                        )
                );
            }

            List<AlarmData> alarmRows = normalizedRows.stream()
                    .map(row -> mapAlarmRow(row, uploadRecord.getId()))
                    .filter(this::hasAlarmRowContent)
                    .toList();
            insertedRows = insertInChunks(alarmRows, AlarmData.class);
            return Map.of(
                    "success", true,
                    "message", "Alarm upload complete. Inserted " + insertedRows + " row(s).",
                    "data", Map.of(
                            "uploadId", uploadRecord.getId(),
                            "datasetType", "alarm",
                            "totalRows", parsedRows.size(),
                            "insertedRows", insertedRows,
                            "skippedRows", parsedRows.size() - alarmRows.size() + (alarmRows.size() - insertedRows),
                            "detectedHeaders", detectedHeaders
                    )
            );
        } catch (Exception ex) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", false);
            response.put("message", "Server error: " + (ex.getMessage() == null ? "Unknown error" : ex.getMessage()));
            if (uploadRecordId > 0) {
                response.put("data", Map.of(
                        "uploadId", uploadRecordId,
                        "datasetType", datasetType.name().toLowerCase(Locale.ROOT)
                ));
            }
            return response;
        } finally {
            deleteTempFile(storedPath);
            logPerf("[UPLOAD][" + datasetType.name().toLowerCase(Locale.ROOT) + "] file=\"" + originalName + "\" uploadId=" + uploadRecordId + " total=" + (System.currentTimeMillis() - requestStartedAt) + "ms");
        }
    }

    private Map<String, Object> toUploadHistoryPayloadWithCounts(UploadHistory history) {
        Map<String, Object> payload = toUploadHistoryPayloadWithoutCounts(history);
        payload.put("_count", Map.of(
                "uploadData", uploadDataRepository.countByFileId(history.getId()),
                "alarmData", alarmDataRepository.countByFileId(history.getId())
        ));
        return payload;
    }

    private Map<String, Object> toUploadHistoryPayloadWithoutCounts(UploadHistory history) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", history.getId());
        payload.put("fileName", history.getFileName());
        payload.put("fileType", history.getFileType());
        payload.put("uploadedBy", history.getUploadedBy());
        payload.put("remarks", history.getRemarks());
        payload.put("createdAt", history.getCreatedAt());
        return payload;
    }

    private Map<String, Object> toNetworkDataPayload(UploadData row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", row.getId());
        payload.put("date", row.getDate());
        payload.put("fileId", row.getFileId());
        payload.put("Cell_Name", row.getCellName());
        payload.put("Site", row.getSite());
        payload.put("Band", row.getBand());
        payload.put("Tech", row.getTech());
        payload.put("Sector_ID", row.getSectorId());
        payload.put("Sector_Name", row.getSectorName());
        payload.put("Groups", row.getGroups());
        payload.put("UL_PRB_Utilization_Rate", row.getUlPrbUtilizationRate());
        payload.put("DL_PRB_Utilization_Rate", row.getDlPrbUtilizationRate());
        payload.put("UME_4G_Data_Volume_STD_MAPS_MB_903593_1", row.getUme4gDataVolumeStdMapsMb9035931());
        payload.put("UME_E_UTRAN_IP_Throughput_UE_UL_STD_Kbps", row.getUmeEutranIpThroughputUeUlStdKbps());
        payload.put("UME_E_UTRAN_IP_Throughput_UE_DL_STD_Kbps", row.getUmeEutranIpThroughputUeDlStdKbps());
        payload.put("E_RAB_Drop_Rate", row.getErabDropRate());
        payload.put("Initial_ERAB_Establishment_Success_Rate", row.getInitialErabEstablishmentSuccessRate());
        payload.put("RRC_Establishment_Success_Rate", row.getRrcEstablishmentSuccessRate());
        payload.put("Mean_RRC_Connected_User_Number", row.getMeanRrcConnectedUserNumber());
        payload.put("Maximum_RRC_Connected_User_Number", row.getMaximumRrcConnectedUserNumber());
        payload.put("E_RAB_Setup_Success_Rate", row.getErabSetupSuccessRate());
        payload.put("RRC_Drop_Rate", row.getRrcDropRate());
        payload.put("VOLTE_CSSR_Eric", row.getVolteCssrEric());
        payload.put("VOLTE_DCR_Eric", row.getVolteDcrEric());
        payload.put("Inter_Freq_HOSR", row.getInterFreqHosr());
        payload.put("Intra_Freq_HOSR", row.getIntraFreqHosr());
        payload.put("CSFB_Success_Rate", row.getCsfbSuccessRate());

        UploadHistory history = uploadHistoryRepository.findById(row.getFileId()).orElse(null);
        if (history != null) {
            Map<String, Object> uploadHistoryPayload = new LinkedHashMap<>();
            uploadHistoryPayload.put("fileName", history.getFileName());
            uploadHistoryPayload.put("uploadedBy", history.getUploadedBy());
            uploadHistoryPayload.put("createdAt", history.getCreatedAt());
            payload.put("uploadHistory", uploadHistoryPayload);
        }
        return payload;
    }

    private Map<String, Object> toSiteDataPayload(SiteData row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", row.getId());
        payload.put("fileId", row.getFileId());
        payload.put("Cell_Name", row.getCellName());
        payload.put("SI_CI", row.getSiCi());
        payload.put("EGCI", row.getEgci());
        payload.put("SuNetwork_ID", row.getSuNetworkId());
        payload.put("SITEID", row.getSiteId());
        payload.put("Site_Name", row.getSiteName());
        payload.put("Cell_ID", row.getCellId());
        payload.put("SEC_ID", row.getSecId());
        payload.put("Sector", row.getSector());
        payload.put("lon", row.getLon());
        payload.put("lat", row.getLat());
        payload.put("TAC", row.getTac());
        payload.put("PCI", row.getPci());
        payload.put("AZIMUTH", row.getAzimuth());
        payload.put("Antenna_Height", row.getAntennaHeight());
        payload.put("M_tilt", row.getMTilt());
        payload.put("E_tilt", row.getETilt());
        payload.put("TX_RX", row.getTxRx());
        payload.put("Real_Transmit_Power_of_Resource", row.getRealTransmitPowerOfResource());
        payload.put("Referenced_Signal_Power_of_Resource", row.getReferencedSignalPowerOfResource());
        payload.put("cellSize", row.getCellSize());
        payload.put("cellRadius", row.getCellRadius());
        payload.put("RachRootSequence", row.getRachRootSequence());
        payload.put("Bandwidth", row.getBandwidth());
        payload.put("Band", row.getBand());
        payload.put("Technology", row.getTechnology());
        payload.put("EARFCN", row.getEarfcn());
        payload.put("Frequency", row.getFrequency());
        payload.put("Downlink_Center_Frequency", row.getDownlinkCenterFrequency());
        payload.put("Region", row.getRegion());
        payload.put("Cluster", row.getCluster());
        payload.put("OMM", row.getOmm());
        payload.put("Antenna", row.getAntenna());
        payload.put("RET", row.getRet());
        return payload;
    }

    private Map<String, Object> toAlarmSummaryPayload(AlarmData row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", row.getId());
        String circle = firstNonBlank(row.getSubNetworkId(), row.getSubNetwork2Id());
        String name = firstNonBlank(row.getUserLabel(), row.getManagedElementId(), row.getMeContextId());
        String enodeBId = firstNonBlank(row.getManagedElementId(), row.getMeContextId());
        String alarmNumber = firstNonBlank(row.getAlarmPortId(), row.getVsAlarmPortId(), String.valueOf(row.getId()));
        String alarmText = firstNonBlank(row.getAlarmSlogan(), row.getConfigDataDnPrefix());
        String supplementaryInfo = firstNonBlank(row.getAvailabilityStatus(), row.getOperationalState(), row.getFilterAlgorithm());
        payload.put("Circle", circle.isBlank() ? "N/A" : circle);
        payload.put("Name", name.isBlank() ? "Unknown" : name);
        payload.put("EnodeBID", enodeBId.isBlank() ? "N/A" : enodeBId);
        payload.put("AlarmNumber", alarmNumber.isBlank() ? String.valueOf(row.getId()) : alarmNumber);
        payload.put("AlarmText", alarmText.isBlank() ? "N/A" : alarmText);
        payload.put("SupplementryInfo", supplementaryInfo.isBlank() ? "N/A" : supplementaryInfo);
        payload.put("Severity", normalizeSeverity(row.getPerceivedSeverity()));
        payload.put("AlarmTime", row.getDatetime());
        payload.put("StandardAging", getStandardAging(row.getDatetime()));
        return payload;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isBlank()) {
                return trimmed;
            }
        }
        return "";
    }

    private jakarta.persistence.criteria.Predicate hasAlarmDetailPredicate(
            jakarta.persistence.criteria.Root<AlarmData> root,
            jakarta.persistence.criteria.CriteriaBuilder cb
    ) {
        return cb.or(
                cb.isNotNull(root.get("perceivedSeverity")),
                cb.isNotNull(root.get("alarmPortId")),
                cb.isNotNull(root.get("alarmSlogan")),
                cb.isNotNull(root.get("datetime")),
                cb.isNotNull(root.get("userLabel")),
                cb.isNotNull(root.get("managedElementId")),
                cb.isNotNull(root.get("meContextId"))
        );
    }

    private boolean hasAlarmRowContent(AlarmData row) {
        if (row == null) {
            return false;
        }
        return !firstNonBlank(
                row.getPerceivedSeverity(),
                row.getAlarmPortId(),
                row.getAlarmSlogan(),
                row.getUserLabel(),
                row.getManagedElementId(),
                row.getMeContextId(),
                row.getSubNetworkId()
        ).isBlank() || row.getDatetime() != null;
    }

    private String normalizeSeverity(String value) {
        String raw = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (raw.contains("critical")) return "Critical";
        if (raw.contains("major")) return "Major";
        if (raw.contains("minor")) return "Minor";
        if (raw.contains("warning")) return "Warning";
        return "Unknown";
    }

    private String getStandardAging(LocalDateTime dateValue) {
        if (dateValue == null) {
            return "N/A";
        }
        long diffMs = System.currentTimeMillis() - dateValue.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        if (diffMs < 0) {
            return "N/A";
        }

        long totalMinutes = diffMs / 60000L;
        long days = totalMinutes / 1440L;
        long hours = (totalMinutes % 1440L) / 60L;
        long minutes = totalMinutes % 60L;

        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    private Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            normalized.put(canonicalizeHeader(entry.getKey()), entry.getValue());
        }
        return normalized;
    }

    private Map<String, String> parseColumnMapping(String columnMappingJson) {
        if (columnMappingJson == null || columnMappingJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> rawMapping = objectMapper.readValue(columnMappingJson, new TypeReference<Map<String, String>>() {});
            Map<String, String> cleanedMapping = new LinkedHashMap<>();
            rawMapping.forEach((targetField, sourceColumn) -> {
                if (targetField == null || targetField.isBlank() || sourceColumn == null || sourceColumn.isBlank()) {
                    return;
                }
                String standardTarget = toMappedTargetColumn(targetField);
                if (standardTarget != null) {
                    cleanedMapping.put(standardTarget, sourceColumn.trim());
                }
            });
            return cleanedMapping;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid columnMapping JSON. Expected format like {\"cell\":\"Short_name\",\"site\":\"Site_ID\",\"technology\":\"Tech\"}.");
        }
    }

    private List<Map<String, Object>> applyColumnMapping(List<Map<String, Object>> rows, Map<String, String> columnMapping) {
        if (rows == null || rows.isEmpty() || columnMapping == null || columnMapping.isEmpty()) {
            return rows;
        }
        List<Map<String, Object>> mappedRows = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> mappedRow = new LinkedHashMap<>(row);
            for (Map.Entry<String, String> mapping : columnMapping.entrySet()) {
                Object value = findMappedColumnValue(row, mapping.getValue());
                if (value != null) {
                    mappedRow.put(mapping.getKey(), value);
                }
            }
            mappedRows.add(mappedRow);
        }
        return mappedRows;
    }

    private Object findMappedColumnValue(Map<String, Object> row, String sourceColumn) {
        String sourceKey = canonicalizeHeader(sourceColumn);
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (canonicalizeHeader(entry.getKey()).equals(sourceKey)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String toMappedTargetColumn(String targetField) {
        return switch (canonicalizeHeader(targetField)) {
            case "cell", "cellname", "cellid" -> "Cell_Name";
            case "site", "siteid", "sitename" -> "Site";
            case "technology", "tech", "rat" -> "Tech";
            case "band", "carrier", "layer", "frequencyband" -> "Band";
            case "sector", "sectorid", "sec", "secid" -> "Sector_ID";
            case "sectorname" -> "Sector_Name";
            case "group", "groups", "region", "cluster" -> "Groups";
            case "date", "datetime", "timestamp", "time" -> "Date";
            default -> null;
        };
    }

    private AppendOption parseAppendOption(String value) {
        if (value == null || value.isBlank()) {
            return AppendOption.APPEND;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
        if (normalized.equals("OVER_WRITE")) {
            normalized = "OVERWRITE";
        }
        try {
            return AppendOption.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid appendOption. Allowed values are append, merge, and overwrite.");
        }
    }

    private String kpiBusinessKey(UploadData row) {
        return String.join("|",
                keyPart(row.getDate()),
                keyPart(row.getCellName()),
                keyPart(row.getSite()),
                keyPart(row.getBand()),
                keyPart(row.getTech()),
                keyPart(row.getSectorId()),
                keyPart(row.getSectorName()),
                keyPart(row.getGroups()),
                keyPart(row.getUlPrbUtilizationRate()),
                keyPart(row.getDlPrbUtilizationRate()),
                keyPart(row.getUme4gDataVolumeStdMapsMb9035931()),
                keyPart(row.getUmeEutranIpThroughputUeUlStdKbps()),
                keyPart(row.getUmeEutranIpThroughputUeDlStdKbps()),
                keyPart(row.getErabDropRate()),
                keyPart(row.getInitialErabEstablishmentSuccessRate()),
                keyPart(row.getRrcEstablishmentSuccessRate()),
                keyPart(row.getMeanRrcConnectedUserNumber()),
                keyPart(row.getMaximumRrcConnectedUserNumber()),
                keyPart(row.getErabSetupSuccessRate()),
                keyPart(row.getRrcDropRate()),
                keyPart(row.getVolteCssrEric()),
                keyPart(row.getVolteDcrEric()),
                keyPart(row.getInterFreqHosr()),
                keyPart(row.getIntraFreqHosr()),
                keyPart(row.getCsfbSuccessRate())
        );
    }

    private String keyPart(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number number) {
            return String.format(Locale.ROOT, "%.8f", number.doubleValue());
        }
        return String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    private List<Map<String, Object>> toKpiCacheRows(List<UploadData> rows) {
        List<Map<String, Object>> cacheRows = new ArrayList<>();
        for (UploadData row : rows) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("date", row.getDate());
            payload.put("Cell_Name", row.getCellName());
            payload.put("Site", row.getSite());
            payload.put("Band", row.getBand());
            payload.put("Tech", row.getTech());
            payload.put("Sector_ID", row.getSectorId());
            payload.put("Sector_Name", row.getSectorName());
            payload.put("Groups", row.getGroups());
            payload.put("UL_PRB_Utilization_Rate", row.getUlPrbUtilizationRate());
            payload.put("DL_PRB_Utilization_Rate", row.getDlPrbUtilizationRate());
            payload.put("UME_4G_Data_Volume_STD_MAPS_MB_903593_1", row.getUme4gDataVolumeStdMapsMb9035931());
            payload.put("UME_E_UTRAN_IP_Throughput_UE_UL_STD_Kbps", row.getUmeEutranIpThroughputUeUlStdKbps());
            payload.put("UME_E_UTRAN_IP_Throughput_UE_DL_STD_Kbps", row.getUmeEutranIpThroughputUeDlStdKbps());
            payload.put("E_RAB_Drop_Rate", row.getErabDropRate());
            payload.put("Initial_ERAB_Establishment_Success_Rate", row.getInitialErabEstablishmentSuccessRate());
            payload.put("RRC_Establishment_Success_Rate", row.getRrcEstablishmentSuccessRate());
            payload.put("Mean_RRC_Connected_User_Number", row.getMeanRrcConnectedUserNumber());
            payload.put("Maximum_RRC_Connected_User_Number", row.getMaximumRrcConnectedUserNumber());
            payload.put("E_RAB_Setup_Success_Rate", row.getErabSetupSuccessRate());
            payload.put("RRC_Drop_Rate", row.getRrcDropRate());
            payload.put("VOLTE_CSSR_Eric", row.getVolteCssrEric());
            payload.put("VOLTE_DCR_Eric", row.getVolteDcrEric());
            payload.put("Inter_Freq_HOSR", row.getInterFreqHosr());
            payload.put("Intra_Freq_HOSR", row.getIntraFreqHosr());
            payload.put("CSFB_Success_Rate", row.getCsfbSuccessRate());
            cacheRows.add(payload);
        }
        return cacheRows;
    }

    private String canonicalizeHeader(String key) {
        if (key == null) {
            return "";
        }
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private Map<String, Object> buildKpiDynamicCachePayload(List<Map<String, Object>> rows) {
        List<Map<String, Object>> canonicalRows = new ArrayList<>();
        Map<String, String> metricLabels = new LinkedHashMap<>();
        Map<String, Map<String, Object>> columnStats = new LinkedHashMap<>();
        List<String> preferredFilterKeys = List.of("tech", "band", "groups", "site", "sectorname", "sectorid", "cellname", "date");
        Set<String> metricKeys = new LinkedHashSet<>();

        for (Map<String, Object> rawRow : rows) {
            for (Map.Entry<String, Object> entry : rawRow.entrySet()) {
                String key = canonicalizeHeader(entry.getKey());
                Map<String, Object> stat = columnStats.computeIfAbsent(key, ignored -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("label", entry.getKey());
                    value.put("nonEmptyCount", 0);
                    value.put("numericCount", 0);
                    value.put("numericRatio", 0.0);
                    value.put("isDimension", isLikelyDimensionColumn(key));
                    value.put("hasKpiHint", hasKpiHeaderHint(entry.getKey()));
                    return value;
                });
                Object value = entry.getValue();
                if (value != null && !String.valueOf(value).trim().isBlank()) {
                    stat.put("nonEmptyCount", ((Number) stat.get("nonEmptyCount")).intValue() + 1);
                }
                if (parseFloatSafe(value) != null) {
                    stat.put("numericCount", ((Number) stat.get("numericCount")).intValue() + 1);
                }
            }
        }

        for (Map<String, Object> stat : columnStats.values()) {
            int nonEmpty = ((Number) stat.get("nonEmptyCount")).intValue();
            int numeric = ((Number) stat.get("numericCount")).intValue();
            stat.put("numericRatio", nonEmpty > 0 ? (double) numeric / nonEmpty : 0.0);
        }

        for (Map.Entry<String, Map<String, Object>> entry : columnStats.entrySet()) {
            Map<String, Object> stat = entry.getValue();
            boolean isDimension = Boolean.TRUE.equals(stat.get("isDimension"));
            int numericCount = ((Number) stat.get("numericCount")).intValue();
            double numericRatio = ((Number) stat.get("numericRatio")).doubleValue();
            boolean hasKpiHint = Boolean.TRUE.equals(stat.get("hasKpiHint"));
            if (isDimension || numericCount == 0) {
                continue;
            }
            if (numericRatio >= 0.6 || (hasKpiHint && numericRatio >= 0.2)) {
                metricKeys.add(entry.getKey());
            }
        }

        List<String> detectedFilterKeys = columnStats.keySet().stream()
                .filter(key -> !metricKeys.contains(key))
                .filter(key -> ((Number) columnStats.get(key).get("nonEmptyCount")).intValue() > 0)
                .toList();

        List<String> selectedFilterKeys = preferredFilterKeys.stream()
                .filter(detectedFilterKeys::contains)
                .collect(Collectors.toCollection(ArrayList::new));
        detectedFilterKeys.stream()
                .filter(key -> !preferredFilterKeys.contains(key))
                .forEach(selectedFilterKeys::add);

        for (Map<String, Object> rawRow : rows) {
            Map<String, Object> normalized = normalizeRow(rawRow);
            Map<String, Object> dimensions = new LinkedHashMap<>();
            Map<String, Object> metrics = new LinkedHashMap<>();

            for (String dimensionKey : detectedFilterKeys) {
                Object rawValue = pickValue(normalized, dimensionKey);
                String value = safeString(rawValue);
                if (value != null) {
                    dimensions.put(dimensionKey, value);
                }
            }

            for (Map.Entry<String, Object> entry : rawRow.entrySet()) {
                String metricKey = canonicalizeHeader(entry.getKey());
                if (!metricKeys.contains(metricKey)) {
                    continue;
                }
                Double parsed = parseFloatSafe(entry.getValue());
                if (parsed == null) {
                    continue;
                }
                metrics.put(metricKey, parsed);
                metricLabels.putIfAbsent(metricKey, entry.getKey());
            }

            Map<String, Object> canonicalRow = new LinkedHashMap<>();
            canonicalRow.put("date", parseDatePartSafe(pickValue(normalized, "date", "datetime", "timestamp", "time")));
            canonicalRow.put("cellName", safeString(pickValue(normalized, "cellname", "cellid", "cell", "shortname", "short_name")));
            canonicalRow.put("site", safeString(pickValue(normalized, "site", "siteid", "site_name", "sitename")));
            canonicalRow.put("band", safeString(pickValue(normalized, "band", "frequency_band", "layer", "carrier")));
            canonicalRow.put("tech", safeString(pickValue(normalized, "tech", "technology")));
            String sector = firstNonBlank(
                    safeString(pickValue(normalized, "sectorid", "sector_id", "secid", "sec_id", "sec")),
                    safeString(pickValue(normalized, "sectorname", "sector_name", "sector")),
                    deriveSectorFromKpiRow(normalized)
            );
            canonicalRow.put("sectorid", sector);
            canonicalRow.put("sectorname", firstNonBlank(safeString(pickValue(normalized, "sectorname", "sector_name", "sector")), sector));
            canonicalRow.put("groups", safeString(pickValue(normalized, "groups", "group", "region", "cluster")));
            canonicalRow.put("dimensions", dimensions);
            canonicalRow.put("metrics", metrics);
            canonicalRows.add(canonicalRow);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generatedAt", Instant.now().toString());
        payload.put("metricLabels", metricLabels);
        payload.put("columnStats", columnStats);
        payload.put("detectedMetricKeys", new ArrayList<>(metricKeys));
        payload.put("selectedMetricKeys", new ArrayList<>(metricKeys));
        payload.put("detectedFilterKeys", detectedFilterKeys);
        payload.put("selectedFilterKeys", selectedFilterKeys);
        payload.put("records", canonicalRows);
        return payload;
    }

    private Map<String, Object> mergeKpiDynamicCachePayloads(Map<String, Object> existing, Map<String, Object> incoming, boolean dedupeRecords) {
        if (existing == null || existing.isEmpty()) {
            return incoming == null ? new LinkedHashMap<>() : incoming;
        }
        if (incoming == null || incoming.isEmpty()) {
            return existing;
        }

        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("generatedAt", Instant.now().toString());

        Map<String, String> metricLabels = new LinkedHashMap<>();
        metricLabels.putAll(toStringMap(existing.get("metricLabels")));
        metricLabels.putAll(toStringMap(incoming.get("metricLabels")));
        merged.put("metricLabels", metricLabels);

        Map<String, Map<String, Object>> columnStats = new LinkedHashMap<>();
        mergeColumnStats(columnStats, toNestedObjectMap(existing.get("columnStats")));
        mergeColumnStats(columnStats, toNestedObjectMap(incoming.get("columnStats")));
        merged.put("columnStats", columnStats);

        merged.put("detectedMetricKeys", unionStringLists(existing.get("detectedMetricKeys"), incoming.get("detectedMetricKeys")));
        merged.put("selectedMetricKeys", unionStringLists(existing.get("selectedMetricKeys"), incoming.get("selectedMetricKeys")));
        merged.put("detectedFilterKeys", unionStringLists(existing.get("detectedFilterKeys"), incoming.get("detectedFilterKeys")));
        merged.put("selectedFilterKeys", unionStringLists(existing.get("selectedFilterKeys"), incoming.get("selectedFilterKeys")));

        List<Map<String, Object>> records = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        appendCacheRecords(records, seen, getMapRecordList(existing.get("records")), dedupeRecords);
        appendCacheRecords(records, seen, getMapRecordList(incoming.get("records")), dedupeRecords);
        merged.put("records", records);
        return merged;
    }

    private void mergeColumnStats(Map<String, Map<String, Object>> target, Map<String, Map<String, Object>> source) {
        for (Map.Entry<String, Map<String, Object>> entry : source.entrySet()) {
            Map<String, Object> incoming = entry.getValue();
            Map<String, Object> current = target.computeIfAbsent(entry.getKey(), ignored -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("label", incoming.getOrDefault("label", entry.getKey()));
                value.put("nonEmptyCount", 0);
                value.put("numericCount", 0);
                value.put("numericRatio", 0.0);
                value.put("isDimension", incoming.getOrDefault("isDimension", false));
                value.put("hasKpiHint", incoming.getOrDefault("hasKpiHint", false));
                return value;
            });
            int nonEmpty = toInt(current.get("nonEmptyCount")) + toInt(incoming.get("nonEmptyCount"));
            int numeric = toInt(current.get("numericCount")) + toInt(incoming.get("numericCount"));
            current.put("nonEmptyCount", nonEmpty);
            current.put("numericCount", numeric);
            current.put("numericRatio", nonEmpty > 0 ? (double) numeric / nonEmpty : 0.0);
            current.put("isDimension", Boolean.TRUE.equals(current.get("isDimension")) || Boolean.TRUE.equals(incoming.get("isDimension")));
            current.put("hasKpiHint", Boolean.TRUE.equals(current.get("hasKpiHint")) || Boolean.TRUE.equals(incoming.get("hasKpiHint")));
        }
    }

    private void appendCacheRecords(List<Map<String, Object>> target, Set<String> seen, List<Map<String, Object>> source, boolean dedupeRecords) {
        for (Map<String, Object> record : source) {
            if (!dedupeRecords || seen.add(dynamicRecordKey(record))) {
                target.add(record);
            }
        }
    }

    private CleanKpiRecordsResult cleanKpiRecords(List<Map<String, Object>> records) {
        List<Map<String, Object>> cleaned = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int emptyMetricRows = 0;
        int duplicateRows = 0;
        int invalidDimensionRows = 0;

        for (Map<String, Object> record : records) {
            if (getObjectMap(record.get("metrics")).isEmpty()) {
                emptyMetricRows++;
                continue;
            }
            if (missingRequiredDynamicDimensions(record)) {
                invalidDimensionRows++;
                continue;
            }
            String rowKey = dynamicRecordKey(record);
            if (!seen.add(rowKey)) {
                duplicateRows++;
                continue;
            }
            cleaned.add(record);
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", true);
        stats.put("inputRows", records.size());
        stats.put("storedRows", cleaned.size());
        stats.put("removedEmptyMetricRows", emptyMetricRows);
        stats.put("removedDuplicateRows", duplicateRows);
        stats.put("removedInvalidDimensionRows", invalidDimensionRows);
        stats.put("removedRows", emptyMetricRows + duplicateRows + invalidDimensionRows);
        return new CleanKpiRecordsResult(cleaned, stats);
    }

    private boolean missingRequiredDynamicDimensions(Map<String, Object> record) {
        return dimensionValue(record, "date", "date", "datetime", "timestamp") == null
                || dimensionValue(record, "cellName", "cellid", "cell_id", "cell", "shortname", "short_name") == null
                || dimensionValue(record, "site", "siteid", "site_id", "sitename", "site_name") == null
                || dimensionValue(record, "tech", "tech", "technology", "rat") == null;
    }

    private String dimensionValue(Map<String, Object> record, String topLevelKey, String... dimensionKeys) {
        String topLevel = safeString(record.get(topLevelKey));
        if (topLevel != null) {
            return topLevel;
        }
        Map<String, Object> dimensions = getObjectMap(record.get("dimensions"));
        for (String key : dimensionKeys) {
            String value = safeString(dimensions.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String dynamicRecordKey(Map<String, Object> record) {
        String rawKey = String.join("|",
                keyPart(dimensionValue(record, "date", "date", "datetime", "timestamp")),
                keyPart(dimensionValue(record, "cellName", "cellid", "cell_id", "cell", "shortname", "short_name")),
                keyPart(dimensionValue(record, "site", "siteid", "site_id", "sitename", "site_name")),
                keyPart(dimensionValue(record, "band", "band", "frequencyband", "frequency_band", "layer", "carrier")),
                keyPart(dimensionValue(record, "tech", "tech", "technology", "rat")),
                keyPart(dimensionValue(record, "sectorid", "sectorid", "sector_id", "secid", "sec_id", "sec")),
                keyPart(dimensionValue(record, "sectorname", "sectorname", "sector_name", "sector")),
                keyPart(dimensionValue(record, "groups", "groups", "group", "region", "cluster")),
                keyPart(getObjectMap(record.get("metrics")).toString())
        );
        return sha256Hex(rawKey);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            char[] chars = new char[hash.length * 2];
            char[] hex = "0123456789abcdef".toCharArray();
            for (int index = 0; index < hash.length; index++) {
                int item = hash[index] & 0xff;
                chars[index * 2] = hex[item >>> 4];
                chars[index * 2 + 1] = hex[item & 0x0f];
            }
            return new String(chars);
        } catch (Exception ex) {
            return String.valueOf(value == null ? "" : value.hashCode());
        }
    }

    private List<String> unionStringLists(Object first, Object second) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.addAll(toStringList(first));
        values.addAll(toStringList(second));
        return new ArrayList<>(values);
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(String::valueOf)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private Map<String, String> toStringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : getObjectMap(value).entrySet()) {
            result.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return result;
    }

    private Map<String, Map<String, Object>> toNestedObjectMap(Object value) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : getObjectMap(value).entrySet()) {
            result.put(entry.getKey(), getObjectMap(entry.getValue()));
        }
        return result;
    }

    private Map<String, Object> getObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private List<Map<String, Object>> getMapRecordList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            result.add(getObjectMap(item));
        }
        return result;
    }

    private int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private boolean isLikelyDimensionColumn(String canonicalKey) {
        if (canonicalKey == null) {
            return false;
        }
        if (Set.of("date", "datetime", "cellname", "cell_name", "site", "band", "tech", "sectorid", "sector_id", "sectorname", "sector_name", "groups", "group").contains(canonicalKey)) {
            return true;
        }
        return canonicalKey.endsWith("id") || canonicalKey.contains("name") || canonicalKey.contains("region") || canonicalKey.contains("cluster");
    }

    private boolean hasKpiHeaderHint(String header) {
        String clean = canonicalizeHeader(header);
        return List.of("rate", "throughput", "volume", "utilization", "success", "drop", "hosr", "sinr", "cqi", "packetloss", "srvcc", "volte", "dcr", "rrc", "erab", "prb", "csfb").stream()
                .anyMatch(clean::contains);
    }

    private Object pickValue(Map<String, Object> row, String... aliases) {
        for (String alias : aliases) {
            Object value = row.get(canonicalizeHeader(alias));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String safeString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> || value instanceof Collection<?>) {
            return null;
        }
        String parsed = String.valueOf(value).trim();
        return parsed.isEmpty() ? null : parsed;
    }

    private Double parseFloatSafe(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Map<?, ?> || value instanceof Collection<?>) {
            return null;
        }
        try {
            String cleaned = String.valueOf(value).replaceAll("[%,\\s]", "").trim();
            if (cleaned.isEmpty()) {
                return null;
            }
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer parseIntSafe(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            String cleaned = String.valueOf(value).trim();
            if (cleaned.isEmpty()) {
                return null;
            }
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private LocalDateTime parseDateSafe(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof java.util.Date date) {
            return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
        }
        if (value instanceof Number number) {
            double excelValue = number.doubleValue();
            long millis = Math.round((excelValue - 25569d) * 86400d * 1000d);
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text);
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(text).atStartOfDay();
        } catch (Exception ignored) {
        }
        try {
            return Instant.parse(text).atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception ignored) {
        }
        return null;
    }

    private String parseDatePartSafe(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text && text.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            return text;
        }
        LocalDateTime parsed = parseDateSafe(value);
        return parsed == null ? null : parsed.toLocalDate().toString();
    }

    private LocalDateTime parseDateTimeLoose(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(value).atStartOfDay();
        } catch (Exception ignored) {
        }
        try {
            return Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception ignored) {
        }
        return null;
    }

    private void deleteTempFile(Path filePath) {
        if (filePath != null) {
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException ignored) {
            }
        }
    }

    private void clearRedisKpiCaches(long fileId) {
        RedisService.RedisCacheService cacheService = redisCacheServiceProvider.getIfAvailable();
        if (cacheService == null) {
            return;
        }
        String summary = "kpi:summary:file:" + fileId;
        String batch = "kpi:batch-data:file:" + fileId;
        String columns = "kpi:columns:file:" + fileId;
        cacheService.deleteCacheKey(summary);
        cacheService.deleteCacheKey(batch);
        cacheService.deleteCacheKey(columns);
    }

    private String getExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot != -1 ? filename.substring(lastDot) : "";
    }

    private void logPerf(String message) {
        if (isTruthy(perfLogsEnabledRaw)) {
            System.out.println(message);
        }
    }

    private boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        String cleaned = value.trim().toLowerCase(Locale.ROOT);
        return cleaned.equals("1") || cleaned.equals("true") || cleaned.equals("yes") || cleaned.equals("on");
    }

    private void addSiteDataColumnIfMissing(String columnName, String alterSql) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'sitedata'
                  AND COLUMN_NAME = ?
                """, Integer.class, columnName);
        if (count == null || count == 0) {
            jdbcTemplate.execute(alterSql);
        }
    }

    private void addSiteDataIndexIfMissing(String indexName, String createSql) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'sitedata'
                  AND INDEX_NAME = ?
                """, Integer.class, indexName);
        if (count == null || count == 0) {
            jdbcTemplate.execute(createSql);
        }
    }

    private <T> int insertInChunks(List<T> rows, Class<T> type) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int totalInserted = 0;
        for (int i = 0; i < rows.size(); i += INSERT_CHUNK_SIZE) {
            List<T> chunk = rows.subList(i, Math.min(rows.size(), i + INSERT_CHUNK_SIZE));
            totalInserted += batchInsertIgnore(chunk, type);
        }
        return totalInserted;
    }

    private <T> int batchInsertIgnore(List<T> rows, Class<T> type) {
        String table = getTableName(type);
        List<Field> fields = getPersistentFields(type);
        String columnsSql = fields.stream()
                .map(this::getColumnName)
                .map(this::quoteIdentifier)
                .collect(Collectors.joining(", "));
        String placeholders = fields.stream().map(field -> "?").collect(Collectors.joining(", "));
        String sql = "INSERT IGNORE INTO " + quoteIdentifier(table) + " (" + columnsSql + ") VALUES (" + placeholders + ")";

        int[] counts = jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                T row = rows.get(i);
                for (int index = 0; index < fields.size(); index++) {
                    Field field = fields.get(index);
                    field.setAccessible(true);
                    try {
                        Object value = field.get(row);
                        ps.setObject(index + 1, toJdbcValue(value));
                    } catch (IllegalAccessException ex) {
                        throw new SQLException("Failed to read field " + field.getName(), ex);
                    }
                }
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
        int inserted = 0;
        for (int count : counts) {
            if (count > 0) {
                inserted += count;
            } else if (count == Statement.SUCCESS_NO_INFO) {
                inserted++;
            }
        }
        return inserted;
    }

    private Object toJdbcValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return Timestamp.valueOf(localDateTime);
        }
        if (value instanceof LocalDate localDate) {
            return Timestamp.valueOf(localDate.atStartOfDay());
        }
        if (value instanceof java.util.Date date) {
            return new Timestamp(date.getTime());
        }
        return value;
    }

    private List<Field> getPersistentFields(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> !field.getName().equals("serialVersionUID"))
                .filter(field -> !Collection.class.isAssignableFrom(field.getType()))
                .filter(field -> !Map.class.isAssignableFrom(field.getType()))
                .filter(field -> !field.isAnnotationPresent(jakarta.persistence.Transient.class))
                .sorted(Comparator.comparing(Field::getName))
                .toList();
    }

    private String getColumnName(Field field) {
        jakarta.persistence.Column column = field.getAnnotation(jakarta.persistence.Column.class);
        if (column != null && !column.name().isBlank()) {
            return column.name();
        }
        return field.getName();
    }

    private String getTableName(Class<?> type) {
        jakarta.persistence.Table table = type.getAnnotation(jakarta.persistence.Table.class);
        if (table != null && !table.name().isBlank()) {
            return table.name();
        }
        return type.getSimpleName();
    }

    private String quoteIdentifier(String identifier) {
        if (identifier != null && identifier.length() >= 2 && identifier.startsWith("`") && identifier.endsWith("`")) {
            return identifier;
        }
        return "`" + identifier.replace("`", "``") + "`";
    }

    private boolean hasLegacyKpiMetrics(List<UploadData> rows) {
        if (rows == null || rows.isEmpty()) {
            return false;
        }
        return rows.stream().anyMatch(row ->
                row.getUlPrbUtilizationRate() != null
                        || row.getDlPrbUtilizationRate() != null
                        || row.getUme4gDataVolumeStdMapsMb9035931() != null
                        || row.getUmeEutranIpThroughputUeUlStdKbps() != null
                        || row.getUmeEutranIpThroughputUeDlStdKbps() != null
                        || row.getErabDropRate() != null
                        || row.getInitialErabEstablishmentSuccessRate() != null
                        || row.getRrcEstablishmentSuccessRate() != null
                        || row.getMeanRrcConnectedUserNumber() != null
                        || row.getMaximumRrcConnectedUserNumber() != null
                        || row.getErabSetupSuccessRate() != null
                        || row.getRrcDropRate() != null
                        || row.getVolteCssrEric() != null
                        || row.getVolteDcrEric() != null
                        || row.getInterFreqHosr() != null
                        || row.getIntraFreqHosr() != null
                        || row.getCsfbSuccessRate() != null
        );
    }

    private UploadData mapKpiRow(Map<String, Object> row, Long fileId) {
        UploadData data = new UploadData();
        data.setFileId(fileId);
        data.setCompanyId(roleAuthorizationService.currentUserCompanyIdOrNull());
        data.setDate(parseDateSafe(pickValue(row, "date", "datetime")));
        data.setCellName(safeString(pickValue(row, "cell_name", "cellname", "cell", "short_name", "shortname", "4g_cell_name", "4gcellname")));
        data.setSite(safeString(pickValue(row, "site")));
        data.setBand(safeString(pickValue(row, "band")));
        data.setTech(safeString(pickValue(row, "tech", "technology", "rat")));
        data.setSectorId(safeString(pickValue(row, "sector_id", "sectorid", "sector", "sec_id", "secid", "sec")));
        data.setSectorName(safeString(pickValue(row, "sector_name", "sectorname", "sector")));
        data.setGroups(safeString(pickValue(row, "groups", "group")));
        data.setUlPrbUtilizationRate(parseFloatSafe(pickValue(row, "ul_prb_utilization_rate", "ulprbutilizationrate")));
        data.setDlPrbUtilizationRate(parseFloatSafe(pickValue(row, "dl_prb_utilization_rate", "dlprbutilizationrate")));
        data.setUme4gDataVolumeStdMapsMb9035931(parseFloatSafe(pickValue(row, "ume_4g_data_volume_std_maps_mb_903593_1", "ume4gdatavolumestdmapsmb9035931")));
        data.setUmeEutranIpThroughputUeUlStdKbps(parseFloatSafe(pickValue(row, "ume_e_utran_ip_throughput_ue_ul_std_kbps", "umeeutranipthroughputueulstdkbps")));
        data.setUmeEutranIpThroughputUeDlStdKbps(parseFloatSafe(pickValue(row, "ume_e_utran_ip_throughput_ue_dl_std_kbps", "umeeutranipthroughputuedlstdkbps")));
        data.setErabDropRate(parseFloatSafe(pickValue(row, "e_rab_drop_rate", "erabdroprate")));
        data.setInitialErabEstablishmentSuccessRate(parseFloatSafe(pickValue(row, "initial_erab_establishment_success_rate", "initialerabestablishmentsuccessrate")));
        data.setRrcEstablishmentSuccessRate(parseFloatSafe(pickValue(row, "rrc_establishment_success_rate", "rrcestablishmentsuccessrate")));
        data.setMeanRrcConnectedUserNumber(parseIntSafe(pickValue(row, "mean_rrc_connected_user_number", "meanrrcconnectedusernumber")) == null ? null : parseIntSafe(pickValue(row, "mean_rrc_connected_user_number", "meanrrcconnectedusernumber")).doubleValue());
        data.setMaximumRrcConnectedUserNumber(parseIntSafe(pickValue(row, "maximum_rrc_connected_user_number", "maximumrrcconnectedusernumber")) == null ? null : parseIntSafe(pickValue(row, "maximum_rrc_connected_user_number", "maximumrrcconnectedusernumber")).doubleValue());
        data.setErabSetupSuccessRate(parseFloatSafe(pickValue(row, "e_rab_setup_success_rate", "erabsetupsuccessrate")));
        data.setRrcDropRate(parseFloatSafe(pickValue(row, "rrc_drop_rate", "rrcdroprate")));
        data.setVolteCssrEric(parseFloatSafe(pickValue(row, "volte_cssr_eric", "voltecssreric")));
        data.setVolteDcrEric(parseFloatSafe(pickValue(row, "volte_dcr_eric", "voltedcreric")));
        data.setInterFreqHosr(parseFloatSafe(pickValue(row, "inter_freq_hosr", "interfreqhosr")));
        data.setIntraFreqHosr(parseFloatSafe(pickValue(row, "intra_freq_hosr", "intrafreqhosr")));
        data.setCsfbSuccessRate(parseFloatSafe(pickValue(row, "csfb_success_rate", "csfbsuccessrate")));
        return data;
    }

    private long nextSiteDataId() {
        Long next = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(`id`), 0) + 1 FROM `sitedata`", Long.class);
        return next == null || next < 1 ? 1L : next;
    }

    private SiteData mapSiteRow(Map<String, Object> row, long generatedId, Long fileId) {
        String cellName = safeString(pickValue(row, "4g_cell_name", "4gcellname", "lte_cell_name", "ltecellname", "cell_name", "cellname", "cell", "short_name", "shortname", "eutrancell", "eutrancellfdd", "eutrancelltdd", "localcellname", "local_cell_name", "cell_name_full"));
        String cellId = safeString(pickValue(row, "4g_cell_id", "4gcellid", "lte_cell_id", "ltecellid", "cell_id", "cellid"));
        CellNameParts cellParts = parseCellNameParts(cellName);
        Integer earfcn = parseIntSafe(pickValue(row, "earfcn", "earcfcn", "dl_earfcn", "dl_earcfcn", "dlearfcn", "dlearcfcn", "earfcndl", "earcfcndl", "earfcn_dl", "earcfcn_dl", "earfcn_downlink", "earcfcn_downlink", "downlink_earfcn", "downlink_earcfcn", "dl_channel_number", "dlchannelnumber", "channel_number", "channelnumber", "e_arfcn", "e-arfcn"));
        String bandFromEarfcn = lteBandFromEarfcn(earfcn);
        String siteId = firstNonBlank(safeString(pickValue(row, "4g_site_name", "4gsitename", "lte_site_name", "ltesitename", "siteid", "site_id", "site")), cellParts.siteId);
        String siteName = safeString(pickValue(row, "4g_site_name", "4gsitename", "lte_site_name", "ltesitename", "site_name", "sitename"));
        if (isBlank(cellName) && isBlank(cellId) && isBlank(siteId) && isBlank(siteName)) {
            return null;
        }
        SiteData data = new SiteData();
        data.setId(generatedId);
        data.setFileId(fileId);
        data.setCompanyId(roleAuthorizationService.currentUserCompanyIdOrNull());
        data.setCellName(cellName);
        data.setSiCi(safeString(pickValue(row, "si_ci", "sici")));
        data.setEgci(safeString(pickValue(row, "egci")));
        data.setSuNetworkId(safeString(pickValue(row, "sunetwork_id", "sunetworkid")));
        data.setSiteId(siteId);
        data.setSiteName(firstNonBlank(siteName, siteId));
        data.setCellId(cellId);
        data.setSecId(safeString(pickValue(row, "sec_id", "secid", "sectorid", "sector_id", "sector")));
        data.setSector(firstNonBlank(safeString(pickValue(row, "sector", "sec", "sector_id", "sectorid", "sec_id", "secid", "sector_name", "sectorname")), cellParts.sector));
        data.setLon(parseFloatSafe(pickValue(row, "lon", "lng", "long", "longitude", "site_longitude", "sitelongitude", "cell_longitude", "celllongitude", "x", "decimal_longitude")));
        data.setLat(parseFloatSafe(pickValue(row, "lat", "latitude", "site_latitude", "sitelatitude", "cell_latitude", "celllatitude", "y", "decimal_latitude")));
        data.setTac(parseIntSafe(pickValue(row, "tac")));
        data.setPci(parseIntSafe(pickValue(row, "pci", "physical_cell_id_pci", "physicalcellidpci", "physical_cell_id", "physicalcellid")));
        data.setAzimuth(parseIntSafe(pickValue(row, "azimuth")));
        data.setAntennaHeight(parseFloatSafe(pickValue(row, "antenna_height", "antennaheight", "height_in_m", "heightinm", "height")));
        data.setMTilt(parseFloatSafe(pickValue(row, "m_tilt", "mtilt")));
        data.setETilt(parseFloatSafe(pickValue(row, "e_tilt", "etilt")));
        data.setTxRx(safeString(pickValue(row, "tx_rx", "txrx")));
        data.setRealTransmitPowerOfResource(parseFloatSafe(pickValue(row, "real_transmit_power_of_resource", "realtransmitpowerofresource")));
        data.setReferencedSignalPowerOfResource(parseFloatSafe(pickValue(row, "referenced_signal_power_of_resource", "referencedsignalpowerofresource")));
        data.setCellSize(safeString(pickValue(row, "cellsize", "cell_size")));
        data.setCellRadius(parseFloatSafe(pickValue(row, "cellradius", "cell_radius")));
        data.setRachRootSequence(parseIntSafe(pickValue(row, "rachrootsequence", "rach_root_sequence")));
        data.setBandwidth(parseIntSafe(pickValue(row, "bandwidth")));
        data.setBand(firstNonBlank(bandFromEarfcn, safeString(pickValue(row, "band", "frequency_band", "frequencyband", "freq_band", "freqband", "band_name", "bandname", "lte_band", "lteband", "band_indicator", "bandindicator")), cellParts.band));
        data.setTechnology(firstNonBlank(safeString(pickValue(row, "technology", "tech", "rat", "radio_access_technology", "radioaccesstechnology", "rat_type", "rattype", "technology_type", "technologytype")), cellParts.technology, isBlank(bandFromEarfcn) ? "" : "LTE"));
        data.setEarfcn(earfcn);
        data.setFrequency(parseIntSafe(pickValue(row, "frequency")));
        data.setDownlinkCenterFrequency(parseIntSafe(pickValue(row, "downlink_center_frequency", "downlinkcenterfrequency", "downlink_frequency")));
        data.setRegion(safeString(pickValue(row, "region")));
        data.setCluster(safeString(pickValue(row, "cluster")));
        data.setOmm(safeString(pickValue(row, "omm")));
        data.setAntenna(safeString(pickValue(row, "antenna")));
        data.setRet(safeString(pickValue(row, "ret")));
        return data;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String deriveSectorFromKpiRow(Map<String, Object> row) {
        String shortName = safeString(pickValue(row, "shortname", "short_name"));
        if (!isBlank(shortName)) {
            String[] parts = shortName.trim().split("_");
            if (parts.length > 0) {
                String last = parts[parts.length - 1].trim();
                if (last.matches("[A-Za-z0-9]{1,3}")) {
                    return last.toUpperCase(Locale.ROOT);
                }
            }
        }

        String cell = safeString(pickValue(row, "cellname", "cellid", "cell", "cell_id", "cell_name"));
        if (isBlank(cell)) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([A-Za-z])$")
                .matcher(cell.trim());
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : "";
    }

    private CellNameParts parseCellNameParts(String cellName) {
        if (isBlank(cellName)) {
            return CellNameParts.empty();
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(.+?)_([A-Za-z])(\\d{1,3})(?:_?([A-Za-z0-9]+))?$")
                .matcher(cellName.trim());
        if (!matcher.matches()) {
            return CellNameParts.empty();
        }

        String techCode = matcher.group(2).toUpperCase(Locale.ROOT);
        String technology = switch (techCode) {
            case "L" -> "LTE";
            case "N" -> "NR";
            case "G" -> "GSM";
            case "U" -> "UMTS";
            case "W" -> "WCDMA";
            default -> techCode;
        };
        String band = techCode + String.format(Locale.ROOT, "%02d", Integer.parseInt(matcher.group(3)));
        String sectorGroup = matcher.group(4);
        String sector = isBlank(sectorGroup)
                ? ""
                : sectorGroup.substring(sectorGroup.length() - 1).toUpperCase(Locale.ROOT);
        return new CellNameParts(matcher.group(1), technology, band, sector);
    }

    private String lteBandFromEarfcn(Integer earfcn) {
        if (earfcn == null) {
            return "";
        }
        int[][] ranges = new int[][] {
                {0, 599, 1}, {600, 1199, 2}, {1200, 1949, 3}, {1950, 2399, 4},
                {2400, 2649, 5}, {2650, 2749, 6}, {2750, 3449, 7}, {3450, 3799, 8},
                {3800, 4149, 9}, {4150, 4749, 10}, {4750, 4949, 11}, {5010, 5179, 12},
                {5180, 5279, 13}, {5280, 5379, 14}, {5730, 5849, 17}, {5850, 5999, 18},
                {6000, 6149, 19}, {6150, 6449, 20}, {6450, 6599, 21}, {6600, 7399, 22},
                {7500, 7699, 23}, {7700, 8039, 24}, {8040, 8689, 25}, {8690, 9039, 26},
                {9040, 9209, 27}, {9210, 9659, 28}, {9660, 9769, 29}, {9770, 9869, 30},
                {9870, 9919, 31}, {9920, 10359, 32}, {36000, 36199, 33}, {36200, 36349, 34},
                {36350, 36949, 35}, {36950, 37549, 36}, {37550, 37749, 37}, {37750, 38249, 38},
                {38250, 38649, 39}, {38650, 39649, 40}, {39650, 41589, 41}, {41590, 43589, 42},
                {43590, 45589, 43}, {45590, 46589, 44}, {46590, 46789, 45}, {46790, 54539, 46},
                {54540, 55239, 47}, {55240, 56739, 48}, {56740, 58239, 49}, {58240, 59089, 50},
                {59090, 59139, 51}, {59140, 60139, 52}, {60140, 60254, 53}, {65536, 66435, 65},
                {66436, 67335, 66}, {67336, 67535, 67}, {67536, 67835, 68}, {67836, 68335, 69},
                {68336, 68585, 70}, {68586, 68935, 71}, {68936, 68985, 72}, {68986, 69035, 73},
                {69036, 69465, 74}, {69466, 70315, 75}, {70316, 70365, 76}, {70366, 70545, 85},
                {70546, 70595, 87}, {70596, 70645, 88}
        };

        for (int[] range : ranges) {
            if (earfcn >= range[0] && earfcn <= range[1]) {
                return "L" + String.format(Locale.ROOT, "%02d", range[2]);
            }
        }
        return "";
    }

    private record CellNameParts(String siteId, String technology, String band, String sector) {
        private static CellNameParts empty() {
            return new CellNameParts("", "", "", "");
        }
    }

    private AlarmData mapAlarmRow(Map<String, Object> row, Long fileId) {
        AlarmData data = new AlarmData();
        data.setFileId(fileId);
        data.setCompanyId(roleAuthorizationService.currentUserCompanyIdOrNull());
        data.setFilename(safeString(pickValue(row, "filename", "file_name", "source_file", "sourcefile")));
        data.setDatetime(parseDateSafe(pickValue(row, "alarm_time", "alarmtime", "datetime", "date_time", "date", "date2", "time")));
        data.setConfigDataDnPrefix(safeString(pickValue(row, "configdata_dnprefix", "configdatadnprefix", "dn_prefix", "dnprefix")));
        data.setSubNetworkId(safeString(pickValue(row, "circle", "subnetwork_id", "subnetworkid")));
        data.setSubNetwork2Id(safeString(pickValue(row, "subnetwork_2_id", "subnetwork2id")));
        data.setMeContextId(safeString(pickValue(row, "enodeb_id", "enodebid", "enb_id", "enbid", "mecontext_id", "mecontextid")));
        data.setManagedElementId(safeString(pickValue(row, "enodeb_id", "enodebid", "enb_id", "enbid", "managedelement_id", "managedelementid")));
        data.setVsEquipmentId(safeString(pickValue(row, "vsequipment_id", "vsequipmentid")));
        data.setVsFieldReplaceableUnitId(safeString(pickValue(row, "vsfieldreplaceableunit_id", "vsfieldreplaceableunitid")));
        data.setVsAlarmPortId(safeString(pickValue(row, "vsalarmport_id", "vsalarmportid")));
        data.setUserLabel(safeString(pickValue(row, "name", "userlabel", "user_label", "ne_name", "nename", "node_name", "nodename")));
        data.setAdministrativeState(safeString(pickValue(row, "administrativestate", "administrative_state")));
        data.setPerceivedSeverity(safeString(pickValue(row, "severity", "perceivedseverity", "perceived_severity")));
        data.setAlarmPortId(safeString(pickValue(row, "alarm_number", "alarmnumber", "alarmportid", "alarm_port_id")));
        data.setAlarmSlogan(safeString(pickValue(row, "alarm_text", "alarmtext", "alarm_name", "alarmname", "alarmslogan", "alarm_slogan")));
        data.setFilterDelay(safeString(pickValue(row, "filterdelay", "filter_delay")));
        data.setOperationalState(safeString(pickValue(row, "operationalstate", "operational_state")));
        data.setFilterTime(safeString(pickValue(row, "standard_aging", "standardaging", "filtertime", "filter_time")));
        data.setAvailabilityStatus(safeString(pickValue(row, "supplementry_information", "supplementryinformation", "supplementary_information", "supplementaryinformation", "supplemental_information", "supplementalinformation", "availabilitystatus", "availability_status")));
        data.setFilterAlgorithm(safeString(pickValue(row, "ageingformula", "agingformula", "filteralgorithm", "filter_algorithm")));
        data.setNormallyOpen(safeString(pickValue(row, "normallyopen", "normally_open")));
        data.setAlarmInExternalMe(safeString(pickValue(row, "gpvs", "alarminexternalme", "alarm_in_external_me")));
        data.setCreatedAt(LocalDateTime.now(ZoneId.of("UTC")));
        return data;
    }

    private enum DatasetType {
        KPI,
        SITE,
        ALARM
    }

    private enum AppendOption {
        APPEND,
        MERGE,
        OVERWRITE
    }

    private record CleanKpiRecordsResult(List<Map<String, Object>> records, Map<String, Object> stats) {
        private static CleanKpiRecordsResult unchanged(List<Map<String, Object>> records) {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("enabled", false);
            stats.put("inputRows", records == null ? 0 : records.size());
            stats.put("storedRows", records == null ? 0 : records.size());
            stats.put("removedEmptyMetricRows", 0);
            stats.put("removedDuplicateRows", 0);
            stats.put("removedInvalidDimensionRows", 0);
            stats.put("removedRows", 0);
            return new CleanKpiRecordsResult(records == null ? List.of() : records, stats);
        }
    }

    private void ensureKpiThreshold(Long fileId) {
        if (fileId == null) {
            return;
        }

        thresholdRepository.findByFileId(fileId).orElseGet(() -> {
            Threshold threshold = new Threshold();
            threshold.setFileId(fileId);
            threshold.setCompanyId(roleAuthorizationService.currentUserCompanyIdOrNull());
            threshold.setCreatedAt(LocalDateTime.now());
            threshold.setUpdatedAt(LocalDateTime.now());
            return thresholdRepository.save(threshold);
        });
    }
}

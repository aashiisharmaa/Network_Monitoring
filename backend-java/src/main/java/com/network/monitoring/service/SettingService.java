package com.network.monitoring.service;

import com.network.monitoring.entity.Threshold;
import com.network.monitoring.repository.UploadHistoryRepository;
import com.network.monitoring.repository.ThresholdRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class SettingService {

    private static final Set<String> RESERVED_KEYS = Set.of("id", "fileId", "success", "message", "thresholdOperators", "operators", "dynamicThresholds");
    private static final Set<String> KNOWN_THRESHOLD_FIELDS = Set.of(
            "UL_PRB_Utilization_Rate",
            "DL_PRB_Utilization_Rate",
            "E_RAB_Drop_Rate",
            "RRC_Drop_Rate",
            "Initial_ERAB_Establishment_Success_Rate",
            "RRC_Establishment_Success_Rate",
            "E_RAB_Setup_Success_Rate",
            "VOLTE_CSSR_Eric",
            "VOLTE_DCR_Eric",
            "Inter_Freq_HOSR",
            "Intra_Freq_HOSR",
            "CSFB_Success_Rate",
            "Max_RRC_Users"
    );

    private final ThresholdRepository thresholdRepository;
    private final UploadHistoryRepository uploadHistoryRepository;

    public SettingService(ThresholdRepository thresholdRepository,
                          UploadHistoryRepository uploadHistoryRepository) {
        this.thresholdRepository = thresholdRepository;
        this.uploadHistoryRepository = uploadHistoryRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getSettings(Long fileId) {
        Threshold globalRow = thresholdRepository.findFirstByFileIdIsNullOrderByIdAsc()
                .orElseGet(this::createDefaultGlobalThreshold);
        Threshold scopedRow = fileId == null ? null : thresholdRepository.findByFileId(fileId).orElse(null);

        Map<String, Object> globalPayload = buildThresholdPayload(globalRow);
        Map<String, Object> scopedPayload = scopedRow == null ? Map.of() : buildThresholdPayload(scopedRow);

        Map<String, Object> response = new HashMap<>(globalPayload);
        if (fileId != null) {
            response.putAll(scopedPayload);
        }

        response.put("id", fileId != null && scopedRow != null ? scopedRow.getId() : globalRow.getId());
        response.put("fileId", fileId != null ? fileId : globalRow.getFileId());
        return response;
    }

    @Transactional
    public Map<String, Object> updateSettings(Long fileId, Map<String, Object> body) {
        Map<String, Double> incomingThresholds = sanitizeThresholdMap(body);
        Map<String, String> incomingOperators = sanitizeOperatorMap(getMapValue(body, "thresholdOperators", "operators"));

        if (incomingThresholds.isEmpty() && incomingOperators.isEmpty()) {
            throw new IllegalArgumentException("No valid threshold values or operators provided.");
        }

        if (fileId != null && !uploadHistoryRepository.existsById(fileId)) {
            throw new NoSuchElementException("No upload found for fileId " + fileId + ".");
        }

        Threshold existingRow = fileId == null ? thresholdRepository.findFirstByFileIdIsNullOrderByIdAsc().orElseGet(this::createDefaultGlobalThreshold)
                : thresholdRepository.findByFileId(fileId).orElse(null);

        if (existingRow == null) {
            existingRow = new Threshold();
            existingRow.setFileId(fileId);
            existingRow.setCreatedAt(LocalDateTime.now());
        }

        existingRow.setUpdatedAt(LocalDateTime.now());

        if (!incomingThresholds.isEmpty()) {
            applyThresholdValues(existingRow, incomingThresholds);
        }

        if (!incomingOperators.isEmpty()) {
            Map<String, Object> mergedOperators = new LinkedHashMap<>();
            if (existingRow.getThresholdOperators() != null) {
                mergedOperators.putAll(existingRow.getThresholdOperators());
            }
            mergedOperators.putAll(incomingOperators);
            existingRow.setThresholdOperators(mergedOperators);
        }

        Threshold saved = thresholdRepository.save(existingRow);
        return buildThresholdPayload(saved);
    }

    public byte[] exportThresholdWorkbook(Map<String, Number> thresholds, Map<String, String> operators, String scopeFileId) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Thresholds");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Metric_Key");
            header.createCell(1).setCellValue("Threshold_Value");
            header.createCell(2).setCellValue("Operator");

            List<String> keys = new ArrayList<>(thresholds.keySet());
            keys.sort(String::compareTo);
            int rowIndex = 1;
            for (String key : keys) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(key);
                row.createCell(1).setCellValue(thresholds.get(key).doubleValue());
                row.createCell(2).setCellValue(operators.getOrDefault(key, getDefaultOperatorForMetric(key)));
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    public Map<String, Object> importThresholdWorkbook(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Threshold file is required.");
        }

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("Workbook does not contain any sheets.");
            }

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet.getPhysicalNumberOfRows() < 1) {
                throw new IllegalArgumentException("Workbook is empty.");
            }

            DataFormatter formatter = new DataFormatter();
            Row headerRow = sheet.getRow(0);
            Map<Integer, String> headerMap = new LinkedHashMap<>();
            for (int columnIndex = 0; columnIndex < headerRow.getLastCellNum(); columnIndex++) {
                String headerValue = normalizeColumnName(formatter.formatCellValue(headerRow.getCell(columnIndex)));
                if (!headerValue.isBlank()) {
                    headerMap.put(columnIndex, headerValue);
                }
            }

            Set<String> thresholdKeyColumns = Set.of("metric_key", "threshold_key", "metric", "kpi", "kpi_key", "key", "name");
            Set<String> thresholdValueColumns = Set.of("threshold_value", "threshold", "value", "target", "target_value");
            Set<String> operatorColumns = Set.of("operator", "threshold_operator", "comparison_operator", "condition");

            Map<String, Number> thresholds = new LinkedHashMap<>();
            Map<String, String> operators = new LinkedHashMap<>();
            int importedCount = 0;
            int totalRows = 0;

            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) continue;
                totalRows++;
                String key = null;
                Number thresholdValue = null;
                String operatorValue = null;

                for (Map.Entry<Integer, String> entry : headerMap.entrySet()) {
                    String columnName = entry.getValue();
                    String raw = formatter.formatCellValue(row.getCell(entry.getKey()));
                    if (raw == null || raw.isBlank()) {
                        continue;
                    }

                    if (thresholdKeyColumns.contains(columnName)) {
                        key = raw.trim();
                    } else if (thresholdValueColumns.contains(columnName)) {
                        try {
                            thresholdValue = Double.parseDouble(raw.trim());
                        } catch (NumberFormatException ignored) {
                        }
                    } else if (operatorColumns.contains(columnName)) {
                        operatorValue = raw.trim();
                    }
                }

                if (key == null || thresholdValue == null || !Double.isFinite(thresholdValue.doubleValue())) {
                    continue;
                }

                thresholds.put(key, thresholdValue);
                operators.put(key, operatorValue == null || operatorValue.isBlank() ? getDefaultOperatorForMetric(key) : operatorValue);
                importedCount++;
            }

            if (thresholds.isEmpty()) {
                throw new IllegalArgumentException("No valid threshold rows found. Keep columns like Metric_Key, Threshold_Value, Operator.");
            }

            return Map.<String, Object>of(
                    "thresholds", thresholds,
                    "operators", operators,
                    "totalRows", totalRows,
                    "importedCount", importedCount
            );
        }
    }

    private Threshold createDefaultGlobalThreshold() {
        Threshold threshold = new Threshold();
        threshold.setFileId(null);
        threshold.setCreatedAt(LocalDateTime.now());
        threshold.setUpdatedAt(LocalDateTime.now());
        return thresholdRepository.save(threshold);
    }

    private Map<String, Object> buildThresholdPayload(Threshold threshold) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", threshold.getId());
        payload.put("fileId", threshold.getFileId());

        KNOWN_THRESHOLD_FIELDS.forEach(field -> {
            Object value = getThresholdValue(threshold, field);
            if (value != null) {
                payload.put(field, value);
            }
        });

        if (threshold.getDynamicThresholds() != null) {
            payload.putAll(threshold.getDynamicThresholds());
        }

        payload.put("thresholdOperators", threshold.getThresholdOperators() == null ? Map.of() : threshold.getThresholdOperators());
        return payload;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMapValue(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            Object value = body.get(key);
            if (value instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        }
        return Map.of();
    }

    private Map<String, Double> sanitizeThresholdMap(Map<String, Object> payload) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String key = String.valueOf(entry.getKey()).trim();
            if (key.isBlank() || RESERVED_KEYS.contains(key)) {
                continue;
            }
            Double numeric = parseNumeric(entry.getValue());
            if (numeric == null) {
                continue;
            }
            result.put(key, numeric);
        }
        return result;
    }

    private Map<String, String> sanitizeOperatorMap(Map<String, Object> payload) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String key = String.valueOf(entry.getKey()).trim();
            if (key.isBlank()) {
                continue;
            }
            String operator = String.valueOf(entry.getValue()).trim();
            if (!operator.isBlank() && Set.of(">", ">=", "<", "<=").contains(operator)) {
                result.put(key, operator);
            }
        }
        return result;
    }

    private Double parseNumeric(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    private String getDefaultOperatorForMetric(String metricKey) {
        String normalized = normalizeColumnName(metricKey);
        return Set.of("erabdroprate", "rrcdroprate", "voltedcr", "minerabdroprate", "minrrcdroprate", "e_rab_drop_rate", "rrc_drop_rate", "volte_dcr_eric").contains(normalized)
                ? "<="
                : ">=";
    }

    private String normalizeColumnName(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private void applyThresholdValues(Threshold threshold, Map<String, Double> thresholds) {
        Map<String, Object> dynamic = threshold.getDynamicThresholds() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(threshold.getDynamicThresholds());
        for (Map.Entry<String, Double> entry : thresholds.entrySet()) {
            String key = entry.getKey();
            if (KNOWN_THRESHOLD_FIELDS.contains(key)) {
                setKnownThresholdValue(threshold, key, entry.getValue());
            } else {
                dynamic.put(key, entry.getValue());
            }
        }
        threshold.setDynamicThresholds(dynamic);
    }

    private void setKnownThresholdValue(Threshold threshold, String key, Double value) {
        switch (key) {
            case "UL_PRB_Utilization_Rate" -> threshold.setUL_PRB_Utilization_Rate(value);
            case "DL_PRB_Utilization_Rate" -> threshold.setDL_PRB_Utilization_Rate(value);
            case "E_RAB_Drop_Rate" -> threshold.setE_RAB_Drop_Rate(value);
            case "RRC_Drop_Rate" -> threshold.setRRC_Drop_Rate(value);
            case "Initial_ERAB_Establishment_Success_Rate" -> threshold.setInitial_ERAB_Establishment_Success_Rate(value);
            case "RRC_Establishment_Success_Rate" -> threshold.setRRC_Establishment_Success_Rate(value);
            case "E_RAB_Setup_Success_Rate" -> threshold.setE_RAB_Setup_Success_Rate(value);
            case "VOLTE_CSSR_Eric" -> threshold.setVOLTE_CSSR_Eric(value);
            case "VOLTE_DCR_Eric" -> threshold.setVOLTE_DCR_Eric(value);
            case "Inter_Freq_HOSR" -> threshold.setInter_Freq_HOSR(value);
            case "Intra_Freq_HOSR" -> threshold.setIntra_Freq_HOSR(value);
            case "CSFB_Success_Rate" -> threshold.setCSFB_Success_Rate(value);
            case "Max_RRC_Users" -> threshold.setMax_RRC_Users(value);
            default -> {
                Map<String, Object> dynamic = threshold.getDynamicThresholds() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(threshold.getDynamicThresholds());
                dynamic.put(key, value);
                threshold.setDynamicThresholds(dynamic);
            }
        }
    }

    private Object getThresholdValue(Threshold threshold, String key) {
        return switch (key) {
            case "UL_PRB_Utilization_Rate" -> threshold.getUL_PRB_Utilization_Rate();
            case "DL_PRB_Utilization_Rate" -> threshold.getDL_PRB_Utilization_Rate();
            case "E_RAB_Drop_Rate" -> threshold.getE_RAB_Drop_Rate();
            case "RRC_Drop_Rate" -> threshold.getRRC_Drop_Rate();
            case "Initial_ERAB_Establishment_Success_Rate" -> threshold.getInitial_ERAB_Establishment_Success_Rate();
            case "RRC_Establishment_Success_Rate" -> threshold.getRRC_Establishment_Success_Rate();
            case "E_RAB_Setup_Success_Rate" -> threshold.getE_RAB_Setup_Success_Rate();
            case "VOLTE_CSSR_Eric" -> threshold.getVOLTE_CSSR_Eric();
            case "VOLTE_DCR_Eric" -> threshold.getVOLTE_DCR_Eric();
            case "Inter_Freq_HOSR" -> threshold.getInter_Freq_HOSR();
            case "Intra_Freq_HOSR" -> threshold.getIntra_Freq_HOSR();
            case "CSFB_Success_Rate" -> threshold.getCSFB_Success_Rate();
            case "Max_RRC_Users" -> threshold.getMax_RRC_Users();
            default -> null;
        };
    }
}

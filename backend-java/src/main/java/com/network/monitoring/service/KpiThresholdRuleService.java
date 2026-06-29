package com.network.monitoring.service;

import com.network.monitoring.dto.request.KpiThresholdRuleRequest;
import com.network.monitoring.entity.KpiThresholdRule;
import com.network.monitoring.exception.ResourceNotFoundException;
import com.network.monitoring.repository.KpiThresholdRuleRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class KpiThresholdRuleService {

    private static final String LOWER_IS_BAD = "LOWER_IS_BAD";
    private static final String HIGHER_IS_BAD = "HIGHER_IS_BAD";

    private final KpiThresholdRuleRepository repository;
    private final JdbcTemplate jdbcTemplate;

    public KpiThresholdRuleService(KpiThresholdRuleRepository repository, JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void ensureTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `kpi_threshold_rules` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT,
                  `metricName` VARCHAR(191) NOT NULL,
                  `technology` VARCHAR(64) NULL,
                  `direction` VARCHAR(32) NOT NULL DEFAULT 'LOWER_IS_BAD',
                  `criticalValue` DOUBLE NULL,
                  `majorValue` DOUBLE NULL,
                  `minorValue` DOUBLE NULL,
                  `warningValue` DOUBLE NULL,
                  `enabled` BIT(1) NOT NULL DEFAULT b'1',
                  `createdAt` DATETIME NULL,
                  `updatedAt` DATETIME NULL,
                  PRIMARY KEY (`id`),
                  INDEX `idx_kpi_threshold_rules_metric` (`metricName`, `technology`, `enabled`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRules() {
        return repository.findAll().stream()
                .map(this::toPayload)
                .toList();
    }

    @Transactional
    public Map<String, Object> createRule(KpiThresholdRuleRequest request) {
        KpiThresholdRule rule = new KpiThresholdRule();
        applyRequest(rule, request, true);
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());
        return toPayload(repository.save(rule));
    }

    @Transactional
    public Map<String, Object> updateRule(Long id, KpiThresholdRuleRequest request) {
        KpiThresholdRule rule = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Threshold rule not found for id " + id + "."));
        applyRequest(rule, request, false);
        rule.setUpdatedAt(LocalDateTime.now());
        return toPayload(repository.save(rule));
    }

    @Transactional
    public void deleteRule(Long id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("Threshold rule not found for id " + id + ".");
        }
        repository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> evaluate(String metricName, Double value, String technology) {
        if (metricName == null || metricName.isBlank()) {
            throw new IllegalArgumentException("metricName is required.");
        }
        if (value == null) {
            throw new IllegalArgumentException("value is required.");
        }

        KpiThresholdRule rule = findBestRule(metricName, technology);
        String severity = rule == null ? "NORMAL" : calculateSeverity(rule, value);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("metricName", metricName);
        response.put("technology", technology);
        response.put("value", value);
        response.put("severity", severity);
        response.put("rule", rule == null ? null : toPayload(rule));
        return response;
    }

    public String calculateSeverity(KpiThresholdRule rule, Double value) {
        if (rule == null || value == null || !Boolean.TRUE.equals(rule.getEnabled())) {
            return "NORMAL";
        }
        boolean lowerIsBad = LOWER_IS_BAD.equals(rule.getDirection());
        if (matches(value, rule.getCriticalValue(), lowerIsBad)) return "CRITICAL";
        if (matches(value, rule.getMajorValue(), lowerIsBad)) return "MAJOR";
        if (matches(value, rule.getMinorValue(), lowerIsBad)) return "MINOR";
        if (matches(value, rule.getWarningValue(), lowerIsBad)) return "WARNING";
        return "NORMAL";
    }

    private KpiThresholdRule findBestRule(String metricName, String technology) {
        String metricKey = normalize(metricName);
        String techKey = normalize(technology);
        List<KpiThresholdRule> rules = repository.findAllByEnabledTrue();

        return rules.stream()
                .filter(rule -> normalize(rule.getMetricName()).equals(metricKey))
                .filter(rule -> !techKey.isBlank() && normalize(rule.getTechnology()).equals(techKey))
                .findFirst()
                .orElseGet(() -> rules.stream()
                        .filter(rule -> normalize(rule.getMetricName()).equals(metricKey))
                        .filter(rule -> normalize(rule.getTechnology()).isBlank())
                        .findFirst()
                        .orElse(null));
    }

    private void applyRequest(KpiThresholdRule rule, KpiThresholdRuleRequest request, boolean creating) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (creating || request.getMetricName() != null) {
            if (request.getMetricName() == null || request.getMetricName().isBlank()) {
                throw new IllegalArgumentException("metricName is required.");
            }
            rule.setMetricName(request.getMetricName().trim());
        }
        if (request.getTechnology() != null) {
            rule.setTechnology(request.getTechnology().isBlank() ? null : request.getTechnology().trim());
        }
        if (creating || request.getDirection() != null) {
            rule.setDirection(normalizeDirection(request.getDirection()));
        }
        if (request.getCriticalValue() != null) rule.setCriticalValue(request.getCriticalValue());
        if (request.getMajorValue() != null) rule.setMajorValue(request.getMajorValue());
        if (request.getMinorValue() != null) rule.setMinorValue(request.getMinorValue());
        if (request.getWarningValue() != null) rule.setWarningValue(request.getWarningValue());
        if (request.getEnabled() != null) rule.setEnabled(request.getEnabled());
        if (rule.getEnabled() == null) rule.setEnabled(true);
    }

    private String normalizeDirection(String direction) {
        if (direction == null || direction.isBlank()) {
            return LOWER_IS_BAD;
        }
        String normalized = direction.trim().toUpperCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
        if (!Objects.equals(normalized, LOWER_IS_BAD) && !Objects.equals(normalized, HIGHER_IS_BAD)) {
            throw new IllegalArgumentException("direction must be LOWER_IS_BAD or HIGHER_IS_BAD.");
        }
        return normalized;
    }

    private boolean matches(double value, Double threshold, boolean lowerIsBad) {
        if (threshold == null) {
            return false;
        }
        return lowerIsBad ? value <= threshold : value >= threshold;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private Map<String, Object> toPayload(KpiThresholdRule rule) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", rule.getId());
        payload.put("metricName", rule.getMetricName());
        payload.put("technology", rule.getTechnology());
        payload.put("direction", rule.getDirection());
        payload.put("criticalValue", rule.getCriticalValue());
        payload.put("majorValue", rule.getMajorValue());
        payload.put("minorValue", rule.getMinorValue());
        payload.put("warningValue", rule.getWarningValue());
        payload.put("enabled", rule.getEnabled());
        payload.put("createdAt", rule.getCreatedAt());
        payload.put("updatedAt", rule.getUpdatedAt());
        return payload;
    }
}

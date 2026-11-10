package com.network.monitoring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Conservative append policy: identical observations skip; changed observations reject the file. */
final class KpiObservationMerge {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final Set<String> ROOT_FIELDS = Set.of("date", "cellName", "site", "band", "tech", "sectorid", "sectorname", "groups");

    record Result(List<Map<String, Object>> all, List<Map<String, Object>> added, List<Integer> acceptedIndexes,
                  int duplicates, String firstDate, String lastDate) { }

    static List<String> validateFields(List<String> fields) {
        if (fields == null || !fields.contains("date") || !fields.contains("cellName")
                || fields.size() > 20 || fields.stream().distinct().count() != fields.size()) {
            throw new IllegalArgumentException("identityFields must explicitly include date and cellName, with no duplicates (maximum 20 fields).");
        }
        for (String field : fields) {
            if (field == null || (!ROOT_FIELDS.contains(field) && !field.matches("dimensions\\.[a-zA-Z0-9_]+"))) {
                throw new IllegalArgumentException("Observation identity must use canonical dimension fields, not KPI values.");
            }
        }
        return fields.stream().sorted().toList();
    }

    static Result merge(List<Map<String, Object>> existing, List<Map<String, Object>> incoming, List<String> fields) {
        validateFields(fields);
        Map<String, String> known = new LinkedHashMap<>();
        for (Map<String, Object> record : existing) {
            String key = identity(record, fields);
            String signature = json(normalize(record));
            String previous = known.putIfAbsent(key, signature);
            if (previous != null && !previous.equals(signature)) {
                throw new IllegalArgumentException("Existing data has conflicting observations for this identityFields policy. Review the dataset grain first.");
            }
        }
        List<Map<String, Object>> all = new ArrayList<>(existing);
        List<Map<String, Object>> added = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        int duplicates = 0;
        String first = null, last = null;
        for (int index = 0; index < incoming.size(); index++) {
            Map<String, Object> record = incoming.get(index);
            if (!(record.get("metrics") instanceof Map<?, ?> metrics) || metrics.isEmpty()) {
                throw new IllegalArgumentException("No numeric KPI values at data row " + (index + 1));
            }
            String date;
            try { date = LocalDate.parse(String.valueOf(record.get("date"))).toString(); }
            catch (java.time.DateTimeException ex) { throw new IllegalArgumentException("Invalid data date at row " + (index + 1)); }
            first = first == null || date.compareTo(first) < 0 ? date : first;
            last = last == null || date.compareTo(last) > 0 ? date : last;
            String key = identity(record, fields);
            String signature = json(normalize(record));
            String previous = known.putIfAbsent(key, signature);
            if (previous != null) {
                if (!previous.equals(signature)) throw new IllegalArgumentException(
                        "Changed values for an existing observation at data row " + (index + 1) + ". Corrections require review; nothing was appended.");
                duplicates++;
            } else {
                all.add(record);
                added.add(record);
                indexes.add(index);
            }
        }
        return new Result(all, added, indexes, duplicates, first, last);
    }

    private static String identity(Map<String, Object> record, List<String> fields) {
        List<String> values = new ArrayList<>();
        for (String field : fields) {
            Object value = field.startsWith("dimensions.") && record.get("dimensions") instanceof Map<?, ?> dimensions
                    ? dimensions.get(field.substring(11)) : record.get(field);
            if (value == null || value.toString().isBlank()) throw new IllegalArgumentException("Missing observation identity field: " + field);
            values.add(value.toString().trim());
        }
        return json(values);
    }

    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, item) -> normalized.put(key.toString(), normalize(item)));
            return normalized;
        }
        if (value instanceof List<?> list) return list.stream().map(KpiObservationMerge::normalize).toList();
        if (value instanceof Number number) {
            if (!Double.isFinite(number.doubleValue())) throw new IllegalArgumentException("Non-finite KPI value.");
            return new BigDecimal(number.toString()).stripTrailingZeros();
        }
        return value instanceof String text ? text.trim() : value;
    }

    static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalArgumentException("Invalid ingestion settings or observation.", ex); }
    }
}

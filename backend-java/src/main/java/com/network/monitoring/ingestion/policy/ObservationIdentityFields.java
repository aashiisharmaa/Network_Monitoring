package com.network.monitoring.ingestion.policy;

import java.util.List;
import java.util.Set;

/** Canonical identity-field contract shared by manual and scheduled ingestion. */
public final class ObservationIdentityFields {
    private ObservationIdentityFields() {}
    private static final Set<String> ROOT_FIELDS = Set.of("date", "cellName", "site", "band", "tech", "sectorid", "sectorname", "groups");
    public static List<String> validate(List<String> fields) {
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

}

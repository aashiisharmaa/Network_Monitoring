package com.network.monitoring.alarm.ingestion;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.monitoring.alarm.dto.AlarmImport.AlarmRow;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.network.monitoring.alarm.dto.AlarmModels.*;
import static com.network.monitoring.alarm.exception.AlarmConfigurationException.invalid;
import static com.network.monitoring.alarm.support.AlarmValues.*;
/** Snapshot mapping/identity policy; no database, filesystem or delivery side effects. */
@Component
public class AlarmObservationNormalizer {
    private final ObjectMapper json;
    public AlarmObservationNormalizer(ObjectMapper json) { this.json=json; }
    public LinkedHashMap<String, Observation> normalize(Frozen config,Instant asOf,List<AlarmRow> rows) {
            LinkedHashMap<String, Observation> observations = new LinkedHashMap<>();
            Map<String, Site> siteLookup = new HashMap<>();
            config.sites().forEach(site -> siteLookup.put(site.objectKey(), site));
            Map<String, Catalog> ruleLookup = new HashMap<>();
            config.catalog().forEach(rule -> ruleLookup.put(ruleKey(rule.oem(), rule.technology(), rule.matchField(), rule.matchValue()), rule));
            for (var row : rows) {
                if (!row.technology().equals(config.feed().technology()) || (!config.feed().oem().equals("MIXED") && !row.oem().equals(config.feed().oem())))
                    throw invalid("Row " + row.sourceRow() + ": OEM/technology conflicts with the feed profile.");
                if (row.raisedAt().isAfter(asOf)) throw invalid("Row " + row.sourceRow() + ": raised time is after the snapshot time.");
                if (row.resource().length() > 512 || row.alarmCode().length() > 255 || row.sourceSeverity().length() > 128)
                    throw invalid("Row " + row.sourceRow() + ": source identity fields exceed the supported storage budget.");
                Scope scope = config.circles().get(row.circle().trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " "));
                if (scope == null || (config.feed().circleId() != null && config.feed().circleId() != scope.circleId()))
                    throw invalid("Row " + row.sourceRow() + ": circle is unmapped, inactive or outside the feed scope.");
                List<Catalog> rules = new ArrayList<>(2);
                Catalog codeRule = ruleLookup.get(ruleKey(row.oem(), row.technology(), "CODE", row.alarmCode()));
                Catalog textRule = ruleLookup.get(ruleKey(row.oem(), row.technology(), "TEXT", row.alarmText()));
                if (codeRule != null) rules.add(codeRule); if (textRule != null) rules.add(textRule);
                if (rules.size() > 1) throw invalid("Row " + row.sourceRow() + ": multiple catalogue rules match; resolve the ambiguity.");
                Site mappedSite = siteLookup.get(row.resource());
                String site = null;
                if (mappedSite != null) {
                    if (mappedSite.circleId() != scope.circleId()) throw invalid("Row " + row.sourceRow() + ": site mapping conflicts with source circle.");
                    site = mappedSite.siteCode();
                }
                String key = digest(encode(List.of(row.oem(), row.technology(), row.resource(), row.alarmCode().isEmpty() ? row.alarmText() : row.alarmCode(), row.raisedAt().toString())).getBytes(StandardCharsets.UTF_8));
                var observation = new Observation(key, scope, site, row, rules.isEmpty() ? null : rules.get(0));
                var previous = observations.putIfAbsent(key, observation);
                if (previous != null && (!previous.row().alarmText().equals(row.alarmText()) || !previous.row().sourceSeverity().equals(row.sourceSeverity())
                        || !previous.row().name().equals(row.name()) || !previous.row().supplementaryInformation().equals(row.supplementaryInformation()) || previous.scope().circleId() != scope.circleId()))
                    throw invalid("Row " + row.sourceRow() + ": conflicting records share the same provisional alarm identity.");
            }
        return observations;
    }
    private static String ruleKey(String oem, String rat, String field, String value) { return oem + '\0' + rat + '\0' + field + '\0' + value; }
    private String encode(Object value) {
        try {return json.writeValueAsString(value);}
        catch(Exception error) {throw new IllegalStateException("Cannot serialize alarm record",error);}
    }
}

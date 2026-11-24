package com.network.monitoring.alarm.repository;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only schema/configuration probes, safe before optional migrations are installed. */
@Repository
public class AlarmReadinessRepository {
    private final JdbcTemplate jdbc;
    public AlarmReadinessRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public Map<String,Object> inspect(long company) {
        Set<String> tables=new HashSet<>(jdbc.queryForList("SELECT LOWER(table_name) FROM information_schema.tables WHERE table_schema=DATABASE()",String.class));
        boolean master=tables.containsAll(List.of("alarm_zones","alarm_circles","alarm_circle_aliases","alarm_source_feed_profiles"));
        boolean workflow=master&&tables.containsAll(List.of("alarm_catalog_rules","alarm_site_objects","alarm_ingestion_runs","alarm_observations","alarm_automation_settings","alarm_delivery_outbox","alarm_operator_events","alarm_escalation_decisions"));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("schema",Map.of("008",master,"009",workflow,"010",tables.contains("alarm_operational_snapshots")));
        result.put("activeFeeds",tables.contains("alarm_source_feed_profiles")?count("alarm_source_feed_profiles",company," AND active=TRUE"):null);
        result.put("catalogueRules",tables.contains("alarm_catalog_rules")?count("alarm_catalog_rules",company,""):null);
        result.put("mappedSiteObjects",tables.contains("alarm_site_objects")?count("alarm_site_objects",company,""):null);
        return result;
    }
    private int count(String table,long company,String filter) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE company_id=?"+filter,Integer.class,company);
    }
}

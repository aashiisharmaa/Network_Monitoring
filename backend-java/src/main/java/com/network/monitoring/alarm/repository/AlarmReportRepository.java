package com.network.monitoring.alarm.repository;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Tenant-scoped report reads. Summary queries omit large raw JSON and source text. */
@Repository
public class AlarmReportRepository {
    private final JdbcTemplate jdbc;
    public AlarmReportRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public Optional<Map<String,Object>> source(long company,long run) {
        return jdbc.queryForList("SELECT id,feed_id,snapshot_at,status,result_json FROM alarm_ingestion_runs WHERE company_id=? AND id=?",company,run).stream().findFirst();
    }
    public List<Map<String,Object>> observations(long company,long run,Long circle,boolean summaryOnly) {
        return jdbc.queryForList("SELECT "+fields(summaryOnly)+" FROM alarm_observations WHERE company_id=? AND run_id=?"
                +(circle==null?"":" AND circle_id=?")+" ORDER BY circle_name,classification,resource_name LIMIT 5001",args(company,run,circle));
    }
    /** Bounded cohort reads avoid two database round trips per source feed. */
    public List<Map<String,Object>> sources(long company,List<Long> runs) {
        if(runs.isEmpty()) return List.of();
        return jdbc.queryForList("SELECT id,feed_id,snapshot_at,status FROM alarm_ingestion_runs WHERE company_id=? AND id IN ("
                +placeholders(runs)+")",cohortArgs(company,runs,null));
    }
    public List<Map<String,Object>> observations(long company,List<Long> runs,Long circle,boolean summaryOnly) {
        if(runs.isEmpty()) return List.of();
        return jdbc.queryForList("SELECT run_id,"+fields(summaryOnly)+" FROM alarm_observations WHERE company_id=? AND run_id IN ("
                +placeholders(runs)+")"+(circle==null?"":" AND circle_id=?")
                +" ORDER BY "+(summaryOnly?"run_id":"run_id,circle_name,classification,resource_name")+" LIMIT 50001",cohortArgs(company,runs,circle));
    }
    private static String fields(boolean summaryOnly) {
        String fields="circle_id,circle_name,zone_name,site_code,technology,classification,included,service_affecting,site_down,raised_at";
        if(!summaryOnly) fields+=" ,id,identity_key,oem,resource_name,alarm_code,alarm_text,source_severity,"
                +"JSON_UNQUOTE(JSON_EXTRACT(row_json,'$.supplementaryInformation')) supplementary_information,"
                +"JSON_UNQUOTE(JSON_EXTRACT(row_json,'$.sourceAnnotations.remarks')) source_remarks,"
                +"JSON_UNQUOTE(JSON_EXTRACT(row_json,'$.sourceAnnotations.l2Remarks')) l2_remarks,"
                +"JSON_UNQUOTE(JSON_EXTRACT(row_json,'$.sourceAnnotations.circleUpdate')) circle_update";
        return fields;
    }
    private static String placeholders(List<Long> runs) { return String.join(",",Collections.nCopies(runs.size(),"?")); }
    private static Object[] cohortArgs(long company,List<Long> runs,Long circle) {
        var args=new ArrayList<Object>();args.add(company);args.addAll(runs);if(circle!=null)args.add(circle);return args.toArray();
    }
    public int count(long company,long run,Long circle) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM alarm_observations WHERE company_id=? AND run_id=?"
                +(circle==null?"":" AND circle_id=?"),Integer.class,args(company,run,circle));
    }
    public boolean circleExists(long company,long circle) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM alarm_circles WHERE company_id=? AND id=?",Integer.class,company,circle)==1;
    }
    private Object[] args(long company,long run,Long circle) {
        return circle==null?new Object[]{company,run}:new Object[]{company,run,circle};
    }
}

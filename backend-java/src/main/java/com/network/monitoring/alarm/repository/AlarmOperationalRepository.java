package com.network.monitoring.alarm.repository;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** Every read/write is tenant-scoped; revisions are inserted, never overwritten. */
@Repository
public class AlarmOperationalRepository {
    private final JdbcTemplate jdbc;
    public AlarmOperationalRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public Optional<Map<String,Object>> latest(long company,LocalDateTime at) {
        return jdbc.queryForList("SELECT * FROM alarm_operational_snapshots WHERE company_id=? AND effective_at<=? ORDER BY effective_at DESC,revision DESC LIMIT 1",company,at).stream().findFirst();
    }
    public Optional<Map<String,Object>> slot(long company,LocalDateTime at) {
        return jdbc.queryForList("SELECT id,revision FROM alarm_operational_snapshots WHERE company_id=? AND effective_at=? ORDER BY revision DESC LIMIT 1",company,at).stream().findFirst();
    }
    public Optional<Map<String,Object>> find(long company,long id) {
        return jdbc.queryForList("SELECT * FROM alarm_operational_snapshots WHERE company_id=? AND id=?",company,id).stream().findFirst();
    }
    public List<Map<String,Object>> history(long company,int limit) {
        return jdbc.queryForList("SELECT id,effective_at,revision,supersedes_id,based_on_id,source_reference,input_sha256,created_by,created_at FROM alarm_operational_snapshots WHERE company_id=? ORDER BY effective_at DESC,revision DESC LIMIT ?",company,limit);
    }
    public long insert(long company,LocalDateTime at,int revision,Long previous,Long base,String reference,String json,String checksum,long actor) {
        var keys=new GeneratedKeyHolder();
        jdbc.update(connection->{
            var statement=connection.prepareStatement("INSERT INTO alarm_operational_snapshots(company_id,effective_at,revision,supersedes_id,based_on_id,schema_version,source_reference,input_json,input_sha256,created_by) VALUES(?,?,?,?,?,'alarm-operational-v1',?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
            Object[] values={company,at,revision,previous,base,reference,json,checksum,actor};
            for(int i=0;i<values.length;i++) statement.setObject(i+1,values[i]);
            return statement;
        },keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
}

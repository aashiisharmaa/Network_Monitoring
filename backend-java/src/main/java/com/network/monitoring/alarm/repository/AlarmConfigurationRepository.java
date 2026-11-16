package com.network.monitoring.alarm.repository;

import com.network.monitoring.alarm.exception.AlarmConfigurationException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import static com.network.monitoring.alarm.dto.AlarmConfiguration.*;

@Repository
public class AlarmConfigurationRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<Zone> ZONE = (r, n) -> new Zone(r.getLong("id"), r.getLong("company_id"),
            r.getString("code"), r.getString("name"), r.getBoolean("active"), r.getInt("version"));
    private static final RowMapper<Circle> CIRCLE = (r, n) -> new Circle(r.getLong("id"), r.getLong("company_id"),
            r.getLong("zone_id"), r.getString("code"), r.getString("name"), r.getBoolean("active"), r.getInt("version"));
    private static final RowMapper<Alias> ALIAS = (r, n) -> new Alias(r.getLong("id"), r.getLong("company_id"),
            r.getLong("circle_id"), r.getString("alias_key"), r.getBoolean("canonical"));
    private static final RowMapper<Feed> FEED = (r, n) -> new Feed(r.getLong("id"), r.getLong("company_id"),
            r.getString("source_key"), r.getString("name"), r.getString("oem"), r.getString("technology"),
            r.getString("file_format"), r.getString("sheet_name"), r.getString("source_timezone"),
            r.getString("dump_type"), r.getObject("circle_id", Long.class), r.getBoolean("active"), r.getInt("version"));

    public AlarmConfigurationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Serializes namespace changes for a tenant. Called only inside a service transaction.
    public void lockCompany(long company) {
        if (jdbc.queryForList("SELECT id FROM companies WHERE id=? FOR UPDATE", Long.class, company).isEmpty())
            throw AlarmConfigurationException.missing();
    }
    public List<Zone> zones(long company, long after, int limit) {
        return jdbc.query("SELECT * FROM alarm_zones WHERE company_id=? AND id>? ORDER BY id LIMIT ?", ZONE, company, after, limit);
    }
    public Optional<Zone> zone(long company, long id) {
        return jdbc.query("SELECT * FROM alarm_zones WHERE company_id=? AND id=?", ZONE, company, id).stream().findFirst();
    }
    public long createZone(long company, String code, String name, long actor) {
        return insert("INSERT INTO alarm_zones(company_id,code,name,created_by,updated_by) VALUES (?,?,?,?,?)", company, code, name, actor, actor);
    }
    public int updateZone(long company, long id, String name, boolean active, int version, long actor) {
        return jdbc.update("UPDATE alarm_zones SET name=?,active=?,version=version+1,updated_by=?,updated_at=CURRENT_TIMESTAMP(6) WHERE company_id=? AND id=? AND version=?",
                name, active, actor, company, id, version);
    }
    public List<Circle> circles(long company, long after, int limit) {
        return jdbc.query("SELECT * FROM alarm_circles WHERE company_id=? AND id>? ORDER BY id LIMIT ?", CIRCLE, company, after, limit);
    }
    public Optional<Circle> circle(long company, long id) {
        return jdbc.query("SELECT * FROM alarm_circles WHERE company_id=? AND id=?", CIRCLE, company, id).stream().findFirst();
    }
    public long createCircle(long company, long zone, String code, String name, long actor) {
        return insert("INSERT INTO alarm_circles(company_id,zone_id,code,name,created_by,updated_by) VALUES (?,?,?,?,?,?)", company, zone, code, name, actor, actor);
    }
    public int updateCircle(long company, long id, CircleUpdate request, String name, long actor) {
        return jdbc.update("UPDATE alarm_circles SET zone_id=?,name=?,active=?,version=version+1,updated_by=?,updated_at=CURRENT_TIMESTAMP(6) WHERE company_id=? AND id=? AND version=?",
                request.zoneId(), name, request.active(), actor, company, id, request.version());
    }
    public List<Alias> aliases(long company, long after, int limit) {
        return jdbc.query("SELECT * FROM alarm_circle_aliases WHERE company_id=? AND id>? ORDER BY id LIMIT ?", ALIAS, company, after, limit);
    }
    public Optional<Alias> alias(long company, String key) {
        return jdbc.query("SELECT * FROM alarm_circle_aliases WHERE company_id=? AND alias_key=?", ALIAS, company, key).stream().findFirst();
    }
    public Optional<Alias> aliasById(long company, long id) {
        return jdbc.query("SELECT * FROM alarm_circle_aliases WHERE company_id=? AND id=?", ALIAS, company, id).stream().findFirst();
    }
    public long createAlias(long company, long circle, String alias, boolean canonical, long actor) {
        return insert("INSERT INTO alarm_circle_aliases(company_id,circle_id,alias_key,canonical,created_by) VALUES (?,?,?,?,?)", company, circle, alias, canonical, actor);
    }
    public void deleteAlias(long company, long id) {
        jdbc.update("DELETE FROM alarm_circle_aliases WHERE company_id=? AND id=? AND canonical=FALSE", company, id);
    }
    public List<Feed> feeds(long company, long after, int limit) {
        return jdbc.query("SELECT * FROM alarm_source_feed_profiles WHERE company_id=? AND id>? ORDER BY id LIMIT ?", FEED, company, after, limit);
    }
    public Optional<Feed> feed(long company, long id) {
        return jdbc.query("SELECT * FROM alarm_source_feed_profiles WHERE company_id=? AND id=?", FEED, company, id).stream().findFirst();
    }
    public long createFeed(long company, String key, FeedUpdate data, long actor) {
        return insert("INSERT INTO alarm_source_feed_profiles(company_id,source_key,name,oem,technology,file_format,sheet_name,source_timezone,dump_type,circle_id,created_by,updated_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                company, key, data.name(), data.oem(), data.technology(), data.fileFormat(), data.sheet(), data.sourceTimezone(), data.dumpType(), data.circleId(), actor, actor);
    }
    public int updateFeed(long company, long id, FeedUpdate data, long actor) {
        return jdbc.update("UPDATE alarm_source_feed_profiles SET name=?,oem=?,technology=?,file_format=?,sheet_name=?,source_timezone=?,dump_type=?,circle_id=?,active=?,version=version+1,updated_by=?,updated_at=CURRENT_TIMESTAMP(6) WHERE company_id=? AND id=? AND version=?",
                data.name(), data.oem(), data.technology(), data.fileFormat(), data.sheet(), data.sourceTimezone(), data.dumpType(), data.circleId(), data.active(), actor, company, id, data.version());
    }
    private long insert(String sql, Object... values) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, keys);
        Number id = keys.getKey();
        if (id == null) throw new IllegalStateException("Database did not return a configuration ID.");
        return id.longValue();
    }
}

package com.network.monitoring.alarm.repository;

import com.network.monitoring.alarm.dto.AlarmModels.Catalog;
import com.network.monitoring.alarm.dto.AlarmModels.Site;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import static com.network.monitoring.alarm.exception.AlarmConfigurationException.invalid;
import static com.network.monitoring.alarm.support.AlarmValues.*;

/** Tenant-scoped persistence; does not depend on controllers or service implementations. */
@Repository
public class AlarmDefinitionRepository {
    private final JdbcTemplate jdbc;
    public AlarmDefinitionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Catalog> catalogs(long company) {
        var rows = jdbc.queryForList("SELECT * FROM alarm_catalog_rules WHERE company_id=? ORDER BY id LIMIT 1001", company);
        if (rows.size() > 1000) throw invalid("Configuration exceeds the supported per-feed processing budget.");
        return rows.stream().map(r -> new Catalog(number(r, "id"), string(r, "oem"), string(r, "technology"),
                string(r, "match_field"), string(r, "match_value"), string(r, "classification"), bool(r, "included"),
                bool(r, "service_affecting"), bool(r, "site_down"))).toList();
    }

    public long insertCatalog(long company, Catalog rule, long actor) {
        return insert("INSERT INTO alarm_catalog_rules(company_id,oem,technology,match_field,match_value,classification,included,service_affecting,site_down,created_by) VALUES (?,?,?,?,?,?,?,?,?,?)",
                company, rule.oem(), rule.technology(), rule.matchField(), rule.matchValue(), rule.classification(),
                rule.included(), rule.serviceAffecting(), rule.siteDown(), actor);
    }

    public int deleteCatalog(long company, long id) {
        return jdbc.update("DELETE FROM alarm_catalog_rules WHERE company_id=? AND id=?", company, id);
    }

    public List<Site> sites(long company, long feed) {
        var rows = jdbc.queryForList("SELECT * FROM alarm_site_objects WHERE company_id=? AND feed_id=? ORDER BY id LIMIT 20001", company, feed);
        if (rows.size() > 20000) throw invalid("Site mapping limit exceeded.");
        return rows.stream().map(r -> new Site(number(r, "id"), number(r, "circle_id"), string(r, "object_key"), string(r, "site_code"))).toList();
    }

    public long insertSite(long company, long feed, Site site, long actor) {
        return insert("INSERT INTO alarm_site_objects(company_id,feed_id,circle_id,object_key,site_code,created_by) VALUES (?,?,?,?,?,?)",
                company, feed, site.circleId(), site.objectKey(), site.siteCode(), actor);
    }

    public int deleteSite(long company, long feed, long id) {
        return jdbc.update("DELETE FROM alarm_site_objects WHERE company_id=? AND feed_id=? AND id=?", company, feed, id);
    }

    private long insert(String sql, Object... values) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
}

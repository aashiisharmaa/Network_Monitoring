package com.network.monitoring.alarm.service;

import com.network.monitoring.alarm.exception.AlarmConfigurationException;
import com.network.monitoring.alarm.repository.AlarmConfigurationRepository;
import com.network.monitoring.alarm.security.AlarmTenantAccess;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.network.monitoring.alarm.dto.AlarmConfiguration.*;
import static com.network.monitoring.alarm.exception.AlarmConfigurationException.*;

@Service
@Transactional(readOnly = true)
public class AlarmConfigurationService {
    private final AlarmTenantAccess access;
    private final AlarmConfigurationRepository repository;

    public AlarmConfigurationService(AlarmTenantAccess access, AlarmConfigurationRepository repository) {
        this.access = access;
        this.repository = repository;
    }

    public List<Zone> zones(long company, long after, int limit) {
        access.require(company, false); page(after, limit); return repository.zones(company, after, limit);
    }
    public List<Circle> circles(long company, long after, int limit) {
        access.require(company, false); page(after, limit); return repository.circles(company, after, limit);
    }
    public List<Alias> aliases(long company, long after, int limit) {
        access.require(company, false); page(after, limit); return repository.aliases(company, after, limit);
    }
    public List<Feed> feeds(long company, long after, int limit) {
        access.require(company, false); page(after, limit); return repository.feeds(company, after, limit);
    }
    public Feed feed(long company, long id) {
        access.require(company, false); return repository.feed(company, id).orElseThrow(AlarmConfigurationException::missing);
    }

    @Transactional
    public Zone createZone(long company, ZoneCreate request) {
        long actor = write(company);
        long id = repository.createZone(company, identifier(request.code()), text(request.name(), 160, "Zone name"), actor);
        return repository.zone(company, id).orElseThrow(AlarmConfigurationException::missing);
    }
    @Transactional
    public Zone updateZone(long company, long id, ZoneUpdate request) {
        long actor = write(company); repository.zone(company, id).orElseThrow(AlarmConfigurationException::missing);
        version(request.version());
        updated(repository.updateZone(company, id, text(request.name(), 160, "Zone name"), request.active(), request.version(), actor));
        return repository.zone(company, id).orElseThrow(AlarmConfigurationException::missing);
    }
    @Transactional
    public Circle createCircle(long company, CircleCreate request) {
        long actor = write(company); activeZone(company, request.zoneId());
        String code = identifier(request.code());
        if (repository.alias(company, code).isPresent()) throw conflict("Circle code is already used by a circle or alias.");
        long id = repository.createCircle(company, request.zoneId(), code, text(request.name(), 160, "Circle name"), actor);
        repository.createAlias(company, id, code, true, actor);
        return repository.circle(company, id).orElseThrow(AlarmConfigurationException::missing);
    }
    @Transactional
    public Circle updateCircle(long company, long id, CircleUpdate request) {
        long actor = write(company); repository.circle(company, id).orElseThrow(AlarmConfigurationException::missing);
        if (request.active()) activeZone(company, request.zoneId());
        else repository.zone(company, request.zoneId()).orElseThrow(AlarmConfigurationException::missing);
        version(request.version());
        updated(repository.updateCircle(company, id, request, text(request.name(), 160, "Circle name"), actor));
        return repository.circle(company, id).orElseThrow(AlarmConfigurationException::missing);
    }
    @Transactional
    public Alias createAlias(long company, AliasCreate request) {
        long actor = write(company); activeCircle(company, request.circleId());
        String alias = aliasKey(request.alias());
        if (repository.alias(company, alias).isPresent()) throw conflict("Alias is already mapped. Remove the existing noncanonical alias before remapping.");
        long id = repository.createAlias(company, request.circleId(), alias, false, actor);
        return repository.aliasById(company, id).orElseThrow(AlarmConfigurationException::missing);
    }
    @Transactional
    public void deleteAlias(long company, long id) {
        write(company);
        var alias = repository.aliasById(company, id).orElseThrow(AlarmConfigurationException::missing);
        if (alias.canonical()) throw conflict("A canonical circle code cannot be removed.");
        repository.deleteAlias(company, id);
    }

    public Resolution resolveCircle(long company, String raw) {
        access.require(company, false);
        return resolve(company, raw);
    }
    private Resolution resolve(long company, String raw) {
        String key = aliasKey(raw);
        var alias = repository.alias(company, key);
        if (alias.isEmpty()) return new Resolution(raw, "UNMAPPED", null);
        var circle = repository.circle(company, alias.get().circleId()).orElseThrow(AlarmConfigurationException::missing);
        var zone = repository.zone(company, circle.zoneId()).orElseThrow(AlarmConfigurationException::missing);
        if (!circle.active() || !zone.active()) return new Resolution(raw, "INACTIVE", null);
        return new Resolution(raw, "MAPPED", circle);
    }

    @Transactional
    public Feed createFeed(long company, FeedCreate request) {
        long actor = write(company);
        var data = normalize(new FeedUpdate(request.name(), request.oem(), request.technology(), request.fileFormat(),
                request.sheet(), request.sourceTimezone(), request.dumpType(), request.circleId(), false, 1));
        if (data.circleId() != null) activeCircle(company, data.circleId());
        long id = repository.createFeed(company, identifier(request.sourceKey()), data, actor);
        return repository.feed(company, id).orElseThrow(AlarmConfigurationException::missing);
    }
    @Transactional
    public Feed updateFeed(long company, long id, FeedUpdate request) {
        long actor = write(company); repository.feed(company, id).orElseThrow(AlarmConfigurationException::missing);
        var data = normalize(request);
        if (data.circleId() != null) {
            if (data.active()) activeCircle(company, data.circleId());
            else repository.circle(company, data.circleId()).orElseThrow(AlarmConfigurationException::missing);
        }
        updated(repository.updateFeed(company, id, data, actor));
        return repository.feed(company, id).orElseThrow(AlarmConfigurationException::missing);
    }

    private long write(long company) {
        long actor = access.require(company, true);
        repository.lockCompany(company);
        return actor;
    }
    private Zone activeZone(long company, long id) {
        var zone = repository.zone(company, id).orElseThrow(AlarmConfigurationException::missing);
        if (!zone.active()) throw conflict("Parent zone is inactive.");
        return zone;
    }
    private Circle activeCircle(long company, long id) {
        var circle = repository.circle(company, id).orElseThrow(AlarmConfigurationException::missing);
        if (!circle.active()) throw conflict("Circle is inactive.");
        activeZone(company, circle.zoneId());
        return circle;
    }
    private static FeedUpdate normalize(FeedUpdate request) {
        String oem = upper(request.oem());
        if (oem.equals("E///")) oem = "ERICSSON";
        if (!Set.of("NOKIA", "ERICSSON", "ZTE", "HUAWEI", "SAMSUNG", "MIXED").contains(oem)) throw invalid("Unsupported OEM; use a supported vendor or MIXED.");
        String rat = upper(request.technology()), format = upper(request.fileFormat()), type = upper(request.dumpType());
        if (!Set.of("2G", "4G").contains(rat)) throw invalid("Technology must be 2G or 4G.");
        if (!Set.of("CSV", "XLSX").contains(format)) throw invalid("File format must be CSV or XLSX.");
        if (!Set.of("SNAPSHOT", "DELTA").contains(type)) throw invalid("Dump type must explicitly be SNAPSHOT or DELTA.");
        String sheet = request.sheet() == null || request.sheet().isBlank() ? null : request.sheet();
        if (format.equals("XLSX") && (sheet == null || sheet.length() > 31 || sheet.matches(".*[\\\\/?*\\[\\]:].*")
                || sheet.startsWith("'") || sheet.endsWith("'") || sheet.chars().anyMatch(Character::isISOControl)))
            throw invalid("XLSX requires an exact valid sheet name of at most 31 characters.");
        if (format.equals("CSV") && sheet != null) throw invalid("CSV cannot specify a worksheet.");
        String timezone = text(request.sourceTimezone(), 64, "Source timezone");
        try { timezone = ZoneId.of(timezone).getId(); }
        catch (DateTimeException e) { throw invalid("Source timezone must be an explicit valid ZoneId."); }
        if (request.circleId() != null && request.circleId() <= 0) throw invalid("Circle ID must be positive.");
        version(request.version());
        return new FeedUpdate(text(request.name(), 160, "Feed name"), oem, rat, format, sheet, timezone,
                type, request.circleId(), request.active(), request.version());
    }
    private static String identifier(String input) {
        ascii(input);
        String key = upper(input);
        if (!key.matches("[A-Z0-9][A-Z0-9_.-]{0,63}")) throw invalid("Code/source key must contain 1–64 ASCII letters, digits, dots, underscores or hyphens.");
        return key;
    }
    private static String aliasKey(String input) {
        ascii(input);
        String key = upper(input).replaceAll("\\s+", " ");
        if (!key.matches("[A-Z0-9][A-Z0-9_. -]{0,63}")) throw invalid("Circle alias must contain 1–64 ASCII letters, digits, spaces, dots, underscores or hyphens.");
        return key;
    }
    private static String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static void ascii(String input) {
        if (input != null && input.chars().anyMatch(c -> c > 127)) throw invalid("Codes and aliases must use explicit ASCII identifiers.");
    }
    private static String text(String input, int max, String field) {
        if (input == null || input.isBlank() || input.trim().length() > max || input.chars().anyMatch(Character::isISOControl))
            throw invalid(field + " must contain 1–" + max + " characters without control characters.");
        return input.trim();
    }
    private static void page(long after, int limit) { if (after < 0 || limit < 1 || limit > 200) throw invalid("Use afterId >= 0 and limit between 1 and 200."); }
    private static void version(int value) { if (value < 1) throw invalid("Current version is required for updates."); }
    private static void updated(int count) { if (count != 1) throw conflict("Configuration changed. Reload its current version before saving."); }
}

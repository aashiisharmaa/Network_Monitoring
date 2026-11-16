package com.network.monitoring.alarm.support;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.network.monitoring.alarm.exception.AlarmConfigurationException.*;

/** Shared value conversions; no service or database dependency. */
public final class AlarmValues {
    private AlarmValues() {}
    public static Instant instant(String value) { try { return OffsetDateTime.parse(value).toInstant(); } catch (Exception e) { throw invalid("An explicit ISO timestamp with offset is required."); } }
    public static Instant jdbcInstant(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
        if (value instanceof LocalDateTime date) return date.toInstant(ZoneOffset.UTC);
        throw new IllegalStateException("Unexpected database datetime type");
    }
    public static LocalDateTime timestamp(Instant instant) { return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC); }
    public static String digest(byte[] data) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); } catch (Exception e) { throw new IllegalStateException(e); } }
    public static long number(Map<String, Object> row, String field) { return ((Number) row.get(field)).longValue(); }
    public static String string(Map<String, Object> row, String field) { return Objects.toString(row.get(field), ""); }
    public static boolean bool(Map<String, Object> row, String field) { Object value = row.get(field); return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() != 0; }
    public static String required(String value, int length) { if (value == null || value.isBlank() || value.length() > length || value.chars().anyMatch(Character::isISOControl)) throw invalid("A required field is blank, too long or contains control characters."); return value.trim(); }
}

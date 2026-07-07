package com.network.monitoring.service;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionService {

    private static final long TOKEN_CACHE_TTL_MILLIS = 30_000L;

    private final JdbcTemplate jdbcTemplate;
    private final Map<String, CachedSession> tokenCache = new ConcurrentHashMap<>();

    public SessionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void ensureIndexes() {
        addIndexIfMissing("idx_session_token", "CREATE INDEX `idx_session_token` ON `session` (`token`(191))");
        addIndexIfMissing("idx_session_expires", "CREATE INDEX `idx_session_expires` ON `session` (`expiresAt`)");
        cleanupExpiredSessions();
    }

    public boolean isTokenActive(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        CachedSession cached = tokenCache.get(token);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.active();
        }

        List<Integer> rows = jdbcTemplate.query("""
                SELECT 1
                FROM `session`
                WHERE `token` = ? AND `expiresAt` > ?
                LIMIT 1
                """, (rs, rowNum) -> rs.getInt(1), token, Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC)));
        boolean active = !rows.isEmpty();
        tokenCache.put(token, new CachedSession(active, now + TOKEN_CACHE_TTL_MILLIS));
        return active;
    }

    public void deleteByToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        tokenCache.remove(token);
        jdbcTemplate.update("DELETE FROM `session` WHERE `token` = ?", token);
    }

    @Scheduled(cron = "0 10 2 * * *")
    public void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        tokenCache.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        jdbcTemplate.update("DELETE FROM `session` WHERE `expiresAt` <= ?",
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC)));
    }

    private void addIndexIfMissing(String indexName, String ddl) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'session'
                  AND INDEX_NAME = ?
                """, Integer.class, indexName);
        if (count == null || count == 0) {
            jdbcTemplate.execute(ddl);
        }
    }

    private record CachedSession(boolean active, long expiresAtMillis) {
    }
}

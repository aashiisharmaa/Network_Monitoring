package com.network.monitoring.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Configuration
public class RedisService {

    @Value("${app.redis-enabled}")
    private boolean redisEnabled;

    @Value("${app.redis-url}")
    private String redisUrl;

    @Value("${app.redis-ttl-seconds}")
    private long ttlSeconds;

    @Bean
    @ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "true")
    public LettuceConnectionFactory redisConnectionFactory() {
        String stripped = redisUrl.replace("redis://", "");
        String[] pieces = stripped.split(":");
        String host = pieces[0];
        int port = 6379;
        if (pieces.length > 1) {
            port = Integer.parseInt(pieces[1]);
        }
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        return new LettuceConnectionFactory(config);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "true")
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) {
        LettuceConnectionFactory connectionFactory = factory;
        connectionFactory.afterPropertiesSet();
        return new StringRedisTemplate(connectionFactory);
    }

    @Service
    public static class RedisCacheService {
        private final StringRedisTemplate redisTemplate;
        private final boolean redisEnabled;
        private final long ttlSeconds;
        private static final ObjectMapper MAPPER = new ObjectMapper();

        public RedisCacheService(@Autowired(required = false) StringRedisTemplate redisTemplate,
                                 @Value("${app.redis-enabled}") boolean redisEnabled,
                                 @Value("${app.redis-ttl-seconds}") long ttlSeconds) {
            this.redisTemplate = redisTemplate;
            this.redisEnabled = redisEnabled;
            this.ttlSeconds = ttlSeconds;
        }

        public <T> T getCachedJson(String key, Class<T> valueType) {
            if (!redisEnabled || redisTemplate == null) {
                return null;
            }
            try {
                String raw = redisTemplate.opsForValue().get(key);
                if (raw == null) {
                    return null;
                }
                return MAPPER.readValue(raw, valueType);
            } catch (Exception ex) {
                return null;
            }
        }

        public void setCachedJson(String key, Object value) {
            if (!redisEnabled || redisTemplate == null) {
                return;
            }
            try {
                String raw = MAPPER.writeValueAsString(value);
                redisTemplate.opsForValue().set(key, raw, java.time.Duration.ofSeconds(ttlSeconds));
            } catch (JsonProcessingException ignored) {
            }
        }

        public void deleteCacheKey(String key) {
            if (!redisEnabled || redisTemplate == null) {
                return;
            }
            redisTemplate.delete(key);
        }
    }
}

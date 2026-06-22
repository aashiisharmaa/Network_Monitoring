package com.network.monitoring.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

@Configuration
public class DatabaseConfig {

    @Value("${spring.datasource.url:}")
    private String springDatasourceUrl;

    @Value("${spring.datasource.username:}")
    private String springDatasourceUsername;

    @Value("${spring.datasource.password:}")
    private String springDatasourcePassword;

    @Value("${DATABASE_URL:}")
    private String prismaDatabaseUrl;

    @Value("${DB_USER:}")
    private String dbUser;

    @Value("${DB_PASSWORD:}")
    private String dbPassword;

    @Bean
    @Primary
    public DataSource dataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        ConnectionInfo info = resolveConnectionInfo();
        dataSource.setJdbcUrl(info.jdbcUrl());
        if (!info.username().isBlank()) {
            dataSource.setUsername(info.username());
        }
        if (!info.password().isBlank()) {
            dataSource.setPassword(info.password());
        }
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return dataSource;
    }

    private ConnectionInfo resolveConnectionInfo() {
        String candidate = firstNonBlank(springDatasourceUrl, prismaDatabaseUrl, "");
        if (candidate.isBlank()) {
            return new ConnectionInfo(
                    "jdbc:mysql://127.0.0.1:3306/network_monitoring?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                    firstNonBlank(springDatasourceUsername, dbUser, ""),
                    firstNonBlank(springDatasourcePassword, dbPassword, "")
            );
        }

        if (candidate.toLowerCase(Locale.ROOT).startsWith("jdbc:")) {
            return new ConnectionInfo(
                    candidate,
                    firstNonBlank(springDatasourceUsername, dbUser, ""),
                    firstNonBlank(springDatasourcePassword, dbPassword, "")
            );
        }

        if (candidate.contains("://")) {
            try {
                URI uri = new URI(candidate);
                String scheme = uri.getScheme();
                if (scheme != null && scheme.toLowerCase(Locale.ROOT).contains("mysql")) {
                    String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
                    int port = uri.getPort() > 0 ? uri.getPort() : 3306;
                    String path = uri.getPath() == null || uri.getPath().isBlank() ? "/network_monitoring" : uri.getPath();
                    String query = uri.getQuery();
                    String jdbcUrl = "jdbc:mysql://" + host + ":" + port + path;
                    if (query != null && !query.isBlank()) {
                        jdbcUrl += "?" + query;
                    } else {
                        jdbcUrl += "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
                    }
                    String userInfo = uri.getUserInfo() == null ? "" : uri.getUserInfo();
                    String username = firstNonBlank(springDatasourceUsername, dbUser, extractUser(userInfo));
                    String password = firstNonBlank(springDatasourcePassword, dbPassword, extractPassword(userInfo));
                    return new ConnectionInfo(jdbcUrl, username, password);
                }
            } catch (URISyntaxException ignored) {
            }
        }

        return new ConnectionInfo(
                candidate,
                firstNonBlank(springDatasourceUsername, dbUser, ""),
                firstNonBlank(springDatasourcePassword, dbPassword, "")
        );
    }

    private String extractUser(String userInfo) {
        if (userInfo == null || userInfo.isBlank()) {
            return "";
        }
        int idx = userInfo.indexOf(':');
        return idx >= 0 ? userInfo.substring(0, idx) : userInfo;
    }

    private String extractPassword(String userInfo) {
        if (userInfo == null || userInfo.isBlank()) {
            return "";
        }
        int idx = userInfo.indexOf(':');
        return idx >= 0 && idx + 1 < userInfo.length() ? userInfo.substring(idx + 1) : "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private record ConnectionInfo(String jdbcUrl, String username, String password) {
    }
}

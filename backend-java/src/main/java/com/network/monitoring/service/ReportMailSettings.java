package com.network.monitoring.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.report-mail")
public class ReportMailSettings {
    private boolean outboundEnabled;
    private String from = "reports@localhost.invalid", host = "", username = "", password = "", spoolPath = "uploads/report-mail";
    private int port = 587, timeoutMillis = 10000, maxMessageBytes = 20000000, maxAttempts = 5;
    private boolean starttls = true, ssl;
    public boolean isOutboundEnabled() { return outboundEnabled; }
    public void setOutboundEnabled(boolean v) { outboundEnabled = v; }
    public String getFrom() { return from; } public void setFrom(String v) { from = v; }
    public String getHost() { return host; } public void setHost(String v) { host = v; }
    public String getUsername() { return username; } public void setUsername(String v) { username = v; }
    public String getPassword() { return password; } public void setPassword(String v) { password = v; }
    public String getSpoolPath() { return spoolPath; } public void setSpoolPath(String v) { spoolPath = v; }
    public int getPort() { return port; } public void setPort(int v) { port = v; }
    public int getTimeoutMillis() { return timeoutMillis; } public void setTimeoutMillis(int v) { timeoutMillis = v; }
    public int getMaxMessageBytes() { return maxMessageBytes; } public void setMaxMessageBytes(int v) { maxMessageBytes = v; }
    public int getMaxAttempts() { return maxAttempts; } public void setMaxAttempts(int v) { maxAttempts = v; }
    public boolean isStarttls() { return starttls; } public void setStarttls(boolean v) { starttls = v; }
    public boolean isSsl() { return ssl; } public void setSsl(boolean v) { ssl = v; }
    public void validate() {
        ReportMailProfile.address(from);
        if (port < 1 || port > 65535 || timeoutMillis < 1000 || timeoutMillis > 120000 || maxMessageBytes < 1024 || maxMessageBytes > 50000000 || maxAttempts < 1 || maxAttempts > 10) throw new IllegalArgumentException("Invalid report mail limits.");
        if (outboundEnabled && (host == null || host.isBlank() || (!starttls && !ssl) || from.endsWith(".invalid"))) throw new IllegalArgumentException("Outbound mail requires an SMTP host, TLS and a real sender address.");
    }
}

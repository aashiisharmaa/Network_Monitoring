package com.network.monitoring.alarm.config;

public class AlarmConfigurationException extends RuntimeException {
    private final int status;
    private final String code;

    public AlarmConfigurationException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
    public static AlarmConfigurationException invalid(String message) {
        return new AlarmConfigurationException(400, "INVALID_ALARM_CONFIGURATION", message);
    }
    public static AlarmConfigurationException missing() {
        return new AlarmConfigurationException(404, "ALARM_CONFIGURATION_NOT_FOUND", "Configuration was not found in this company.");
    }
    public static AlarmConfigurationException conflict(String message) {
        return new AlarmConfigurationException(409, "ALARM_CONFIGURATION_CONFLICT", message);
    }
}

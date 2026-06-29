package com.network.monitoring.dto.request;

public class KpiThresholdRuleRequest {
    private String metricName;
    private String technology;
    private String direction;
    private Double criticalValue;
    private Double majorValue;
    private Double minorValue;
    private Double warningValue;
    private Boolean enabled;

    public String getMetricName() {
        return metricName;
    }

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    public String getTechnology() {
        return technology;
    }

    public void setTechnology(String technology) {
        this.technology = technology;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public Double getCriticalValue() {
        return criticalValue;
    }

    public void setCriticalValue(Double criticalValue) {
        this.criticalValue = criticalValue;
    }

    public Double getMajorValue() {
        return majorValue;
    }

    public void setMajorValue(Double majorValue) {
        this.majorValue = majorValue;
    }

    public Double getMinorValue() {
        return minorValue;
    }

    public void setMinorValue(Double minorValue) {
        this.minorValue = minorValue;
    }

    public Double getWarningValue() {
        return warningValue;
    }

    public void setWarningValue(Double warningValue) {
        this.warningValue = warningValue;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}

package com.network.monitoring.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "kpi_threshold_rules")
public class KpiThresholdRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "metricName", nullable = false, length = 191)
    private String metricName;

    @Column(name = "technology", length = 64)
    private String technology;

    @Column(name = "direction", nullable = false, length = 32)
    private String direction;

    @Column(name = "criticalValue")
    private Double criticalValue;

    @Column(name = "majorValue")
    private Double majorValue;

    @Column(name = "minorValue")
    private Double minorValue;

    @Column(name = "warningValue")
    private Double warningValue;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "createdAt")
    private LocalDateTime createdAt;

    @Column(name = "updatedAt")
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

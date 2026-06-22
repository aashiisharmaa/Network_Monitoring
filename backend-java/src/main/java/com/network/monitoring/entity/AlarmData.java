package com.network.monitoring.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "alarmdata")
public class AlarmData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fileId")
    private Long fileId;

    @Column(name = "FILENAME")
    private String filename;

    @Column(name = "DATETIME")
    private LocalDateTime datetime;

    @Column(name = "configData_dnPrefix")
    private String configDataDnPrefix;

    @Column(name = "SubNetwork_id")
    private String subNetworkId;

    @Column(name = "SubNetwork_2_id")
    private String subNetwork2Id;

    @Column(name = "MeContext_id")
    private String meContextId;

    @Column(name = "ManagedElement_id")
    private String managedElementId;

    @Column(name = "vsEquipment_id")
    private String vsEquipmentId;

    @Column(name = "vsFieldReplaceableUnit_id")
    private String vsFieldReplaceableUnitId;

    @Column(name = "vsAlarmPort_id")
    private String vsAlarmPortId;

    @Column(name = "userLabel")
    private String userLabel;

    @Column(name = "administrativeState")
    private String administrativeState;

    @Column(name = "perceivedSeverity")
    private String perceivedSeverity;

    @Column(name = "alarmPortId")
    private String alarmPortId;

    @Column(name = "alarmSlogan")
    private String alarmSlogan;

    @Column(name = "filterDelay")
    private String filterDelay;

    @Column(name = "operationalState")
    private String operationalState;

    @Column(name = "filterTime")
    private String filterTime;

    @Column(name = "availabilityStatus")
    private String availabilityStatus;

    @Column(name = "filterAlgorithm")
    private String filterAlgorithm;

    @Column(name = "normallyOpen")
    private String normallyOpen;

    @Column(name = "alarmInExternalMe")
    private String alarmInExternalMe;

    @Column(name = "createdAt")
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public LocalDateTime getDatetime() {
        return datetime;
    }

    public void setDatetime(LocalDateTime datetime) {
        this.datetime = datetime;
    }

    public String getConfigDataDnPrefix() {
        return configDataDnPrefix;
    }

    public void setConfigDataDnPrefix(String configDataDnPrefix) {
        this.configDataDnPrefix = configDataDnPrefix;
    }

    public String getSubNetworkId() {
        return subNetworkId;
    }

    public void setSubNetworkId(String subNetworkId) {
        this.subNetworkId = subNetworkId;
    }

    public String getSubNetwork2Id() {
        return subNetwork2Id;
    }

    public void setSubNetwork2Id(String subNetwork2Id) {
        this.subNetwork2Id = subNetwork2Id;
    }

    public String getMeContextId() {
        return meContextId;
    }

    public void setMeContextId(String meContextId) {
        this.meContextId = meContextId;
    }

    public String getManagedElementId() {
        return managedElementId;
    }

    public void setManagedElementId(String managedElementId) {
        this.managedElementId = managedElementId;
    }

    public String getVsEquipmentId() {
        return vsEquipmentId;
    }

    public void setVsEquipmentId(String vsEquipmentId) {
        this.vsEquipmentId = vsEquipmentId;
    }

    public String getVsFieldReplaceableUnitId() {
        return vsFieldReplaceableUnitId;
    }

    public void setVsFieldReplaceableUnitId(String vsFieldReplaceableUnitId) {
        this.vsFieldReplaceableUnitId = vsFieldReplaceableUnitId;
    }

    public String getVsAlarmPortId() {
        return vsAlarmPortId;
    }

    public void setVsAlarmPortId(String vsAlarmPortId) {
        this.vsAlarmPortId = vsAlarmPortId;
    }

    public String getUserLabel() {
        return userLabel;
    }

    public void setUserLabel(String userLabel) {
        this.userLabel = userLabel;
    }

    public String getAdministrativeState() {
        return administrativeState;
    }

    public void setAdministrativeState(String administrativeState) {
        this.administrativeState = administrativeState;
    }

    public String getPerceivedSeverity() {
        return perceivedSeverity;
    }

    public void setPerceivedSeverity(String perceivedSeverity) {
        this.perceivedSeverity = perceivedSeverity;
    }

    public String getAlarmPortId() {
        return alarmPortId;
    }

    public void setAlarmPortId(String alarmPortId) {
        this.alarmPortId = alarmPortId;
    }

    public String getAlarmSlogan() {
        return alarmSlogan;
    }

    public void setAlarmSlogan(String alarmSlogan) {
        this.alarmSlogan = alarmSlogan;
    }

    public String getFilterDelay() {
        return filterDelay;
    }

    public void setFilterDelay(String filterDelay) {
        this.filterDelay = filterDelay;
    }

    public String getOperationalState() {
        return operationalState;
    }

    public void setOperationalState(String operationalState) {
        this.operationalState = operationalState;
    }

    public String getFilterTime() {
        return filterTime;
    }

    public void setFilterTime(String filterTime) {
        this.filterTime = filterTime;
    }

    public String getAvailabilityStatus() {
        return availabilityStatus;
    }

    public void setAvailabilityStatus(String availabilityStatus) {
        this.availabilityStatus = availabilityStatus;
    }

    public String getFilterAlgorithm() {
        return filterAlgorithm;
    }

    public void setFilterAlgorithm(String filterAlgorithm) {
        this.filterAlgorithm = filterAlgorithm;
    }

    public String getNormallyOpen() {
        return normallyOpen;
    }

    public void setNormallyOpen(String normallyOpen) {
        this.normallyOpen = normallyOpen;
    }

    public String getAlarmInExternalMe() {
        return alarmInExternalMe;
    }

    public void setAlarmInExternalMe(String alarmInExternalMe) {
        this.alarmInExternalMe = alarmInExternalMe;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

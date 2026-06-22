package com.network.monitoring.entity;

import com.network.monitoring.util.JsonAttributeConverter;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "threshold")
public class Threshold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fileId", unique = true)
    private Long fileId;

    @Column(name = "createdAt")
    private LocalDateTime createdAt;

    @Column(name = "updatedAt")
    private LocalDateTime updatedAt;

    @Convert(converter = JsonAttributeConverter.class)
    @Column(name = "dynamicThresholds", columnDefinition = "json")
    private Map<String, Object> dynamicThresholds;

    @Convert(converter = JsonAttributeConverter.class)
    @Column(name = "thresholdOperators", columnDefinition = "json")
    private Map<String, Object> thresholdOperators;

    @Column(name = "UL_PRB_Utilization_Rate")
    private Double UL_PRB_Utilization_Rate;

    @Column(name = "DL_PRB_Utilization_Rate")
    private Double DL_PRB_Utilization_Rate;

    @Column(name = "E_RAB_Drop_Rate")
    private Double E_RAB_Drop_Rate;

    @Column(name = "RRC_Drop_Rate")
    private Double RRC_Drop_Rate;

    @Column(name = "Initial_ERAB_Establishment_Success_Rate")
    private Double Initial_ERAB_Establishment_Success_Rate;

    @Column(name = "RRC_Establishment_Success_Rate")
    private Double RRC_Establishment_Success_Rate;

    @Column(name = "E_RAB_Setup_Success_Rate")
    private Double E_RAB_Setup_Success_Rate;

    @Column(name = "VOLTE_CSSR_Eric")
    private Double VOLTE_CSSR_Eric;

    @Column(name = "VOLTE_DCR_Eric")
    private Double VOLTE_DCR_Eric;

    @Column(name = "Inter_Freq_HOSR")
    private Double Inter_Freq_HOSR;

    @Column(name = "Intra_Freq_HOSR")
    private Double Intra_Freq_HOSR;

    @Column(name = "CSFB_Success_Rate")
    private Double CSFB_Success_Rate;

    @Column(name = "Max_RRC_Users")
    private Double Max_RRC_Users;

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

    public Map<String, Object> getDynamicThresholds() {
        return dynamicThresholds;
    }

    public void setDynamicThresholds(Map<String, Object> dynamicThresholds) {
        this.dynamicThresholds = dynamicThresholds;
    }

    public Map<String, Object> getThresholdOperators() {
        return thresholdOperators;
    }

    public void setThresholdOperators(Map<String, Object> thresholdOperators) {
        this.thresholdOperators = thresholdOperators;
    }

    public Double getUL_PRB_Utilization_Rate() {
        return UL_PRB_Utilization_Rate;
    }

    public void setUL_PRB_Utilization_Rate(Double UL_PRB_Utilization_Rate) {
        this.UL_PRB_Utilization_Rate = UL_PRB_Utilization_Rate;
    }

    public Double getDL_PRB_Utilization_Rate() {
        return DL_PRB_Utilization_Rate;
    }

    public void setDL_PRB_Utilization_Rate(Double DL_PRB_Utilization_Rate) {
        this.DL_PRB_Utilization_Rate = DL_PRB_Utilization_Rate;
    }

    public Double getE_RAB_Drop_Rate() {
        return E_RAB_Drop_Rate;
    }

    public void setE_RAB_Drop_Rate(Double E_RAB_Drop_Rate) {
        this.E_RAB_Drop_Rate = E_RAB_Drop_Rate;
    }

    public Double getRRC_Drop_Rate() {
        return RRC_Drop_Rate;
    }

    public void setRRC_Drop_Rate(Double RRC_Drop_Rate) {
        this.RRC_Drop_Rate = RRC_Drop_Rate;
    }

    public Double getInitial_ERAB_Establishment_Success_Rate() {
        return Initial_ERAB_Establishment_Success_Rate;
    }

    public void setInitial_ERAB_Establishment_Success_Rate(Double Initial_ERAB_Establishment_Success_Rate) {
        this.Initial_ERAB_Establishment_Success_Rate = Initial_ERAB_Establishment_Success_Rate;
    }

    public Double getRRC_Establishment_Success_Rate() {
        return RRC_Establishment_Success_Rate;
    }

    public void setRRC_Establishment_Success_Rate(Double RRC_Establishment_Success_Rate) {
        this.RRC_Establishment_Success_Rate = RRC_Establishment_Success_Rate;
    }

    public Double getE_RAB_Setup_Success_Rate() {
        return E_RAB_Setup_Success_Rate;
    }

    public void setE_RAB_Setup_Success_Rate(Double E_RAB_Setup_Success_Rate) {
        this.E_RAB_Setup_Success_Rate = E_RAB_Setup_Success_Rate;
    }

    public Double getVOLTE_CSSR_Eric() {
        return VOLTE_CSSR_Eric;
    }

    public void setVOLTE_CSSR_Eric(Double VOLTE_CSSR_Eric) {
        this.VOLTE_CSSR_Eric = VOLTE_CSSR_Eric;
    }

    public Double getVOLTE_DCR_Eric() {
        return VOLTE_DCR_Eric;
    }

    public void setVOLTE_DCR_Eric(Double VOLTE_DCR_Eric) {
        this.VOLTE_DCR_Eric = VOLTE_DCR_Eric;
    }

    public Double getInter_Freq_HOSR() {
        return Inter_Freq_HOSR;
    }

    public void setInter_Freq_HOSR(Double Inter_Freq_HOSR) {
        this.Inter_Freq_HOSR = Inter_Freq_HOSR;
    }

    public Double getIntra_Freq_HOSR() {
        return Intra_Freq_HOSR;
    }

    public void setIntra_Freq_HOSR(Double Intra_Freq_HOSR) {
        this.Intra_Freq_HOSR = Intra_Freq_HOSR;
    }

    public Double getCSFB_Success_Rate() {
        return CSFB_Success_Rate;
    }

    public void setCSFB_Success_Rate(Double CSFB_Success_Rate) {
        this.CSFB_Success_Rate = CSFB_Success_Rate;
    }

    public Double getMax_RRC_Users() {
        return Max_RRC_Users;
    }

    public void setMax_RRC_Users(Double Max_RRC_Users) {
        this.Max_RRC_Users = Max_RRC_Users;
    }
}

package com.network.monitoring.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "uploaddata")
public class UploadData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "date")
    private LocalDateTime date;

    @Column(name = "fileId")
    private Long fileId;

    @Column(name = "Cell_Name")
    private String cellName;

    @Column(name = "Site")
    private String site;

    @Column(name = "Band")
    private String band;

    @Column(name = "Tech")
    private String tech;

    @Column(name = "Sector_ID")
    private String sectorId;

    @Column(name = "Sector_Name")
    private String sectorName;

    @Column(name = "Groups")
    private String groups;

    @Column(name = "UL_PRB_Utilization_Rate")
    private Double ulPrbUtilizationRate;

    @Column(name = "DL_PRB_Utilization_Rate")
    private Double dlPrbUtilizationRate;

    @Column(name = "UME_4G_Data_Volume_STD_MAPS_MB_903593_1")
    private Double ume4gDataVolumeStdMapsMb9035931;

    @Column(name = "UME_E_UTRAN_IP_Throughput_UE_UL_STD_Kbps")
    private Double umeEutranIpThroughputUeUlStdKbps;

    @Column(name = "UME_E_UTRAN_IP_Throughput_UE_DL_STD_Kbps")
    private Double umeEutranIpThroughputUeDlStdKbps;

    @Column(name = "E_RAB_Drop_Rate")
    private Double erabDropRate;

    @Column(name = "Initial_ERAB_Establishment_Success_Rate")
    private Double initialErabEstablishmentSuccessRate;

    @Column(name = "RRC_Establishment_Success_Rate")
    private Double rrcEstablishmentSuccessRate;

    @Column(name = "Mean_RRC_Connected_User_Number")
    private Double meanRrcConnectedUserNumber;

    @Column(name = "Maximum_RRC_Connected_User_Number")
    private Double maximumRrcConnectedUserNumber;

    @Column(name = "E_RAB_Setup_Success_Rate")
    private Double erabSetupSuccessRate;

    @Column(name = "RRC_Drop_Rate")
    private Double rrcDropRate;

    @Column(name = "VOLTE_CSSR_Eric")
    private Double volteCssrEric;

    @Column(name = "VOLTE_DCR_Eric")
    private Double volteDcrEric;

    @Column(name = "Inter_Freq_HOSR")
    private Double interFreqHosr;

    @Column(name = "Intra_Freq_HOSR")
    private Double intraFreqHosr;

    @Column(name = "CSFB_Success_Rate")
    private Double csfbSuccessRate;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDateTime getDate() {
        return date;
    }

    public void setDate(LocalDateTime date) {
        this.date = date;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public String getCellName() {
        return cellName;
    }

    public void setCellName(String cellName) {
        this.cellName = cellName;
    }

    public String getSite() {
        return site;
    }

    public void setSite(String site) {
        this.site = site;
    }

    public String getBand() {
        return band;
    }

    public void setBand(String band) {
        this.band = band;
    }

    public String getTech() {
        return tech;
    }

    public void setTech(String tech) {
        this.tech = tech;
    }

    public String getSectorId() {
        return sectorId;
    }

    public void setSectorId(String sectorId) {
        this.sectorId = sectorId;
    }

    public String getSectorName() {
        return sectorName;
    }

    public void setSectorName(String sectorName) {
        this.sectorName = sectorName;
    }

    public String getGroups() {
        return groups;
    }

    public void setGroups(String groups) {
        this.groups = groups;
    }

    public Double getUlPrbUtilizationRate() {
        return ulPrbUtilizationRate;
    }

    public void setUlPrbUtilizationRate(Double ulPrbUtilizationRate) {
        this.ulPrbUtilizationRate = ulPrbUtilizationRate;
    }

    public Double getDlPrbUtilizationRate() {
        return dlPrbUtilizationRate;
    }

    public void setDlPrbUtilizationRate(Double dlPrbUtilizationRate) {
        this.dlPrbUtilizationRate = dlPrbUtilizationRate;
    }

    public Double getUme4gDataVolumeStdMapsMb9035931() {
        return ume4gDataVolumeStdMapsMb9035931;
    }

    public void setUme4gDataVolumeStdMapsMb9035931(Double ume4gDataVolumeStdMapsMb9035931) {
        this.ume4gDataVolumeStdMapsMb9035931 = ume4gDataVolumeStdMapsMb9035931;
    }

    public Double getUmeEutranIpThroughputUeUlStdKbps() {
        return umeEutranIpThroughputUeUlStdKbps;
    }

    public void setUmeEutranIpThroughputUeUlStdKbps(Double umeEutranIpThroughputUeUlStdKbps) {
        this.umeEutranIpThroughputUeUlStdKbps = umeEutranIpThroughputUeUlStdKbps;
    }

    public Double getUmeEutranIpThroughputUeDlStdKbps() {
        return umeEutranIpThroughputUeDlStdKbps;
    }

    public void setUmeEutranIpThroughputUeDlStdKbps(Double umeEutranIpThroughputUeDlStdKbps) {
        this.umeEutranIpThroughputUeDlStdKbps = umeEutranIpThroughputUeDlStdKbps;
    }

    public Double getErabDropRate() {
        return erabDropRate;
    }

    public void setErabDropRate(Double erabDropRate) {
        this.erabDropRate = erabDropRate;
    }

    public Double getInitialErabEstablishmentSuccessRate() {
        return initialErabEstablishmentSuccessRate;
    }

    public void setInitialErabEstablishmentSuccessRate(Double initialErabEstablishmentSuccessRate) {
        this.initialErabEstablishmentSuccessRate = initialErabEstablishmentSuccessRate;
    }

    public Double getRrcEstablishmentSuccessRate() {
        return rrcEstablishmentSuccessRate;
    }

    public void setRrcEstablishmentSuccessRate(Double rrcEstablishmentSuccessRate) {
        this.rrcEstablishmentSuccessRate = rrcEstablishmentSuccessRate;
    }

    public Double getMeanRrcConnectedUserNumber() {
        return meanRrcConnectedUserNumber;
    }

    public void setMeanRrcConnectedUserNumber(Double meanRrcConnectedUserNumber) {
        this.meanRrcConnectedUserNumber = meanRrcConnectedUserNumber;
    }

    public Double getMaximumRrcConnectedUserNumber() {
        return maximumRrcConnectedUserNumber;
    }

    public void setMaximumRrcConnectedUserNumber(Double maximumRrcConnectedUserNumber) {
        this.maximumRrcConnectedUserNumber = maximumRrcConnectedUserNumber;
    }

    public Double getErabSetupSuccessRate() {
        return erabSetupSuccessRate;
    }

    public void setErabSetupSuccessRate(Double erabSetupSuccessRate) {
        this.erabSetupSuccessRate = erabSetupSuccessRate;
    }

    public Double getRrcDropRate() {
        return rrcDropRate;
    }

    public void setRrcDropRate(Double rrcDropRate) {
        this.rrcDropRate = rrcDropRate;
    }

    public Double getVolteCssrEric() {
        return volteCssrEric;
    }

    public void setVolteCssrEric(Double volteCssrEric) {
        this.volteCssrEric = volteCssrEric;
    }

    public Double getVolteDcrEric() {
        return volteDcrEric;
    }

    public void setVolteDcrEric(Double volteDcrEric) {
        this.volteDcrEric = volteDcrEric;
    }

    public Double getInterFreqHosr() {
        return interFreqHosr;
    }

    public void setInterFreqHosr(Double interFreqHosr) {
        this.interFreqHosr = interFreqHosr;
    }

    public Double getIntraFreqHosr() {
        return intraFreqHosr;
    }

    public void setIntraFreqHosr(Double intraFreqHosr) {
        this.intraFreqHosr = intraFreqHosr;
    }

    public Double getCsfbSuccessRate() {
        return csfbSuccessRate;
    }

    public void setCsfbSuccessRate(Double csfbSuccessRate) {
        this.csfbSuccessRate = csfbSuccessRate;
    }
}

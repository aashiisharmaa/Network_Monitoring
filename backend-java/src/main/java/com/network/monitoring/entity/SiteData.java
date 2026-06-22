package com.network.monitoring.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sitedata")
public class SiteData {

    @Id
    private Long id;

    @Column(name = "Cell Name")
    private String cellName;

    @Column(name = "SI_CI")
    private String siCi;

    @Column(name = "EGCI")
    private String egci;

    @Column(name = "SuNetwork ID")
    private String suNetworkId;

    @Column(name = "SITEID")
    private String siteId;

    @Column(name = "Site Name")
    private String siteName;

    @Column(name = "Cell ID")
    private String cellId;

    @Column(name = "SEC ID")
    private String secId;

    @Column(name = "lon")
    private Double lon;

    @Column(name = "lat")
    private Double lat;

    @Column(name = "TAC")
    private Integer tac;

    @Column(name = "PCI")
    private Integer pci;

    @Column(name = "AZIMUTH")
    private Integer azimuth;

    @Column(name = "Antenna Height")
    private Double antennaHeight;

    @Column(name = "M-tilt")
    private Double mTilt;

    @Column(name = "E-tilt")
    private Double eTilt;

    @Column(name = "TX/RX")
    private String txRx;

    @Column(name = "Real Transmit Power of Resource")
    private Double realTransmitPowerOfResource;

    @Column(name = "Referenced Signal Power of Resource")
    private Double referencedSignalPowerOfResource;

    @Column(name = "cellSize")
    private String cellSize;

    @Column(name = "cellRadius")
    private Double cellRadius;

    @Column(name = "RachRootSequence")
    private Integer rachRootSequence;

    @Column(name = "Bandwidth")
    private Integer bandwidth;

    @Column(name = "Frequency")
    private Integer frequency;

    @Column(name = "Downlink Center Frequency")
    private Integer downlinkCenterFrequency;

    @Column(name = "Region")
    private String region;

    @Column(name = "Cluster")
    private String cluster;

    @Column(name = "OMM")
    private String omm;

    @Column(name = "Antenna")
    private String antenna;

    @Column(name = "RET")
    private String ret;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCellName() {
        return cellName;
    }

    public void setCellName(String cellName) {
        this.cellName = cellName;
    }

    public String getSiCi() {
        return siCi;
    }

    public void setSiCi(String siCi) {
        this.siCi = siCi;
    }

    public String getEgci() {
        return egci;
    }

    public void setEgci(String egci) {
        this.egci = egci;
    }

    public String getSuNetworkId() {
        return suNetworkId;
    }

    public void setSuNetworkId(String suNetworkId) {
        this.suNetworkId = suNetworkId;
    }

    public String getSiteId() {
        return siteId;
    }

    public void setSiteId(String siteId) {
        this.siteId = siteId;
    }

    public String getSiteName() {
        return siteName;
    }

    public void setSiteName(String siteName) {
        this.siteName = siteName;
    }

    public String getCellId() {
        return cellId;
    }

    public void setCellId(String cellId) {
        this.cellId = cellId;
    }

    public String getSecId() {
        return secId;
    }

    public void setSecId(String secId) {
        this.secId = secId;
    }

    public Double getLon() {
        return lon;
    }

    public void setLon(Double lon) {
        this.lon = lon;
    }

    public Double getLat() {
        return lat;
    }

    public void setLat(Double lat) {
        this.lat = lat;
    }

    public Integer getTac() {
        return tac;
    }

    public void setTac(Integer tac) {
        this.tac = tac;
    }

    public Integer getPci() {
        return pci;
    }

    public void setPci(Integer pci) {
        this.pci = pci;
    }

    public Integer getAzimuth() {
        return azimuth;
    }

    public void setAzimuth(Integer azimuth) {
        this.azimuth = azimuth;
    }

    public Double getAntennaHeight() {
        return antennaHeight;
    }

    public void setAntennaHeight(Double antennaHeight) {
        this.antennaHeight = antennaHeight;
    }

    public Double getMTilt() {
        return mTilt;
    }

    public void setMTilt(Double mTilt) {
        this.mTilt = mTilt;
    }

    public Double getETilt() {
        return eTilt;
    }

    public void setETilt(Double eTilt) {
        this.eTilt = eTilt;
    }

    public String getTxRx() {
        return txRx;
    }

    public void setTxRx(String txRx) {
        this.txRx = txRx;
    }

    public Double getRealTransmitPowerOfResource() {
        return realTransmitPowerOfResource;
    }

    public void setRealTransmitPowerOfResource(Double realTransmitPowerOfResource) {
        this.realTransmitPowerOfResource = realTransmitPowerOfResource;
    }

    public Double getReferencedSignalPowerOfResource() {
        return referencedSignalPowerOfResource;
    }

    public void setReferencedSignalPowerOfResource(Double referencedSignalPowerOfResource) {
        this.referencedSignalPowerOfResource = referencedSignalPowerOfResource;
    }

    public String getCellSize() {
        return cellSize;
    }

    public void setCellSize(String cellSize) {
        this.cellSize = cellSize;
    }

    public Double getCellRadius() {
        return cellRadius;
    }

    public void setCellRadius(Double cellRadius) {
        this.cellRadius = cellRadius;
    }

    public Integer getRachRootSequence() {
        return rachRootSequence;
    }

    public void setRachRootSequence(Integer rachRootSequence) {
        this.rachRootSequence = rachRootSequence;
    }

    public Integer getBandwidth() {
        return bandwidth;
    }

    public void setBandwidth(Integer bandwidth) {
        this.bandwidth = bandwidth;
    }

    public Integer getFrequency() {
        return frequency;
    }

    public void setFrequency(Integer frequency) {
        this.frequency = frequency;
    }

    public Integer getDownlinkCenterFrequency() {
        return downlinkCenterFrequency;
    }

    public void setDownlinkCenterFrequency(Integer downlinkCenterFrequency) {
        this.downlinkCenterFrequency = downlinkCenterFrequency;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getCluster() {
        return cluster;
    }

    public void setCluster(String cluster) {
        this.cluster = cluster;
    }

    public String getOmm() {
        return omm;
    }

    public void setOmm(String omm) {
        this.omm = omm;
    }

    public String getAntenna() {
        return antenna;
    }

    public void setAntenna(String antenna) {
        this.antenna = antenna;
    }

    public String getRet() {
        return ret;
    }

    public void setRet(String ret) {
        this.ret = ret;
    }
}

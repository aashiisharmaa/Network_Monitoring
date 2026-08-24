package com.network.monitoring.service;

import com.network.monitoring.entity.SiteData;
import com.network.monitoring.repository.SiteDataRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PciAnalysisService {

    private static final double NEAR_DISTANCE_KM = 1.0;
    private static final double MAX_NEIGHBOUR_DISTANCE_KM = 8.0;
    private static final double AZIMUTH_TOLERANCE_DEG = 80.0;

    private final SiteDataRepository siteDataRepository;

    public PciAnalysisService(SiteDataRepository siteDataRepository) {
        this.siteDataRepository = siteDataRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> analyze(Long fileId, Integer pci, String sourceSiteId) {
        if (fileId == null) {
            throw new IllegalArgumentException("fileId is required.");
        }
        if (pci == null) {
            throw new IllegalArgumentException("pci is required.");
        }

        List<SiteData> rows = siteDataRepository.findByFileId(fileId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("No site data found for fileId " + fileId + ".");
        }

        Map<String, SiteAggregate> sites = new LinkedHashMap<>();
        for (SiteData row : rows) {
            if (row.getSiteId() == null || row.getSiteId().isBlank()) {
                continue;
            }
            SiteAggregate site = sites.computeIfAbsent(normalize(row.getSiteId()), ignored -> new SiteAggregate(row));
            site.add(row);
        }

        List<SiteAggregate> selectedPciSites = sites.values().stream()
                .filter(site -> site.hasPci(pci))
                .sorted(Comparator.comparing(SiteAggregate::siteId, String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (selectedPciSites.isEmpty()) {
            throw new IllegalArgumentException("No site found with PCI " + pci + " in this file.");
        }

        SiteAggregate source = resolveSource(selectedPciSites, sourceSiteId);
        List<Map<String, Object>> samePciSites = new ArrayList<>();
        List<Map<String, Object>> inferredNeighbours = new ArrayList<>();
        int inferredSamePciCellCount = 0;

        for (SiteAggregate candidate : selectedPciSites) {
            if (normalize(candidate.siteId()).equals(normalize(source.siteId()))) {
                continue;
            }
            double distance = distanceKm(source.lat, source.lon, candidate.lat, candidate.lon);
            boolean inferredNeighbour = isInferredNeighbour(source, candidate, distance);
            Map<String, Object> payload = candidate.toPayload(pci, distance, inferredNeighbour);
            samePciSites.add(payload);
            if (inferredNeighbour) {
                inferredNeighbours.add(payload);
                inferredSamePciCellCount += candidate.matchingPciCellCount(pci);
            }
        }

        samePciSites.sort(Comparator.comparingDouble(item -> number(item.get("distanceKm"))));
        inferredNeighbours.sort(Comparator.comparingDouble(item -> number(item.get("distanceKm"))));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileId", fileId);
        response.put("pci", pci);
        response.put("sourceSite", source.toPayload(pci, 0.0, true));
        response.put("selectedPciSiteCount", selectedPciSites.size());
        response.put("samePciSites", samePciSites);
        response.put("inferredNeighbours", inferredNeighbours);
        response.put("samePciSiteCount", samePciSites.size());
        response.put("inferredNeighbourSiteCount", inferredNeighbours.size());
        response.put("inferredSamePciCellCount", inferredSamePciCellCount);
        response.put("collisionSummary", collisionSummary(pci, samePciSites.size(), inferredNeighbours.size(), inferredSamePciCellCount));
        response.put("confusionSummary", confusionSummary(inferredSamePciCellCount));
        return response;
    }

    private SiteAggregate resolveSource(List<SiteAggregate> sites, String sourceSiteId) {
        if (sourceSiteId != null && !sourceSiteId.isBlank()) {
            String requested = normalize(sourceSiteId);
            return sites.stream()
                    .filter(site -> normalize(site.siteId()).equals(requested) || normalize(site.siteName).equals(requested))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Source site " + sourceSiteId + " does not use the selected PCI."));
        }
        return sites.get(0);
    }

    private Map<String, Object> collisionSummary(Integer pci, int samePciSiteCount, int inferredNeighbourSiteCount, int inferredCellCount) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (inferredNeighbourSiteCount > 0) {
            summary.put("status", "Potential collision");
            summary.put("severity", "RISK");
            summary.put("detail", inferredNeighbourSiteCount + " inferred neighbour site(s), " + inferredCellCount + " cell(s), reuse PCI " + pci + ".");
        } else if (samePciSiteCount > 0) {
            summary.put("status", "Reuse found, no inferred overlap");
            summary.put("severity", "WATCH");
            summary.put("detail", "Same PCI exists in the database, but not inside the inferred neighbour window.");
        } else {
            summary.put("status", "No reuse found");
            summary.put("severity", "NORMAL");
            summary.put("detail", "No other visible site uses this PCI.");
        }
        return summary;
    }

    private Map<String, Object> confusionSummary(int inferredCellCount) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (inferredCellCount >= 2) {
            summary.put("status", "Potential confusion");
            summary.put("severity", "RISK");
            summary.put("detail", "Source can infer " + inferredCellCount + " neighbour cells with the same PCI.");
        } else if (inferredCellCount == 1) {
            summary.put("status", "Single same-PCI inferred neighbour");
            summary.put("severity", "WATCH");
            summary.put("detail", "Collision risk exists, but confusion needs two or more same-PCI neighbour cells.");
        } else {
            summary.put("status", "No confusion inferred");
            summary.put("severity", "NORMAL");
            summary.put("detail", "No same-PCI inferred neighbours for this source.");
        }
        return summary;
    }

    private boolean isInferredNeighbour(SiteAggregate source, SiteAggregate candidate, double distanceKm) {
        if (!Double.isFinite(distanceKm) || distanceKm > MAX_NEIGHBOUR_DISTANCE_KM) {
            return false;
        }
        if (distanceKm <= NEAR_DISTANCE_KM) {
            return true;
        }
        return siteFacesCandidate(source, candidate) || siteFacesCandidate(candidate, source);
    }

    private boolean siteFacesCandidate(SiteAggregate site, SiteAggregate candidate) {
        Double bearing = bearingDeg(site.lat, site.lon, candidate.lat, candidate.lon);
        if (bearing == null) {
            return false;
        }
        return site.cells.stream()
                .map(SiteCell::azimuth)
                .anyMatch(azimuth -> {
                    Double delta = angleDeltaDeg(azimuth, bearing);
                    return delta != null && delta <= AZIMUTH_TOLERANCE_DEG;
                });
    }

    private double distanceKm(Double lat1, Double lon1, Double lat2, Double lon2) {
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) {
            return Double.POSITIVE_INFINITY;
        }
        double earthRadiusKm = 6371.0;
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaPhi = Math.toRadians(lat2 - lat1);
        double deltaLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
        return round(earthRadiusKm * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
    }

    private Double bearingDeg(Double lat1, Double lon1, Double lat2, Double lon2) {
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) {
            return null;
        }
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(deltaLon) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(deltaLon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    private Double angleDeltaDeg(Integer left, Double right) {
        if (left == null || right == null) {
            return null;
        }
        return Math.abs(((left - right + 540.0) % 360.0) - 180.0);
    }

    private double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.POSITIVE_INFINITY;
    }

    private double round(double value) {
        if (!Double.isFinite(value)) {
            return value;
        }
        return Math.round(value * 100.0) / 100.0;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static class SiteAggregate {
        private final String siteId;
        private String siteName;
        private Double lat;
        private Double lon;
        private final List<SiteCell> cells = new ArrayList<>();

        private SiteAggregate(SiteData row) {
            this.siteId = row.getSiteId();
            this.siteName = row.getSiteName();
            this.lat = row.getLat();
            this.lon = row.getLon();
        }

        private void add(SiteData row) {
            if ((siteName == null || siteName.isBlank()) && row.getSiteName() != null) {
                siteName = row.getSiteName();
            }
            if (lat == null && row.getLat() != null) {
                lat = row.getLat();
            }
            if (lon == null && row.getLon() != null) {
                lon = row.getLon();
            }
            cells.add(new SiteCell(row.getCellName(), row.getPci(), row.getAzimuth(), row.getBand(), row.getTechnology(), row.getSector()));
        }

        private String siteId() {
            return siteId;
        }

        private boolean hasPci(Integer pci) {
            return cells.stream().anyMatch(cell -> pci != null && pci.equals(cell.pci()));
        }

        private int matchingPciCellCount(Integer pci) {
            return (int) cells.stream().filter(cell -> pci != null && pci.equals(cell.pci())).count();
        }

        private Map<String, Object> toPayload(Integer pci, double distanceKm, boolean inferredNeighbour) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("siteId", siteId);
            payload.put("site", siteId);
            payload.put("siteName", siteName);
            payload.put("lat", lat);
            payload.put("lon", lon);
            payload.put("distanceKm", Double.isFinite(distanceKm) ? distanceKm : null);
            payload.put("inferredNeighbour", inferredNeighbour);
            payload.put("matchingPciCellCount", matchingPciCellCount(pci));
            payload.put("matchingPciCells", cells.stream()
                    .filter(cell -> pci != null && pci.equals(cell.pci()))
                    .map(SiteCell::toPayload)
                    .toList());
            return payload;
        }
    }

    private record SiteCell(String cellName, Integer pci, Integer azimuth, String band, String technology, String sector) {
        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("cellName", cellName);
            payload.put("pci", pci);
            payload.put("azimuth", azimuth);
            payload.put("band", band);
            payload.put("technology", technology);
            payload.put("sector", sector);
            return payload;
        }
    }
}

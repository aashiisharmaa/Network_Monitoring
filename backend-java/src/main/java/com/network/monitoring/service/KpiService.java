package com.network.monitoring.service;

import com.network.monitoring.entity.UploadData;
import com.network.monitoring.repository.UploadDataRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class KpiService {

    private final UploadDataRepository uploadDataRepository;
    private final KpiCacheService kpiCacheService;

    public KpiService(UploadDataRepository uploadDataRepository,
                      KpiCacheService kpiCacheService) {
        this.uploadDataRepository = uploadDataRepository;
        this.kpiCacheService = kpiCacheService;
    }

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<UploadData> findUploadDataByMetric(String metricProperty, Long fileId, int maxResults) {
        validateMetricProperty(metricProperty);
        StringBuilder jpql = new StringBuilder("SELECT u FROM UploadData u WHERE u.").append(metricProperty).append(" IS NOT NULL");
        if (fileId != null) {
            jpql.append(" AND u.fileId = :fileId");
        }
        jpql.append(" ORDER BY u.id DESC");
        TypedQuery<UploadData> query = entityManager.createQuery(jpql.toString(), UploadData.class);
        if (fileId != null) {
            query.setParameter("fileId", fileId);
        }
        query.setMaxResults(maxResults);
        return query.getResultList();
    }

    public Map<String, Integer> countAvailableMetrics(Long fileId) {
        return uploadDataRepository.countAvailableMetrics(fileId);
    }

    public Map<String, Object> readKpiCache(Long fileId) {
        return kpiCacheService.readKpiCache(fileId);
    }

    public Map<String, Object> updateKpiCache(long fileId, java.util.function.UnaryOperator<Map<String, Object>> updater) {
        return kpiCacheService.updateKpiCache(fileId, updater);
    }

    @Transactional(readOnly = true)
    public List<UploadData> findAllKpis(Long fileId) {
        StringBuilder jpql = new StringBuilder("SELECT u FROM UploadData u");
        if (fileId != null) {
            jpql.append(" WHERE u.fileId = :fileId");
        }
        jpql.append(" ORDER BY u.id DESC");
        TypedQuery<UploadData> query = entityManager.createQuery(jpql.toString(), UploadData.class);
        if (fileId != null) {
            query.setParameter("fileId", fileId);
        }
        return query.getResultList();
    }

    private void validateMetricProperty(String metricProperty) {
        if (metricProperty == null || metricProperty.isBlank()) {
            throw new IllegalArgumentException("Metric property is required.");
        }

        switch (metricProperty) {
            case "ulPrbUtilizationRate",
                    "dlPrbUtilizationRate",
                    "ume4gDataVolumeStdMapsMb9035931",
                    "umeEutranIpThroughputUeUlStdKbps",
                    "umeEutranIpThroughputUeDlStdKbps",
                    "erabDropRate",
                    "initialErabEstablishmentSuccessRate",
                    "rrcEstablishmentSuccessRate",
                    "meanRrcConnectedUserNumber",
                    "maximumRrcConnectedUserNumber",
                    "erabSetupSuccessRate",
                    "rrcDropRate",
                    "volteCssrEric",
                    "volteDcrEric",
                    "interFreqHosr",
                    "intraFreqHosr",
                    "csfbSuccessRate" -> {
                // supported property
            }
            default -> throw new IllegalArgumentException("Unknown KPI metric property: " + metricProperty);
        }
    }
}

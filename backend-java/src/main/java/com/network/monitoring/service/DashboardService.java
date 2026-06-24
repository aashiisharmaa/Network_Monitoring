package com.network.monitoring.service;

import com.network.monitoring.entity.UploadData;
import com.network.monitoring.entity.UploadHistory;
import com.network.monitoring.repository.AlarmDataRepository;
import com.network.monitoring.repository.UploadDataRepository;
import com.network.monitoring.repository.UploadHistoryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardService {

    private final UploadDataRepository uploadDataRepository;
    private final UploadHistoryRepository uploadHistoryRepository;
    private final AlarmDataRepository alarmDataRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public DashboardService(UploadDataRepository uploadDataRepository,
                            UploadHistoryRepository uploadHistoryRepository,
                            AlarmDataRepository alarmDataRepository) {
        this.uploadDataRepository = uploadDataRepository;
        this.uploadHistoryRepository = uploadHistoryRepository;
        this.alarmDataRepository = alarmDataRepository;
    }

    public List<String> listDistinctCells() {
        return listDistinctValues("cellName", null);
    }

    public List<String> listDistinctCells(Long fileId) {
        return listDistinctValues("cellName", fileId);
    }

    public List<String> listDistinctSites() {
        return listDistinctValues("site", null);
    }

    public List<String> listDistinctSites(Long fileId) {
        return listDistinctValues("site", fileId);
    }

    public List<String> listDistinctBands() {
        return listDistinctValues("band", null);
    }

    public List<String> listDistinctBands(Long fileId) {
        return listDistinctValues("band", fileId);
    }

    public List<String> listDistinctTech() {
        return listDistinctValues("tech", null);
    }

    public List<String> listDistinctTech(Long fileId) {
        return listDistinctValues("tech", fileId);
    }

    public List<Map<String, Object>> listDistinctSectors() {
        return listDistinctSectors(null);
    }

    public List<Map<String, Object>> listDistinctSectors(Long fileId) {
        return listDistinctSectorSummaries(fileId);
    }

    public List<String> listDistinctGroups() {
        return listDistinctValues("groups", null);
    }

    public List<String> listDistinctGroups(Long fileId) {
        return listDistinctValues("groups", fileId);
    }

    public long countUploadRecords(Long fileId) {
        if (fileId == null) {
            return uploadDataRepository.count();
        }
        return uploadDataRepository.countByFileId(fileId);
    }

    public Map<String, Object> getLatestUploadPayload(Long fileId) {
        UploadHistory latest = getLatestUpload(fileId);
        if (latest == null) {
            return null;
        }

        Long uploadCount = latest.getId() == null ? 0L : uploadDataRepository.countByFileId(latest.getId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fileName", latest.getFileName());
        payload.put("uploadedBy", latest.getUploadedBy());
        payload.put("createdAt", latest.getCreatedAt());
        payload.put("_count", Map.of(
                "uploadData", uploadCount
        ));
        return payload;
    }

    public UploadHistory getLatestUpload(Long fileId) {
        if (fileId == null) {
            return uploadHistoryRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                    .stream()
                    .findFirst()
                    .orElse(null);
        }
        return uploadHistoryRepository.findById(fileId).orElse(null);
    }

    @Transactional(readOnly = true)
    public Map<String, List<Map<String, Object>>> getDetailedStats() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        result.put("bandDistribution", groupByCount("band"));
        result.put("techDistribution", groupByCount("tech"));
        result.put("groupDistribution", groupByCount("groups", "group"));
        result.put("sectorDistribution", groupByCount("sectorId", "sectorId"));
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getPerformanceMetrics(Long fileId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<UploadData> root = query.from(UploadData.class);

        query.multiselect(
                cb.avg(root.get("ulPrbUtilizationRate")).alias("avgUl"),
                cb.avg(root.get("dlPrbUtilizationRate")).alias("avgDl"),
                cb.avg(root.get("erabDropRate")).alias("avgErabDropRate"),
                cb.avg(root.get("rrcDropRate")).alias("avgRrcDropRate"),
                cb.avg(root.get("initialErabEstablishmentSuccessRate")).alias("avgErabSuccessRate"),
                cb.avg(root.get("rrcEstablishmentSuccessRate")).alias("avgRrcSuccessRate"),
                cb.avg(root.get("erabSetupSuccessRate")).alias("avgErabSetupRate"),
                cb.avg(root.get("volteCssrEric")).alias("avgVolteCssr"),
                cb.avg(root.get("volteDcrEric")).alias("avgVolteDcr"),
                cb.avg(root.get("interFreqHosr")).alias("avgInterFreqHosr"),
                cb.avg(root.get("intraFreqHosr")).alias("avgIntraFreqHosr"),
                cb.avg(root.get("csfbSuccessRate")).alias("avgCsfbSuccessRate"),
                cb.max(root.get("ulPrbUtilizationRate")).alias("maxUlUtilization"),
                cb.max(root.get("dlPrbUtilizationRate")).alias("maxDlUtilization"),
                cb.max(root.get("maximumRrcConnectedUserNumber")).alias("maxRrcUsers"),
                cb.min(root.get("erabDropRate")).alias("minErabDropRate"),
                cb.min(root.get("rrcDropRate")).alias("minRrcDropRate")
        );

        if (fileId != null) {
            query.where(cb.equal(root.get("fileId"), fileId));
        }

        Tuple tuple = entityManager.createQuery(query).getSingleResult();
        Map<String, Object> averages = new LinkedHashMap<>();
        averages.put("ulPrbUtilization", tuple.get("avgUl"));
        averages.put("dlPrbUtilization", tuple.get("avgDl"));
        averages.put("erabDropRate", tuple.get("avgErabDropRate"));
        averages.put("rrcDropRate", tuple.get("avgRrcDropRate"));
        averages.put("erabSuccessRate", tuple.get("avgErabSuccessRate"));
        averages.put("rrcSuccessRate", tuple.get("avgRrcSuccessRate"));
        averages.put("erabSetupRate", tuple.get("avgErabSetupRate"));
        averages.put("volteCssr", tuple.get("avgVolteCssr"));
        averages.put("volteDcr", tuple.get("avgVolteDcr"));
        averages.put("interFreqHosr", tuple.get("avgInterFreqHosr"));
        averages.put("intraFreqHosr", tuple.get("avgIntraFreqHosr"));
        averages.put("csfbSuccessRate", tuple.get("avgCsfbSuccessRate"));

        Map<String, Object> peaks = new LinkedHashMap<>();
        peaks.put("maxUlUtilization", tuple.get("maxUlUtilization"));
        peaks.put("maxDlUtilization", tuple.get("maxDlUtilization"));
        peaks.put("maxRrcUsers", tuple.get("maxRrcUsers"));
        peaks.put("minErabDropRate", tuple.get("minErabDropRate"));
        peaks.put("minRrcDropRate", tuple.get("minRrcDropRate"));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileId", fileId);
        response.put("averages", averages);
        response.put("peaks", peaks);
        return response;
    }

    private List<String> listDistinctValues(String property, Long fileId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<String> query = cb.createQuery(String.class);
        Root<UploadData> root = query.from(UploadData.class);
        Path<String> path = root.get(property);
        query.select(path).distinct(true);
        List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
        predicates.add(cb.isNotNull(path));
        if (fileId != null) {
            predicates.add(cb.equal(root.get("fileId"), fileId));
        }
        query.where(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        query.orderBy(cb.asc(path));
        return entityManager.createQuery(query).getResultList();
    }

    private List<Map<String, Object>> groupByCount(String property) {
        return groupByCount(property, property);
    }

    private List<Map<String, Object>> groupByCount(String property, String outputKey) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<UploadData> root = query.from(UploadData.class);
        Path<String> path = root.get(property);
        query.multiselect(path.alias("value"), cb.count(path).alias("count"));
        query.where(cb.isNotNull(path));
        query.groupBy(path);
        query.orderBy(cb.desc(cb.count(path)));
        List<Tuple> rows = entityManager.createQuery(query).getResultList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Tuple row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put(outputKey, row.get("value"));
            item.put("count", row.get("count"));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> listDistinctSectorSummaries(Long fileId) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT `Sector_ID` AS id, MIN(`Sector_Name`) AS name ");
        sql.append("FROM `uploaddata` ");
        sql.append("WHERE `Sector_ID` IS NOT NULL");
        if (fileId != null) {
            sql.append(" AND `fileId` = :fileId");
        }
        sql.append(" GROUP BY `Sector_ID` ORDER BY `Sector_ID` ASC");

        Query query = entityManager.createNativeQuery(sql.toString());
        if (fileId != null) {
            query.setParameter("fileId", fileId);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> sectors = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.length > 0 ? row[0] : null);
            item.put("name", row.length > 1 ? row[1] : null);
            sectors.add(item);
        }
        return sectors;
    }
}

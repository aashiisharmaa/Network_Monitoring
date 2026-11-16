package com.network.monitoring.kpi.repository;

import com.network.monitoring.kpi.entity.UploadData;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadDataRepository extends JpaRepository<UploadData, Long>, UploadDataRepositoryCustom {
    List<UploadData> findDistinctByCellNameIsNotNullOrderByCellNameAsc();
    List<UploadData> findDistinctBySiteIsNotNullOrderBySiteAsc();
    List<UploadData> findDistinctByBandIsNotNullOrderByBandAsc();
    List<UploadData> findDistinctByTechIsNotNullOrderByTechAsc();
    List<UploadData> findDistinctBySectorIdIsNotNullOrderBySectorIdAsc();
    List<UploadData> findDistinctByGroupsIsNotNullOrderByGroupsAsc();
    List<UploadData> findAllByFileId(Long fileId);
    long countByFileId(Long fileId);
    long deleteByFileId(Long fileId);
    Page<UploadData> findAllByOrderByIdDesc(Pageable pageable);
}

package com.network.monitoring.repository;

import com.network.monitoring.entity.UploadData;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

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

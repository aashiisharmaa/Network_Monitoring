package com.network.monitoring.repository;

import com.network.monitoring.entity.AlarmData;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface AlarmDataRepository extends JpaRepository<AlarmData, Long>, JpaSpecificationExecutor<AlarmData> {
    List<AlarmData> findAllByFileId(Long fileId);
    long countByFileId(Long fileId);
    long deleteByFileId(Long fileId);
    Page<AlarmData> findAllByOrderByIdDesc(Pageable pageable);
}

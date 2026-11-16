package com.network.monitoring.kpi.repository;

import com.network.monitoring.kpi.entity.AlarmData;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AlarmDataRepository extends JpaRepository<AlarmData, Long>, JpaSpecificationExecutor<AlarmData> {
    List<AlarmData> findAllByFileId(Long fileId);
    long countByFileId(Long fileId);
    long deleteByFileId(Long fileId);
    Page<AlarmData> findAllByOrderByIdDesc(Pageable pageable);
}

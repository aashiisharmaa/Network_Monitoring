package com.network.monitoring.kpi.repository;

import com.network.monitoring.kpi.entity.Threshold;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ThresholdRepository extends JpaRepository<Threshold, Long> {
    Optional<Threshold> findByFileId(Long fileId);
    Optional<Threshold> findFirstByFileIdIsNullOrderByIdAsc();
}

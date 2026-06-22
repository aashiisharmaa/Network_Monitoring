package com.network.monitoring.repository;

import com.network.monitoring.entity.Threshold;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ThresholdRepository extends JpaRepository<Threshold, Long> {
    Optional<Threshold> findByFileId(Long fileId);
    Optional<Threshold> findFirstByFileIdIsNullOrderByIdAsc();
}

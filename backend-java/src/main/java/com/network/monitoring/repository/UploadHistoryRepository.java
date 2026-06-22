package com.network.monitoring.repository;

import com.network.monitoring.entity.UploadHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UploadHistoryRepository extends JpaRepository<UploadHistory, Long> {
    Optional<UploadHistory> findFirstByOrderByCreatedAtDesc();
}

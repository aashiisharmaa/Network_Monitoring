package com.network.monitoring.ingestion.repository;

import com.network.monitoring.ingestion.entity.UploadHistory;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UploadHistoryRepository extends JpaRepository<UploadHistory, Long> {
    Optional<UploadHistory> findFirstByOrderByCreatedAtDesc();
}

package com.network.monitoring.ingestion.repository;

import com.network.monitoring.ingestion.entity.UploadHistory;
import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UploadHistoryRepository extends JpaRepository<UploadHistory, Long> {
    Optional<UploadHistory> findFirstByOrderByCreatedAtDesc();
    List<UploadHistory> findAllByCompanyId(Long companyId, Sort sort);
}

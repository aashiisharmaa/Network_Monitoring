package com.network.monitoring.spatial.repository;

import com.network.monitoring.spatial.entity.SiteData;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SiteDataRepository extends JpaRepository<SiteData, Long> {
    Page<SiteData> findAllByOrderByIdDesc(Pageable pageable);
    Page<SiteData> findAllByFileId(Long fileId, Pageable pageable);
    List<SiteData> findByFileId(Long fileId);
    long deleteByFileId(Long fileId);
}

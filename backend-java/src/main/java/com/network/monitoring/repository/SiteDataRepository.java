package com.network.monitoring.repository;

import com.network.monitoring.entity.SiteData;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SiteDataRepository extends JpaRepository<SiteData, Long> {
    Page<SiteData> findAllByOrderByIdDesc(Pageable pageable);
    Page<SiteData> findAllByFileId(Long fileId, Pageable pageable);
    List<SiteData> findByFileId(Long fileId);
    long deleteByFileId(Long fileId);
}

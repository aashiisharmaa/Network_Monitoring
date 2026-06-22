package com.network.monitoring.repository;

import com.network.monitoring.entity.SiteData;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SiteDataRepository extends JpaRepository<SiteData, Long> {
    Page<SiteData> findAllByOrderByIdDesc(Pageable pageable);
}

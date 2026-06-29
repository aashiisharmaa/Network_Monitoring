package com.network.monitoring.repository;

import com.network.monitoring.entity.KpiThresholdRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KpiThresholdRuleRepository extends JpaRepository<KpiThresholdRule, Long> {
    List<KpiThresholdRule> findAllByEnabledTrue();
}

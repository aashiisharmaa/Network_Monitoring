package com.network.monitoring.kpi.repository;

import com.network.monitoring.kpi.entity.KpiThresholdRule;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KpiThresholdRuleRepository extends JpaRepository<KpiThresholdRule, Long> {
    List<KpiThresholdRule> findAllByEnabledTrue();
}

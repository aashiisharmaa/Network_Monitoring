package com.network.monitoring.kpi.repository;

import com.network.monitoring.kpi.entity.Settings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingsRepository extends JpaRepository<Settings, Long> {
}

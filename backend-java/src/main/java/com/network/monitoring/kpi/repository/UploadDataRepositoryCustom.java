package com.network.monitoring.kpi.repository;

import java.util.Map;

public interface UploadDataRepositoryCustom {
    Map<String, Integer> countAvailableMetrics(Long fileId);
}

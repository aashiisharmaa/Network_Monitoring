package com.network.monitoring.repository;

import java.util.Map;

public interface UploadDataRepositoryCustom {
    Map<String, Integer> countAvailableMetrics(Long fileId);
}

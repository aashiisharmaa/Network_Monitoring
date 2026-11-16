package com.network.monitoring.architecture;

import jakarta.persistence.Entity;
import java.util.Set;
import java.util.stream.Collectors;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import static org.junit.jupiter.api.Assertions.*;

/** Validates moved entities/associations/converters without opening a DB connection. */
class PersistenceMappingTest {
    @Test void allExistingEntitiesAndCustomRepositoryFragmentRemainDiscoverable() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        var entities = scanner.findCandidateComponents("com.network.monitoring");
        try (var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .applySetting("hibernate.dialect", "org.hibernate.dialect.MySQLDialect").build()) {
            var sources = new MetadataSources(registry);
            for (var entity : entities) sources.addAnnotatedClass(Class.forName(entity.getBeanClassName()));
            var metadata = sources.buildMetadata();
            ((org.hibernate.boot.spi.MetadataImplementor) metadata).validate();
            assertEquals(Set.of("Company", "User", "Session", "AppLicense", "LicenseAuditLog", "UploadHistory",
                    "UploadData", "Threshold", "Settings", "KpiThresholdRule", "AlarmData", "SiteData"),
                    metadata.getEntityBindings().stream().map(e -> e.getMappedClass().getSimpleName()).collect(Collectors.toSet()));
        }
        assertEquals(com.network.monitoring.kpi.repository.UploadDataRepositoryCustom.class.getPackageName(),
                com.network.monitoring.kpi.repository.UploadDataRepositoryImpl.class.getPackageName());
        assertTrue(com.network.monitoring.kpi.repository.UploadDataRepositoryCustom.class
                .isAssignableFrom(com.network.monitoring.kpi.repository.UploadDataRepositoryImpl.class));
    }
}

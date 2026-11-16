package com.network.monitoring.architecture;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import static org.junit.jupiter.api.Assertions.*;

class BackendArchitectureTest {
    private static final Path ROOT = Path.of("src/main/java/com/network/monitoring");
    private static final Set<String> FEATURES = Set.of("alarm", "identity", "licensing", "ingestion", "kpi",
            "spatial", "rca", "reporting", "nightly", "platform", "shared");

    @Test void classesHaveFeatureOwnershipAndDependenciesPointOutOfSharedCode() throws Exception {
        try (var files = Files.walk(ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Path relative = ROOT.relativize(file);
                if (relative.toString().equals("Application.java")) continue;
                assertTrue(FEATURES.contains(relative.getName(0).toString()), "Missing feature owner: " + file);
                String source = Files.readString(file);
                String parent = ROOT.relativize(file.getParent()).toString().replace('\\', '.').replace('/', '.');
                assertTrue(source.contains("package com.network.monitoring." + parent + ";"), "Wrong Java package: " + file);
                for (String line : source.lines().filter(l -> l.startsWith("import ")).toList()) {
                    if (relative.getName(0).toString().equals("shared") && line.contains("com.network.monitoring."))
                        assertTrue(line.contains("com.network.monitoring.shared."), "Shared code imports a feature: " + line);
                    if (parent.endsWith(".repository"))
                        assertFalse(line.contains(".service.") || line.contains(".api."), "Repository calls an upper layer: " + file);
                    if (parent.endsWith(".api"))
                        assertFalse(line.contains("org.springframework.jdbc") || line.contains("jakarta.persistence"), "HTTP layer imports database code: " + file);
                }
            }
        }
    }

    @Test void springFindsFeatureComponentsWithoutDuplicateBeanNames() {
        var registry = new DefaultListableBeanFactory();
        registry.setAllowBeanDefinitionOverriding(false);
        var scanner = new ClassPathBeanDefinitionScanner(registry, false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        scanner.scan("com.network.monitoring");
        for (String bean : List.of("authService", "licenseService", "uploadService", "kpiService",
                "sitePredictionService", "recommendationService", "commonReportService", "nightlyWorkflowService",
                "alarmWorkflowService", "reportMailTransport", "reportDatasetRepository", "securityConfig"))
            assertTrue(registry.containsBeanDefinition(bean), "Missing Spring component: " + bean);
    }
}

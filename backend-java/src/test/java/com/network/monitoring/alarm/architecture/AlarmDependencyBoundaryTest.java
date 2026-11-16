package com.network.monitoring.alarm.architecture;

import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Prevents framework/IO concerns from leaking back into contracts and normalization. */
class AlarmDependencyBoundaryTest {
    private static final Path ROOT = Path.of("src/main/java/com/network/monitoring/alarm");

    @Test void contractsRepositoriesAndControllersKeepTheirBoundaries() throws Exception {
        forbidden("dto", List.of(".alarm.service.", ".alarm.repository.", ".alarm.ingestion.",
                "org.springframework", "java.sql", "java.nio.file", "jakarta.mail"));
        forbidden("repository", List.of(".alarm.service.", ".alarm.api.", ".alarm.storage.", "jakarta.mail"));
        forbidden("api", List.of("org.springframework.jdbc", "java.sql", "java.nio.file", "jakarta.mail"));
        forbidden("ingestion/AlarmObservationNormalizer.java", List.of("org.springframework.jdbc", "java.sql", "java.nio.file", "jakarta.mail"));
        forbidden("scheduling/AlarmFolderWatcher.java", List.of("jakarta.mail", "ReportMailTransport"));
        forbidden("storage", List.of("org.springframework.jdbc", "java.sql", ".alarm.service.", "jakarta.mail"));
    }

    private void forbidden(String relative, List<String> dependencies) throws Exception {
        Path path = ROOT.resolve(relative);
        try (var sources = Files.walk(path)) {
            for (Path file : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    if (!line.startsWith("import ")) continue;
                    for (String dependency : dependencies)
                        assertFalse(line.contains(dependency), file + " imports forbidden dependency " + dependency);
                }
            }
        }
    }
}

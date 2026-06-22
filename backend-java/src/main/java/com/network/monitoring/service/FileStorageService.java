package com.network.monitoring.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;

@Service
public class FileStorageService {

    private final Path basePath;

    public FileStorageService(@Value("${app.upload-base-path}") String basePath) {
        Path configured = Paths.get(basePath == null || basePath.isBlank() ? "uploads" : basePath.trim());
        this.basePath = configured.isAbsolute()
                ? configured.toAbsolutePath().normalize()
                : resolveWorkspaceRoot().resolve(configured).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.basePath);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to create upload directory", ex);
        }
    }

    public Path storeFile(MultipartFile file) throws IOException {
        String folderName = LocalDate.now().toString();
        Path folder = basePath.resolve(folderName);
        Files.createDirectories(folder);
        String filename = System.currentTimeMillis() + "-" + file.getOriginalFilename();
        Path target = folder.resolve(filename);
        file.transferTo(target.toFile());
        return target;
    }

    private Path resolveWorkspaceRoot() {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path name = cwd.getFileName();
        String current = name == null ? "" : name.toString().toLowerCase();
        if ("backend-java".equals(current) || "backend".equals(current)) {
            Path parent = cwd.getParent();
            if (parent != null) {
                return parent;
            }
        }
        return cwd;
    }
}

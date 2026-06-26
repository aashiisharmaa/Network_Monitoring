package com.network.monitoring.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class KpiUploadPreviewService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".xlsx", ".xls", ".csv");

    private final ParserService parserService;
    private final FileStorageService fileStorageService;

    public KpiUploadPreviewService(ParserService parserService, FileStorageService fileStorageService) {
        this.parserService = parserService;
        this.fileStorageService = fileStorageService;
    }

    public Map<String, Object> preview(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return Map.of("success", false, "message", "No file uploaded.");
        }

        String originalName = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String ext = getExtension(originalName);
        if (!ALLOWED_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT))) {
            return Map.of("success", false, "message", "Invalid file type. Only .xlsx, .xls, and .csv files are supported.");
        }

        Path storedPath = fileStorageService.storeFile(file);
        try {
            return parserService.previewFile(storedPath);
        } finally {
            Files.deleteIfExists(storedPath);
        }
    }

    private String getExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot != -1 ? filename.substring(lastDot).toLowerCase(Locale.ROOT) : "";
    }
}

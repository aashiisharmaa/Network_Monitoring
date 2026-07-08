package com.network.monitoring.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class LbPredictionService {

    private final KpiDynamicDataService kpiDynamicDataService;
    private final KpiCacheService kpiCacheService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.python-service-path}")
    private String pythonServicePath;

    @Value("${app.python-venv-path:}")
    private String pythonVenvPath;

    @Value("${app.python-bin:}")
    private String pythonBin;

    public LbPredictionService(KpiDynamicDataService kpiDynamicDataService,
                               KpiCacheService kpiCacheService) {
        this.kpiDynamicDataService = kpiDynamicDataService;
        this.kpiCacheService = kpiCacheService;
    }

    public Map<String, Object> runWcf(Long fileId, MultipartFile taFile, Map<String, Object> request) throws IOException {
        if (fileId == null || fileId <= 0) {
            throw new IllegalArgumentException("fileId is required.");
        }
        List<Map<String, Object>> records = kpiDynamicDataService.findRecordsByFileId(fileId);
        if (records.isEmpty()) {
            records = kpiDynamicDataService.getRecordsFromCachePayload(kpiCacheService.readKpiCache(fileId));
        }
        if (records.isEmpty()) {
            throw new IllegalStateException("No KPI records found for fileId " + fileId + ".");
        }

        Path workDir = Files.createTempDirectory("lb-wcf-");
        Path kpiInput = workDir.resolve("kpi_input.xlsx");
        Path configPath = workDir.resolve("config.json");
        Path resultPath = workDir.resolve("result.json");
        Path outputPath = workDir.resolve("lb_wcf_output.xlsx");
        Path taInput = null;

        try {
            writeWorkbook(flattenRecords(records), kpiInput);
            Map<String, Object> config = buildConfig(request);
            Files.writeString(configPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config), StandardCharsets.UTF_8);

            if (taFile != null && !taFile.isEmpty()) {
                String name = taFile.getOriginalFilename() == null ? "ta_input.xlsx" : taFile.getOriginalFilename();
                taInput = workDir.resolve(safeFilename(name));
                taFile.transferTo(taInput.toFile());
            }

            List<String> args = new ArrayList<>(resolvePythonCommand());
            args.add(getPythonMainPath().toString());
            args.add("run-lb-wcf");
            args.add("--kpi-input");
            args.add(kpiInput.toString());
            if (taInput != null) {
                args.add("--ta-input");
                args.add(taInput.toString());
            }
            args.add("--config");
            args.add(configPath.toString());
            args.add("--json-out");
            args.add(resultPath.toString());
            args.add("--output");
            args.add(outputPath.toString());

            ProcessResult result = executeProcess(args, getWorkspaceRoot());
            if (result.exitCode != 0) {
                throw new IllegalArgumentException(cleanPythonError(result.stderr.isBlank() ? result.stdout : result.stderr));
            }
            if (!Files.exists(resultPath)) {
                throw new IllegalStateException("Python LB prediction did not produce JSON output.");
            }

            Map<String, Object> payload = mapper.readValue(Files.readString(resultPath, StandardCharsets.UTF_8), new TypeReference<>() {});
            payload.put("fileId", fileId);
            payload.putIfAbsent("taInputUsed", taInput != null);
            return payload;
        } finally {
            cleanup(workDir);
        }
    }

    private Map<String, Object> buildConfig(Map<String, Object> request) {
        Map<String, Object> config = new LinkedHashMap<>();
        String method = stringValue(request == null ? null : request.get("method"));
        config.put("method", method.isBlank() ? "both" : method);
        config.put("quantile", numberOrDefault(request == null ? null : request.get("quantile"), 0.10));
        config.put("ml_mode", (int) numberOrDefault(request == null ? null : request.get("mlMode"), 3));
        return config;
    }

    private List<Map<String, Object>> flattenRecords(List<Map<String, Object>> records) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> record : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("Date", record.get("date"));
            row.put("Cell", firstNonBlank(record.get("cellName"), dimension(record, "cell", "cellname", "shortname", "short_name")));
            row.put("Site", firstNonBlank(record.get("site"), dimension(record, "site", "siteid", "site_id")));
            row.put("Band", firstNonBlank(record.get("band"), dimension(record, "band", "layer", "carrier")));
            row.put("Sector", firstNonBlank(record.get("sectorid"), record.get("sectorname"), dimension(record, "sector", "sectorid", "sector_id")));
            row.put("Groups", firstNonBlank(record.get("groups"), dimension(record, "groups", "group", "cluster")));

            objectMap(record.get("dimensions")).forEach(row::put);
            Map<String, Object> metrics = objectMap(record.get("metrics"));
            metrics.forEach(row::put);
            addAlias(row, metrics, "DL_throughput", "dlthroughput", "userdl", "thpdl", "throughputuedl", "dluserthroughput");
            addAlias(row, metrics, "PRB DL Utilization %", "dlprb", "prbdl", "dlresourceblock", "dlutilization", "dlutilisation");
            addAlias(row, metrics, "Total Traffic(MB)", "trafficmb", "datavolumemb", "traffic");
            addAlias(row, metrics, "Total Traffic(GB)", "trafficgb", "datavolumegb");
            addAlias(row, metrics, "Avg_number_of_RRC_users", "meanrrc", "avgconnecteduser", "connecteduser");
            addAlias(row, metrics, "Inter_freq_HOSR%", "interfreqhosr", "interfrequencyhosr");
            addAlias(row, metrics, "RRC_SR%", "rrcsuccess", "rrcsr", "rrcsetup");
            rows.add(row);
        }
        return rows;
    }

    private void addAlias(Map<String, Object> row, Map<String, Object> metrics, String alias, String... tokens) {
        if (row.get(alias) != null) {
            return;
        }
        for (Map.Entry<String, Object> entry : metrics.entrySet()) {
            String key = normalize(entry.getKey());
            for (String token : tokens) {
                if (key.contains(normalize(token))) {
                    row.put(alias, entry.getValue());
                    return;
                }
            }
        }
    }

    private void writeWorkbook(List<Map<String, Object>> rows, Path outputPath) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200)) {
            workbook.setCompressTempFiles(true);
            Sheet sheet = workbook.createSheet("Input");
            LinkedHashSet<String> headers = new LinkedHashSet<>();
            for (Map<String, Object> row : rows) {
                headers.addAll(row.keySet());
            }
            Row headerRow = sheet.createRow(0);
            int col = 0;
            for (String header : headers) {
                headerRow.createCell(col++).setCellValue(header);
            }
            int rowIndex = 1;
            for (Map<String, Object> item : rows) {
                Row excelRow = sheet.createRow(rowIndex++);
                col = 0;
                for (String header : headers) {
                    Cell cell = excelRow.createCell(col++);
                    Object value = item.get(header);
                    if (value instanceof Number number) {
                        cell.setCellValue(number.doubleValue());
                    } else if (value instanceof Boolean bool) {
                        cell.setCellValue(bool);
                    } else if (value != null) {
                        cell.setCellValue(String.valueOf(value));
                    }
                }
            }
            try (var stream = Files.newOutputStream(outputPath)) {
                workbook.write(stream);
            } finally {
                workbook.dispose();
            }
        }
    }

    private List<String> resolvePythonCommand() {
        String envBin = pythonBin == null ? "" : pythonBin.trim();
        if (!envBin.isEmpty() && canExecutePythonCommand(envBin)) return List.of(envBin);
        String venvPython = pythonVenvPath == null ? "" : pythonVenvPath.trim();
        if (!venvPython.isEmpty() && canExecutePythonCommand(venvPython)) return List.of(venvPython);
        Path defaultVenvPython = getWorkspaceRoot().resolve("python service").resolve(".venv").resolve("Scripts").resolve("python.exe");
        if (Files.exists(defaultVenvPython) && canExecutePythonCommand(defaultVenvPython.toString())) return List.of(defaultVenvPython.toString());
        if (canExecutePythonCommand("py", "-3")) return List.of("py", "-3");
        if (canExecutePythonCommand("python3")) return List.of("python3");
        if (canExecutePythonCommand("python")) return List.of("python");
        throw new IllegalStateException("Python runtime could not be resolved. Set PYTHON_BIN or PROJECT_VENV_PYTHON.");
    }

    private boolean canExecutePythonCommand(String... commandAndArgs) {
        try {
            String[] probe = Arrays.copyOf(commandAndArgs, commandAndArgs.length + 1);
            probe[probe.length - 1] = "--version";
            Process process = new ProcessBuilder(probe).redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private ProcessResult executeProcess(List<String> args, Path cwd) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(args);
        builder.directory(cwd.toFile());
        Process process = builder.start();
        String stdout = readStream(process.getInputStream());
        String stderr = readStream(process.getErrorStream());
        try {
            return new ProcessResult(process.waitFor(), stdout, stderr);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Python process interrupted", ex);
        }
    }

    private String readStream(java.io.InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) builder.append(line).append(System.lineSeparator());
            return builder.toString().trim();
        }
    }

    private Path getPythonServiceDir() {
        Path configured = Paths.get(pythonServicePath == null || pythonServicePath.isBlank() ? "python service" : pythonServicePath.trim());
        return configured.isAbsolute() ? configured.toAbsolutePath().normalize() : getWorkspaceRoot().resolve(configured).toAbsolutePath().normalize();
    }

    private Path getPythonMainPath() {
        return getPythonServiceDir().resolve("main.py");
    }

    private Path getWorkspaceRoot() {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        String name = cwd.getFileName() == null ? "" : cwd.getFileName().toString().toLowerCase(Locale.ROOT);
        if ("backend-java".equals(name) || "backend".equals(name)) {
            Path parent = cwd.getParent();
            if (parent != null) return parent;
        }
        return cwd;
    }

    private void cleanup(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try {
            Files.walk(directory).sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private Object dimension(Map<String, Object> record, String... keys) {
        Map<String, Object> dimensions = objectMap(record.get("dimensions"));
        for (String key : keys) {
            Object value = dimensions.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return value;
        }
        return null;
    }

    private Object firstNonBlank(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) return value;
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private double numberOrDefault(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String safeFilename(String value) {
        String cleaned = String.valueOf(value == null ? "input.xlsx" : value).replaceAll("[\\\\/:*?\"<>|]", "_");
        return cleaned.isBlank() ? "input.xlsx" : cleaned;
    }

    private String cleanPythonError(String value) {
        String message = stringValue(value);
        if (message.isBlank()) {
            return "LB/WCF prediction failed.";
        }
        String[] lines = message.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            int valueErrorIndex = line.indexOf("ValueError:");
            if (valueErrorIndex >= 0) {
                return line.substring(valueErrorIndex + "ValueError:".length()).trim();
            }
            if (!line.isBlank() && !line.startsWith("File ") && !line.startsWith("Traceback")) {
                return line;
            }
        }
        return message;
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }
}

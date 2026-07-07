package com.network.monitoring.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.monitoring.entity.UploadData;
import com.network.monitoring.repository.UploadDataRepository;
import com.network.monitoring.service.KpiCacheService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;

@Service
public class RecommendationService {

    private static final int DEFAULT_INPUT_ROW_LIMIT = 10000;
    private static final int MAX_INPUT_ROW_LIMIT = 50000;

    private final KpiCacheService kpiCacheService;
    private final KpiDynamicDataService kpiDynamicDataService;
    private final UploadDataRepository uploadDataRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.python-service-path}")
    private String pythonServicePath;

    @Value("${app.python-venv-path:}")
    private String pythonVenvPath;

    @Value("${app.python-bin:}")
    private String pythonBin;

    @Value("${app.perf-logs-enabled:false}")
    private String perfLogsEnabledRaw;

    public RecommendationService(KpiCacheService kpiCacheService,
                                 KpiDynamicDataService kpiDynamicDataService,
                                 UploadDataRepository uploadDataRepository) {
        this.kpiCacheService = kpiCacheService;
        this.kpiDynamicDataService = kpiDynamicDataService;
        this.uploadDataRepository = uploadDataRepository;
    }

    public RecommendationResult runRecommendation(long fileId, Map<String, Object> body, boolean exportExcel) throws IOException {
        long startedAt = System.currentTimeMillis();
        ensurePythonEntry();

        Map<String, Object> cache = kpiCacheService.readKpiCache(fileId);
        List<Map<String, Object>> rows = cache == null ? List.of() : toCanonicalRows(cache);
        if (rows.isEmpty()) {
            rows = kpiDynamicDataService.findRecordsByFileId(fileId);
        }
        if (rows.isEmpty()) {
            rows = toCanonicalRows(uploadDataRepository.findAllByFileId(fileId));
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("No KPI data found for fileId " + fileId + ".");
        }

        int inputRowLimit = normalizeRowLimit(body == null ? null : body.get("inputRowLimit"));
        List<Map<String, Object>> preparedRows = prepareRowsForPython(rows, body == null ? Map.of() : body, inputRowLimit);

        Path workDir = Files.createTempDirectory("rca-run-");
        Path inputPath = workDir.resolve("input.xlsx");
        Path configPath = workDir.resolve("config.json");
        Path jsonOutPath = workDir.resolve("result.json");
        Path excelOutPath = workDir.resolve("result.xlsx");

        writeWorkbook(preparedRows, inputPath);
        Map<String, Object> config = buildPythonConfig(body);
        config.put("input_row_limit", inputRowLimit);
        config.put("input_rows_sent", preparedRows.size());
        config.put("source_rows_available", rows.size());
        Files.writeString(configPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config), StandardCharsets.UTF_8);

        List<String> args = new ArrayList<>(resolvePythonCommand());
        args.add(getPythonMainPath().toString());
        args.add("run");
        args.add("--input");
        args.add(inputPath.toString());
        args.add("--config");
        args.add(configPath.toString());
        args.add("--json-out");
        args.add(jsonOutPath.toString());
        if (exportExcel) {
            args.add("--export-excel");
            args.add("--excel-out");
            args.add(excelOutPath.toString());
        }

        ProcessResult result = executeProcess(args, getWorkspaceRoot());
        if (result.exitCode != 0) {
            cleanup(workDir);
            throw new IllegalStateException(result.stderr.isBlank() ? result.stdout : result.stderr);
        }

        if (!Files.exists(jsonOutPath)) {
            cleanup(workDir);
            throw new IllegalStateException("Python RCA did not produce JSON output.");
        }

        Map<String, Object> payload = mapper.readValue(Files.readString(jsonOutPath, StandardCharsets.UTF_8), new TypeReference<>() {});
        if (isTruthy(perfLogsEnabledRaw)) {
            logPerf("runRecommendation", fileId, preparedRows.size(), System.currentTimeMillis() - startedAt);
        }

        return new RecommendationResult(payload, exportExcel ? excelOutPath : null, workDir);
    }

    public List<String> listPresets() throws IOException {
        Map<String, Object> presets = readPresetsFile();
        return new ArrayList<>(presets.keySet());
    }

    public void savePreset(String name, Map<String, Object> config) throws IOException {
        Path path = getPythonServiceDir().resolve("rca_presets.json");
        Files.createDirectories(path.getParent());
        Map<String, Object> presets = new java.util.LinkedHashMap<>(readPresetsFile());
        presets.put(name, config);
        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(presets), StandardCharsets.UTF_8);
    }

    public Map<String, Object> loadPreset(String name) throws IOException {
        Map<String, Object> presets = readPresetsFile();
        Object config = presets.get(name);
        if (config == null) {
            throw new IllegalArgumentException("Preset not found: " + name);
        }
        return mapper.convertValue(config, new TypeReference<>() {});
    }

    private void writeWorkbook(List<Map<String, Object>> rows, Path outputPath) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200)) {
            workbook.setCompressTempFiles(true);
            Sheet sheet = workbook.createSheet("Input");
            LinkedHashSet<String> headers = new LinkedHashSet<>();
            for (Map<String, Object> row : rows) {
                headers.addAll(row.keySet());
            }
            int headerIndex = 0;
            Row headerRow = sheet.createRow(0);
            for (String header : headers) {
                Cell cell = headerRow.createCell(headerIndex++);
                cell.setCellValue(header);
            }
            int rowIndex = 1;
            for (Map<String, Object> row : rows) {
                Row excelRow = sheet.createRow(rowIndex++);
                int colIndex = 0;
                for (String header : headers) {
                    Cell cell = excelRow.createCell(colIndex++);
                    Object value = row.get(header);
                    if (value == null) {
                        continue;
                    }
                    if (value instanceof Number number) {
                        cell.setCellValue(number.doubleValue());
                    } else if (value instanceof Boolean bool) {
                        cell.setCellValue(bool);
                    } else {
                        cell.setCellValue(value.toString());
                    }
                }
            }
            Files.createDirectories(outputPath.getParent());
            try (var stream = Files.newOutputStream(outputPath)) {
                workbook.write(stream);
                workbook.dispose();
            }
        }
    }

    private List<Map<String, Object>> prepareRowsForPython(List<Map<String, Object>> rows,
                                                           Map<String, Object> body,
                                                           int rowLimit) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<String> neededColumns = resolveNeededColumns(body);
        List<Map<String, Object>> source = rows.size() <= rowLimit ? rows : evenlySample(rows, rowLimit);
        if (neededColumns.isEmpty()) {
            return source;
        }
        List<Map<String, Object>> prepared = new ArrayList<>(source.size());
        for (Map<String, Object> row : source) {
            Map<String, Object> slim = new java.util.LinkedHashMap<>();
            for (String column : neededColumns) {
                if (row.containsKey(column)) {
                    slim.put(column, row.get(column));
                }
            }
            if (!slim.isEmpty()) {
                prepared.add(slim);
            }
        }
        return prepared.isEmpty() ? source : prepared;
    }

    private List<Map<String, Object>> evenlySample(List<Map<String, Object>> rows, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, rows.size()));
        if (rows.size() <= safeLimit) {
            return rows;
        }
        List<Map<String, Object>> sampled = new ArrayList<>(safeLimit);
        double step = (double) rows.size() / safeLimit;
        for (int i = 0; i < safeLimit; i++) {
            sampled.add(rows.get(Math.min(rows.size() - 1, (int) Math.floor(i * step))));
        }
        return sampled;
    }

    private List<String> resolveNeededColumns(Map<String, Object> body) {
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        if (body == null) {
            return List.of();
        }
        addMapValues(columns, body.get("columnMapping"));
        addMapValues(columns, body.get("kpiMapping"));
        return columns.stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private void addMapValues(LinkedHashSet<String> columns, Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return;
        }
        for (Object item : map.values()) {
            if (item == null) {
                continue;
            }
            String text = String.valueOf(item).trim();
            if (!text.isBlank()) {
                columns.add(text);
            }
        }
    }

    private int normalizeRowLimit(Object value) {
        int parsed = DEFAULT_INPUT_ROW_LIMIT;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else if (value != null) {
            try {
                parsed = Integer.parseInt(String.valueOf(value));
            } catch (Exception ignored) {
                parsed = DEFAULT_INPUT_ROW_LIMIT;
            }
        }
        if (parsed <= 0) {
            return DEFAULT_INPUT_ROW_LIMIT;
        }
        return Math.min(parsed, MAX_INPUT_ROW_LIMIT);
    }

    private Map<String, Object> buildPythonConfig(Map<String, Object> body) {
        Map<String, Object> config = new HashMap<>();
        config.put("vendor", body.getOrDefault("vendor", "nokia"));
        Object preset = body.get("preset");
        if (preset instanceof String presetName) {
            if (!presetName.isBlank()) {
                config.put("preset", presetName.trim());
            }
        } else if (preset != null && !preset.toString().isBlank()) {
            config.put("preset", preset.toString().trim());
        }
        config.put("sheet_name", body.get("sheetName"));
        config.put("column_mapping", body.getOrDefault("columnMapping", Map.of()));
        config.put("kpi_mapping", body.getOrDefault("kpiMapping", Map.of()));
        config.put("selected_kpis", body.getOrDefault("selectedKpis", List.of()));
        config.put("kpis", body.getOrDefault("kpis", Map.of()));
        config.put("rca_thresholds", body.getOrDefault("rcaThresholds", Map.of()));
        config.put("severity_thresholds", body.getOrDefault("severityThresholds", Map.of()));
        config.put("anomaly_thresholds", body.getOrDefault("anomalyThresholds", Map.of()));
        config.put("date_mode", "pre_post".equals(body.get("dateMode")) ? "pre_post" : "single");

        Map<String, Object> dateFilter = new HashMap<>();
        dateFilter.put("start_date", body.getOrDefault("startDate", null));
        dateFilter.put("end_date", body.getOrDefault("endDate", null));
        config.put("date_filter", dateFilter);

        Map<String, Object> preDateFilter = new HashMap<>();
        preDateFilter.put("start_date", body.getOrDefault("preStartDate", null));
        preDateFilter.put("end_date", body.getOrDefault("preEndDate", null));
        config.put("pre_date_filter", preDateFilter);

        Map<String, Object> postDateFilter = new HashMap<>();
        postDateFilter.put("start_date", body.getOrDefault("postStartDate", null));
        postDateFilter.put("end_date", body.getOrDefault("postEndDate", null));
        config.put("post_date_filter", postDateFilter);
        return config;
    }

    private Map<String, Object> readPresetsFile() throws IOException {
        Path path = getPythonServiceDir().resolve("rca_presets.json");
        if (!Files.exists(path)) {
            return Map.of();
        }
        String content = Files.readString(path, StandardCharsets.UTF_8);
        if (content.isBlank()) {
            return Map.of();
        }
        return mapper.readValue(content, new TypeReference<>() {});
    }

    private void ensurePythonEntry() {
        Path mainPath = getPythonMainPath();
        if (!Files.exists(mainPath)) {
            throw new IllegalStateException("Python RCA entry not found: " + mainPath);
        }
    }

    private List<String> resolvePythonCommand() {
        String envBin = pythonBin == null ? "" : pythonBin.trim();
        if (!envBin.isEmpty() && canExecutePythonCommand(envBin)) {
            return List.of(envBin);
        }
        String venvPython = pythonVenvPath == null ? "" : pythonVenvPath.trim();
        if (!venvPython.isEmpty() && canExecutePythonCommand(venvPython)) {
            return List.of(venvPython);
        }
        Path defaultVenvPython = getWorkspaceRoot().resolve("python service").resolve(".venv").resolve("Scripts").resolve("python.exe");
        if (Files.exists(defaultVenvPython) && canExecutePythonCommand(defaultVenvPython.toString())) {
            return List.of(defaultVenvPython.toString());
        }
        if (canExecutePythonCommand("py", "-3")) {
            return List.of("py", "-3");
        }
        if (canExecutePythonCommand("python3")) {
            return List.of("python3");
        }
        if (canExecutePythonCommand("python")) {
            return List.of("python");
        }
        throw new IllegalStateException(buildPythonResolutionError(defaultVenvPython));
    }

    private boolean canExecutePythonCommand(String... commandAndArgs) {
        try {
            String[] probe = Arrays.copyOf(commandAndArgs, commandAndArgs.length + 1);
            probe[probe.length - 1] = "--version";
            Process process = new ProcessBuilder(probe)
                    .redirectErrorStream(true)
                    .start();
            int code = process.waitFor();
            return code == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String buildPythonResolutionError(Path defaultVenvPython) {
        StringBuilder message = new StringBuilder("Python runtime could not be resolved.");
        Path pyvenvCfg = defaultVenvPython.getParent() == null || defaultVenvPython.getParent().getParent() == null
                ? null
                : defaultVenvPython.getParent().getParent().resolve("pyvenv.cfg");
        if (pyvenvCfg != null && Files.exists(pyvenvCfg)) {
            Map<String, String> cfg = readKeyValueFile(pyvenvCfg);
            String home = cfg.get("home");
            String executable = cfg.get("executable");
            if (home != null || executable != null) {
                message.append(" Bundled venv points to ");
                if (home != null) {
                    message.append(home);
                }
                if (executable != null) {
                    if (home != null) {
                        message.append(" (");
                    }
                    message.append("executable: ").append(executable);
                    if (home != null) {
                        message.append(")");
                    }
                }
                message.append(".");
            }
        }
        message.append(" Set `PYTHON_BIN` or `PROJECT_VENV_PYTHON` to a valid Python executable, ");
        message.append("or recreate `python service/.venv` from a working Python 3.13 install.");
        return message.toString();
    }

    private Map<String, String> readKeyValueFile(Path path) {
        Map<String, String> result = new HashMap<>();
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int idx = trimmed.indexOf('=');
                if (idx <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, idx).trim().toLowerCase(Locale.ROOT);
                String value = trimmed.substring(idx + 1).trim();
                result.put(key, value);
            }
        } catch (IOException ignored) {
        }
        return result;
    }

    private Path getPythonServiceDir() {
        Path configured = Paths.get(pythonServicePath == null || pythonServicePath.isBlank() ? "python service" : pythonServicePath.trim());
        if (configured.isAbsolute()) {
            return configured.toAbsolutePath().normalize();
        }
        return getWorkspaceRoot().resolve(configured).toAbsolutePath().normalize();
    }

    private Path getPythonMainPath() {
        return getPythonServiceDir().resolve("main.py");
    }

    private Path getWorkspaceRoot() {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path cwdName = cwd.getFileName();
        String name = cwdName == null ? "" : cwdName.toString().toLowerCase();
        if ("backend-java".equals(name) || "backend".equals(name)) {
            Path parent = cwd.getParent();
            if (parent != null) {
                return parent;
            }
        }
        if (Files.exists(cwd.resolve("python service")) || Files.exists(cwd.resolve("backend")) || Files.exists(cwd.resolve("backend-java"))) {
            return cwd;
        }
        return cwd;
    }

    private ProcessResult executeProcess(List<String> args, Path cwd) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(args);
        builder.directory(cwd.toFile());
        Process process = builder.start();
        String stdout = readStream(process.getInputStream());
        String stderr = readStream(process.getErrorStream());
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Python process interrupted", ex);
        }
        return new ProcessResult(exitCode, stdout, stderr);
    }

    private String readStream(java.io.InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append(System.lineSeparator());
            }
            return builder.toString().trim();
        }
    }

    private List<Map<String, Object>> toCanonicalRows(Map<String, Object> cache) {
        List<Map<String, Object>> rawRecords = getRecordList(cache.get("records"));
        List<Map<String, Object>> canonical = new ArrayList<>();
        for (Map<String, Object> record : rawRecords) {
            Map<String, Object> row = new HashMap<>();
            row.put("date", record.getOrDefault("date", null));
            row.put("cellname", record.getOrDefault("cellName", null));
            row.put("site", record.getOrDefault("site", null));
            row.put("tech", record.getOrDefault("tech", null));
            row.put("sectorid", record.getOrDefault("sectorid", null));
            row.put("sectorname", record.getOrDefault("sectorname", null));
            row.put("groups", record.getOrDefault("groups", null));
            Map<String, Object> dimensions = getObjectMap(record.get("dimensions"));
            Map<String, Object> metrics = getObjectMap(record.get("metrics"));
            dimensions.forEach(row::put);
            metrics.forEach(row::put);
            canonical.add(row);
        }
        return canonical;
    }

    private List<Map<String, Object>> toCanonicalRows(List<UploadData> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> canonical = new ArrayList<>(rows.size());
        for (UploadData data : rows) {
            canonical.add(toCanonicalRow(data));
        }
        return canonical;
    }

    private Map<String, Object> toCanonicalRow(UploadData data) {
        Map<String, Object> row = new HashMap<>();
        row.put("date", data.getDate());
        row.put("cellname", safeString(data.getCellName()));
        row.put("site", safeString(data.getSite()));
        row.put("band", safeString(data.getBand()));
        row.put("tech", safeString(data.getTech()));
        row.put("sectorid", safeString(data.getSectorId()));
        row.put("sectorname", safeString(data.getSectorName()));
        row.put("groups", safeString(data.getGroups()));
        row.put("ulprbutilizationrate", data.getUlPrbUtilizationRate());
        row.put("dlprbutilizationrate", data.getDlPrbUtilizationRate());
        row.put("ume4gdatavolumestdmapsmb9035931", data.getUme4gDataVolumeStdMapsMb9035931());
        row.put("umeeutranipthroughputueulstdkbps", data.getUmeEutranIpThroughputUeUlStdKbps());
        row.put("umeeutranipthroughputuedlstdkbps", data.getUmeEutranIpThroughputUeDlStdKbps());
        row.put("erabdroprate", data.getErabDropRate());
        row.put("initialerabestablishmentsuccessrate", data.getInitialErabEstablishmentSuccessRate());
        row.put("rrcestablishmentsuccessrate", data.getRrcEstablishmentSuccessRate());
        row.put("meanrrcconnectedusernumber", data.getMeanRrcConnectedUserNumber());
        row.put("maximumrrcconnectedusernumber", data.getMaximumRrcConnectedUserNumber());
        row.put("erabsetupsuccessrate", data.getErabSetupSuccessRate());
        row.put("rrcdroprate", data.getRrcDropRate());
        row.put("voltecssreric", data.getVolteCssrEric());
        row.put("voltedcreric", data.getVolteDcrEric());
        row.put("interfreqhosr", data.getInterFreqHosr());
        row.put("intrafreqhosr", data.getIntraFreqHosr());
        row.put("csfbsuccessrate", data.getCsfbSuccessRate());
        return row;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getObjectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    result.put(key, entry.getValue());
                }
            }
            return result;
        }
        return new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getRecordList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }

    public void cleanup(Path directory) {
        if (directory != null && Files.exists(directory)) {
            try {
                Files.walk(directory)
                        .sorted((a, b) -> b.compareTo(a))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException ignored) {
                            }
                        });
            } catch (IOException ignored) {
            }
        }
    }

    private void logPerf(String operation, long fileId, int rowCount, long elapsedMillis) {
        if (isTruthy(perfLogsEnabledRaw)) {
            System.out.println(String.format("[RECOMMENDATION] op=%s fileId=%d rows=%d elapsedMs=%d", operation, fileId, rowCount, elapsedMillis));
        }
    }

    private boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        String cleaned = value.trim().toLowerCase();
        return cleaned.equals("1") || cleaned.equals("true") || cleaned.equals("yes") || cleaned.equals("on");
    }

    private String safeString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    public static class RecommendationResult {
        public final Map<String, Object> payload;
        public final Path excelPath;
        public final Path workDir;

        public RecommendationResult(Map<String, Object> payload, Path excelPath, Path workDir) {
            this.payload = payload;
            this.excelPath = excelPath;
            this.workDir = workDir;
        }
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }
}

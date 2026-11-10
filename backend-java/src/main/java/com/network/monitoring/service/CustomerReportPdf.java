package com.network.monitoring.service;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import java.awt.Color;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Customer-facing PDF presentation of the same persisted report results as the appendices. */
final class CustomerReportPdf implements AutoCloseable {
    private static final Color NAVY = new Color(20, 43, 67), TEAL = new Color(0, 123, 137), MUTED = new Color(89, 107, 123), PALE = new Color(239, 245, 249);
    private final PDDocument document = new PDDocument();
    private final PDFont regular, bold;
    private final DailyAnalysisService.ReportInput input;
    private final boolean demo;
    private PDPageContentStream canvas;
    private float y;
    private int page;
    private static Map<String, Object> map(Object value) { return CommonReportRenderer.map(value); }
    private static String text(Object value) { return value == null ? "Not available" : String.valueOf(value); }
    private static String value(Map<String, Object> row, String... keys) {
        for (String key : keys) for (var entry : row.entrySet()) if (entry.getKey().replaceAll("[^A-Za-z0-9]", "").equalsIgnoreCase(key.replaceAll("[^A-Za-z0-9]", "")) && entry.getValue() != null && !entry.getValue().toString().isBlank()) {
            if (entry.getValue() instanceof Collection<?> list) return String.join("; ", list.stream().map(String::valueOf).toList());
            return entry.getValue().toString();
        }
        return "Not available";
    }
    private static String n(Object value) { return value instanceof Number number && Double.isFinite(number.doubleValue()) ? String.format(Locale.ROOT, "%,.2f", number.doubleValue()) : "—"; }
    private static String label(String key) {
        return Map.ofEntries(Map.entry("prb", "PRB utilisation"), Map.entry("dlprbutilizationrate", "DL PRB utilisation"), Map.entry("mvpuschsinr", "PUSCH SINR"),
                Map.entry("mveutranaveragecqi", "Average CQI"), Map.entry("rrcsetup", "RRC setup success"), Map.entry("erabsetup", "ERAB setup success"),
                Map.entry("availability", "Availability"), Map.entry("dcr", "Drop call rate"), Map.entry("throughput", "DL user throughput"), Map.entry("packetloss", "Packet loss")).getOrDefault(key, key.replace('_', ' '));
    }
    private static String unit(Object raw) { return switch(text(raw)) { case "PERCENT" -> "%"; case "MBPS" -> "Mbps"; case "DB" -> "dB"; case "DBM" -> "dBm"; default -> text(raw); }; }
    private CustomerReportPdf(DailyAnalysisService.ReportInput input) throws IOException {
        this.input = input; demo = Boolean.TRUE.equals(input.summary().get("demo"));
        regular = font(false); bold = font(true); newPage("Daily network performance");
    }
    private PDFont font(boolean weight) throws IOException {
        for (String path : List.of(weight ? "C:/Windows/Fonts/arialbd.ttf" : "C:/Windows/Fonts/arial.ttf", weight ? "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" : "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")) if (Files.isRegularFile(Path.of(path))) return PDType0Font.load(document, Path.of(path).toFile());
        return new PDType1Font(weight ? Standard14Fonts.FontName.HELVETICA_BOLD : Standard14Fonts.FontName.HELVETICA);
    }
    static void write(Path file, DailyAnalysisService.ReportInput input, Map<String, Object> summary, Map<String, Object> modules, Map<String, List<Map<String, Object>>> worst) throws IOException {
        try (var pdf = new CustomerReportPdf(input)) {
            pdf.executive(summary, modules);
            pdf.newPage("KPI comparison scorecard"); pdf.scorecard(worst);
            pdf.charts(worst);
            pdf.newPage("Root causes and recommended actions"); pdf.rca(map(modules.get("rca")));
            pdf.newPage("Alarm conditions and action guidance"); pdf.actions(modules);
            pdf.newPage("Coverage, definitions and audit trail"); pdf.coverage(summary, modules);
            pdf.canvas.close(); pdf.canvas = null; pdf.document.save(file.toFile());
        }
    }
    private void executive(Map<String, Object> summary, Map<String, Object> modules) throws IOException {
        title("Daily performance briefing"); paragraph("Reporting date: " + input.date() + "   |   Baseline: " + input.date().minusDays(7) + " to " + input.date().minusDays(1), 10, MUTED);
        if (demo) callout("DEMONSTRATION — synthetic network observations. Calculations and analysis use the application engines; these are not customer findings.");
        var trends = map(summary.get("kpiTrends")); long degraded = 0, improved = 0, unknown = 0;
        for (Object counts : trends.values()) { var row = map(counts); degraded += count(row.get("day:DEGRADED")); improved += count(row.get("day:IMPROVED")); unknown += count(row.get("day:UNASSESSED")); }
        cards(List.of("Cell groups", "KPI metrics", "Degraded vs D-1", "Improved vs D-1"), List.of(text(input.summary().getOrDefault("groups", "—")), text(input.profile().metrics().size()), String.valueOf(degraded), String.valueOf(improved)));
        section("Executive assessment");
        paragraph(degraded + " cell–KPI comparisons degraded and " + improved + " improved against the previous day. " + unknown + " comparisons could not be directionally assessed. These are comparison counts, not unique affected cells.", 11, NAVY);
        paragraph("Report coverage: " + summary.get("coverage") + ". Review the section coverage below before using the action list. Root causes are rule-based hypotheses; field/counter verification is required before applying changes.", 10, MUTED);
        section("Section coverage");
        table(List.of("Section", "Coverage", "Scope / limitation"), new float[]{115, 80, 316}, modules.entrySet().stream().map(entry -> {
            var result = map(entry.getValue()); return List.of(moduleName(entry.getKey()), text(result.get("status")), text(result.get("reason")));
        }).toList());
        paragraph("Full daily comparisons are supplied in report.xlsx and report.json. The PDF prioritises affected groups and actions for review.", 9, MUTED);
    }
    private static long count(Object value) { return value instanceof Number n ? n.longValue() : 0; }
    private static String moduleName(String key) { return switch(key) { case "thresholds" -> "Threshold assessment"; case "alarmCandidates" -> "Detected alarm conditions"; case "worstCells" -> "Worst cell groups"; case "rca" -> "Root cause analysis"; case "prediction" -> "KPI-based action guidance"; default -> key; }; }
    private void scorecard(Map<String, List<Map<String, Object>>> worst) throws IOException {
        title("Priority KPI comparison"); paragraph("One row per KPI, showing its worst current-day group using the configured improvement direction. This is not a network-wide average. Delta is current minus previous day; percentage KPIs use percentage points (pp).", 10, MUTED);
        List<List<String>> rows = new ArrayList<>();
        for (var entry : worst.entrySet()) {
            var item = entry.getValue().get(0); var current = map(item.get("current")); var day = map(item.get("previousDay")); var week = map(item.get("previousSevenDays"));
            rows.add(List.of(label(entry.getKey()) + "\n" + group(item), n(current.get("value")) + " " + unit(item.get("unit")), n(day.get("baselineValue")), n(day.get("difference")) + ("PERCENT".equals(item.get("unit")) ? " pp" : ""), n(week.get("baselineValue")), text(day.get("trend"))));
        }
        if (rows.isEmpty()) paragraph("No valid directional KPI values were available for ranking.", 11, MUTED);
        else table(List.of("KPI / priority group", "Today", "D-1", "Day delta", "7-day base", "Day trend"), new float[]{158, 70, 60, 70, 70, 83}, rows);
        section("Additional priority groups");
        for (var entry : worst.entrySet()) {
            paragraph(label(entry.getKey()) + ": " + String.join("  |  ", entry.getValue().stream().limit(3).map(row -> group(row) + " " + n(map(row.get("current")).get("value")) + " " + unit(row.get("unit"))).toList()), 9, NAVY);
        }
    }
    private void charts(Map<String, List<Map<String, Object>>> worst) throws IOException {
        int index = 0;
        for (var entry : worst.entrySet()) {
            if (index >= 20) break;
            if (index % 4 == 0) { newPage("Eight-day performance trends"); title("Priority-cell trends"); paragraph("Each panel uses the worst group for that KPI. Missing observations remain gaps; the y-axis scale is shown separately for each chart.", 10, MUTED); }
            int panel = index % 4; chart(entry.getValue().get(0), 42 + (panel % 2) * 266, 660 - (panel / 2) * 280, 245, 220); index++;
        }
        if (worst.size() > 20) { y = 100; paragraph("Charts for the first 20 directional KPIs are shown. Additional metrics and all group observations remain in the appendices.", 9, MUTED); }
    }
    private void rca(Map<String, Object> module) throws IOException {
        title("Suspected causes and RF actions");
        paragraph("Cause classification and recommendations below come from the RCA engine. They identify investigation priorities, not confirmed fault resolutions.", 10, MUTED);
        paragraph("Coverage: " + text(module.get("status")) + " — " + text(module.get("reason")), 9, MUTED);
        var data = map(module.get("data")); List<?> items = data.get("anomaly_details") instanceof List<?> list ? list : List.of();
        List<Map<String, Object>> chosen = new ArrayList<>(); Set<String> cells = new HashSet<>();
        for (Object raw : items) { var row = map(raw); String cell = value(row, "Short name", "cellName"); String date = value(row, "Date", "date"); if (date.startsWith(input.date().toString()) && cells.add(cell)) chosen.add(row); if (chosen.size() >= 4) break; }
        if (chosen.isEmpty()) paragraph("No current-day RCA anomaly details were available. This can mean no anomaly rows met the configured criteria, or RCA was not run. See module coverage and the JSON appendix.", 11, MUTED);
        if (!chosen.isEmpty()) paragraph("Up to four current-day cell groups are detailed here. Remaining returned anomaly rows are available in the Excel and JSON appendices. Parameter values are review guidance, not automatically approved changes.", 9, MUTED);
        int rank = 0;
        for (var row : chosen) {
            ensure(210);
            section("Priority " + (++rank) + " · " + value(row, "Short name", "cellName") + " · " + value(row, "Severity"));
            labelled("Suspected cause", value(row, "Primary Cause", "primaryCause"));
            String explanation = value(row, "RCA Explanation");
            if (!explanation.equals("Not available")) {
                String narrative = String.join("\n", Arrays.stream(explanation.split("\\R")).filter(line -> {
                    String lower = line.strip().toLowerCase(Locale.ROOT);
                    return !lower.startsWith("kpi evidence:") && !lower.startsWith("top kpi drivers:") && !lower.startsWith("detected kpi pattern:") && !lower.startsWith("additional patterns:");
                }).toList());
                labelled("Reason / interpretation", "The configured RCA criteria matched " + value(row, "Primary Cause", "primaryCause") + ". Supporting observations are listed separately under KPI evidence.\n" + narrative);
            }
            String secondary = value(row, "Secondary Causes", "Additional Patterns"); if (!secondary.equals("Not available") && !secondary.isBlank()) labelled("Additional patterns", secondary);
            String evidence = value(row, "Evidence", "KPI Evidence", "Evidence Details"); if (!evidence.equals("Not available")) labelled("KPI evidence", evidence);
            labelled("Recommended investigation / action", value(row, "Vendor Recommendation", "RF Recommendation", "Recommendation", "recommendations", "Nokia Recommendation", "Ericsson Recommendation"));
            String parameters = value(row, "Recommended Parameters", "Parameter Recommendation", "Nokia Parameter Recommendation", "Ericsson Parameter Recommendation");
            String values = value(row, "Recommended Values"), notes = value(row, "Implementation Notes");
            List<String> names = numbered(parameters), guidance = numbered(values), checks = numbered(notes);
            if (names.size() > 1 && names.size() == guidance.size() && names.size() == checks.size()) {
                section("Parameter / feature review matrix"); List<List<String>> matrix = new ArrayList<>();
                for (int i = 0; i < names.size(); i++) matrix.add(List.of(names.get(i), guidance.get(i), checks.get(i)));
                table(List.of("Parameter / feature", "Suggested review", "Verification / notes"), new float[]{160, 170, 181}, matrix);
            } else {
                if (!parameters.equals("Not available")) labelled("Parameter / feature review guidance", parameters);
                if (!values.equals("Not available")) labelled("Suggested review values", values);
                if (!notes.equals("Not available")) labelled("Implementation / verification notes", notes);
            }
        }
    }
    private static List<String> numbered(String value) { return Arrays.stream(value.split("\\R")).map(line -> line.replaceFirst("^\\s*\\d+\\.\\s*", "").strip()).filter(line -> !line.isEmpty()).toList(); }
    private void actions(Map<String, Object> modules) throws IOException {
        title("Detected conditions and action guidance");
        var alarms = map(map(modules.get("alarmCandidates")).get("data")); var today = map(alarms.get("currentDay")); var yesterday = map(alarms.get("previousDay"));
        paragraph("Threshold breach candidates: " + text(today.getOrDefault("candidateCount", "not assessed")) + " today; " + text(yesterday.getOrDefault("candidateCount", "not assessed")) + " previous day. Repeated per-observation breaches are not unique active alarm incidents, acknowledgements or confirmed resolutions.", 10, MUTED);
        List<List<String>> rows = new ArrayList<>(); Set<String> seen = new HashSet<>();
        if (today.get("items") instanceof List<?> list) for (Object item : list) {
            var row = map(item); String key = text(row.get("cellName")) + ":" + row.get("metric"); if (!seen.add(key)) continue;
            rows.add(List.of(text(row.get("cellName")), label(text(row.get("metric"))), n(row.get("value")), n(row.get("threshold")), text(row.get("severity")))); if (rows.size() >= 8) break;
        }
        if (!rows.isEmpty()) table(List.of("Cell group", "KPI", "Observed", "Threshold", "Severity"), new float[]{125, 155, 75, 75, 81}, rows);
        section("KPI-based recommendations");
        var prediction = map(map(modules.get("prediction")).get("data"));
        paragraph("These are current-data heuristics, not a forecast of tomorrow's KPI values. Site-master, TA and alarm-context inputs are excluded unless explicitly pinned by the pipeline.", 10, MUTED);
        if (prediction.get("items") instanceof List<?> list && !list.isEmpty()) for (Object raw : list.stream().limit(3).toList()) {
            var row = map(raw); labelled(value(row, "cellName", "cell", "site"), value(row, "action", "actionLabel", "recommendation", "actionCode"));
            String reason = value(row, "reason", "rationale", "reasons"); if (!reason.equals("Not available")) paragraph(reason, 10, NAVY);
        } else paragraph("No action guidance was returned. Review the module status and prerequisites.", 10, MUTED);
    }
    private void coverage(Map<String, Object> summary, Map<String, Object> modules) throws IOException {
        title("Read the report with its coverage");
        table(List.of("Item", "Definition / provenance"), new float[]{145, 366}, List.of(
                List.of("Reporting date", input.date().toString()), List.of("Comparison window", input.date().minusDays(7) + " to " + input.date().minusDays(1) + " (previous day also reported separately)"),
                List.of("Aggregation", "Configured per KPI. Sample MEAN is descriptive; a counter-weighted rate requires configured numerator/denominator RATIO."),
                List.of("Improvement", "Taken from the configured KPI direction. Neutral or insufficient samples remain unassessed."),
                List.of("Export coverage", summary.get("comparisonRows") + " complete comparison rows within the configured export bound. PDF detail lists are prioritised and bounded."),
                List.of("Dataset / snapshot", input.datasetVersion() + " / " + input.snapshotId()), List.of("Profile hash", input.profileHash()), List.of("Source SHA-256", input.sourceFingerprint())));
        section("Available files");
        labelled("report.pdf", "Executive summary, priority KPI scorecard, trend charts, RCA actions and section coverage.");
        labelled("report.xlsx", "Daily comparison rows with numeric values, eight-day observations and returned analysis detail tables.");
        labelled("report.json", "Machine-readable manifest, full comparisons, baseline status, returned module payloads and summary.");
        paragraph("A report is not proof that a fault is resolved. Validate counter definitions, completeness, transport and congestion before making parameter changes. Customer-specific branding and recipients are applied only when configured.", 10, MUTED);
        if (demo) callout("DEMO ONLY: all cell/site names and observations in this report are synthetic. No customer database or external email was used to produce this example.");
    }
    private static String group(Map<String, Object> row) { var dimensions = map(row.get("dimensions")); return text(dimensions.getOrDefault("cellName", String.join(" / ", dimensions.values().stream().map(String::valueOf).toList()))); }
    private void chart(Map<String, Object> item, float left, float top, float width, float height) throws IOException {
        rectangle(left, top - height, width, height, PALE);
        draw(label(text(item.get("metric"))), left + 12, top - 21, 11, bold, NAVY);
        draw(group(item), left + 12, top - 38, 8, regular, MUTED);
        List<?> points = (List<?>) item.getOrDefault("trend", List.of()); double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (Object raw : points) { Object v = map(map(raw).get("observation")).get("value"); if (v instanceof Number number && Double.isFinite(number.doubleValue())) { min = Math.min(min, number.doubleValue()); max = Math.max(max, number.doubleValue()); } }
        if (!Double.isFinite(min) || points.size() < 2) { draw("No valid trend observations", left + 12, top - 80, 9, regular, MUTED); return; }
        double pad = Math.max((max - min) * .12, .1); min -= pad; max += pad;
        float base = top - height + 46, plotHeight = 112, start = left + 43, plotWidth = width - 62;
        for (int tick = 0; tick < 3; tick++) { float yy = base + tick * plotHeight / 2; line(start, yy, start + plotWidth, yy, new Color(210, 222, 230), .5f); draw(String.format(Locale.ROOT, "%.1f", min + tick * (max - min) / 2), left + 7, yy - 3, 7, regular, MUTED); }
        Float px = null, py = null;
        for (int i = 0; i < points.size(); i++) {
            var point = map(points.get(i)); Object v = map(point.get("observation")).get("value"); float x = start + i * plotWidth / (points.size() - 1);
            String date = text(point.get("date")); draw(date.length() >= 10 ? date.substring(5) : date, x - 11, base - 16, 6, regular, MUTED);
            if (!(v instanceof Number number) || !Double.isFinite(number.doubleValue())) { px = null; py = null; continue; }
            float yy = base + (float) ((number.doubleValue() - min) / (max - min) * plotHeight);
            if (px != null) line(px, py, x, yy, TEAL, 1.8f); rectangle(x - 2, yy - 2, 4, 4, TEAL); px = x; py = yy;
        }
        draw("Unit: " + unit(item.get("unit")) + "  |  Today: " + n(map(item.get("current")).get("value")), left + 12, top - height + 13, 8, regular, MUTED);
    }
    private void newPage(String heading) throws IOException {
        if (canvas != null) canvas.close(); PDPage sheet = new PDPage(PDRectangle.A4); document.addPage(sheet); canvas = new PDPageContentStream(document, sheet); page++;
        rectangle(0, 777, 595, 65, NAVY); draw("NETWORK MONITOR", 42, 811, 11, bold, Color.WHITE); draw(heading, 42, 791, 9, regular, new Color(191, 219, 232));
        draw(input.date().toString(), 482, 809, 9, regular, Color.WHITE);
        draw((demo ? "SYNTHETIC DEMO  |  " : "") + "Daily network report", 42, 26, 8, regular, MUTED); draw(String.valueOf(page), 540, 26, 8, bold, MUTED); y = 745;
    }
    private void ensure(float height) throws IOException { if (y - height < 60) newPage("Daily report · continued"); }
    private void title(String value) throws IOException { ensure(45); draw(value, 42, y, 22, bold, NAVY); y -= 36; }
    private void section(String value) throws IOException { ensure(64); y -= 7; draw(value, 42, y, 13, bold, TEAL); y -= 23; }
    private void labelled(String label, String value) throws IOException { float block = wrap(value, regular, 10, 511).size() * 15 + 43; ensure(Math.min(block, 600)); paragraph(label, 10, TEAL); paragraph(value, 10, NAVY); }
    private void paragraph(String value, int size, Color color) throws IOException { var rows = wrap(value, regular, size, 511); ensure(Math.min(rows.size() * (size + 5) + 9, 600)); for (String row : rows) { ensure(size + 6); draw(row, 42, y, size, regular, color); y -= size + 5; } y -= 9; }
    private void callout(String value) throws IOException { var rows = wrap(value, regular, 10, 485); float height = rows.size() * 15 + 22; ensure(height + 12); rectangle(42, y - height + 10, 511, height, new Color(255, 246, 225)); for (String row : rows) { draw(row, 55, y - 5, 10, regular, NAVY); y -= 15; } y -= 27; }
    private void cards(List<String> labels, List<String> values) throws IOException { ensure(90); for (int i = 0; i < labels.size(); i++) { float x = 42 + i * 130; rectangle(x, y - 65, 121, 70, PALE); draw(labels.get(i), x + 9, y - 12, 8, regular, MUTED); draw(values.get(i), x + 9, y - 42, 23, bold, TEAL); } y -= 90; }
    private void table(List<String> headers, float[] widths, List<List<String>> rows) throws IOException {
        tableRow(headers, widths, true, 0);
        for (int i = 0; i < rows.size(); i++) {
            float height = rowHeight(rows.get(i), widths, 8.5f);
            if (y - height < 60) { newPage("Daily report · continued"); tableRow(headers, widths, true, 0); }
            tableRow(rows.get(i), widths, false, i);
        }
        y -= 17;
    }
    private float rowHeight(List<String> row, float[] widths, float size) throws IOException { int lines = 1; for (int i = 0; i < widths.length; i++) lines = Math.max(lines, wrap(row.get(i), regular, size, widths[i] - 14).size()); return lines * (size + 4) + 15; }
    private void tableRow(List<String> values, float[] widths, boolean header, int index) throws IOException {
        float height = rowHeight(values, widths, header ? 8 : 8.5f); ensure(height);
        rectangle(42, y - height + 7, 511, height, header ? NAVY : index % 2 == 0 ? PALE : Color.WHITE);
        float x = 42;
        for (int i = 0; i < widths.length; i++) { float yy = y - 7; for (String row : wrap(values.get(i), header ? bold : regular, header ? 8 : 8.5f, widths[i] - 14)) { draw(row, x + 7, yy, header ? 8 : 8.5f, header ? bold : regular, header ? Color.WHITE : NAVY); yy -= header ? 12 : 12.5f; } x += widths[i]; }
        y -= height;
    }
    private List<String> wrap(String raw, PDFont font, float size, float width) throws IOException {
        List<String> result = new ArrayList<>(); String cleaned = supported(raw);
        for (String paragraph : cleaned.split("\n", -1)) { String remaining = paragraph.strip(); if (remaining.isEmpty()) { result.add(""); continue; } while (!remaining.isEmpty()) { int length = remaining.length(); while (length > 1 && font.getStringWidth(remaining.substring(0, length)) * size / 1000 > width) length--; if (length < remaining.length()) { int space = remaining.lastIndexOf(' ', length); if (space > length / 2) length = space; } result.add(remaining.substring(0, length)); remaining = remaining.substring(length).stripLeading(); } }
        return result;
    }
    private String supported(String raw) { StringBuilder out = new StringBuilder(); text(raw).codePoints().forEach(cp -> { String c = new String(Character.toChars(cp)); if (cp == '\n') { out.append('\n'); return; } try { regular.encode(c); out.append(c); } catch (Exception ex) { out.append('?'); } }); return out.toString().replace('\r', ' ').replace('\t', ' '); }
    private void draw(String value, float x, float yy, float size, PDFont font, Color color) throws IOException { canvas.setNonStrokingColor(color); canvas.beginText(); canvas.setFont(font, size); canvas.newLineAtOffset(x, yy); canvas.showText(supported(value).replace('\n', ' ')); canvas.endText(); }
    private void rectangle(float x, float yy, float width, float height, Color color) throws IOException { canvas.setNonStrokingColor(color); canvas.addRect(x, yy, width, height); canvas.fill(); }
    private void line(float x, float yy, float xx, float y2, Color color, float width) throws IOException { canvas.setStrokingColor(color); canvas.setLineWidth(width); canvas.moveTo(x, yy); canvas.lineTo(xx, y2); canvas.stroke(); }
    public void close() throws IOException { if (canvas != null) canvas.close(); document.close(); }
}

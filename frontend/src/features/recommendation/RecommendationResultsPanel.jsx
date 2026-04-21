import React, { useEffect, useMemo, useRef, useState } from "react";
import {
  ResponsiveContainer,
  ComposedChart,
  LineChart,
  Line,
  Area,
  Bar,
  ReferenceLine,
  XAxis,
  YAxis,
  Tooltip,
  CartesianGrid,
  Tooltip as RechartsTooltip,
  Legend,
} from "recharts";
import {
  LINE_COLORS,
  formatDateHeader,
  getHeatColor,
  normalizeShortName,
  toDateKey,
} from "./recommendationUtils";
import { Activity, BarChart3, Download, Info } from "lucide-react";

const CRITICAL_CAUSE_COLOR_MAP = {
  "UL interference / noise rise": "#7c3aed",
  "Accessibility / setup failure": "#ef4444",
  "Coverage gap / overshooting": "#f59e0b",
  "Transport / packet loss": "#ec4899",
  "Availability / outage": "#0ea5e9",
  "No major anomaly": "#94a3b8",
  "Mobility / handover issue": "#10b981",
  "General degradation": "#8b5cf6",
  "Congestion / capacity": "#f97316",
  "Radio quality degradation": "#22c55e",
};

const FALLBACK_CAUSE_COLORS = [
  "#7c3aed",
  "#ef4444",
  "#f59e0b",
  "#ec4899",
  "#0ea5e9",
  "#10b981",
  "#f97316",
  "#22c55e",
  "#8b5cf6",
  "#64748b",
];

const causeToKey = (cause) =>
  `critical_cause_${String(cause || "unknown")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "")}`;

const renderLineTooltip = ({ active, payload, label }) => {
  if (!active || !Array.isArray(payload) || !payload.length) return null;
  const visible = payload
    .filter((item) => item?.value !== null && item?.value !== undefined)
    .sort((a, b) => Number(b?.value || 0) - Number(a?.value || 0));
  if (!visible.length) return null;
  return (
    <div className="w-64 rounded-lg border border-zinc-300 bg-white/100 px-3 py-2 text-xs shadow-xl ring-1 ring-zinc-200">
      <div className="mb-1 font-semibold text-zinc-900">
        Date: {formatDateHeader(label)}
      </div>
      <div className="max-h-56 space-y-1 overflow-y-auto pr-1">
        {visible.map((item) => (
          <div
            key={item.dataKey}
            className="flex items-center justify-between gap-3"
          >
            <span className="min-w-0 flex items-center gap-1 text-zinc-700">
              <span
                className="inline-block h-2 w-2 rounded-full"
                style={{ background: item.color }}
              />
              <span className="truncate">{item.dataKey}</span>
            </span>
            <span className="font-semibold text-zinc-900">
              {Number(item.value).toFixed(2)}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
};

const renderTrendTooltip = ({ active, payload, label }) => {
  if (!active || !Array.isArray(payload) || !payload.length) return null;
  const visible = payload.filter((item) => Number(item?.value || 0) > 0);
  if (!visible.length) return null;
  return (
    <div className="rounded-lg border border-zinc-300 bg-white px-3 py-2 text-xs shadow-lg">
      <div className="mb-1 font-semibold text-zinc-900">
        Date: {formatDateHeader(label)}
      </div>
      {visible.map((item) => (
        <div
          key={item.dataKey}
          className="flex items-center justify-between gap-3 text-zinc-700"
        >
          <span className="flex items-center gap-1">
            <span
              className="inline-block h-2 w-2 rounded-full"
              style={{ background: item.color }}
            />
            {item.name}
          </span>
          <span className="font-semibold text-zinc-900">
            {Number(item.value).toFixed(0)}
          </span>
        </div>
      ))}
    </div>
  );
};

const splitTopNumberedItems = (value, limit = 5) => {
  const text = String(value || "")
    .replace(/\r/g, "")
    .replace(/^"+|"+$/g, "")
    .trim();
  if (!text) return [];

  const matches = [...text.matchAll(/(?:^|\n)\s*\d+\.\s*([\s\S]*?)(?=(?:\n\s*\d+\.\s*)|$)/g)];
  const items = matches
    .map((m) => String(m[1] || "").replace(/\n+/g, " ").trim())
    .filter(Boolean);
  if (items.length) return items.slice(0, limit);

  return text
    .split(/\n+/)
    .map((line) => line.replace(/^\s*[-*]\s*/, "").trim())
    .filter(Boolean)
    .slice(0, limit);
};

export default function RecommendationResultsPanel({
  result,
  resultView,
  setResultView,
  resultScope,
  setResultScope,
  selectedFileId,
  selectedFileLabel,
  currentConfig,
  availableColumns,
}) {
  const [selectedChartNames, setSelectedChartNames] = useState([]);
  const [shortNameQuery, setShortNameQuery] = useState("");
  const [heatTooltip, setHeatTooltip] = useState(null);
  const [showHeatScores, setShowHeatScores] = useState(false);
  const [exportingPdf, setExportingPdf] = useState(false);
  const visualizationRef = useRef(null);
  const isPrePostMode = result?.period_mode === "pre_post";
  const preFilterMeta = result?.period_filters?.pre || null;
  const postFilterMeta = result?.period_filters?.post || null;
  const shortNameMappedKey = currentConfig?.columnMapping?.["Short name"] || "";
  const shortNameDisplayLabel = useMemo(() => {
    if (!shortNameMappedKey) return "Short name";
    const hit = Array.isArray(availableColumns)
      ? availableColumns.find((col) => col?.key === shortNameMappedKey)
      : null;
    return hit?.label || shortNameMappedKey;
  }, [shortNameMappedKey, availableColumns]);

  const lineChartModel = useMemo(() => {
    const rows = Array.isArray(result?.anomaly_details)
      ? result.anomaly_details
      : [];
    if (!rows.length) return { chartData: [], shortNames: [] };

    const dateSet = new Set();
    const scoreByNameDate = new Map();
    const totals = new Map();

    rows.forEach((row) => {
      const name = String(row?.["Short name"] || "").trim();
      const date = toDateKey(row?.Date);
      const score = Number(row?.["Anomaly Score"]);
      if (!name || !date || Number.isNaN(score)) return;
      dateSet.add(date);
      const key = `${name}__${date}`;
      const prev = scoreByNameDate.get(key);
      const value = prev === undefined ? score : Math.max(prev, score);
      scoreByNameDate.set(key, value);
      totals.set(name, (totals.get(name) || 0) + score);
    });

    const xDates = Array.from(dateSet).sort();
    const shortNames = Array.from(totals.keys()).sort(
      (a, b) => (totals.get(b) || 0) - (totals.get(a) || 0),
    );
    const chartData = xDates.map((date) => {
      const chartRow = { date };
      shortNames.forEach((name) => {
        const key = `${name}__${date}`;
        chartRow[name] = scoreByNameDate.has(key)
          ? scoreByNameDate.get(key)
          : null;
      });
      return chartRow;
    });

    return { chartData, shortNames };
  }, [result]);

  const scopedShortNames = useMemo(() => {
    const names = lineChartModel.shortNames || [];
    if (resultScope === "all") return names;
    if (resultScope === "top20") return names.slice(0, 20);
    return names.slice(0, 10);
  }, [lineChartModel.shortNames, resultScope]);

  const filteredShortNamesForList = useMemo(() => {
    const q = String(shortNameQuery || "").trim().toLowerCase();
    if (!q) return scopedShortNames;
    return scopedShortNames.filter((name) =>
      String(name).toLowerCase().includes(q),
    );
  }, [scopedShortNames, shortNameQuery]);

  const colorByShortName = useMemo(() => {
    const map = new Map();
    lineChartModel.shortNames.forEach((name, idx) => {
      map.set(name, LINE_COLORS[idx % LINE_COLORS.length]);
    });
    return map;
  }, [lineChartModel.shortNames]);

  const scopedShortNameSet = useMemo(
    () => new Set(scopedShortNames.map((name) => normalizeShortName(name))),
    [scopedShortNames],
  );

  const filteredAnomalyDetails = useMemo(() => {
    const rows = Array.isArray(result?.anomaly_details)
      ? result.anomaly_details
      : [];
    if (!rows.length) return [];
    if (resultScope === "all") return rows;
    return rows.filter((row) =>
      scopedShortNameSet.has(normalizeShortName(row?.["Short name"])),
    );
  }, [result, resultScope, scopedShortNameSet]);

  const displayedAnomalyDetails = useMemo(() => {
    const rows = Array.isArray(filteredAnomalyDetails) ? [...filteredAnomalyDetails] : [];
    rows.sort((a, b) => {
      const da = toDateKey(a?.Date);
      const db = toDateKey(b?.Date);
      if (da !== db) return db.localeCompare(da);
      const pa = String(a?.Period || "");
      const pb = String(b?.Period || "");
      if (pa !== pb) {
        if (pb === "Post") return 1;
        if (pa === "Post") return -1;
      }
      const sa = Number(a?.["Anomaly Score"]);
      const sb = Number(b?.["Anomaly Score"]);
      return (Number.isFinite(sb) ? sb : -Infinity) - (Number.isFinite(sa) ? sa : -Infinity);
    });
    if (isPrePostMode) {
      return rows;
    }
    return rows.slice(0, 100);
  }, [filteredAnomalyDetails, isPrePostMode]);

  const criticalCauseMeta = useMemo(() => {
    const counts = new Map();
    filteredAnomalyDetails.forEach((row) => {
      const sev = String(row?.Severity || "").toLowerCase();
      if (!sev.includes("critical")) return;
      const cause = String(row?.["Primary Cause"] || "Unknown").trim() || "Unknown";
      counts.set(cause, (counts.get(cause) || 0) + 1);
    });

    return Array.from(counts.entries())
      .sort((a, b) => b[1] - a[1])
      .map(([name], idx) => ({
        name,
        key: causeToKey(name),
        color: CRITICAL_CAUSE_COLOR_MAP[name] || FALLBACK_CAUSE_COLORS[idx % FALLBACK_CAUSE_COLORS.length],
      }));
  }, [filteredAnomalyDetails]);

  const dateTrendModel = useMemo(() => {
    if (!filteredAnomalyDetails.length) return [];
    const causeKeyByName = new Map(
      criticalCauseMeta.map((item) => [item.name, item.key]),
    );
    const byDate = new Map();
    filteredAnomalyDetails.forEach((row) => {
      const date = toDateKey(row?.Date);
      if (!date) return;
      if (!byDate.has(date)) {
        const baseCriticalByCause = Object.fromEntries(
          criticalCauseMeta.map((cause) => [cause.key, 0]),
        );
        byDate.set(date, {
          date,
          anomaly_count: 0,
          critical_count: 0,
          ...baseCriticalByCause,
        });
      }
      const agg = byDate.get(date);
      const sev = String(row?.Severity || "").toLowerCase();
      const cause = String(row?.["Primary Cause"] || "Unknown").trim() || "Unknown";
      agg.anomaly_count += 1;
      if (sev.includes("critical")) {
        agg.critical_count += 1;
        const causeKey = causeKeyByName.get(cause);
        if (causeKey) agg[causeKey] += 1;
      }
    });
    return Array.from(byDate.values())
      .sort((a, b) => a.date.localeCompare(b.date))
      .map((row) => ({
        ...row,
      }));
  }, [filteredAnomalyDetails, criticalCauseMeta]);

  const heatMap = useMemo(() => {
    const rows = Array.isArray(result?.anomaly_details)
      ? result.anomaly_details
      : [];
    if (!rows.length)
      return { xDates: [], yNames: [], cells: [], minScore: 0, maxScore: 0 };

    const dateSet = new Set();
    const nameSet = new Set();
    const scoreMap = new Map();
    const scoreAggMap = new Map();
    const scores = [];

    rows.forEach((row) => {
      const name = String(row?.["Short name"] || "").trim();
      const date = toDateKey(row?.Date);
      const score = Number(row?.["Anomaly Score"]);
      if (!name || !date || Number.isNaN(score)) return;
      nameSet.add(name);
      dateSet.add(date);
      const key = `${name}__${date}`;
      const previous = scoreAggMap.get(key) || {
        totalScore: 0,
        count: 0,
        severity: "",
        cause: "",
      };
      const next = {
        totalScore: previous.totalScore + score,
        count: previous.count + 1,
        severity: row?.Severity || previous.severity || "",
        cause: row?.["Primary Cause"] || previous.cause || "",
      };
      scoreAggMap.set(key, next);
    });

    scoreAggMap.forEach((value, key) => {
      const averagedScore = value.count ? value.totalScore / value.count : null;
      scoreMap.set(key, {
        score: averagedScore,
        severity: value.severity || "",
        cause: value.cause || "",
      });
      if (averagedScore !== null && !Number.isNaN(Number(averagedScore))) {
        scores.push(Number(averagedScore));
      }
    });

    const xDates = Array.from(dateSet).sort();
    const yNames = Array.from(nameSet).sort();
    const cells = yNames.map((name) =>
      xDates.map((date) => {
        const key = `${name}__${date}`;
        const hit = scoreMap.get(key);
        return {
          shortName: name,
          date,
          score: hit?.score ?? null,
          severity: hit?.severity ?? "",
          cause: hit?.cause ?? "",
        };
      }),
    );

    return {
      xDates,
      yNames,
      cells,
      minScore: scores.length ? Math.min(...scores) : 0,
      maxScore: scores.length ? Math.max(...scores) : 0,
    };
  }, [result]);

  const filteredHeatMap = useMemo(() => {
    if (!heatMap.xDates.length || !heatMap.yNames.length) return heatMap;
    const indexByName = new Map(heatMap.yNames.map((name, idx) => [name, idx]));
    const yNames = scopedShortNames.filter((name) => indexByName.has(name));
    const cells = yNames.map((name) => heatMap.cells[indexByName.get(name)]);
    const scores = [];
    cells.forEach((row) => {
      row.forEach((cell) => {
        const score = Number(cell?.score);
        if (!Number.isNaN(score)) scores.push(score);
      });
    });
    return {
      ...heatMap,
      yNames,
      cells,
      minScore: scores.length ? Math.min(...scores) : 0,
      maxScore: scores.length ? Math.max(...scores) : 0,
    };
  }, [heatMap, scopedShortNames]);

  const heatMapPostBoundaryIndex = useMemo(() => {
    if (!isPrePostMode || !preFilterMeta?.end_date) return -1;
    return filteredHeatMap.xDates.findIndex((d) => d === preFilterMeta.end_date);
  }, [isPrePostMode, preFilterMeta, filteredHeatMap.xDates]);

  const worsenedRows = useMemo(() => {
    const rows = Array.isArray(result?.worsened_recommendations)
      ? result.worsened_recommendations
      : [];
    const badRows = rows.filter((row) => {
      const direction = String(row?.delta_direction || "").toLowerCase();
      if (direction) return direction !== "good";
      return Number(row?.delta) > 0;
    });
    const goodRows = rows.filter((row) => {
      const direction = String(row?.delta_direction || "").toLowerCase();
      if (direction) return direction === "good";
      return Number(row?.delta) < 0;
    });
    if (resultScope === "all") return [...badRows, ...goodRows];
    const limit = resultScope === "top20" ? 20 : 10;
    return [...badRows.slice(0, limit), ...goodRows.slice(0, limit)];
  }, [result, resultScope]);

  const worsenedScopeLabel = useMemo(() => {
    if (resultScope === "all") return "all";
    if (resultScope === "top20") return "20";
    return "10";
  }, [resultScope]);

  const worsenedShortNameSet = useMemo(
    () =>
      new Set(
        worsenedRows
          .map((row) => normalizeShortName(row?.["Short name"]))
          .filter(Boolean),
      ),
    [worsenedRows],
  );

  const getHeatScoreTextColor = (score, minScore, maxScore) => {
    if (score === null || score === undefined || Number.isNaN(Number(score))) return "#111827";
    if (maxScore <= minScore) return "#111827";
    const t = (Number(score) - minScore) / (maxScore - minScore);
    return t >= 0.58 ? "#ffffff" : "#111827";
  };

  const exportVisualizationPdf = async () => {
    try {
      const target = visualizationRef.current;
      if (!target) return;
      setExportingPdf(true);
      const [{ toPng }, { jsPDF }] = await Promise.all([
        import("html-to-image"),
        import("jspdf"),
      ]);
      const imageData = await toPng(target, {
        cacheBust: true,
        pixelRatio: 2,
        backgroundColor: "#ffffff",
      });

      const doc = new jsPDF({ orientation: "landscape", unit: "mm", format: "a4" });
      const pageWidth = doc.internal.pageSize.getWidth();
      const pageHeight = doc.internal.pageSize.getHeight();
      const margin = 8;
      const contentWidth = pageWidth - margin * 2;
      const imageProps = doc.getImageProperties(imageData);
      const renderedHeight = (imageProps.height * contentWidth) / imageProps.width;
      const usableHeight = pageHeight - margin * 2;
      let remainingHeight = renderedHeight;
      let yOffset = margin;

        const selectedKpis = Array.isArray(currentConfig?.selectedKpis)
          ? currentConfig.selectedKpis
          : [];
        const kpiConfig = currentConfig?.kpis || {};
        const appliedThresholds = result?.applied_thresholds || {};
        const prettifyKey = (value) =>
          String(value || "")
            .replace(/_/g, " ")
            .replace(/\b\w/g, (m) => m.toUpperCase());

      const metadataLines = [
        `Generated: ${new Date().toLocaleString()}`,
        `Source File: ${selectedFileLabel || "-"}${selectedFileId ? ` (ID: ${selectedFileId})` : ""}`,
        `Vendor: ${result?.vendor || "-"}`,
        `Mode: ${isPrePostMode ? "Pre vs Post" : "Single Range"}`,
        isPrePostMode
          ? `Pre Range: ${preFilterMeta?.start_date || "-"} to ${preFilterMeta?.end_date || "-"}`
          : `Range: ${result?.applied_date_filter?.start_date || "-"} to ${result?.applied_date_filter?.end_date || "-"}`,
        isPrePostMode
          ? `Post Range: ${postFilterMeta?.start_date || "-"} to ${postFilterMeta?.end_date || "-"}`
          : `Rows: ${result?.applied_date_filter?.rows_after_filter ?? "-"} / ${result?.applied_date_filter?.rows_before_filter ?? "-"}`,
        `Scope: ${String(resultScope || "").toUpperCase()}`,
        `Summary: Anomalies ${result?.summary?.anomaly_count || 0}, Critical ${result?.summary?.critical_count || 0}, Major ${result?.summary?.major_count || 0}`,
        `Selected KPIs: ${selectedKpis.length}`,
      ];

      doc.setFont("helvetica", "bold");
      doc.setFontSize(16);
      doc.text("RCA Recommendation Visualization Report", margin, margin + 2);
      let metaY = margin + 10;
      doc.setFont("helvetica", "normal");
      doc.setFontSize(10);
        metadataLines.forEach((line) => {
          const wrapped = doc.splitTextToSize(line, pageWidth - margin * 2);
          doc.text(wrapped, margin, metaY);
          metaY += wrapped.length * 4 + 1;
        });

        const ensureSpace = (neededHeight) => {
          if (metaY + neededHeight > pageHeight - margin) {
            doc.addPage();
            metaY = margin;
          }
        };

        const drawTableSection = (title, columns, rows) => {
          if (!rows.length) return;
          ensureSpace(14);
          doc.setFont("helvetica", "bold");
          doc.setFontSize(11);
          doc.text(title, margin, metaY);
          metaY += 5;

          const drawHeader = () => {
            doc.setFillColor(245, 245, 245);
            doc.rect(margin, metaY, contentWidth, 7, "F");
            doc.setFont("helvetica", "bold");
            doc.setFontSize(9);
            let x = margin;
            columns.forEach((col) => {
              doc.text(col.label, x + 1.5, metaY + 4.8);
              x += col.width;
            });
            metaY += 7;
          };

          drawHeader();
          doc.setFont("helvetica", "normal");
          doc.setFontSize(9);

          rows.forEach((row) => {
            const wrappedByCol = columns.map((col) =>
              doc.splitTextToSize(String(row[col.key] ?? "-"), col.width - 3),
            );
            const rowLines = Math.max(...wrappedByCol.map((lines) => lines.length), 1);
            const rowHeight = rowLines * 3.8 + 2;
            if (metaY + rowHeight > pageHeight - margin) {
              doc.addPage();
              metaY = margin;
              drawHeader();
              doc.setFont("helvetica", "normal");
              doc.setFontSize(9);
            }
            let x = margin;
            wrappedByCol.forEach((lines, idx) => {
              doc.text(lines, x + 1.5, metaY + 3.2);
              x += columns[idx].width;
            });
            doc.setDrawColor(235, 235, 235);
            doc.line(margin, metaY + rowHeight, margin + contentWidth, metaY + rowHeight);
            metaY += rowHeight;
          });
          metaY += 2;
        };

        const kpiRows = selectedKpis.map((kpiName) => {
          const meta = kpiConfig?.[kpiName] || {};
          return {
            kpi: kpiName,
            threshold: meta?.threshold ?? "-",
            weight: meta?.weight ?? "-",
            direction: meta?.direction || "-",
          };
        });
        drawTableSection(
          "KPI Thresholds / Weights",
          [
            { key: "kpi", label: "KPI", width: contentWidth * 0.58 },
            { key: "threshold", label: "Threshold", width: contentWidth * 0.14 },
            { key: "weight", label: "Weight", width: contentWidth * 0.12 },
            { key: "direction", label: "Direction", width: contentWidth * 0.16 },
          ],
          kpiRows,
        );

        const toRuleRows = (obj) =>
          Object.entries(obj || {})
            .sort(([a], [b]) => a.localeCompare(b))
            .map(([key, value]) => ({ rule: prettifyKey(key), value: String(value) }));

        drawTableSection(
          "Applied RCA Rule Thresholds",
          [
            { key: "rule", label: "Rule", width: contentWidth * 0.78 },
            { key: "value", label: "Value", width: contentWidth * 0.22 },
          ],
          toRuleRows(appliedThresholds?.rca_thresholds),
        );
        drawTableSection(
          "Applied Severity Thresholds",
          [
            { key: "rule", label: "Rule", width: contentWidth * 0.78 },
            { key: "value", label: "Value", width: contentWidth * 0.22 },
          ],
          toRuleRows(appliedThresholds?.severity_thresholds),
        );
        drawTableSection(
          "Applied Anomaly Thresholds",
          [
            { key: "rule", label: "Rule", width: contentWidth * 0.78 },
            { key: "value", label: "Value", width: contentWidth * 0.22 },
          ],
          toRuleRows(appliedThresholds?.anomaly_thresholds),
        );

        doc.addPage();
      doc.addImage(imageData, "PNG", margin, yOffset, contentWidth, renderedHeight, undefined, "FAST");
      remainingHeight -= usableHeight;

      while (remainingHeight > 0) {
        doc.addPage();
        yOffset = margin - (renderedHeight - remainingHeight);
        doc.addImage(imageData, "PNG", margin, yOffset, contentWidth, renderedHeight, undefined, "FAST");
        remainingHeight -= usableHeight;
      }

      const stamp = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);
      doc.save(`recommendation_visualization_${stamp}.pdf`);
    } catch (error) {
      console.error("Failed to export recommendation visualization PDF:", error);
      alert(`Failed to export visualization PDF: ${error?.message || "Unknown error"}`);
    } finally {
      setExportingPdf(false);
    }
  };

  useEffect(() => {
    if (!scopedShortNames.length) {
      setSelectedChartNames([]);
      return;
    }
    if (isPrePostMode) {
      const worsenedInScope = scopedShortNames.filter((name) =>
        worsenedShortNameSet.has(normalizeShortName(name)),
      );
      if (worsenedInScope.length) {
        setSelectedChartNames(worsenedInScope);
        return;
      }
    }
    if (resultScope === "top20") {
      setSelectedChartNames(scopedShortNames.slice(0, 20));
      return;
    }
    if (resultScope === "top10") {
      setSelectedChartNames(scopedShortNames.slice(0, 10));
      return;
    }
    setSelectedChartNames(scopedShortNames.slice(0, 5));
  }, [scopedShortNames, resultScope, isPrePostMode, worsenedShortNameSet]);

  const toggleChartName = (name) => {
    setSelectedChartNames((prev) =>
      prev.includes(name) ? prev.filter((n) => n !== name) : [...prev, name],
    );
  };

  if (!result) return null;

  return (
    <section className="rounded-2xl border border-zinc-200 bg-white p-4 shadow-sm">
      <div className="flex items-center justify-between gap-3">
        <h3 className="text-lg font-semibold text-zinc-900">Result Summary</h3>
        <div className="flex flex-wrap items-center gap-2">
          {resultView === "visualization" ? (
            <button
              type="button"
              onClick={exportVisualizationPdf}
              disabled={exportingPdf}
              className="inline-flex items-center gap-2 rounded-lg border border-zinc-300 px-3 py-1.5 text-sm text-zinc-700 hover:bg-zinc-100 disabled:opacity-60"
            >
              <Download className="h-4 w-4" />
              {exportingPdf ? "Exporting PDF..." : "Export Visualization PDF"}
            </button>
          ) : null}
          <div className="inline-flex rounded-lg border border-zinc-300 p-1">
            <button
              type="button"
              onClick={() => setResultView("table")}
              className={`rounded-md px-3 py-1.5 text-sm ${resultView === "table" ? "bg-zinc-900 text-white" : "text-zinc-700 hover:bg-zinc-100"}`}
            >
              Table
            </button>
            <button
              type="button"
              onClick={() => setResultView("visualization")}
              className={`rounded-md px-3 py-1.5 text-sm ${resultView === "visualization" ? "bg-zinc-900 text-white" : "text-zinc-700 hover:bg-zinc-100"}`}
            >
              Visualization
            </button>
          </div>
          <div className="inline-flex rounded-lg border border-zinc-300 p-1">
            <button
              type="button"
              onClick={() => setResultScope("top10")}
              className={`rounded-md px-3 py-1.5 text-sm ${resultScope === "top10" ? "bg-zinc-900 text-white" : "text-zinc-700 hover:bg-zinc-100"}`}
            >
              Top 10
            </button>
            <button
              type="button"
              onClick={() => setResultScope("top20")}
              className={`rounded-md px-3 py-1.5 text-sm ${resultScope === "top20" ? "bg-zinc-900 text-white" : "text-zinc-700 hover:bg-zinc-100"}`}
            >
              Top 20
            </button>
            <button
              type="button"
              onClick={() => setResultScope("all")}
              className={`rounded-md px-3 py-1.5 text-sm ${resultScope === "all" ? "bg-zinc-900 text-white" : "text-zinc-700 hover:bg-zinc-100"}`}
            >
              All
            </button>
          </div>
        </div>
      </div>

      <div className="mt-3 grid grid-cols-1 gap-3 md:grid-cols-3 text-sm">
        <div className="rounded-lg border border-zinc-200 bg-zinc-50 p-3">
          Anomalies: <strong>{result?.summary?.anomaly_count || 0}</strong>
        </div>
        <div className="rounded-lg border border-zinc-200 bg-zinc-50 p-3">
          Critical: <strong>{result?.summary?.critical_count || 0}</strong>
        </div>
        <div className="rounded-lg border border-zinc-200 bg-zinc-50 p-3">
          Major: <strong>{result?.summary?.major_count || 0}</strong>
        </div>
      </div>

      {isPrePostMode ? (
        <div className="mt-3 rounded-lg border border-zinc-200 bg-zinc-50 p-3 text-sm text-zinc-700">
          <div>
            Pre range: {preFilterMeta?.start_date || "Start"} to {preFilterMeta?.end_date || "End"} | Rows:{" "}
            {preFilterMeta?.rows_after_filter ?? 0} / {preFilterMeta?.rows_before_filter ?? 0}
          </div>
          <div className="mt-1">
            Post range: {postFilterMeta?.start_date || "Start"} to {postFilterMeta?.end_date || "End"} | Rows:{" "}
            {postFilterMeta?.rows_after_filter ?? 0} / {postFilterMeta?.rows_before_filter ?? 0}
          </div>
        </div>
      ) : result?.applied_date_filter ? (
        <div className="mt-3 rounded-lg border border-zinc-200 bg-zinc-50 p-3 text-sm text-zinc-700">
          Date filter: {result.applied_date_filter.start_date || "Start"} to{" "}
          {result.applied_date_filter.end_date || "End"} | Rows:{" "}
          {result.applied_date_filter.rows_after_filter} /{" "}
          {result.applied_date_filter.rows_before_filter}
        </div>
      ) : null}

      {Array.isArray(result?.missing_kpis) && result.missing_kpis.length ? (
        <div className="mt-3 rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
          Missing KPI mappings: {result.missing_kpis.join(", ")}
        </div>
      ) : null}

      {resultView === "table" ? (
        <div className="mt-3 overflow-auto rounded-lg border border-zinc-200">
          <table className="w-full text-sm">
            <thead className="bg-zinc-50">
              <tr className="text-left">
                {isPrePostMode ? <th className="px-3 py-2">Period</th> : null}
                <th className="px-3 py-2">{shortNameDisplayLabel}</th>
                <th className="px-3 py-2">Date</th>
                <th className="px-3 py-2">Severity</th>
                <th className="px-3 py-2">Score</th>
                <th className="px-3 py-2">Primary Cause</th>
                <th className="px-3 py-2">Recommendation</th>
              </tr>
            </thead>
            <tbody>
              {displayedAnomalyDetails.map((row, idx) => (
                <tr
                  key={`${row["Short name"] || "row"}-${idx}`}
                  className={
                    idx % 2 ? "bg-zinc-50/40 align-top" : "bg-white align-top"
                  }
                >
                  {isPrePostMode ? <td className="px-3 py-2">{row.Period || "-"}</td> : null}
                  <td className="px-3 py-2">{row["Short name"]}</td>
                  <td className="px-3 py-2">
                    {row.Date ? String(row.Date).slice(0, 10) : ""}
                  </td>
                  <td className="px-3 py-2">{row.Severity}</td>
                  <td className="px-3 py-2">{row["Anomaly Score"]}</td>
                  <td className="px-3 py-2">{row["Primary Cause"]}</td>
                  <td className="px-3 py-2">
                    {row["Vendor Recommendation"] ||
                      row["Nokia Recommendation"]}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div ref={visualizationRef} className="mt-4 space-y-6">
          {/* SECTION 1: DATE-WISE ANOMALY TREND */}
          {dateTrendModel.length > 0 && (
            <div className="rounded-2xl border border-zinc-200 bg-white shadow-sm overflow-hidden">
              <div className="border-b border-zinc-100 bg-white p-5 flex items-center justify-between">
                <div>
                  <h3 className="text-sm font-bold text-zinc-900 tracking-tight flex items-center gap-2">
                    <BarChart3 className="h-4 w-4 text-blue-500" />
                    Date-wise Anomaly Trend
                  </h3>
                  <p className="text-[11px] text-zinc-500 font-medium uppercase tracking-wider mt-0.5">
                    Critical Cause Stack vs Total Events
                  </p>
                </div>
                <div className="flex gap-4">
                  <div className="flex items-center gap-2">
                    <div className="h-1.5 w-3 rounded-full bg-blue-500" />
                    <span className="text-[10px] font-bold text-zinc-600 uppercase">
                      Total
                    </span>
                  </div>
                  <div className="flex items-center gap-2">
                    <div className="h-1.5 w-3 rounded-full bg-zinc-700" />
                    <span className="text-[10px] font-bold text-zinc-600 uppercase">
                      Critical (stacked)
                    </span>
                  </div>
                </div>
              </div>
              <div className="p-5">
                <div className="h-72 w-full">
                  <ResponsiveContainer width="100%" height="100%">
                    <ComposedChart
                      data={dateTrendModel}
                      margin={{ top: 10, right: 10, left: -20, bottom: 0 }}
                    >
                      <defs>
                        <linearGradient
                          id="colorTotal"
                          x1="0"
                          y1="0"
                          x2="0"
                          y2="1"
                        >
                          <stop
                            offset="5%"
                            stopColor="#3b82f6"
                            stopOpacity={0.1}
                          />
                          <stop
                            offset="95%"
                            stopColor="#3b82f6"
                            stopOpacity={0}
                          />
                        </linearGradient>
                      </defs>
                      <CartesianGrid
                        strokeDasharray="3 3"
                        vertical={false}
                        stroke="#f1f1f4"
                      />
                      <XAxis
                        dataKey="date"
                        axisLine={false}
                        tickLine={false}
                        tick={{ fontSize: 10, fill: "#a1a1aa" }}
                        tickFormatter={(v) => formatDateHeader(v)}
                      />
                      <YAxis
                        yAxisId="count"
                        axisLine={false}
                        tickLine={false}
                        tick={{ fontSize: 10, fill: "#a1a1aa" }}
                      />
                      <Tooltip
                        content={renderTrendTooltip}
                        cursor={{ fill: "#f8fafc" }}
                      />
                      {isPrePostMode && preFilterMeta?.end_date ? (
                        <ReferenceLine
                          x={preFilterMeta.end_date}
                          yAxisId="count"
                          stroke="#06b6d4"
                          strokeWidth={2}
                          strokeDasharray="6 6"
                        />
                      ) : null}
                      <Area
                        yAxisId="count"
                        type="monotone"
                        dataKey="anomaly_count"
                        fill="url(#colorTotal)"
                        stroke="#3b82f6"
                        strokeWidth={2}
                        dot={{ r: 3, fill: "#3b82f6", strokeWidth: 0 }}
                        activeDot={{ r: 4, fill: "#2563eb", strokeWidth: 0 }}
                      />
                      <Bar
                        yAxisId="count"
                        dataKey="critical_count"
                        fill="transparent"
                        hide
                        barSize={20}
                      />
                      {criticalCauseMeta.map((cause) => (
                        <Bar
                          key={`critical-bar-${cause.key}`}
                          yAxisId="count"
                          dataKey={cause.key}
                          name={cause.name}
                          stackId="critical_by_cause"
                          fill={cause.color}
                          barSize={20}
                        />
                      ))}
                    </ComposedChart>
                  </ResponsiveContainer>
                </div>
                {criticalCauseMeta.length > 0 ? (
                  <div className="mt-4 rounded-lg border border-zinc-200 bg-zinc-50/50 p-3">
                    <div className="mb-2 text-[10px] font-bold uppercase tracking-widest text-zinc-500">
                      Critical Cause Legend
                    </div>
                    <div className="flex flex-wrap gap-2">
                      {criticalCauseMeta.map((cause) => (
                        <div
                          key={`critical-legend-${cause.key}`}
                          className="inline-flex items-center gap-2 rounded-full border border-zinc-200 bg-white px-2.5 py-1 text-[10px] font-semibold text-zinc-700"
                        >
                          <span
                            className="h-2.5 w-2.5 rounded-full"
                            style={{ background: cause.color }}
                          />
                          <span>{cause.name}</span>
                        </div>
                      ))}
                    </div>
                  </div>
                ) : null}
              </div>
            </div>
          )}

          {/* SECTION 2: ANOMALY SCORE TREND BY SHORT NAME */}
          {scopedShortNames.length > 0 && (
            <div className="rounded-2xl border border-zinc-200 bg-white shadow-sm overflow-hidden">
              <div className="border-b border-zinc-100 bg-zinc-50/30 p-5 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
                <div>
                  <h3 className="text-sm font-bold text-zinc-900 tracking-tight flex items-center gap-2">
                    <Activity className="h-4 w-4 text-indigo-500" />
                    {`Anomaly Score Trend by ${shortNameDisplayLabel}`}
                  </h3>
                  <p className="text-[11px] text-zinc-500 font-medium mt-0.5">
                    Select nodes to compare performance
                  </p>
                </div>
                <div className="flex bg-white rounded-lg border border-zinc-200 p-1 shadow-sm h-fit">
                  <button
                    onClick={() => setSelectedChartNames(filteredShortNamesForList)}
                    className="px-3 py-1 text-[10px] font-bold text-zinc-500 hover:text-indigo-600 transition-colors uppercase"
                  >
                    All
                  </button>
                  <div className="w-px h-3 bg-zinc-200 my-auto" />
                  <button
                    onClick={() => setSelectedChartNames([])}
                    className="px-3 py-1 text-[10px] font-bold text-zinc-500 hover:text-red-600 transition-colors uppercase"
                  >
                    None
                  </button>
                </div>
              </div>

              <div className="flex flex-col lg:flex-row min-h-[450px]">
                {/* Selection Sidebar */}
                <div className="w-full lg:w-72 border-r border-zinc-100 bg-zinc-50/20 p-4 max-h-[450px] overflow-y-auto custom-scrollbar">
                  <div className="mb-3">
                    <input
                      type="text"
                      value={shortNameQuery}
                      onChange={(e) => setShortNameQuery(e.target.value)}
                      placeholder="Search short name..."
                      className="w-full rounded-lg border border-zinc-200 bg-white px-3 py-2 text-xs focus:border-indigo-500 focus:outline-none"
                    />
                  </div>
                  <div className="space-y-1">
                    {filteredShortNamesForList.map((name) => {
                      const isSelected = selectedChartNames.includes(name);
                      const color = colorByShortName.get(name) || LINE_COLORS[0];
                      return (
                        <button
                          key={`side-${name}`}
                          onClick={() => toggleChartName(name)}
                          className={`group flex w-full items-center gap-3 rounded-xl px-3 py-2 transition-all ${
                            isSelected
                              ? "bg-white shadow-sm ring-1 ring-zinc-200"
                              : "opacity-50 hover:opacity-100"
                          }`}
                        >
                          <div
                            className="h-2 w-2 shrink-0 rounded-full"
                            style={{
                              backgroundColor: color,
                              boxShadow: isSelected
                                ? `0 0 8px ${color}`
                                : "none",
                            }}
                          />
                          <span
                            className={`truncate text-[11px] font-bold tracking-tight text-left ${isSelected ? "text-zinc-900" : "text-zinc-500"}`}
                          >
                            {name}
                          </span>
                        </button>
                      );
                    })}
                  </div>
                </div>

                {/* Chart Canvas */}
                <div className="flex-1 p-6">
                  {selectedChartNames.length ? (
                    <div className="h-full w-full">
                      <ResponsiveContainer width="100%" height="100%">
                        <LineChart
                          data={lineChartModel.chartData}
                          margin={{ top: 10, right: 10, left: -20, bottom: 0 }}
                        >
                          <CartesianGrid
                            strokeDasharray="3 3"
                            vertical={false}
                            stroke="#f1f1f4"
                          />
                          <XAxis
                            dataKey="date"
                            axisLine={false}
                            tickLine={false}
                            tick={{ fontSize: 10, fill: "#a1a1aa" }}
                            tickFormatter={(v) => formatDateHeader(v)}
                          />
                          <YAxis
                            axisLine={false}
                            tickLine={false}
                            tick={{ fontSize: 10, fill: "#a1a1aa" }}
                          />
                          <Tooltip
                            content={renderLineTooltip}
                            cursor={{ stroke: "#e4e4e7", strokeWidth: 2 }}
                            wrapperStyle={{ pointerEvents: "auto", zIndex: 40 }}
                          />
                          {isPrePostMode && preFilterMeta?.end_date ? (
                            <ReferenceLine
                              x={preFilterMeta.end_date}
                              stroke="#06b6d4"
                              strokeWidth={2}
                              strokeDasharray="6 6"
                            />
                          ) : null}
                          {selectedChartNames.map((name) => (
                            <Line
                              key={`line-${name}`}
                              type="monotone"
                              dataKey={name}
                              stroke={colorByShortName.get(name) || LINE_COLORS[0]}
                              strokeWidth={2.5}
                              dot={false}
                              activeDot={{ r: 4, strokeWidth: 0 }}
                            />
                          ))}
                        </LineChart>
                      </ResponsiveContainer>
                    </div>
                  ) : (
                    <div className="flex h-full items-center justify-center border-2 border-dashed border-zinc-100 rounded-2xl bg-zinc-50/30">
                      <p className="text-xs font-bold text-zinc-400 uppercase tracking-widest">
                        Select nodes to view chart
                      </p>
                    </div>
                  )}
                </div>
              </div>
            </div>
          )}

          {/* SECTION 3: SEVERITY HEATMAP */}
          {!filteredHeatMap.xDates.length || !filteredHeatMap.yNames.length ? (
            <div className="p-8 text-center bg-zinc-50 rounded-2xl border border-zinc-200">
              <p className="text-sm text-zinc-500 font-medium">
                No heatmap data available.
              </p>
            </div>
          ) : (
            <div className="rounded-2xl border border-zinc-200 bg-white shadow-sm overflow-hidden">
              <div className="border-b border-zinc-100 bg-white p-5 flex items-center justify-between">
                <div>
                  <h3 className="text-sm font-bold text-zinc-900 tracking-tight">
                    Severity Heatmap Matrix
                  </h3>
                  <p className="text-[11px] text-zinc-500 font-medium uppercase tracking-wider mt-0.5">
                    Node Performance vs Time
                  </p>
                </div>
                <div className="flex items-center gap-2 bg-indigo-50 px-3 py-1 rounded-full text-indigo-700">
                  <button
                    type="button"
                    onClick={() => setShowHeatScores((prev) => !prev)}
                    className="rounded-md border border-indigo-200 bg-white px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide text-indigo-700"
                  >
                    {showHeatScores ? "Hide Score" : "Show Score"}
                  </button>
                  <Info className="h-3 w-3" />
                  <span className="text-[10px] font-bold uppercase">
                    Hover cells for Root Cause
                  </span>
                </div>
              </div>

              <div className="flex gap-4 p-5 overflow-hidden">
                <div className="min-w-0 flex-1 overflow-auto rounded-xl border border-zinc-100 custom-scrollbar">
                  {(() => {
                    const minColWidth =
                      filteredHeatMap.xDates.length <= 14 ? 60 : 40;
                    return (
                      <div
                        className="grid"
                        style={{
                          gridTemplateColumns: `200px repeat(${filteredHeatMap.xDates.length}, minmax(${minColWidth}px, 1fr))`,
                        }}
                      >
                        {/* Heatmap Header */}
                        <div className="sticky left-0 top-0 z-30 bg-zinc-900 px-4 py-2.5 text-[10px] font-bold uppercase tracking-widest text-zinc-400">
                          {shortNameDisplayLabel}
                        </div>
                        {filteredHeatMap.xDates.map((date, dateIdx) => (
                          <div
                            key={`h-x-${date}`}
                            className={`sticky top-0 z-20 bg-zinc-900 px-1 py-2.5 text-center text-[10px] font-bold text-white whitespace-nowrap border-l border-zinc-800 ${
                              heatMapPostBoundaryIndex === dateIdx ? "border-r-2 border-r-cyan-300" : ""
                            }`}
                            style={heatMapPostBoundaryIndex === dateIdx ? { borderRightStyle: "dashed" } : undefined}
                          >
                            {formatDateHeader(date)}
                          </div>
                        ))}

                        {/* Heatmap Body */}
                        {filteredHeatMap.yNames.map((name, rowIdx) => (
                          <React.Fragment key={`h-row-${name}`}>
                            <div className="sticky left-0 z-10 border-t border-zinc-50 bg-white px-4 py-2 text-[10px] font-bold text-zinc-600 truncate shadow-[2px_0_4px_rgba(0,0,0,0.02)]">
                              {name}
                            </div>
                            {filteredHeatMap.cells[rowIdx].map((cell, dateIdx) => (
                              <div
                                key={`h-cell-${name}-${cell.date}`}
                                className={`relative h-8 border-l border-t border-zinc-50 transition-transform hover:z-20 hover:scale-105 hover:shadow-lg cursor-pointer ${
                                  heatMapPostBoundaryIndex === dateIdx ? "border-r-2 border-r-cyan-300" : ""
                                }`}
                                style={{
                                  ...(heatMapPostBoundaryIndex === dateIdx ? { borderRightStyle: "dashed" } : {}),
                                  background:
                                    (cell.score === null ||
                                      cell.score === undefined ||
                                      Number.isNaN(Number(cell.score))) &&
                                    !String(cell.severity || "").trim() &&
                                    !String(cell.cause || "").trim()
                                      ? "#ffffff"
                                      : getHeatColor(
                                          cell.score,
                                          filteredHeatMap.minScore,
                                          filteredHeatMap.maxScore,
                                        ),
                                }}
                                onMouseMove={(e) =>
                                  setHeatTooltip({
                                    x: e.clientX + 14,
                                    y: e.clientY + 14,
                                    shortName: cell.shortName,
                                    date: cell.date,
                                    score: cell.score,
                                    severity: cell.severity,
                                    cause: cell.cause,
                                  })
                                }
                                onMouseLeave={() => setHeatTooltip(null)}
                              >
                                {showHeatScores &&
                                cell.score !== null &&
                                cell.score !== undefined &&
                                !Number.isNaN(Number(cell.score)) ? (
                                  <span
                                    className="pointer-events-none absolute inset-0 flex items-center justify-center text-[9px] font-semibold"
                                    style={{
                                      color: getHeatScoreTextColor(
                                        cell.score,
                                        filteredHeatMap.minScore,
                                        filteredHeatMap.maxScore,
                                      ),
                                    }}
                                  >
                                    {Number(cell.score).toFixed(1)}
                                  </span>
                                ) : null}
                              </div>
                            ))}
                          </React.Fragment>
                        ))}
                      </div>
                    );
                  })()}
                </div>

                {/* Vertical Scale Legend */}
                <div className="w-20 shrink-0 flex flex-col">
                  <span className="text-[10px] font-bold uppercase text-zinc-400 mb-2">
                    Score
                  </span>
                  <div className="flex-1 flex gap-2 rounded-lg border border-zinc-100 p-1 bg-zinc-50/50">
                    <div
                      className="w-3 rounded-md"
                      style={{
                        background:
                          "linear-gradient(to top, #ffffcc, #ffeda0, #fed976, #feb24c, #fd8d3c, #fc4e2a, #e31a1c, #bd0026, #8f001f)",
                      }}
                    />
                    <div className="flex flex-col justify-between py-1 text-[9px] font-bold text-zinc-500">
                      <span>{filteredHeatMap.maxScore.toFixed(0)}</span>
                      <span>
                        {(
                          (filteredHeatMap.maxScore +
                            filteredHeatMap.minScore) /
                          2
                        ).toFixed(0)}
                      </span>
                      <span>{filteredHeatMap.minScore.toFixed(0)}</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          )}

          {isPrePostMode ? (
            <div className="rounded-2xl border border-zinc-200 bg-white shadow-sm overflow-hidden">
              <div className="border-b border-zinc-100 bg-white p-5">
                <h3 className="text-sm font-bold text-zinc-900 tracking-tight">
                  Anomalies recommendation
                </h3>
                <p className="text-[11px] text-zinc-500 font-medium uppercase tracking-wider mt-0.5">
                  {worsenedScopeLabel === "all"
                    ? "Showing all bad deltas and all good deltas (pre vs post average)"
                    : `Showing top ${worsenedScopeLabel} bad deltas and top ${worsenedScopeLabel} good deltas (pre vs post average)`}
                </p>
              </div>
              {!worsenedRows.length ? (
                <div className="p-5 text-sm text-zinc-600">
                  No pre/post delta rows found.
                </div>
              ) : (
                <div className="overflow-auto">
                  <table className="w-full text-sm">
                    <thead className="bg-zinc-50 text-left">
                      <tr>
                        <th className="px-3 py-2">{shortNameDisplayLabel}</th>
                        <th className="px-3 py-2">Pre Z score Avg</th>
                        <th className="px-3 py-2">Post Z score Avg</th>
                        <th className="px-3 py-2">Delta</th>
                        <th className="px-3 py-2">Nokia Parameter / Feature to Check</th>
                        <th className="px-3 py-2">Suggested Initial Value / Action</th>
                        <th className="px-3 py-2">Implementation Note</th>
                      </tr>
                    </thead>
                    <tbody>
                      {worsenedRows.map((row, idx) => {
                        const isGood = String(row.delta_direction || "").toLowerCase() === "good" || Number(row.delta) < 0;
                        const deltaValue = Math.abs(Number(row.delta) || 0).toFixed(2);
                        const params = isGood ? [] : splitTopNumberedItems(row["Nokia Parameter / Feature to Check"], 5);
                        const actions = isGood ? [] : splitTopNumberedItems(row["Suggested Initial Value / Action"], 5);
                        const notes = isGood ? [] : splitTopNumberedItems(row["Implementation Note"], 5);
                        const renderList = (items) => (
                          <ol className="list-decimal pl-4 space-y-0.5">
                            {items.map((item, itemIdx) => (
                              <li key={`w-${idx}-${itemIdx}`} className="text-xs text-zinc-700">
                                {item}
                              </li>
                            ))}
                          </ol>
                        );
                        return (
                          <tr key={`worse-${row["Short name"] || idx}`} className={idx % 2 ? "bg-zinc-50/40 align-top" : "bg-white align-top"}>
                            <td className="px-3 py-2 font-semibold">{row["Short name"]}</td>
                            <td className="px-3 py-2">{row.pre_avg}</td>
                            <td className="px-3 py-2">{row.post_avg}</td>
                            <td className={`px-3 py-2 font-semibold ${isGood ? "text-emerald-600" : "text-rose-600"}`}>
                              {isGood ? `+${deltaValue}` : `-${deltaValue}`}
                            </td>
                            <td className="px-3 py-2 min-w-[260px]">{params.length ? renderList(params) : ""}</td>
                            <td className="px-3 py-2 min-w-[260px]">{actions.length ? renderList(actions) : ""}</td>
                            <td className="px-3 py-2 min-w-[260px]">{notes.length ? renderList(notes) : ""}</td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          ) : null}
        </div>
      )}

      {resultView === "visualization" && heatTooltip ? (
        <div
          className="pointer-events-none fixed z-50 max-w-xs rounded-lg border border-zinc-300 bg-white px-3 py-2 text-xs shadow-lg"
          style={{ left: heatTooltip.x, top: heatTooltip.y }}
        >
          <div className="font-semibold text-zinc-900">
            {heatTooltip.shortName}
          </div>
          <div className="text-zinc-600">Date: {heatTooltip.date}</div>
          <div className="text-zinc-600">
            Score:{" "}
            {heatTooltip.score === null || heatTooltip.score === undefined
              ? "NA"
              : Number(heatTooltip.score).toFixed(2)}
          </div>
          <div className="text-zinc-600">
            Severity: {heatTooltip.severity || "-"}
          </div>
          <div className="line-clamp-2 text-zinc-600">
            Cause: {heatTooltip.cause || "-"}
          </div>
        </div>
      ) : null}
    </section>
  );
}

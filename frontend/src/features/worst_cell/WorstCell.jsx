import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  fetchKpiUploadHistory,
  fetchDynamicKpiMetrics,
  fetchDynamicKpiData,
  fetchDynamicKpiColumns,
} from "../kpi/kpiService";
import {
  getThresholdSetting,
  postThresholdSetting,
} from "../dashboard/services/dashboardService";
import { AlertTriangle, Loader2, Calendar, FileText, Download } from "lucide-react";

const OPERATOR_OPTIONS = [">", ">=", "<", "<="];
const DEFAULT_OPERATOR = ">=";
const THRESHOLD_STORAGE_PREFIX = "dashboardThresholdProfile";
const THRESHOLD_OPERATOR_STORAGE_PREFIX = "dashboardThresholdOperators";
const QUICK_DAY_OPTIONS = [
  { value: "1", label: "Last 1 day" },
  { value: "7", label: "Last 7 days" },
  { value: "15", label: "Last 15 days" },
  { value: "30", label: "Last 30 days" },
  { value: "custom", label: "Custom days" },
];
const DIMENSION_PREFERENCE = [
  "cellName",
  "cellname",
  "cellId",
  "cellid",
  "sectorname",
  "sectorid",
  "site",
  "groups",
  "band",
  "tech",
];

const normalizeKey = (value) =>
  String(value || "")
    .toLowerCase()
    .replace(/[^a-z0-9]/g, "");

const formatMetricNumber = (value) => {
  const number = Number(value);
  if (Number.isNaN(number)) return "";
  return number.toFixed(2);
};

const escapeHtml = (value) =>
  String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");

const resolveKpiCategory = (metricLabel, metricKey) => {
  const source = `${metricLabel || ""} ${metricKey || ""}`.toLowerCase();
  if (source.includes("throughput")) return "Throughput";
  if (source.includes("prb")) return "PRB Utilization";
  if (source.includes("drop")) return "Drop Rate";
  if (source.includes("success")) return "Success Rate";
  if (source.includes("volume")) return "Data Volume";
  if (source.includes("hosr") || source.includes("handover")) return "HOSR";
  if (source.includes("rrc")) return "RRC";
  if (source.includes("erab")) return "ERAB";
  if (source.includes("volte")) return "VoLTE";
  if (source.includes("csfb")) return "CSFB";
  return "Other";
};

const formatRangeText = (start, end, singleDay = "") => {
  if (singleDay) return singleDay;
  if (start && end) return `${start} to ${end}`;
  if (start) return `From ${start}`;
  if (end) return `Until ${end}`;
  return "All dates";
};

const validateFilterInputs = (mode, ranges, quickDays = "") => {
  if (mode === "date_range") {
    if (quickDays) {
      return "";
    }
    const hasStart = Boolean(ranges.preStart);
    const hasEnd = Boolean(ranges.preEnd);
    if ((hasStart && !hasEnd) || (!hasStart && hasEnd)) {
      return "Select both Start Date and End Date in Date Range mode.";
    }
    if (!hasStart && !hasEnd) {
      return "Select Start/End date range or choose a Quick Range.";
    }
    return "";
  }

  if (!ranges.preStart || !ranges.preEnd || !ranges.postStart || !ranges.postEnd) {
    return "Select Pre Start, Pre End, Post Start, and Post End to run comparison.";
  }

  return "";
};

const getDefaultThresholdOperator = (thresholdKey = "") => {
  const normalized = normalizeKey(thresholdKey);
  if (
    normalized.includes("droprate") ||
    normalized.includes("dcr") ||
    normalized === "voltedcreric"
  ) {
    return "<=";
  }
  return ">=";
};

const toDateUTC = (datePart) => {
  const [year, month, day] = String(datePart || "")
    .split("-")
    .map((part) => Number(part));
  if (!year || !month || !day) return null;
  return new Date(Date.UTC(year, month - 1, day));
};

const toDatePartUTC = (date) => {
  if (!(date instanceof Date) || Number.isNaN(date.getTime())) return "";
  return date.toISOString().slice(0, 10);
};

const getTodayDatePart = () => {
  const date = new Date();
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
};

const resolveLastDaysRange = (daysCount, referenceEndDatePart = "") => {
  const days = Number(daysCount);
  if (!Number.isFinite(days) || days <= 0) return { start: "", end: "" };

  const endPart = referenceEndDatePart || getTodayDatePart();
  const endDate = toDateUTC(endPart);
  if (!endDate) return { start: "", end: endPart };
  const startDate = new Date(endDate);
  startDate.setUTCDate(startDate.getUTCDate() - (days - 1));
  return { start: toDatePartUTC(startDate), end: endPart };
};

const sanitizeThresholdPayload = (payload) => {
  const result = {};
  Object.entries(payload || {}).forEach(([key, value]) => {
    if (
      key === "id" ||
      key === "fileId" ||
      key === "success" ||
      key === "message" ||
      key === "thresholdOperators"
    ) {
      return;
    }
    const numeric = Number(value);
    if (!Number.isNaN(numeric)) {
      result[key] = numeric;
    }
  });
  return result;
};

const sanitizeThresholdOperatorPayload = (payload) => {
  const result = {};
  Object.entries(payload || {}).forEach(([key, value]) => {
    const normalizedKey = String(key || "").trim();
    const operator = String(value || "").trim();
    if (!normalizedKey) return;
    if (!OPERATOR_OPTIONS.includes(operator)) return;
    result[normalizedKey] = operator;
  });
  return result;
};

export default function WorstCell() {
  const [fileOptions, setFileOptions] = useState([]);
  const [selectedFileId, setSelectedFileId] = useState("");
  const [metrics, setMetrics] = useState([]);
  const [columns, setColumns] = useState([]);
  const [selectedDimensionKey, setSelectedDimensionKey] = useState("");
  const [compareRanges, setCompareRanges] = useState({
    preStart: "",
    preEnd: "",
    postStart: "",
    postEnd: "",
  });
  const [loading, setLoading] = useState(false);
  const [analysisRows, setAnalysisRows] = useState([]);
  const [loadError, setLoadError] = useState("");
  const [operatorsByMetric, setOperatorsByMetric] = useState({});
  const [downloadSelection, setDownloadSelection] = useState("");
  const [filterMode, setFilterMode] = useState("date_range");
  const [quickDateDays, setQuickDateDays] = useState("");
  const [customQuickDays, setCustomQuickDays] = useState("");
  const [thresholds, setThresholds] = useState({});
  const [thresholdOperators, setThresholdOperators] = useState({});
  const [thresholdDrafts, setThresholdDrafts] = useState({});
  const [updatingThresholdKey, setUpdatingThresholdKey] = useState("");
  const [updatingMetricKeys, setUpdatingMetricKeys] = useState({});
  const workerRef = useRef(null);
  const workerRequestIdRef = useRef(0);
  const workerPendingRef = useRef(new Map());
  const analysisRunIdRef = useRef(0);

  useEffect(() => {
    const worker = new Worker(
      new URL("./worstCell.worker.js", import.meta.url),
      { type: "module" },
    );
    const pendingMap = workerPendingRef.current;

    workerRef.current = worker;

    worker.onmessage = (event) => {
      const { id, result, error } = event.data || {};
      const pending = pendingMap.get(id);
      if (!pending) return;
      pendingMap.delete(id);
      if (error) {
        pending.reject(new Error(error));
      } else {
        pending.resolve(result);
      }
    };

    worker.onerror = (event) => {
      const message = event?.message || "Worker processing failed.";
      pendingMap.forEach(({ reject }) => reject(new Error(message)));
      pendingMap.clear();
    };

    return () => {
      worker.terminate();
      workerRef.current = null;
      pendingMap.forEach(({ reject }) =>
        reject(new Error("Worker terminated.")),
      );
      pendingMap.clear();
    };
  }, []);

  useEffect(() => {
    const loadFiles = async () => {
      const response = await fetchKpiUploadHistory();
      const files = response?.success ? response.data || [] : [];
      setFileOptions(files);
      if (files.length && !selectedFileId) {
        setSelectedFileId(String(files[0].id));
      }
    };
    loadFiles();
  }, [selectedFileId]);

  useEffect(() => {
    const loadFileContext = async () => {
      if (!selectedFileId) {
        setMetrics([]);
        setColumns([]);
        setSelectedDimensionKey("");
        setOperatorsByMetric({});
        return;
      }

      const [metricResponse, columnResponse] = await Promise.all([
        fetchDynamicKpiMetrics(selectedFileId),
        fetchDynamicKpiColumns(selectedFileId),
      ]);

      const nextMetrics = metricResponse?.success ? metricResponse.data || [] : [];
      const nextColumns = Array.isArray(columnResponse?.columns)
        ? columnResponse.columns
        : [];

      setMetrics(nextMetrics);
      setColumns(nextColumns);
      setOperatorsByMetric((prev) => {
        const next = {};
        nextMetrics.forEach((metric) => {
          const current = prev?.[metric.key];
          next[metric.key] = OPERATOR_OPTIONS.includes(current)
            ? current
            : DEFAULT_OPERATOR;
        });
        return next;
      });

      const dimensionKeys = nextColumns
        .filter((column) => column?.isDimension)
        .map((column) => column.key);
      const preferred = DIMENSION_PREFERENCE.find((pref) =>
        dimensionKeys.some((key) => normalizeKey(key) === normalizeKey(pref)),
      );
      const matchedPreferredKey = preferred
        ? dimensionKeys.find((key) => normalizeKey(key) === normalizeKey(preferred))
        : null;
      setSelectedDimensionKey(matchedPreferredKey || dimensionKeys[0] || "");
    };

    loadFileContext();
  }, [selectedFileId]);

  useEffect(() => {
    let mounted = true;

    const loadThresholdConfig = async () => {
      let apiThresholds = {};
      let apiOperators = {};
      try {
        const response = await getThresholdSetting(selectedFileId || undefined);
        if (response?.success && response?.data) {
          apiThresholds = sanitizeThresholdPayload(response.data);
          apiOperators = sanitizeThresholdOperatorPayload(
            response.data.thresholdOperators,
          );
        }
      } catch {
        apiThresholds = {};
        apiOperators = {};
      }

      let localThresholds = {};
      let localOperators = {};
      if (typeof window !== "undefined") {
        try {
          if (selectedFileId) {
            const thresholdRaw = window.localStorage.getItem(
              `${THRESHOLD_STORAGE_PREFIX}:${String(selectedFileId)}`,
            );
            if (thresholdRaw) {
              const parsed = JSON.parse(thresholdRaw);
              if (parsed && typeof parsed === "object") {
                localThresholds = sanitizeThresholdPayload(parsed);
              }
            }
          }
        } catch {
          localThresholds = {};
        }

        try {
          const fileOperatorRaw = selectedFileId
            ? window.localStorage.getItem(
                `${THRESHOLD_OPERATOR_STORAGE_PREFIX}:${String(selectedFileId)}`,
              )
            : null;
          const globalOperatorRaw = window.localStorage.getItem(
            `${THRESHOLD_OPERATOR_STORAGE_PREFIX}:global`,
          );
          const parsedFile = fileOperatorRaw ? JSON.parse(fileOperatorRaw) : null;
          const parsedGlobal = globalOperatorRaw ? JSON.parse(globalOperatorRaw) : null;
          localOperators = {
            ...(parsedGlobal && typeof parsedGlobal === "object" ? parsedGlobal : {}),
            ...(parsedFile && typeof parsedFile === "object" ? parsedFile : {}),
          };
        } catch {
          localOperators = {};
        }
      }

      if (!mounted) return;
      setThresholds({ ...localThresholds, ...apiThresholds });
      setThresholdOperators({
        ...localOperators,
        ...apiOperators,
      });
    };

    loadThresholdConfig();
    return () => {
      mounted = false;
    };
  }, [selectedFileId]);

  useEffect(() => {
    setThresholdDrafts((prev) => {
      const next = {};
      Object.entries(thresholds || {}).forEach(([key, value]) => {
        next[key] = value === undefined || value === null ? "" : String(value);
      });
      return {
        ...prev,
        ...next,
      };
    });
  }, [thresholds]);

  const dimensionOptions = useMemo(() => {
    const keys = columns
      .filter((column) => column?.isDimension)
      .map((column) => ({
        value: column.key,
        label: column.label || column.key,
      }));
    return keys;
  }, [columns]);

  const selectedFile = useMemo(
    () => fileOptions.find((file) => String(file.id) === String(selectedFileId)),
    [fileOptions, selectedFileId],
  );

  const thresholdIndex = useMemo(() => {
    const map = new Map();
    Object.entries(thresholds || {}).forEach(([key, value]) => {
      const normalized = normalizeKey(key);
      const numeric = Number(value);
      if (!normalized || Number.isNaN(numeric)) return;
      map.set(normalized, { thresholdKey: key, thresholdValue: numeric });
    });
    return map;
  }, [thresholds]);

  const effectiveQuickDateDays = useMemo(() => {
    if (quickDateDays !== "custom") return quickDateDays;
    const parsed = Number(customQuickDays);
    if (!Number.isInteger(parsed) || parsed <= 0) return "";
    return String(parsed);
  }, [quickDateDays, customQuickDays]);

  const resolveThresholdForMetric = (metric) => {
    const candidates = [
      normalizeKey(metric?.key),
      normalizeKey(metric?.label),
      normalizeKey(String(metric?.key || "").replace(/^max(imum)?rrc(connected)?user(number|s)?$/i, "maxrrcusers")),
      normalizeKey(String(metric?.label || "").replace(/^max(imum)?\s*rrc.*$/i, "maxrrcusers")),
    ].filter(Boolean);

    for (const candidate of candidates) {
      const direct = thresholdIndex.get(candidate);
      if (direct) {
        const operator =
          thresholdOperators?.[direct.thresholdKey] ||
          getDefaultThresholdOperator(direct.thresholdKey);
        return {
          thresholdKey: direct.thresholdKey,
          thresholdValue: direct.thresholdValue,
          thresholdOperator: OPERATOR_OPTIONS.includes(operator)
            ? operator
            : getDefaultThresholdOperator(direct.thresholdKey),
        };
      }
    }

    const fallbackThresholdKey = String(metric?.key || metric?.label || "").trim();
    if (fallbackThresholdKey) {
      const fallbackValue = Number(thresholds?.[fallbackThresholdKey]);
      return {
        thresholdKey: fallbackThresholdKey,
        thresholdValue: Number.isNaN(fallbackValue) ? null : fallbackValue,
        thresholdOperator:
          thresholdOperators?.[fallbackThresholdKey] ||
          getDefaultThresholdOperator(fallbackThresholdKey),
      };
    }

    return {
      thresholdKey: "",
      thresholdValue: null,
      thresholdOperator: DEFAULT_OPERATOR,
    };
  };

  const analyzeMetricWithWorker = useCallback((payload) => {
    return new Promise((resolve, reject) => {
      const worker = workerRef.current;
      if (!worker) {
        reject(new Error("Analysis worker is not ready."));
        return;
      }

      const id = ++workerRequestIdRef.current;
      workerPendingRef.current.set(id, { resolve, reject });
      worker.postMessage({
        type: "analyzeMetric",
        id,
        payload,
      });
    });
  }, []);

  const persistThresholdProfileLocal = (fileId, values) => {
    if (!fileId || !values || typeof values !== "object") return;
    if (typeof window === "undefined") return;
    try {
      window.localStorage.setItem(
        `${THRESHOLD_STORAGE_PREFIX}:${String(fileId)}`,
        JSON.stringify(values),
      );
    } catch {
      // ignore storage write issues
    }
  };

  const handleThresholdDraftChange = (thresholdKey, rawValue) => {
    if (!thresholdKey) return;
    setThresholdDrafts((prev) => ({
      ...prev,
      [thresholdKey]: rawValue,
    }));
  };

  const updateThresholdFromWorstCell = async (row) => {
    const thresholdKey = row?.thresholdKey;
    if (!thresholdKey || updatingThresholdKey) return;

    const rawDraft = thresholdDrafts?.[thresholdKey];
    const numericValue = Number(rawDraft);
    if (rawDraft === "" || Number.isNaN(numericValue)) {
      setLoadError(`Enter a valid threshold value for ${row.metricLabel}.`);
      return;
    }

    const previousValue = Number(thresholds?.[thresholdKey]);
    if (!Number.isNaN(previousValue) && previousValue === numericValue) {
      return;
    }

    setUpdatingThresholdKey(thresholdKey);
    setLoadError("");
    try {
      const response = await postThresholdSetting(
        {
          [thresholdKey]: numericValue,
          thresholdOperators: {
            [thresholdKey]:
              thresholdOperators?.[thresholdKey] ||
              getDefaultThresholdOperator(thresholdKey),
          },
        },
        selectedFileId || undefined,
      );
      if (!response?.success) {
        throw new Error(response?.message || "Failed to update threshold.");
      }
      const nextThresholds = {
        ...thresholds,
        [thresholdKey]: numericValue,
      };
      setThresholds(nextThresholds);
      persistThresholdProfileLocal(selectedFileId, nextThresholds);
    } catch (error) {
      setLoadError(error?.message || "Failed to update threshold.");
    } finally {
      setUpdatingThresholdKey("");
    }
  };

  const recomputeSingleMetric = async (metricKey, operatorOverride) => {
    if (!selectedFileId || !selectedDimensionKey || !metricKey) return;
    const metric = metrics.find((item) => item.key === metricKey);
    if (!metric) return;

    const filterError = validateFilterInputs(
      filterMode,
      compareRanges,
      effectiveQuickDateDays,
    );
    if (filterError) {
      setLoadError(filterError);
      return;
    }

    setUpdatingMetricKeys((prev) => ({ ...prev, [metricKey]: true }));
    setLoadError("");

    try {
      if (!workerRef.current) {
        throw new Error("Analysis engine is still loading. Please retry in a moment.");
      }

      const response = await fetchDynamicKpiData(selectedFileId, metric.key);
      const metricRows = Array.isArray(response?.data) ? response.data : [];

      const analyzedRow = await analyzeMetricWithWorker({
        metricKey: metric.key,
        metricLabel: metric.label || metric.key,
        mode: "pre_post",
        rows: metricRows,
        dimensionKey: selectedDimensionKey,
        activeRange: { start: "", end: "" },
        preRange: {
          start: compareRanges.preStart || "",
          end: compareRanges.preEnd || "",
        },
        postRange: {
          start: compareRanges.postStart || "",
          end: compareRanges.postEnd || "",
        },
        operator: OPERATOR_OPTIONS.includes(operatorOverride)
          ? operatorOverride
          : DEFAULT_OPERATOR,
        thresholdContext: null,
      });

      setAnalysisRows((prev) => {
        const nextRows = [
          ...prev.filter((row) => row.metricKey !== metricKey),
          analyzedRow,
        ];
        return nextRows.sort((a, b) => {
          if (b.failCount !== a.failCount) return b.failCount - a.failCount;
          return Math.abs(b.averageDelta) - Math.abs(a.averageDelta);
        });
      });
    } catch (error) {
      setLoadError(error?.message || "Failed to update KPI comparison.");
    } finally {
      setUpdatingMetricKeys((prev) => {
        const next = { ...prev };
        delete next[metricKey];
        return next;
      });
    }
  };

  const handleOperatorChange = (metricKey, nextOperator) => {
    setOperatorsByMetric((prev) => ({
      ...prev,
      [metricKey]: nextOperator,
    }));

    if (filterMode !== "pre_post") return;
    recomputeSingleMetric(metricKey, nextOperator);
  };

  const runAnalysis = async () => {
    if (!selectedFileId || !selectedDimensionKey) return;
    const filterError = validateFilterInputs(
      filterMode,
      compareRanges,
      effectiveQuickDateDays,
    );
    if (filterError) {
      setLoadError(filterError);
      setAnalysisRows([]);
      return;
    }

    const runId = ++analysisRunIdRef.current;
    setLoading(true);
    setLoadError("");

    try {
      if (!workerRef.current) {
        throw new Error("Analysis engine is still loading. Please retry in a moment.");
      }

      const isDateRangeMode = filterMode === "date_range";
      const explicitStart = compareRanges.preStart || "";
      const explicitEnd = compareRanges.preEnd || "";
      const activeDateRange = isDateRangeMode
        ? effectiveQuickDateDays
          ? resolveLastDaysRange(effectiveQuickDateDays, explicitEnd || "")
          : { start: explicitStart, end: explicitEnd }
        : { start: "", end: "" };

      const rowsByKpi = [];
      for (const metric of metrics) {
        if (analysisRunIdRef.current !== runId) return;

        const response = await fetchDynamicKpiData(selectedFileId, metric.key);
        let metricRows = Array.isArray(response?.data) ? response.data : [];

        const operator = OPERATOR_OPTIONS.includes(operatorsByMetric?.[metric.key])
          ? operatorsByMetric[metric.key]
          : DEFAULT_OPERATOR;
        const thresholdContext = isDateRangeMode
          ? resolveThresholdForMetric(metric)
          : null;

        const analyzedRow = await analyzeMetricWithWorker({
          metricKey: metric.key,
          metricLabel: metric.label || metric.key,
          mode: isDateRangeMode ? "threshold" : "pre_post",
          rows: metricRows,
          dimensionKey: selectedDimensionKey,
          activeRange: activeDateRange,
          preRange: {
            start: compareRanges.preStart || "",
            end: compareRanges.preEnd || "",
          },
          postRange: {
            start: compareRanges.postStart || "",
            end: compareRanges.postEnd || "",
          },
          operator,
          thresholdContext,
        });

        metricRows = null;
        rowsByKpi.push(analyzedRow);
      }

      if (analysisRunIdRef.current !== runId) return;
      const rows = rowsByKpi.sort((a, b) => {
          if (b.failCount !== a.failCount) return b.failCount - a.failCount;
          return Math.abs(b.averageDelta) - Math.abs(a.averageDelta);
        });

      setAnalysisRows(rows);
    } catch (error) {
      if (analysisRunIdRef.current !== runId) return;
      setLoadError(error?.message || "Failed to run worst cell analysis.");
      setAnalysisRows([]);
    } finally {
      if (analysisRunIdRef.current === runId) {
        setLoading(false);
      }
    }
  };

  useEffect(() => {
    if (!selectedFileId || !selectedDimensionKey || !metrics.length) return;
    const filterError = validateFilterInputs(
      filterMode,
      compareRanges,
      effectiveQuickDateDays,
    );
    if (filterError) {
      setAnalysisRows([]);
      setLoadError("");
      return;
    }
    runAnalysis();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    selectedFileId,
    selectedDimensionKey,
    compareRanges,
    metrics.length,
    filterMode,
    quickDateDays,
    effectiveQuickDateDays,
    customQuickDays,
    thresholds,
    thresholdOperators,
  ]);

  const totalFailedDimensions = useMemo(
    () => analysisRows.reduce((sum, row) => sum + row.failCount, 0),
    [analysisRows],
  );
  const totalComparedDimensions = useMemo(
    () => analysisRows.reduce((sum, row) => sum + row.comparedDimensions, 0),
    [analysisRows],
  );
  const isFilterReady = useMemo(
    () => !validateFilterInputs(filterMode, compareRanges, effectiveQuickDateDays),
    [filterMode, compareRanges, effectiveQuickDateDays],
  );
  const showQuickDateSelector = useMemo(
    () => filterMode === "date_range",
    [filterMode],
  );
  const quickRangePreviewText = useMemo(() => {
    if (filterMode !== "date_range" || !effectiveQuickDateDays) return "";
    const preview = resolveLastDaysRange(
      effectiveQuickDateDays,
      compareRanges.preEnd || "",
    );
    if (!preview.end) return "";
    return preview.end;
  }, [filterMode, effectiveQuickDateDays, compareRanges.preEnd]);

  const downloadOptions = useMemo(() => {
    const dynamicOptions = analysisRows.map((row) => ({
      value: row.metricKey,
      label: row.metricLabel,
    }));
    return [{ value: "__all__", label: "All KPIs" }, ...dynamicOptions];
  }, [analysisRows]);

  const triggerExcelDownload = (rows, fileName, metadata = {}) => {
    const headers = [
      "short name",
      "cell id",
      "site id",
      "Tech",
      "sector",
      "avg pre",
      "avg post",
      "remark",
      "category",
    ];

    const metadataRows = [
      ["KPI File Name", metadata.kpiFileName || "N/A"],
      ["Cell Dimension", metadata.cellDimension || "N/A"],
      ["Pre Range", metadata.preRange || "N/A"],
      ["Post Range", metadata.postRange || "N/A"],
      ["KPI Selection", metadata.kpiSelection || "N/A"],
    ]
      .map(
        ([label, value]) =>
          `<tr><td style="font-weight:700;">${escapeHtml(label)}</td><td colspan="${headers.length - 1}">${escapeHtml(value)}</td></tr>`,
      )
      .join("");

    const headerCells = headers
      .map((header) => `<th style="font-weight:700;">${escapeHtml(header)}</th>`)
      .join("");
    const bodyRows = rows
      .map(
        (row) =>
          `<tr>${headers
            .map((header) => `<td>${escapeHtml(row[header] || "")}</td>`)
            .join("")}</tr>`,
      )
      .join("");

    const html = `<!DOCTYPE html>
<html>
<head><meta charset="utf-8" /></head>
<body>
  <table border="1">
    <tbody>
      <tr><th colspan="${headers.length}" style="font-weight:700;">Worst Cell Export</th></tr>
      ${metadataRows}
      <tr><td colspan="${headers.length}"></td></tr>
      <tr>${headerCells}</tr>
      ${bodyRows}
    </tbody>
  </table>
</body>
</html>`;

    const blob = new Blob(["\uFEFF", html], {
      type: "application/vnd.ms-excel;charset=utf-8;",
    });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `${fileName}.xls`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  };

  const handleDownloadExcel = (selectedMetric) => {
    if (!selectedMetric || !analysisRows.length) return;

    const isAllMetrics = selectedMetric === "__all__";
    const sourceRows = isAllMetrics
      ? analysisRows
      : analysisRows.filter((row) => row.metricKey === selectedMetric);

    const exportRows = [];
    sourceRows.forEach((row) => {
      const category = resolveKpiCategory(row.metricLabel, row.metricKey);
      row.failedDimensions.forEach((dimension) => {
        const meta = dimension.meta || {};
        const isThresholdMode = row.mode === "threshold";
        exportRows.push({
          "short name": meta.shortName || dimension.value || "",
          "cell id": meta.cellId || dimension.value || "",
          "site id": meta.siteId || "",
          Tech: meta.tech || "",
          sector: meta.sector || "",
          "avg pre": formatMetricNumber(
            isThresholdMode ? dimension.avgValue : dimension.preValue,
          ),
          "avg post": formatMetricNumber(
            isThresholdMode ? dimension.thresholdValue : dimension.postValue,
          ),
          remark: isThresholdMode
            ? `${row.metricLabel} failed threshold (${row.thresholdOperator} ${formatMetricNumber(
                row.thresholdValue,
              )})`
            : `${row.metricLabel} KPI failed (Post ${row.operator} Pre)`,
          category,
        });
      });
    });

    if (!exportRows.length) {
      console.warn("No failed rows available for the selected KPI download option.");
      return;
    }

    const selectedOption = downloadOptions.find((option) => option.value === selectedMetric);
    const safePart = (selectedOption?.label || "kpi")
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "_")
      .replace(/^_+|_+$/g, "");
    const timestamp = new Date().toISOString().slice(0, 19).replace(/[:T]/g, "-");
    const fileName = `worst_cell_${safePart || "kpi"}_${timestamp}`;
    const activeRangeFromRows = sourceRows.find((row) => row?.activeRange)?.activeRange;
    const quickRangeLabel = QUICK_DAY_OPTIONS.find(
      (option) => option.value === quickDateDays,
    )?.label;
    const customRangeLabel =
      quickDateDays === "custom" && effectiveQuickDateDays
        ? `Last ${effectiveQuickDateDays} days (Custom)`
        : "";
    const dateRangeText =
      activeRangeFromRows?.start && activeRangeFromRows?.end
        ? `${activeRangeFromRows.start} to ${activeRangeFromRows.end}`
        : formatRangeText(compareRanges.preStart, compareRanges.preEnd);
    const postRangeText =
      filterMode === "date_range"
        ? customRangeLabel || quickRangeLabel
          ? `${customRangeLabel || quickRangeLabel} (${dateRangeText})`
          : dateRangeText
        : formatRangeText(compareRanges.postStart, compareRanges.postEnd);
    const selectedDimension =
      dimensionOptions.find((option) => option.value === selectedDimensionKey)?.label ||
      selectedDimensionKey ||
      "N/A";

    triggerExcelDownload(exportRows, fileName, {
      kpiFileName: selectedFile?.fileName || `#${selectedFile?.id || selectedFileId || "N/A"}`,
      cellDimension: selectedDimension,
      preRange: dateRangeText,
      postRange: postRangeText,
      kpiSelection: selectedOption?.label || "N/A",
    });
  };

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 via-rose-50 to-amber-50 p-6">
      <div className="max-w-[1600px] mx-auto space-y-6">
        <div className="bg-white rounded-2xl border border-rose-100 shadow-lg p-5">
          <div className="flex items-center gap-3 mb-4">
            <AlertTriangle className="w-6 h-6 text-rose-600" />
            <div>
              <h1 className="text-xl font-bold text-gray-800">Worst Cell Analysis</h1>
              <p className="text-sm text-gray-500">
                File-wise KPI failure matrix using page-level pre/post operators.
              </p>
            </div>
          </div>

          <div className="flex items-center justify-end mb-4">
            <div className="inline-flex rounded-lg border border-gray-200 p-1 bg-gray-50">
              <button
                type="button"
                onClick={() => setFilterMode("date_range")}
                className={`px-3 py-1.5 text-xs font-semibold rounded-md transition-colors ${
                  filterMode === "date_range"
                    ? "bg-rose-500 text-white"
                    : "text-gray-700 hover:bg-white"
                }`}
              >
                Date Range
              </button>
              <button
                type="button"
                onClick={() => setFilterMode("pre_post")}
                className={`px-3 py-1.5 text-xs font-semibold rounded-md transition-colors ${
                  filterMode === "pre_post"
                    ? "bg-amber-500 text-white"
                    : "text-gray-700 hover:bg-white"
                }`}
              >
                Pre/Post
              </button>
            </div>
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-4 gap-4">
            <div>
              <label className="block text-sm text-gray-600 font-semibold mb-2">
                KPI File
              </label>
              <select
                value={selectedFileId}
                onChange={(e) => setSelectedFileId(e.target.value)}
                className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm bg-white"
              >
                <option value="">Select file</option>
                {fileOptions.map((file) => (
                  <option key={file.id} value={file.id}>
                    #{file.id} - {file.fileName || "Unnamed file"}
                  </option>
                ))}
              </select>
            </div>

            <div>
              <label className="block text-sm text-gray-600 font-semibold mb-2">
                Cell Dimension
              </label>
              <select
                value={selectedDimensionKey}
                onChange={(e) => setSelectedDimensionKey(e.target.value)}
                className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm bg-white"
              >
                {dimensionOptions.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </div>

            <div>
              <label className="block text-sm text-gray-600 font-semibold mb-2">
                {filterMode === "date_range" ? "Start Date" : "Pre Start"}
              </label>
              <input
                type="date"
                value={compareRanges.preStart}
                onChange={(e) =>
                  setCompareRanges((prev) => ({ ...prev, preStart: e.target.value }))
                }
                className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm bg-white"
              />
            </div>

            <div>
              <label className="block text-sm text-gray-600 font-semibold mb-2">
                {filterMode === "date_range" ? "End Date" : "Pre End"}
              </label>
              <input
                type="date"
                value={compareRanges.preEnd}
                onChange={(e) =>
                  setCompareRanges((prev) => ({ ...prev, preEnd: e.target.value }))
                }
                className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm bg-white"
              />
            </div>

            {filterMode === "pre_post" && (
              <div>
                <label className="block text-sm text-gray-600 font-semibold mb-2">
                  Post Start
                </label>
                <input
                  type="date"
                  value={compareRanges.postStart}
                  onChange={(e) =>
                    setCompareRanges((prev) => ({ ...prev, postStart: e.target.value }))
                  }
                  className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm bg-white"
                />
              </div>
            )}
          </div>

          {filterMode === "pre_post" && (
            <div className="grid grid-cols-1 lg:grid-cols-4 gap-4 mt-4">
              <div>
                <label className="block text-sm text-gray-600 font-semibold mb-2">
                  Post End
                </label>
                <input
                  type="date"
                  value={compareRanges.postEnd}
                  onChange={(e) =>
                    setCompareRanges((prev) => ({ ...prev, postEnd: e.target.value }))
                  }
                  className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm bg-white"
                />
              </div>

              <div className="lg:col-span-3 flex items-end">
                <button
                  onClick={runAnalysis}
                  disabled={loading || !selectedFileId || !selectedDimensionKey || !isFilterReady}
                  className={`px-4 py-2 rounded-lg text-sm font-semibold text-white ${
                    loading || !selectedFileId || !selectedDimensionKey || !isFilterReady
                      ? "bg-gray-300 cursor-not-allowed"
                      : "bg-gradient-to-r from-rose-500 to-amber-500 hover:from-rose-600 hover:to-amber-600"
                  }`}
                >
                  {loading ? "Analyzing..." : "Refresh Worst Cell Analysis"}
                </button>
              </div>
            </div>
          )}

          {filterMode === "date_range" && (
            <div className="grid grid-cols-1 lg:grid-cols-4 gap-4 mt-4">
              <div className="lg:col-span-4 flex items-end">
                <button
                  onClick={runAnalysis}
                  disabled={loading || !selectedFileId || !selectedDimensionKey || !isFilterReady}
                  className={`px-4 py-2 rounded-lg text-sm font-semibold text-white ${
                    loading || !selectedFileId || !selectedDimensionKey || !isFilterReady
                      ? "bg-gray-300 cursor-not-allowed"
                      : "bg-gradient-to-r from-rose-500 to-amber-500 hover:from-rose-600 hover:to-amber-600"
                  }`}
                >
                  {loading ? "Analyzing..." : "Refresh Worst Cell Analysis"}
                </button>
              </div>
            </div>
          )}
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div className="bg-white rounded-xl border border-gray-100 p-4 shadow-sm">
            <p className="text-xs uppercase tracking-wide text-gray-500">Selected File</p>
            <p className="text-base font-bold text-gray-800 mt-1">
              {selectedFile ? `${selectedFile.fileName || "Unnamed"} (#${selectedFile.id})` : "N/A"}
            </p>
          </div>
          <div className="bg-white rounded-xl border border-gray-100 p-4 shadow-sm">
            <p className="text-xs uppercase tracking-wide text-gray-500">Failing KPIs</p>
            <p className="text-2xl font-bold text-rose-600 mt-1">
              {analysisRows.filter((row) => row.failCount > 0).length}
            </p>
          </div>
          <div className="bg-white rounded-xl border border-gray-100 p-4 shadow-sm">
            <p className="text-xs uppercase tracking-wide text-gray-500">Failed Dimensions</p>
            <p className="text-2xl font-bold text-amber-600 mt-1">
              {totalFailedDimensions} / {totalComparedDimensions}
            </p>
          </div>
        </div>

        <div className="bg-white rounded-2xl border border-gray-100 shadow-sm overflow-hidden">
          <div className="px-5 py-4 border-b border-gray-100 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
            <div className="flex items-center gap-2">
              <FileText className="w-4 h-4 text-gray-500" />
              <p className="text-sm font-semibold text-gray-700">
                {filterMode === "date_range"
                  ? `KPI Threshold Fails by ${selectedDimensionKey || "Dimension"}`
                  : `KPI vs Failed ${selectedDimensionKey || "Dimension"} Values`}
              </p>
            </div>

            <div className="flex flex-row items-end gap-1">
              <div>
                {showQuickDateSelector && quickRangePreviewText && (
                <p className="text-[15px] font-medium text-gray-500">
                  Reference date: {quickRangePreviewText}
                </p>
              )}
              </div>
              <div className="flex items-center gap-2">
                {showQuickDateSelector && (
                  <>
                    <select
                      value={quickDateDays}
                      onChange={(e) => {
                        const nextValue = e.target.value;
                        setQuickDateDays(nextValue);
                        if (nextValue !== "custom") {
                          setCustomQuickDays("");
                        }
                      }}
                      className="border border-gray-200 rounded-md px-3 py-2 bg-white text-xs font-semibold text-gray-700"
                    >
                      <option value="">Quick Range</option>
                      {QUICK_DAY_OPTIONS.map((option) => (
                        <option key={`quick-${option.value}`} value={option.value}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                    {quickDateDays === "custom" && (
                      <input
                        type="number"
                        min="1"
                        step="1"
                        value={customQuickDays}
                        onChange={(e) => setCustomQuickDays(e.target.value)}
                        placeholder="Days"
                        className="w-24 border border-gray-200 rounded-md px-3 py-2 bg-white text-xs font-semibold text-gray-700"
                      />
                    )}
                  </>
                )}
                <Download className="w-4 h-4 text-gray-500" />
                <select
                  value={downloadSelection}
                  onChange={(e) => {
                    const nextValue = e.target.value;
                    setDownloadSelection(nextValue);
                    if (nextValue) {
                      handleDownloadExcel(nextValue);
                      setDownloadSelection("");
                    }
                  }}
                  disabled={loading || analysisRows.length === 0}
                  className="border border-gray-200 rounded-md px-3 py-2 bg-white text-xs font-semibold text-gray-700 disabled:bg-gray-100 disabled:text-gray-400"
                >
                  <option value="">Download Excel</option>
                  {downloadOptions.map((option) => (
                    <option key={`download-${option.value}`} value={option.value}>
                      {option.value === "__all__" ? "Download All" : `Download ${option.label}`}
                    </option>
                  ))}
                </select>
              </div>
              
            </div>
          </div>

          {loadError ? (
            <div className="p-5 text-sm text-rose-700 bg-rose-50 border-t border-rose-100">
              {loadError}
            </div>
          ) : loading ? (
            <div className="p-8 flex items-center justify-center gap-3 text-gray-500">
              <Loader2 className="w-5 h-5 animate-spin" />
              <span>Computing worst cells...</span>
            </div>
          ) : analysisRows.length === 0 ? (
            <div className="p-8 text-center text-sm text-gray-500">
              {filterMode === "date_range"
                ? "No failing cells found for the selected date range and threshold rules."
                : "No failing cells found for current file, date ranges, and KPI rules."}
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="min-w-full text-sm">
                <thead className="bg-gray-50">
                  <tr>
                    <th className="px-4 py-3 text-left font-semibold text-gray-700">
                      KPI
                    </th>
                    <th className="px-4 py-3 text-left font-semibold text-gray-700">
                      {filterMode === "date_range" ? "Threshold" : "Operator"}
                    </th>
                    <th className="px-4 py-3 text-left font-semibold text-gray-700">
                      Failed {selectedDimensionKey || "Dimension"}
                    </th>
                    <th className="px-4 py-3 text-left font-semibold text-gray-700">
                      Fails
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {analysisRows.map((row) => (
                    <tr key={row.metricKey} className="border-t border-gray-100 align-top">
                      <td className="px-4 py-3 font-semibold text-gray-800">
                        {row.metricLabel}
                      </td>
                      <td className="px-4 py-3">
                        {filterMode === "date_range" ? (
                          (() => {
                            const hasThresholdKey = Boolean(row.thresholdKey);
                            const isRowSaving =
                              hasThresholdKey &&
                              updatingThresholdKey === row.thresholdKey;

                            return (
                              <div className="flex items-center gap-2">
                                <span className="inline-flex px-2 py-1 rounded-md bg-amber-50 border border-amber-200 text-amber-700 text-xs font-semibold">
                                  {hasThresholdKey ? row.thresholdOperator : "--"}
                                </span>
                                <input
                                  type="number"
                                  step="any"
                                  value={
                                    hasThresholdKey
                                      ? thresholdDrafts?.[row.thresholdKey] ??
                                        (typeof row.thresholdValue === "number"
                                          ? String(row.thresholdValue)
                                          : "")
                                      : ""
                                  }
                                  onChange={(e) =>
                                    handleThresholdDraftChange(
                                      row.thresholdKey,
                                      e.target.value,
                                    )
                                  }
                                  disabled={!hasThresholdKey || isRowSaving}
                                  placeholder={hasThresholdKey ? "Threshold" : "No threshold"}
                                  className="w-24 border border-gray-200 rounded-md px-2 py-1 bg-white text-xs font-semibold text-gray-700 disabled:bg-gray-100 disabled:text-gray-400"
                                />
                                <button
                                  type="button"
                                  onClick={() => updateThresholdFromWorstCell(row)}
                                  disabled={!hasThresholdKey || Boolean(updatingThresholdKey)}
                                  className={`px-2 py-1 rounded-md text-xs font-semibold ${
                                    !hasThresholdKey || Boolean(updatingThresholdKey)
                                      ? "bg-gray-200 text-gray-500 cursor-not-allowed"
                                      : "bg-amber-500 text-white hover:bg-amber-600"
                                  }`}
                                >
                                  {isRowSaving
                                    ? "Saving..."
                                    : hasThresholdKey
                                      ? "Update"
                                      : "No threshold"}
                                </button>
                              </div>
                            );
                          })()
                        ) : (
                          <div className="inline-flex items-center gap-2">
                            <select
                              value={row.operator}
                              onChange={(e) =>
                                handleOperatorChange(row.metricKey, e.target.value)
                              }
                              disabled={Boolean(updatingMetricKeys[row.metricKey])}
                              className="border border-gray-200 rounded-md px-2 py-1 bg-white text-xs font-semibold disabled:bg-gray-100 disabled:text-gray-400"
                            >
                              {OPERATOR_OPTIONS.map((option) => (
                                <option key={`${row.metricKey}-${option}`} value={option}>
                                  Post {option} Pre
                                </option>
                              ))}
                            </select>
                            {updatingMetricKeys[row.metricKey] && (
                              <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-gray-500">
                                <Loader2 className="w-3 h-3 animate-spin" />
                                Updating...
                              </span>
                            )}
                          </div>
                        )}
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex flex-wrap gap-2 max-w-[720px]">
                          {row.failedDimensions.length === 0 && (
                            <span className="text-xs text-emerald-700 font-semibold">
                              {row.comparedDimensions === 0
                                ? filterMode === "date_range"
                                  ? "No data in selected range"
                                  : "No comparable pre/post data"
                                : `No failed ${selectedDimensionKey || "dimension"} values`}
                            </span>
                          )}
                          {row.failedDimensions.map((dimension) => (
                            <span
                              key={`${row.metricKey}-${dimension.value}`}
                              className="inline-flex items-center gap-2 px-2 py-1 rounded-md bg-rose-50 border border-rose-200 text-rose-700 text-xs font-semibold"
                            >
                              {dimension.value}
                              <span className="text-gray-500 font-medium">
                                {filterMode === "date_range"
                                  ? `(avg ${dimension.avgValue.toFixed(2)} vs threshold ${dimension.thresholdOperator} ${dimension.thresholdValue.toFixed(2)})`
                                  : `(${dimension.postValue.toFixed(2)} ${row.operator} ${dimension.preValue.toFixed(2)} = false)`}
                              </span>
                            </span>
                          ))}
                        </div>
                      </td>
                      <td className="px-4 py-3">
                        <span
                          className={`inline-flex px-2 py-1 rounded-full font-bold text-xs ${
                            row.failCount > 0
                              ? "bg-rose-100 text-rose-700"
                              : "bg-emerald-100 text-emerald-700"
                          }`}
                        >
                          {row.failCount} / {row.comparedDimensions}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-4 text-xs text-gray-500 flex items-center gap-2">
          <Calendar className="w-4 h-4" />
          {filterMode === "date_range" ? (
            <span>
              Date Range mode compares cell averages against dashboard thresholds.
            </span>
          ) : (
            <span>
              Operators on this page are independent from KPI View. Default rule is{" "}
              <span className="font-semibold text-gray-700">Post &gt;= Pre</span>.
            </span>
          )}
        </div>
      </div>
    </div>
  );
}

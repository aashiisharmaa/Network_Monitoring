import React, {
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import {
  fetchKpiUploadHistory,
  fetchDynamicKpiMetrics,
  fetchDynamicKpiData,
  fetchDynamicKpiBatchData,
  fetchDynamicBadDaysSummary,
  fetchDynamicKpiColumns,
} from "../kpi/kpiService";
import {
  getThresholdSetting,
  postThresholdSetting,
} from "../dashboard/services/dashboardService";
import {
  AlertTriangle,
  Loader2,
  Calendar,
  FileText,
  Download,
} from "lucide-react";
import UserContext from "../../context/fileContext";

const OPERATOR_OPTIONS = [">", ">=", "<", "<="];
const DEFAULT_OPERATOR = ">=";
const THRESHOLD_STORAGE_PREFIX = "dashboardThresholdProfile";
const THRESHOLD_OPERATOR_STORAGE_PREFIX = "dashboardThresholdOperators";
const QUICK_DAY_OPTIONS = [
  { value: "7", label: "Last 7 days" },
  { value: "10", label: "Last 10 days" },
  { value: "15", label: "Last 15 days" },
  { value: "20", label: "Last 20 days" },
  { value: "custom", label: "Custom days" },
];
const RESULT_FORMAT_OPTIONS = {
  KPI_FIRST: "kpi_first",
  CELL_BAD_DAYS: "cell_bad_days",
};
const CELL_BAD_DAYS_VIEW_OPTIONS = {
  TABLE: "table",
  VISUAL: "visual",
};
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
const WORST_CELL_PAGE_STATE_KEY = "worstCellPageState:v1";
const createWorstCellRuntimeCache = () => ({
  analysisRows: [],
  cellBadDayRows: [],
  fileOptions: [],
  fileContextByFileId: new Map(),
  thresholdConfigByFileId: new Map(),
  metricBatchByFileId: new Map(),
  pendingFileOptionsPromise: null,
  pendingFileContextByFileId: new Map(),
  pendingThresholdConfigByFileId: new Map(),
  pendingMetricBatchByFileId: new Map(),
  updatedAt: 0,
});
let worstCellRuntimeCache = createWorstCellRuntimeCache();

const getWorstCellRuntimeCache = () => {
  if (!worstCellRuntimeCache || typeof worstCellRuntimeCache !== "object") {
    worstCellRuntimeCache = createWorstCellRuntimeCache();
    return worstCellRuntimeCache;
  }

  if (!(worstCellRuntimeCache.fileContextByFileId instanceof Map)) {
    worstCellRuntimeCache.fileContextByFileId = new Map();
  }
  if (!(worstCellRuntimeCache.thresholdConfigByFileId instanceof Map)) {
    worstCellRuntimeCache.thresholdConfigByFileId = new Map();
  }
  if (!(worstCellRuntimeCache.metricBatchByFileId instanceof Map)) {
    worstCellRuntimeCache.metricBatchByFileId = new Map();
  }
  if (!(worstCellRuntimeCache.pendingFileContextByFileId instanceof Map)) {
    worstCellRuntimeCache.pendingFileContextByFileId = new Map();
  }
  if (!(worstCellRuntimeCache.pendingThresholdConfigByFileId instanceof Map)) {
    worstCellRuntimeCache.pendingThresholdConfigByFileId = new Map();
  }
  if (!(worstCellRuntimeCache.pendingMetricBatchByFileId instanceof Map)) {
    worstCellRuntimeCache.pendingMetricBatchByFileId = new Map();
  }
  if (!Array.isArray(worstCellRuntimeCache.fileOptions)) {
    worstCellRuntimeCache.fileOptions = [];
  }
  if (!Array.isArray(worstCellRuntimeCache.analysisRows)) {
    worstCellRuntimeCache.analysisRows = [];
  }
  if (!Array.isArray(worstCellRuntimeCache.cellBadDayRows)) {
    worstCellRuntimeCache.cellBadDayRows = [];
  }

  return worstCellRuntimeCache;
};

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

  if (
    !ranges.preStart ||
    !ranges.preEnd ||
    !ranges.postStart ||
    !ranges.postEnd
  ) {
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

const toDatePartLocal = (value) => {
  if (!value) return "";
  const text = String(value).trim();
  if (/^\d{4}-\d{2}-\d{2}$/.test(text)) return text;
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return "";
  const year = parsed.getFullYear();
  const month = String(parsed.getMonth() + 1).padStart(2, "0");
  const day = String(parsed.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
};

const readValueByKey = (row, key) => {
  if (!row || !key) return undefined;
  if (Object.prototype.hasOwnProperty.call(row, key)) return row[key];

  const target = normalizeKey(key);
  const match = Object.entries(row).find(
    ([candidateKey]) => normalizeKey(candidateKey) === target,
  );
  return match ? match[1] : undefined;
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

const mixHex = (hexA, hexB, t) => {
  const clamped = Math.max(0, Math.min(1, t));
  const a = hexA.replace("#", "");
  const b = hexB.replace("#", "");
  const ar = parseInt(a.slice(0, 2), 16);
  const ag = parseInt(a.slice(2, 4), 16);
  const ab = parseInt(a.slice(4, 6), 16);
  const br = parseInt(b.slice(0, 2), 16);
  const bg = parseInt(b.slice(2, 4), 16);
  const bb = parseInt(b.slice(4, 6), 16);
  const r = Math.round(ar + (br - ar) * clamped)
    .toString(16)
    .padStart(2, "0");
  const g = Math.round(ag + (bg - ag) * clamped)
    .toString(16)
    .padStart(2, "0");
  const bl = Math.round(ab + (bb - ab) * clamped)
    .toString(16)
    .padStart(2, "0");
  return `#${r}${g}${bl}`;
};

const getAverageHeatColor = (value, minValue, maxValue, isMissing) => {
  if (isMissing) return "#ffffff";
  if (!Number.isFinite(value)) return "#ffffff";
  if (
    !Number.isFinite(minValue) ||
    !Number.isFinite(maxValue) ||
    maxValue <= minValue
  ) {
    return "#fee2e2";
  }
  const t = (value - minValue) / (maxValue - minValue);
  return mixHex("#fff7ed", "#dc2626", t);
};

const getDeviationBadnessPct = (
  averageValue,
  thresholdValue,
  thresholdOperator,
) => {
  const avg = Number(averageValue);
  const thr = Number(thresholdValue);
  if (!Number.isFinite(avg) || !Number.isFinite(thr)) return null;
  const base = Math.abs(thr) > 1e-9 ? Math.abs(thr) : 1;
  const rawPct = ((avg - thr) / base) * 100;
  if (thresholdOperator === "<" || thresholdOperator === "<=") {
    return -rawPct;
  }
  return rawPct;
};

const getDeviationHeatColor = (badnessPct, maxAbsBadness, isMissing) => {
  if (isMissing) return "#ffffff";
  if (!Number.isFinite(badnessPct)) return "#ffffff";
  if (!Number.isFinite(maxAbsBadness) || maxAbsBadness <= 0) {
    return "#fef3c7";
  }
  const normalized = Math.max(-1, Math.min(1, badnessPct / maxAbsBadness));
  if (normalized >= 0) {
    return mixHex("#fef3c7", "#dc2626", normalized);
  }
  return mixHex("#16a34a", "#fef3c7", normalized + 1);
};

const evaluateComparison = (leftValue, rightValue, operator) => {
  const left = Number(leftValue);
  const right = Number(rightValue);
  if (!Number.isFinite(left) || !Number.isFinite(right)) return null;

  switch (operator) {
    case ">":
      return left > right;
    case ">=":
      return left >= right;
    case "<":
      return left < right;
    case "<=":
      return left <= right;
    default:
      return left >= right;
  }
};

const getDirectionalAverageHeatColor = (
  averageValue,
  comparisonValue,
  thresholdOperator,
  maxAbsDeltaPct,
  isMissing,
) => {
  if (isMissing) return "#ffffff";
  const avg = Number(averageValue);
  const cmp = Number(comparisonValue);
  if (!Number.isFinite(avg) || !Number.isFinite(cmp)) return "#ffffff";
  const base = Math.abs(cmp) > 1e-9 ? Math.abs(cmp) : 1;
  const deltaPct = ((avg - cmp) / base) * 100;
  const isGood = evaluateComparison(avg, cmp, thresholdOperator);
  const normalized = Number.isFinite(maxAbsDeltaPct) && maxAbsDeltaPct > 0
    ? Math.min(1, Math.abs(deltaPct) / maxAbsDeltaPct)
    : 0.25;
  if (isGood === true) {
    return mixHex("#ecfdf5", "#16a34a", normalized);
  }
  if (isGood === false) {
    return mixHex("#fef3c7", "#dc2626", normalized);
  }
  return "#f9fafb";
};

export default function WorstCell() {
  const hasHydratedRef = useRef(false);
  const { selectedFileId, setSelectedFileId } = useContext(UserContext);
  const [fileOptions, setFileOptions] = useState([]);
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
  const [cellBadDayRows, setCellBadDayRows] = useState([]);
  const [loadError, setLoadError] = useState("");
  const [operatorsByMetric, setOperatorsByMetric] = useState({});
  const [downloadSelection, setDownloadSelection] = useState("");
  const [filterMode, setFilterMode] = useState("date_range");
  const [resultFormat, setResultFormat] = useState(
    RESULT_FORMAT_OPTIONS.KPI_FIRST,
  );
  const [quickDateDays, setQuickDateDays] = useState("");
  const [customQuickDays, setCustomQuickDays] = useState("");
  const [minimumBadDaysInput, setMinimumBadDaysInput] = useState("3");
  const [cellBadDaysViewMode, setCellBadDaysViewMode] = useState(
    CELL_BAD_DAYS_VIEW_OPTIONS.TABLE,
  );
  const [cellHeatModeByCell, setCellHeatModeByCell] = useState({});
  const [cellHeatValueModeByCell, setCellHeatValueModeByCell] = useState({});
  const [cellComparisonBaseByCell, setCellComparisonBaseByCell] = useState({});
  const [cellHeatmapSearch, setCellHeatmapSearch] = useState("");
  const [exportingHeatmapPdfKey, setExportingHeatmapPdfKey] = useState("");
  const [expandedBaselineByCell, setExpandedBaselineByCell] = useState({});
  const [expandedBadDayRows, setExpandedBadDayRows] = useState({});
  const [expandedDayKpiLists, setExpandedDayKpiLists] = useState({});
  const [thresholds, setThresholds] = useState({});
  const [thresholdOperators, setThresholdOperators] = useState({});
  const [thresholdDrafts, setThresholdDrafts] = useState({});
  const [updatingThresholdKey, setUpdatingThresholdKey] = useState("");
  const [updatingMetricKeys, setUpdatingMetricKeys] = useState({});
  const [lastRunContext, setLastRunContext] = useState(null);
  const sharedSelectedFileId = String(selectedFileId || "");
  const workerRef = useRef(null);
  const workerRequestIdRef = useRef(0);
  const workerPendingRef = useRef(new Map());
  const metricRowsCacheRef = useRef(new Map());
  const metricRowsRequestCacheRef = useRef(new Map());
  const metricRowsBatchRequestRef = useRef(new Map());
  const analysisRunIdRef = useRef(0);
  const badDaysDebugLog = useCallback((label, payload) => {
    console.log(`[WorstCell BadDays Debug] ${label}`, payload);
  }, []);

  useEffect(() => {
    if (hasHydratedRef.current) return;
    hasHydratedRef.current = true;
    if (typeof window === "undefined") return;
    try {
      const raw = window.localStorage.getItem(WORST_CELL_PAGE_STATE_KEY);
      if (!raw) return;
      const saved = JSON.parse(raw);
      if (!saved || typeof saved !== "object") return;

      setSelectedDimensionKey(saved.selectedDimensionKey || "");
      setCompareRanges(
        saved.compareRanges || {
          preStart: "",
          preEnd: "",
          postStart: "",
          postEnd: "",
        },
      );
      setOperatorsByMetric(saved.operatorsByMetric || {});
      setFilterMode(saved.filterMode === "pre_post" ? "pre_post" : "date_range");
      setResultFormat(
        saved.resultFormat === RESULT_FORMAT_OPTIONS.CELL_BAD_DAYS
          ? RESULT_FORMAT_OPTIONS.CELL_BAD_DAYS
          : RESULT_FORMAT_OPTIONS.KPI_FIRST,
      );
      setQuickDateDays(saved.quickDateDays || "");
      setCustomQuickDays(saved.customQuickDays || "");
      setMinimumBadDaysInput(saved.minimumBadDaysInput || "3");
      setCellBadDaysViewMode(
        saved.cellBadDaysViewMode === CELL_BAD_DAYS_VIEW_OPTIONS.VISUAL
          ? CELL_BAD_DAYS_VIEW_OPTIONS.VISUAL
          : CELL_BAD_DAYS_VIEW_OPTIONS.TABLE,
      );
      setCellHeatModeByCell(saved.cellHeatModeByCell || {});
      setCellHeatValueModeByCell(saved.cellHeatValueModeByCell || {});
      setCellComparisonBaseByCell(saved.cellComparisonBaseByCell || {});
      setCellHeatmapSearch(saved.cellHeatmapSearch || "");
      setExpandedBaselineByCell(saved.expandedBaselineByCell || {});
      setExpandedBadDayRows(saved.expandedBadDayRows || {});
      setExpandedDayKpiLists(saved.expandedDayKpiLists || {});
      setDownloadSelection(saved.downloadSelection || "");
      setLastRunContext(saved.lastRunContext || null);

      // Restore heavy fetched data from in-memory runtime cache (survives route switches).
      const runtimeCache = getWorstCellRuntimeCache();
      if (runtimeCache && typeof runtimeCache === "object") {
        setAnalysisRows(
          Array.isArray(runtimeCache.analysisRows)
            ? runtimeCache.analysisRows
            : [],
        );
        setCellBadDayRows(
          Array.isArray(runtimeCache.cellBadDayRows)
            ? runtimeCache.cellBadDayRows
            : [],
        );
        setFileOptions(
          Array.isArray(runtimeCache.fileOptions) ? runtimeCache.fileOptions : [],
        );
      }
    } catch {
      // ignore invalid persisted state
    }
  }, []);

  useEffect(() => {
    if (typeof window === "undefined") return;
    try {
      window.localStorage.setItem(
        WORST_CELL_PAGE_STATE_KEY,
        JSON.stringify({
          selectedDimensionKey,
          compareRanges,
          operatorsByMetric,
          downloadSelection,
          filterMode,
          resultFormat,
          quickDateDays,
          customQuickDays,
          minimumBadDaysInput,
          cellBadDaysViewMode,
          cellHeatModeByCell,
          cellHeatValueModeByCell,
          cellComparisonBaseByCell,
          cellHeatmapSearch,
          expandedBaselineByCell,
          expandedBadDayRows,
          expandedDayKpiLists,
          lastRunContext,
        }),
      );
    } catch {
      // ignore storage full / serialization failures
    }
  }, [
    selectedDimensionKey,
    compareRanges,
    operatorsByMetric,
    downloadSelection,
    filterMode,
    resultFormat,
    quickDateDays,
    customQuickDays,
    minimumBadDaysInput,
    cellBadDaysViewMode,
    cellHeatModeByCell,
    cellHeatValueModeByCell,
    cellComparisonBaseByCell,
    cellHeatmapSearch,
    expandedBaselineByCell,
    expandedBadDayRows,
    expandedDayKpiLists,
    lastRunContext,
  ]);

  useEffect(() => {
    const runtimeCache = getWorstCellRuntimeCache();
    runtimeCache.analysisRows = Array.isArray(analysisRows) ? analysisRows : [];
    runtimeCache.cellBadDayRows = Array.isArray(cellBadDayRows)
      ? cellBadDayRows
      : [];
    runtimeCache.updatedAt = Date.now();
  }, [analysisRows, cellBadDayRows]);

  useEffect(() => {
    metricRowsCacheRef.current.clear();
    metricRowsRequestCacheRef.current.clear();
    metricRowsBatchRequestRef.current.clear();
  }, [selectedFileId]);

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
      const runtimeCache = getWorstCellRuntimeCache();
      if (runtimeCache.fileOptions.length) {
        setFileOptions(runtimeCache.fileOptions);
        if (runtimeCache.fileOptions.length) {
          setSelectedFileId((prev) => prev || String(runtimeCache.fileOptions[0].id));
        }
        return;
      }

      if (!runtimeCache.pendingFileOptionsPromise) {
        runtimeCache.pendingFileOptionsPromise = fetchKpiUploadHistory()
          .then((response) => {
            const files = response?.success ? response.data || [] : [];
            runtimeCache.fileOptions = Array.isArray(files) ? files : [];
            runtimeCache.pendingFileOptionsPromise = null;
            return runtimeCache.fileOptions;
          })
          .catch((error) => {
            runtimeCache.pendingFileOptionsPromise = null;
            throw error;
          });
      }

      const files = await runtimeCache.pendingFileOptionsPromise;
      setFileOptions(files);
      if (files.length) {
        setSelectedFileId((prev) => prev || String(files[0].id));
      }
    };
    loadFiles();
  }, []);

  useEffect(() => {
    const loadFileContext = async () => {
      if (!selectedFileId) {
        if (!sharedSelectedFileId) {
          setMetrics([]);
          setColumns([]);
          setSelectedDimensionKey("");
          setOperatorsByMetric({});
          return;
        }
      }

      const fileId = String(sharedSelectedFileId || "");
      if (!fileId) return;

      const runtimeCache = getWorstCellRuntimeCache();
      let cachedContext = runtimeCache.fileContextByFileId.get(fileId);

      if (!cachedContext) {
        if (!runtimeCache.pendingFileContextByFileId.has(fileId)) {
          const requestPromise = Promise.all([
            fetchDynamicKpiMetrics(fileId),
            fetchDynamicKpiColumns(fileId),
          ])
            .then(([metricResponse, columnResponse]) => {
              const nextMetrics = metricResponse?.success
                ? metricResponse.data || []
                : [];
              const nextColumns = Array.isArray(columnResponse?.columns)
                ? columnResponse.columns
                : [];
              const nextContext = {
                metrics: nextMetrics,
                columns: nextColumns,
              };
              runtimeCache.fileContextByFileId.set(fileId, nextContext);
              runtimeCache.pendingFileContextByFileId.delete(fileId);
              return nextContext;
            })
            .catch((error) => {
              runtimeCache.pendingFileContextByFileId.delete(fileId);
              throw error;
            });

          runtimeCache.pendingFileContextByFileId.set(fileId, requestPromise);
        }

        cachedContext = await runtimeCache.pendingFileContextByFileId.get(fileId);
      }

      const nextMetrics = Array.isArray(cachedContext?.metrics)
        ? cachedContext.metrics
        : [];
      const nextColumns = Array.isArray(cachedContext?.columns)
        ? cachedContext.columns
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
        ? dimensionKeys.find(
            (key) => normalizeKey(key) === normalizeKey(preferred),
          )
        : null;
      setSelectedDimensionKey(matchedPreferredKey || dimensionKeys[0] || "");
    };

    loadFileContext();
  }, [sharedSelectedFileId]);

  useEffect(() => {
    let mounted = true;

    const loadThresholdConfig = async () => {
      const fileId = String(selectedFileId || "");
      const runtimeCache = getWorstCellRuntimeCache();
      let cachedConfig = runtimeCache.thresholdConfigByFileId.get(fileId);

      if (!cachedConfig) {
        if (!runtimeCache.pendingThresholdConfigByFileId.has(fileId)) {
          const requestPromise = (async () => {
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
                const parsedFile = fileOperatorRaw
                  ? JSON.parse(fileOperatorRaw)
                  : null;
                const parsedGlobal = globalOperatorRaw
                  ? JSON.parse(globalOperatorRaw)
                  : null;
                localOperators = {
                  ...(parsedGlobal && typeof parsedGlobal === "object"
                    ? parsedGlobal
                    : {}),
                  ...(parsedFile && typeof parsedFile === "object"
                    ? parsedFile
                    : {}),
                };
              } catch {
                localOperators = {};
              }
            }

            const nextConfig = {
              thresholds: { ...localThresholds, ...apiThresholds },
              operators: { ...localOperators, ...apiOperators },
            };
            runtimeCache.thresholdConfigByFileId.set(fileId, nextConfig);
            runtimeCache.pendingThresholdConfigByFileId.delete(fileId);
            return nextConfig;
          })().catch((error) => {
            runtimeCache.pendingThresholdConfigByFileId.delete(fileId);
            throw error;
          });

          runtimeCache.pendingThresholdConfigByFileId.set(fileId, requestPromise);
        }

        cachedConfig = await runtimeCache.pendingThresholdConfigByFileId.get(fileId);
      }

      if (!mounted) return;
      setThresholds(cachedConfig?.thresholds || {});
      setThresholdOperators(cachedConfig?.operators || {});
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
    () =>
      fileOptions.find((file) => String(file.id) === String(selectedFileId)),
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

  const effectiveMinimumBadDays = useMemo(() => {
    const parsed = Number(minimumBadDaysInput);
    if (!Number.isInteger(parsed) || parsed <= 0) return null;
    return parsed;
  }, [minimumBadDaysInput]);

  const resolveThresholdForMetric = (metric) => {
    const candidates = [
      normalizeKey(metric?.key),
      normalizeKey(metric?.label),
      normalizeKey(
        String(metric?.key || "").replace(
          /^max(imum)?rrc(connected)?user(number|s)?$/i,
          "maxrrcusers",
        ),
      ),
      normalizeKey(
        String(metric?.label || "").replace(
          /^max(imum)?\s*rrc.*$/i,
          "maxrrcusers",
        ),
      ),
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

    const fallbackThresholdKey = String(
      metric?.key || metric?.label || "",
    ).trim();
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

  const getCachedMetricRows = useCallback(
    async (fileId, metricKey) => {
      if (!fileId || !metricKey) return [];
      const cacheKey = `${String(fileId)}::${String(metricKey)}`;

      if (metricRowsCacheRef.current.has(cacheKey)) {
        return metricRowsCacheRef.current.get(cacheKey);
      }

      if (!metricRowsRequestCacheRef.current.has(cacheKey)) {
        const requestPromise = fetchDynamicKpiData(fileId, metricKey)
          .then((response) => {
            const nextRows = Array.isArray(response?.data) ? response.data : [];
            metricRowsCacheRef.current.set(cacheKey, nextRows);
            metricRowsRequestCacheRef.current.delete(cacheKey);
            return nextRows;
          })
          .catch((error) => {
            metricRowsRequestCacheRef.current.delete(cacheKey);
            throw error;
          });
        metricRowsRequestCacheRef.current.set(cacheKey, requestPromise);
      }

      return metricRowsRequestCacheRef.current.get(cacheKey);
    },
    [],
  );

  const hydrateMetricRowsCacheFromBatch = useCallback(async (fileId) => {
    if (!fileId) return new Map();
    const batchCacheKey = String(fileId);
    const runtimeCache = getWorstCellRuntimeCache();

    if (runtimeCache.metricBatchByFileId.has(batchCacheKey)) {
      const cachedBatch = runtimeCache.metricBatchByFileId.get(batchCacheKey);
      cachedBatch.forEach((rows, metricKey) => {
        metricRowsCacheRef.current.set(`${batchCacheKey}::${metricKey}`, rows);
      });
      return cachedBatch;
    }

    if (
      !metricRowsBatchRequestRef.current.has(batchCacheKey) &&
      !runtimeCache.pendingMetricBatchByFileId.has(batchCacheKey)
    ) {
      const batchRequest = fetchDynamicKpiBatchData(fileId)
        .then((response) => {
          const metricEntries = Array.isArray(response?.data)
            ? response.data
            : [];
          const hydratedMetricMap = new Map();

          metricEntries.forEach((entry) => {
            const metricKey = String(entry?.metricKey || entry?.key || "").trim();
            if (!metricKey) return;

            const rows = Array.isArray(entry?.data) ? entry.data : [];
            const metricCacheKey = `${batchCacheKey}::${metricKey}`;
            hydratedMetricMap.set(metricKey, rows);
            metricRowsCacheRef.current.set(metricCacheKey, rows);
            metricRowsRequestCacheRef.current.delete(metricCacheKey);
          });

          runtimeCache.metricBatchByFileId.set(batchCacheKey, hydratedMetricMap);
          metricRowsBatchRequestRef.current.delete(batchCacheKey);
          runtimeCache.pendingMetricBatchByFileId.delete(batchCacheKey);
          return hydratedMetricMap;
        })
        .catch((error) => {
          metricRowsBatchRequestRef.current.delete(batchCacheKey);
          runtimeCache.pendingMetricBatchByFileId.delete(batchCacheKey);
          throw error;
        });

      metricRowsBatchRequestRef.current.set(batchCacheKey, batchRequest);
      runtimeCache.pendingMetricBatchByFileId.set(batchCacheKey, batchRequest);
    }

    return (
      metricRowsBatchRequestRef.current.get(batchCacheKey) ||
      runtimeCache.pendingMetricBatchByFileId.get(batchCacheKey)
    );
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
      const runtimeCache = getWorstCellRuntimeCache();
      const fileId = String(selectedFileId || "");
      const cachedConfig = runtimeCache.thresholdConfigByFileId.get(fileId) || {
        thresholds: {},
        operators: {},
      };
      runtimeCache.thresholdConfigByFileId.set(fileId, {
        thresholds: {
          ...(cachedConfig.thresholds || {}),
          [thresholdKey]: numericValue,
        },
        operators: { ...(cachedConfig.operators || {}) },
      });
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
        throw new Error(
          "Analysis engine is still loading. Please retry in a moment.",
        );
      }

      await hydrateMetricRowsCacheFromBatch(selectedFileId);
      const metricRows = await getCachedMetricRows(selectedFileId, metric.key);

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

  const handleResultFormatChange = (nextFormat) => {
    setResultFormat(nextFormat);
  };

  const applyQuickRange = useCallback((days, referenceEndDate = "") => {
    const nextRange = resolveLastDaysRange(days, referenceEndDate || "");
    if (!nextRange.start || !nextRange.end) return;
    setCompareRanges((prev) => ({
      ...prev,
      preStart: nextRange.start,
      preEnd: nextRange.end,
    }));
  }, []);

  const clearQuickRangeSelection = useCallback(() => {
    setQuickDateDays("");
    setCustomQuickDays("");
  }, []);

  const handlePreStartChange = useCallback(
    (nextStart) => {
      clearQuickRangeSelection();
      setCompareRanges((prev) => ({ ...prev, preStart: nextStart }));
    },
    [clearQuickRangeSelection],
  );

  const handlePreEndChange = useCallback(
    (nextEnd) => {
      if (filterMode === "date_range" && effectiveQuickDateDays) {
        const nextRange = resolveLastDaysRange(
          effectiveQuickDateDays,
          nextEnd || "",
        );
        setCompareRanges((prev) => ({
          ...prev,
          preStart: nextRange.start || prev.preStart,
          preEnd: nextEnd || nextRange.end || prev.preEnd,
        }));
        return;
      }
      clearQuickRangeSelection();
      setCompareRanges((prev) => ({ ...prev, preEnd: nextEnd }));
    },
    [clearQuickRangeSelection, effectiveQuickDateDays, filterMode],
  );

  const handleQuickDateChange = useCallback(
    (nextQuickValue) => {
      setQuickDateDays(nextQuickValue);
      if (!nextQuickValue) {
        setCustomQuickDays("");
        return;
      }
      if (nextQuickValue === "custom") {
        return;
      }
      setCustomQuickDays("");
      applyQuickRange(nextQuickValue, compareRanges.preEnd || "");
    },
    [applyQuickRange, compareRanges.preEnd],
  );

  const handleCustomQuickDaysChange = useCallback(
    (nextCustomDays) => {
      setCustomQuickDays(nextCustomDays);
      if (quickDateDays !== "custom") return;
      const parsed = Number(nextCustomDays);
      if (!Number.isInteger(parsed) || parsed <= 0) return;
      applyQuickRange(String(parsed), compareRanges.preEnd || "");
    },
    [applyQuickRange, compareRanges.preEnd, quickDateDays],
  );

  const toggleBadDayRowExpansion = useCallback((rowKey) => {
    if (!rowKey) return;
    setExpandedBadDayRows((prev) => ({
      ...prev,
      [rowKey]: !prev[rowKey],
    }));
  }, []);

  const toggleDayKpiExpansion = useCallback((dayKey) => {
    if (!dayKey) return;
    setExpandedDayKpiLists((prev) => ({
      ...prev,
      [dayKey]: !prev[dayKey],
    }));
  }, []);

  const runBadDaysAverageAudit = useCallback(
    async (rows, activeDateRange, sourceTag = "unknown") => {
      const firstRow = Array.isArray(rows) ? rows[0] : null;
      if (!firstRow?.badDays?.length) {
        badDaysDebugLog("audit_skipped_empty_rows", {
          sourceTag,
          rows: rows?.length || 0,
        });
        return;
      }

      const sampleDays = firstRow.badDays.slice(0, 2);
      const checks = [];
      sampleDays.forEach((day) => {
        (day?.degradedKpis || []).slice(0, 3).forEach((kpiItem) => {
          checks.push({
            cellValue: firstRow.value,
            date: day.date,
            metricKey: kpiItem.metricKey,
            metricLabel: kpiItem.metricLabel,
            displayedAverage: Number(kpiItem.averageValue),
            thresholdOperator: kpiItem.thresholdOperator,
            thresholdValue: Number(kpiItem.thresholdValue),
          });
        });
      });

      badDaysDebugLog("audit_started", {
        sourceTag,
        fileId: selectedFileId,
        dimensionKey: selectedDimensionKey,
        activeDateRange,
        sampledCell: firstRow.value,
        sampledDays: sampleDays.map((day) => day.date),
        checks: checks.length,
      });

      const metricDataCache = new Map();
      const auditRows = [];
      for (const check of checks) {
        if (!metricDataCache.has(check.metricKey)) {
          const metricRows = await getCachedMetricRows(
            selectedFileId,
            check.metricKey,
          );
          metricDataCache.set(check.metricKey, metricRows);
        }

        const metricRows = metricDataCache.get(check.metricKey) || [];
        const matchedRows = metricRows.filter((row) => {
          const rowDate = toDatePartLocal(row?.date);
          if (rowDate !== check.date) return false;
          const dimensionValue = String(
            readValueByKey(row, selectedDimensionKey) ??
              readValueByKey(row, "cellName") ??
              "",
          ).trim();
          return dimensionValue === String(check.cellValue || "").trim();
        });
        const numericValues = matchedRows
          .map((row) => Number(row?.value))
          .filter((value) => Number.isFinite(value));
        const recomputedAverage = numericValues.length
          ? numericValues.reduce((sum, value) => sum + value, 0) /
            numericValues.length
          : null;
        const delta =
          Number.isFinite(recomputedAverage) &&
          Number.isFinite(check.displayedAverage)
            ? Number((recomputedAverage - check.displayedAverage).toFixed(6))
            : null;

        auditRows.push({
          sourceTag,
          metricKey: check.metricKey,
          metricLabel: check.metricLabel,
          date: check.date,
          cellValue: check.cellValue,
          matchedRowCount: matchedRows.length,
          rawValues: numericValues,
          displayedAverage: check.displayedAverage,
          recomputedAverage,
          delta,
          threshold: `${check.thresholdOperator} ${check.thresholdValue}`,
        });
      }

      badDaysDebugLog("audit_rows", auditRows);
      if (auditRows.length) {
        console.table(auditRows);
      }
    },
    [
      badDaysDebugLog,
      getCachedMetricRows,
      selectedDimensionKey,
      selectedFileId,
    ],
  );

  const runAnalysis = async () => {
    if (!selectedFileId || !selectedDimensionKey) return;
    const isCellBadDaysFormat =
      resultFormat === RESULT_FORMAT_OPTIONS.CELL_BAD_DAYS;
    const filterError = validateFilterInputs(
      filterMode,
      compareRanges,
      effectiveQuickDateDays,
    );
    if (filterError) {
      setLoadError(filterError);
      setAnalysisRows([]);
      setCellBadDayRows([]);
      return;
    }
    if (isCellBadDaysFormat && !effectiveMinimumBadDays) {
      setLoadError("Enter a valid minimum bad days value greater than 0.");
      setAnalysisRows([]);
      setCellBadDayRows([]);
      return;
    }

    const runId = ++analysisRunIdRef.current;
    const runContext = {
      selectedFileId: String(selectedFileId || ""),
      selectedDimensionKey: String(selectedDimensionKey || ""),
      filterMode: String(filterMode || ""),
      resultFormat: String(resultFormat || ""),
      compareRanges: {
        preStart: compareRanges.preStart || "",
        preEnd: compareRanges.preEnd || "",
        postStart: compareRanges.postStart || "",
        postEnd: compareRanges.postEnd || "",
      },
      quickDateDays: quickDateDays || "",
      customQuickDays: customQuickDays || "",
      effectiveQuickDateDays: effectiveQuickDateDays || "",
      effectiveMinimumBadDays: effectiveMinimumBadDays || null,
      thresholds,
      thresholdOperators,
    };
    setLoading(true);
    setLoadError("");

    try {
      if (!workerRef.current) {
        throw new Error(
          "Analysis engine is still loading. Please retry in a moment.",
        );
      }

      await hydrateMetricRowsCacheFromBatch(selectedFileId);

      const isDateRangeMode = filterMode === "date_range";
      const explicitStart = compareRanges.preStart || "";
      const explicitEnd = compareRanges.preEnd || "";
      const activeDateRange = isDateRangeMode
        ? effectiveQuickDateDays
          ? resolveLastDaysRange(effectiveQuickDateDays, explicitEnd || "")
          : { start: explicitStart, end: explicitEnd }
        : { start: "", end: "" };

      if (isCellBadDaysFormat) {
        const thresholdContexts = metrics
          .map((metric) => {
            const thresholdContext = resolveThresholdForMetric(metric);
            const thresholdValue = Number(thresholdContext?.thresholdValue);
            if (!Number.isFinite(thresholdValue)) return null;
            return {
              metricKey: metric.key,
              metricLabel: metric.label || metric.key,
              thresholdKey: thresholdContext?.thresholdKey || metric.key,
              thresholdOperator:
                thresholdContext?.thresholdOperator || DEFAULT_OPERATOR,
              thresholdValue,
            };
          })
          .filter(Boolean);

        if (filterMode === "date_range") {
          const badDaysResponse = await fetchDynamicBadDaysSummary(
            selectedFileId,
            {
              dimensionKey: selectedDimensionKey,
              activeRange: activeDateRange,
              minimumBadDays: effectiveMinimumBadDays,
              thresholdContexts,
            },
          );
          const isCurrentRun = analysisRunIdRef.current === runId;
          const hasValidRows =
            badDaysResponse?.success && Array.isArray(badDaysResponse?.rows);
          badDaysDebugLog("backend_bad_days_response_meta", {
            runId,
            currentRunId: analysisRunIdRef.current,
            isCurrentRun,
            success: Boolean(badDaysResponse?.success),
            rowCount: Array.isArray(badDaysResponse?.rows)
              ? badDaysResponse.rows.length
              : 0,
            evaluatedMetricCount: badDaysResponse?.evaluatedMetricCount,
            totalComparedCells: badDaysResponse?.totalComparedCells,
            thresholdContexts: thresholdContexts.length,
            range: activeDateRange,
          });

        if (isCurrentRun && hasValidRows) {
          setCellBadDayRows(badDaysResponse.rows);
          setAnalysisRows([]);
          setLastRunContext(runContext);
          void runBadDaysAverageAudit(
            badDaysResponse.rows,
            activeDateRange,
              "backend_fast_path",
            );
            return;
          }
          badDaysDebugLog("backend_bad_days_fallback_to_worker", {
            reason: isCurrentRun
              ? "backend response missing/invalid for active run"
              : "stale run (newer analysis already started)",
            runId,
            currentRunId: analysisRunIdRef.current,
            isCurrentRun,
            hasValidRows,
          });
        }

        const metricsPayload = [];
        for (const metric of metrics) {
          if (analysisRunIdRef.current !== runId) return;
          const metricRows = await getCachedMetricRows(
            selectedFileId,
            metric.key,
          );
          metricsPayload.push({
            metricKey: metric.key,
            metricLabel: metric.label || metric.key,
            rows: metricRows,
            thresholdContext: resolveThresholdForMetric(metric),
          });
        }

        if (analysisRunIdRef.current !== runId) return;
        const badDaysResult = await analyzeMetricWithWorker(
          filterMode === "pre_post"
            ? {
                mode: "bad_days_pre_post",
                metrics: metricsPayload,
                dimensionKey: selectedDimensionKey,
                preRange: {
                  start: compareRanges.preStart || "",
                  end: compareRanges.preEnd || "",
                },
                postRange: {
                  start: compareRanges.postStart || "",
                  end: compareRanges.postEnd || "",
                },
                minimumBadDays: effectiveMinimumBadDays,
              }
            : {
                mode: "bad_days",
                metrics: metricsPayload,
                dimensionKey: selectedDimensionKey,
                activeRange: activeDateRange,
                minimumBadDays: effectiveMinimumBadDays,
              },
        );

        setCellBadDayRows(
          Array.isArray(badDaysResult?.rows) ? badDaysResult.rows : [],
        );
        setAnalysisRows([]);
        setLastRunContext(runContext);
        badDaysDebugLog("worker_bad_days_result_meta", {
          mode: badDaysResult?.mode,
          rowCount: Array.isArray(badDaysResult?.rows)
            ? badDaysResult.rows.length
            : 0,
          totalComparedCells: badDaysResult?.totalComparedCells,
          range: activeDateRange,
          preRange: badDaysResult?.preRange,
          postRange: badDaysResult?.postRange,
          metricPayloadCount: metricsPayload.length,
        });
        void runBadDaysAverageAudit(
          Array.isArray(badDaysResult?.rows) ? badDaysResult.rows : [],
          activeDateRange,
          "worker_fallback",
        );
      } else {
        const rowsByKpi = [];
        for (const metric of metrics) {
          if (analysisRunIdRef.current !== runId) return;

          let metricRows = await getCachedMetricRows(
            selectedFileId,
            metric.key,
          );

          const operator = OPERATOR_OPTIONS.includes(
            operatorsByMetric?.[metric.key],
          )
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
        setCellBadDayRows([]);
        setLastRunContext(runContext);
      }
    } catch (error) {
      if (analysisRunIdRef.current !== runId) return;
      setLoadError(error?.message || "Failed to run worst cell analysis.");
      setAnalysisRows([]);
      setCellBadDayRows([]);
    } finally {
      if (analysisRunIdRef.current === runId) {
        setLoading(false);
      }
    }
  };

  // Analysis is intentionally manual now: it runs only via the
  // "Refresh Worst Cell Analysis" button click.

  useEffect(() => {
    setExpandedBadDayRows({});
    setExpandedDayKpiLists({});
  }, [cellBadDayRows]);

  useEffect(() => {
    const firstRow = Array.isArray(cellBadDayRows) ? cellBadDayRows[0] : null;
    badDaysDebugLog("cell_bad_day_rows_state_changed", {
      rowCount: cellBadDayRows.length,
      firstRow: firstRow
        ? {
            value: firstRow.value,
            badDayCount: firstRow.badDayCount,
            degradedKpiEventCount: firstRow.degradedKpiEventCount,
            firstDay: firstRow.badDays?.[0]
              ? {
                  date: firstRow.badDays[0].date,
                  degradedKpiCount: firstRow.badDays[0].degradedKpiCount,
                  firstKpi: firstRow.badDays[0].degradedKpis?.[0] || null,
                }
              : null,
          }
        : null,
    });
  }, [badDaysDebugLog, cellBadDayRows]);

  const totalFailedDimensions = useMemo(
    () => analysisRows.reduce((sum, row) => sum + row.failCount, 0),
    [analysisRows],
  );
  const totalComparedDimensions = useMemo(
    () => analysisRows.reduce((sum, row) => sum + row.comparedDimensions, 0),
    [analysisRows],
  );
  const totalBadDaysAcrossCells = useMemo(
    () => cellBadDayRows.reduce((sum, row) => sum + (row.badDayCount || 0), 0),
    [cellBadDayRows],
  );
  const totalDegradedEventsAcrossCells = useMemo(
    () =>
      cellBadDayRows.reduce(
        (sum, row) => sum + (row.degradedKpiEventCount || 0),
        0,
      ),
    [cellBadDayRows],
  );
  const isFilterReady = useMemo(
    () =>
      !validateFilterInputs(filterMode, compareRanges, effectiveQuickDateDays),
    [filterMode, compareRanges, effectiveQuickDateDays],
  );
  const isCellBadDaysView = useMemo(
    () => resultFormat === RESULT_FORMAT_OPTIONS.CELL_BAD_DAYS,
    [resultFormat],
  );
  const isBadDaysInputReady = useMemo(
    () => !isCellBadDaysView || Boolean(effectiveMinimumBadDays),
    [isCellBadDaysView, effectiveMinimumBadDays],
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

  const cellBadDaysHeatmapModels = useMemo(() => {
    if (
      !isCellBadDaysView ||
      !Array.isArray(cellBadDayRows) ||
      !cellBadDayRows.length
    ) {
      return [];
    }

    return cellBadDayRows.map((row) => {
      const heatmapEntries = Array.isArray(row.heatmapEntries)
        ? row.heatmapEntries
        : [];
      const useHeatmapEntries = heatmapEntries.length > 0;
      const metricMetaMap = new Map();
      const itemByMetricDate = new Map();
      const dateSet = new Set();

      if (useHeatmapEntries) {
        heatmapEntries.forEach((entry) => {
          const metricKey = String(
            entry?.metricKey || entry?.metricLabel || "",
          ).trim();
          const date = String(entry?.date || "").trim();
          if (!metricKey || !date) return;
          if (!metricMetaMap.has(metricKey)) {
            metricMetaMap.set(metricKey, {
              metricKey,
              metricLabel: String(entry?.metricLabel || metricKey),
              thresholdOperator: String(
                entry?.thresholdOperator || DEFAULT_OPERATOR,
              ),
              thresholdValue: Number(entry?.thresholdValue),
            });
          }
          itemByMetricDate.set(`${metricKey}__${date}`, entry);
          dateSet.add(date);
        });
      } else {
        (row.badDays || []).forEach((day) => {
          (day.degradedKpis || []).forEach((kpiItem) => {
            const key = String(
              kpiItem.metricKey || kpiItem.metricLabel || "",
            ).trim();
            if (!key) return;
            if (!metricMetaMap.has(key)) {
              metricMetaMap.set(key, {
                metricKey: key,
                metricLabel: String(kpiItem.metricLabel || key),
                thresholdOperator: String(
                  kpiItem.thresholdOperator || DEFAULT_OPERATOR,
                ),
                thresholdValue: Number(kpiItem.thresholdValue),
              });
            }
          });
        });
        (row.badDays || []).forEach((day) => {
          const date = String(day.date || "");
          (day.degradedKpis || []).forEach((kpiItem) => {
            const metricKey = String(
              kpiItem.metricKey || kpiItem.metricLabel || "",
            ).trim();
            if (!metricKey || !date) return;
            itemByMetricDate.set(`${metricKey}__${date}`, kpiItem);
            dateSet.add(date);
          });
        });
      }

      const dateList = [...dateSet].filter(Boolean).sort((a, b) => a.localeCompare(b));
      const metricRows = [...metricMetaMap.values()].sort((a, b) =>
        String(a.metricLabel).localeCompare(String(b.metricLabel)),
      );

      const preAverageByMetric = {};
      const postAverageByMetric = {};

      const matrix = metricRows.map((metric) => {
        const values = dateList.map((date) => {
          const hit = itemByMetricDate.get(`${metric.metricKey}__${date}`);
          const averageValue = Number(hit?.averageValue);
          const thresholdValue = Number(
            hit?.thresholdValue ?? metric.thresholdValue,
          );
          const preAverageValue = Number(hit?.preAverageValue);
          const thresholdOperator = String(
            hit?.thresholdOperator ||
              metric.thresholdOperator ||
              DEFAULT_OPERATOR,
          );
          const period = String(hit?.period || "");
          const isMissing = !hit || !Number.isFinite(averageValue);
          const deviationBadnessPct = isMissing
            ? null
            : getDeviationBadnessPct(
                averageValue,
                thresholdValue,
                thresholdOperator,
              );
          return {
            date,
            period,
            metricKey: metric.metricKey,
            metricLabel: metric.metricLabel,
            averageValue: Number.isFinite(averageValue) ? averageValue : null,
            preAverageValue: Number.isFinite(preAverageValue)
              ? preAverageValue
              : null,
            thresholdValue: Number.isFinite(thresholdValue)
              ? thresholdValue
              : null,
            thresholdOperator,
            deviationBadnessPct,
            isMissing,
          };
        });

        const preValues = values
          .filter((value) => value.period === "pre")
          .map((value) => value.averageValue)
          .filter((value) => Number.isFinite(value));
        const postValues = values
          .filter((value) => value.period === "post")
          .map((value) => value.averageValue)
          .filter((value) => Number.isFinite(value));
        preAverageByMetric[metric.metricKey] = preValues.length
          ? preValues.reduce((sum, value) => sum + value, 0) / preValues.length
          : null;
        postAverageByMetric[metric.metricKey] = postValues.length
          ? postValues.reduce((sum, value) => sum + value, 0) / postValues.length
          : null;

        return {
          metric,
          values,
        };
      });

      const maxAbsBadness = matrix.reduce((maxVal, rowItem) => {
        rowItem.values.forEach((value) => {
          const abs = Math.abs(Number(value.deviationBadnessPct));
          if (Number.isFinite(abs)) {
            maxVal = Math.max(maxVal, abs);
          }
        });
        return maxVal;
      }, 0);

      const allAverageValues = [];
      matrix.forEach((rowItem) => {
        rowItem.values.forEach((value) => {
          if (Number.isFinite(value.averageValue)) {
            allAverageValues.push(value.averageValue);
          }
        });
      });
      const maxAbsAverageDeltaPct = matrix.reduce((maxVal, rowItem) => {
        rowItem.values.forEach((value) => {
          const cmp = Number(value.preAverageValue);
          const avg = Number(value.averageValue);
          if (!Number.isFinite(cmp) || !Number.isFinite(avg)) return;
          const base = Math.abs(cmp) > 1e-9 ? Math.abs(cmp) : 1;
          const deltaPct = Math.abs(((avg - cmp) / base) * 100);
          maxVal = Math.max(maxVal, deltaPct);
        });
        return maxVal;
      }, 0);

      const preEnd = row?.preRange?.end || "";
      const dividerAfterIndex = preEnd ? dateList.indexOf(preEnd) : -1;

      return {
        rowKey: String(row.value || row.meta?.cellId || ""),
        row,
        dateList,
        metricRows,
        matrix,
        maxAbsBadness,
        maxAbsAverageDeltaPct,
        preAverageByMetric,
        postAverageByMetric,
        dividerAfterIndex,
      };
    });
  }, [isCellBadDaysView, cellBadDayRows]);

  const filteredCellBadDaysHeatmapModels = useMemo(() => {
    const search = String(cellHeatmapSearch || "").trim().toLowerCase();
    if (!search) return cellBadDaysHeatmapModels;
    return cellBadDaysHeatmapModels.filter((model) => {
      const candidates = [
        model?.row?.value,
        model?.row?.meta?.cellId,
        model?.row?.meta?.shortName,
      ]
        .map((value) => String(value || "").toLowerCase())
        .join(" ");
      return candidates.includes(search);
    });
  }, [cellBadDaysHeatmapModels, cellHeatmapSearch]);

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
      .map(
        (header) => `<th style="font-weight:700;">${escapeHtml(header)}</th>`,
      )
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
      console.warn(
        "No failed rows available for the selected KPI download option.",
      );
      return;
    }

    const selectedOption = downloadOptions.find(
      (option) => option.value === selectedMetric,
    );
    const safePart = (selectedOption?.label || "kpi")
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "_")
      .replace(/^_+|_+$/g, "");
    const timestamp = new Date()
      .toISOString()
      .slice(0, 19)
      .replace(/[:T]/g, "-");
    const fileName = `worst_cell_${safePart || "kpi"}_${timestamp}`;
    const activeRangeFromRows = sourceRows.find(
      (row) => row?.activeRange,
    )?.activeRange;
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
      dimensionOptions.find((option) => option.value === selectedDimensionKey)
        ?.label ||
      selectedDimensionKey ||
      "N/A";

    triggerExcelDownload(exportRows, fileName, {
      kpiFileName:
        selectedFile?.fileName ||
        `#${selectedFile?.id || selectedFileId || "N/A"}`,
      cellDimension: selectedDimension,
      preRange: dateRangeText,
      postRange: postRangeText,
      kpiSelection: selectedOption?.label || "N/A",
    });
  };

  const handleDownloadBadDaysExcel = () => {
    if (!cellBadDayRows.length) return;
    const exportRows = [];
    cellBadDayRows.forEach((row) => {
      const meta = row.meta || {};
      const preAvgMap = {};
      const postAvgMap = {};
      (row.heatmapEntries || []).forEach((entry) => {
        const key = String(entry.metricKey || entry.metricLabel || "").trim();
        if (!key) return;
        if (!preAvgMap[key]) preAvgMap[key] = [];
        if (!postAvgMap[key]) postAvgMap[key] = [];
        if (entry.period === "pre" && Number.isFinite(Number(entry.averageValue))) {
          preAvgMap[key].push(Number(entry.averageValue));
        }
        if (entry.period === "post" && Number.isFinite(Number(entry.averageValue))) {
          postAvgMap[key].push(Number(entry.averageValue));
        }
      });

      row.degradedKpiNames.forEach((kpiName) => {
        const preList = preAvgMap[kpiName] || [];
        const postList = postAvgMap[kpiName] || [];
        const avgPre = preList.length
          ? preList.reduce((sum, value) => sum + value, 0) / preList.length
          : null;
        const avgPost = postList.length
          ? postList.reduce((sum, value) => sum + value, 0) / postList.length
          : null;

        exportRows.push({
          "short name": meta.shortName || row.value || "",
          "cell id": meta.cellId || row.value || "",
          "site id": meta.siteId || "",
          Tech: meta.tech || "",
          sector: meta.sector || "",
          "avg pre": formatMetricNumber(avgPre),
          "avg post": formatMetricNumber(avgPost),
          remark: `Bad day KPI: ${kpiName}`,
          category: resolveKpiCategory(kpiName, kpiName),
        });
      });
    });

    const selectedDimension =
      dimensionOptions.find((option) => option.value === selectedDimensionKey)
        ?.label ||
      selectedDimensionKey ||
      "N/A";
    const fileName = `cell_bad_days_${new Date()
      .toISOString()
      .slice(0, 19)
      .replace(/[:T]/g, "-")}`;
    triggerExcelDownload(exportRows, fileName, {
      kpiFileName:
        selectedFile?.fileName ||
        `#${selectedFile?.id || selectedFileId || "N/A"}`,
      cellDimension: selectedDimension,
      preRange: formatRangeText(compareRanges.preStart, compareRanges.preEnd),
      postRange: formatRangeText(compareRanges.postStart, compareRanges.postEnd),
      kpiSelection: "Cell Bad Days",
    });
  };

  const toggleBaselineExpansion = (rowKey) => {
    if (!rowKey) return;
    setExpandedBaselineByCell((prev) => ({ ...prev, [rowKey]: !prev[rowKey] }));
  };

  const handleExportCellHeatmapPdf = async (model) => {
    if (!model?.rowKey || exportingHeatmapPdfKey) return;
    setExportingHeatmapPdfKey(model.rowKey);
    try {
      const target = document.getElementById(`cell-heatmap-canvas-${model.rowKey}`);
      if (!target) return;
      const [{ toPng }, { jsPDF }] = await Promise.all([
        import("html-to-image"),
        import("jspdf"),
      ]);
      const imageData = await toPng(target, {
        cacheBust: true,
        backgroundColor: "#ffffff",
        pixelRatio: 2,
      });
      const doc = new jsPDF({ orientation: "landscape", unit: "mm", format: "a4" });
      const pageWidth = doc.internal.pageSize.getWidth();
      const pageHeight = doc.internal.pageSize.getHeight();

      doc.setFontSize(14);
      doc.text("Worst Cell Heatmap Export", 12, 12);
      doc.setFontSize(10);
      const metaLines = [
        `File: ${selectedFile?.fileName || selectedFileId || "N/A"}`,
        `Dimension: ${selectedDimensionKey || "N/A"}`,
        `Cell: ${model?.row?.meta?.shortName || model?.row?.value || "N/A"} | Cell ID: ${model?.row?.meta?.cellId || model?.row?.value || "N/A"}`,
        `Pre Range: ${formatRangeText(compareRanges.preStart, compareRanges.preEnd)}`,
        `Post Range: ${formatRangeText(compareRanges.postStart, compareRanges.postEnd)}`,
      ];
      metaLines.forEach((line, index) => doc.text(line, 12, 20 + index * 6));

      const tableHeaders = ["KPI", "Pre Avg", "Post Avg"];
      const rows = model.metricRows.slice(0, 20).map((metric) => [
        metric.metricLabel,
        formatMetricNumber(model.preAverageByMetric?.[metric.metricKey]),
        formatMetricNumber(model.postAverageByMetric?.[metric.metricKey]),
      ]);
      let y = 56;
      doc.setFontSize(9);
      doc.text(tableHeaders[0], 12, y);
      doc.text(tableHeaders[1], 110, y);
      doc.text(tableHeaders[2], 145, y);
      y += 5;
      rows.forEach((row) => {
        doc.text(String(row[0] || ""), 12, y, { maxWidth: 92 });
        doc.text(String(row[1] || ""), 110, y);
        doc.text(String(row[2] || ""), 145, y);
        y += 5;
      });

      const maxImgWidth = pageWidth - 16;
      const maxImgHeight = pageHeight - (y + 8);
      const imageProps = doc.getImageProperties(imageData);
      const imageWidth = Number(imageProps?.width) || 1;
      const imageHeight = Number(imageProps?.height) || 1;
      const scale = Math.min(maxImgWidth / imageWidth, maxImgHeight / imageHeight);
      const renderWidth = Math.max(1, imageWidth * scale);
      const renderHeight = Math.max(1, imageHeight * scale);
      const renderX = (pageWidth - renderWidth) / 2;
      const renderY = y + 2;
      doc.addImage(imageData, "PNG", renderX, renderY, renderWidth, renderHeight);
      doc.save(`worst_cell_heatmap_${model.rowKey}.pdf`);
    } finally {
      setExportingHeatmapPdfKey("");
    }
  };

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 via-rose-50 to-amber-50 p-6">
      <div className="max-w-[1600px] mx-auto space-y-6">
        <div className="bg-white rounded-2xl border border-rose-100 shadow-lg p-5">
          <div className="flex items-center gap-3 mb-4">
            <AlertTriangle className="w-6 h-6 text-rose-600" />
            <div>
              <h1 className="text-xl font-bold text-gray-800">
                Worst Cell Analysis
              </h1>
              <p className="text-sm text-gray-500">
                File-wise KPI failure matrix using page-level pre/post
                operators.
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
                onClick={() => {
                  setFilterMode("pre_post");
                }}
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

          <div className="flex items-center justify-end mb-4">
            <div className="inline-flex rounded-lg border border-gray-200 p-1 bg-gray-50">
              <button
                type="button"
                onClick={() =>
                  handleResultFormatChange(RESULT_FORMAT_OPTIONS.KPI_FIRST)
                }
                className={`px-3 py-1.5 text-xs font-semibold rounded-md transition-colors ${
                  resultFormat === RESULT_FORMAT_OPTIONS.KPI_FIRST
                    ? "bg-indigo-500 text-white"
                    : "text-gray-700 hover:bg-white"
                }`}
              >
                Current KPI Format
              </button>
              <button
                type="button"
                onClick={() =>
                  handleResultFormatChange(RESULT_FORMAT_OPTIONS.CELL_BAD_DAYS)
                }
                className={`px-3 py-1.5 text-xs font-semibold rounded-md transition-colors ${
                  resultFormat === RESULT_FORMAT_OPTIONS.CELL_BAD_DAYS
                    ? "bg-emerald-500 text-white"
                    : "text-gray-700 hover:bg-white"
                }`}
              >
                Cell Bad Days Format
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
                onChange={(e) => handlePreStartChange(e.target.value)}
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
                onChange={(e) => handlePreEndChange(e.target.value)}
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
                    setCompareRanges((prev) => ({
                      ...prev,
                      postStart: e.target.value,
                    }))
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
                    setCompareRanges((prev) => ({
                      ...prev,
                      postEnd: e.target.value,
                    }))
                  }
                  className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm bg-white"
                />
              </div>

              <div className="lg:col-span-3 flex items-end">
                <button
                  onClick={runAnalysis}
                  disabled={
                    loading ||
                    !selectedFileId ||
                    !selectedDimensionKey ||
                    !isFilterReady ||
                    !isBadDaysInputReady
                  }
                  className={`px-4 py-2 rounded-lg text-sm font-semibold text-white ${
                    loading ||
                    !selectedFileId ||
                    !selectedDimensionKey ||
                    !isFilterReady ||
                    !isBadDaysInputReady
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
              <div>
                <label className="block text-sm text-gray-600 font-semibold mb-2">
                  Quick Range
                </label>
                <div className="flex items-center gap-2">
                  <select
                    value={quickDateDays}
                    onChange={(e) => handleQuickDateChange(e.target.value)}
                    className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm bg-white"
                  >
                    <option value="">Manual date range</option>
                    {QUICK_DAY_OPTIONS.map((option) => (
                      <option
                        key={`quick-top-${option.value}`}
                        value={option.value}
                      >
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
                      onChange={(e) =>
                        handleCustomQuickDaysChange(e.target.value)
                      }
                      placeholder="Days"
                      className="w-24 border border-gray-200 rounded-lg px-3 py-2 text-sm bg-white"
                    />
                  )}
                </div>
                {quickRangePreviewText && (
                  <p className="text-xs text-gray-500 mt-2">
                    Reference end date: {quickRangePreviewText}
                  </p>
                )}
              </div>

              <div>
                <label className="block text-sm text-gray-600 font-semibold mb-2">
                  Minimum Bad Days
                </label>
                <div className="flex items-center gap-2">
                  <input
                    type="number"
                    min="1"
                    step="1"
                    value={minimumBadDaysInput}
                    onChange={(e) => setMinimumBadDaysInput(e.target.value)}
                    placeholder="3"
                    disabled={!isCellBadDaysView}
                    className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm bg-white disabled:bg-gray-100 disabled:text-gray-400"
                  />
                  <span
                    className={`text-[11px] font-semibold ${
                      isCellBadDaysView ? "text-emerald-700" : "text-gray-400"
                    }`}
                  >
                    {isCellBadDaysView
                      ? "Recommended: 3+"
                      : "Cell Bad Days only"}
                  </span>
                </div>
              </div>

              <div className="lg:col-span-4 flex items-end">
                <button
                  onClick={runAnalysis}
                  disabled={
                    loading ||
                    !selectedFileId ||
                    !selectedDimensionKey ||
                    !isFilterReady ||
                    !isBadDaysInputReady
                  }
                  className={`px-4 py-2 rounded-lg text-sm font-semibold text-white ${
                    loading ||
                    !selectedFileId ||
                    !selectedDimensionKey ||
                    !isFilterReady ||
                    !isBadDaysInputReady
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
            <p className="text-xs uppercase tracking-wide text-gray-500">
              Selected File
            </p>
            <p className="text-base font-bold text-gray-800 mt-1">
              {selectedFile
                ? `${selectedFile.fileName || "Unnamed"} (#${selectedFile.id})`
                : "N/A"}
            </p>
          </div>
          <div className="bg-white rounded-xl border border-gray-100 p-4 shadow-sm">
            <p className="text-xs uppercase tracking-wide text-gray-500">
              {isCellBadDaysView ? "Affected Cells" : "Failing KPIs"}
            </p>
            <p className="text-2xl font-bold text-rose-600 mt-1">
              {isCellBadDaysView
                ? cellBadDayRows.length
                : analysisRows.filter((row) => row.failCount > 0).length}
            </p>
          </div>
          <div className="bg-white rounded-xl border border-gray-100 p-4 shadow-sm">
            <p className="text-xs uppercase tracking-wide text-gray-500">
              {isCellBadDaysView
                ? "Bad Days / KPI Events"
                : "Failed Dimensions"}
            </p>
            <p className="text-2xl font-bold text-amber-600 mt-1">
              {isCellBadDaysView
                ? `${totalBadDaysAcrossCells} / ${totalDegradedEventsAcrossCells}`
                : `${totalFailedDimensions} / ${totalComparedDimensions}`}
            </p>
          </div>
        </div>

        <div className="bg-white rounded-2xl border border-gray-100 shadow-sm overflow-hidden">
          <div className="px-5 py-4 border-b border-gray-100 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
            <div className="flex items-center gap-2">
              <FileText className="w-4 h-4 text-gray-500" />
              <p className="text-sm font-semibold text-gray-700">
                {isCellBadDaysView
                  ? `Cell-wise Bad Days by ${selectedDimensionKey || "Dimension"}`
                  : filterMode === "date_range"
                    ? `KPI Threshold Fails by ${selectedDimensionKey || "Dimension"}`
                    : `KPI vs Failed ${selectedDimensionKey || "Dimension"} Values`}
              </p>
            </div>

            <div className="flex flex-row items-end gap-1">
              {isCellBadDaysView ? (
                <button
                  type="button"
                  onClick={handleDownloadBadDaysExcel}
                  disabled={loading || cellBadDayRows.length === 0}
                  className="inline-flex items-center gap-2 border border-gray-200 rounded-md px-3 py-2 bg-white text-xs font-semibold text-gray-700 disabled:bg-gray-100 disabled:text-gray-400"
                >
                  <Download className="w-4 h-4 text-gray-500" />
                  Export Bad Days Excel
                </button>
              ) : (
                <div className="flex items-center gap-2">
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
                      <option
                        key={`download-${option.value}`}
                        value={option.value}
                      >
                        {option.value === "__all__"
                          ? "Download All"
                          : `Download ${option.label}`}
                      </option>
                    ))}
                  </select>
                </div>
              )}
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
          ) : (
            <>
              {isCellBadDaysView ? (
                cellBadDayRows.length === 0 ? (
                  <div className="p-8 text-center text-sm text-gray-500">
                    No cells matched the selected range and minimum bad day
                    criteria.
                  </div>
                ) : (
                  <div className="space-y-4">
                    <div className="px-4 pt-2">
                      <div className="inline-flex rounded-lg border border-gray-200 bg-white p-1">
                        <button
                          type="button"
                          onClick={() =>
                            setCellBadDaysViewMode(
                              CELL_BAD_DAYS_VIEW_OPTIONS.TABLE,
                            )
                          }
                          className={`px-3 py-1.5 text-xs font-semibold rounded-md ${
                            cellBadDaysViewMode ===
                            CELL_BAD_DAYS_VIEW_OPTIONS.TABLE
                              ? "bg-gray-900 text-white"
                              : "text-gray-600 hover:bg-gray-100"
                          }`}
                        >
                          Table
                        </button>
                        <button
                          type="button"
                          onClick={() =>
                            setCellBadDaysViewMode(
                              CELL_BAD_DAYS_VIEW_OPTIONS.VISUAL,
                            )
                          }
                          className={`px-3 py-1.5 text-xs font-semibold rounded-md ${
                            cellBadDaysViewMode ===
                            CELL_BAD_DAYS_VIEW_OPTIONS.VISUAL
                              ? "bg-gray-900 text-white"
                              : "text-gray-600 hover:bg-gray-100"
                          }`}
                        >
                          Visualization
                        </button>
                      </div>
                    </div>

                    {cellBadDaysViewMode ===
                    CELL_BAD_DAYS_VIEW_OPTIONS.TABLE ? (
                      <div className="overflow-x-auto">
                        <table className="min-w-full text-sm">
                          <thead className="bg-gray-50">
                            <tr>
                              <th className="px-4 py-3 text-left font-semibold text-gray-700">
                                {selectedDimensionKey || "Dimension"} Value
                              </th>
                              <th className="px-4 py-3 text-left font-semibold text-gray-700">
                                Bad Days
                              </th>
                              <th className="px-4 py-3 text-left font-semibold text-gray-700">
                                KPI Degraded
                              </th>
                              <th className="px-4 py-3 text-left font-semibold text-gray-700">
                                KPI Names
                              </th>
                              <th className="px-4 py-3 text-left font-semibold text-gray-700">
                                Bad Day Details
                              </th>
                            </tr>
                          </thead>
                          <tbody>
                            {cellBadDayRows.map((row) => {
                              const rowKey = String(
                                row.value || row.meta?.cellId || "",
                              );
                              const isRowExpanded = Boolean(
                                expandedBadDayRows[rowKey],
                              );
                              const visibleDayCount = isRowExpanded
                                ? row.badDays.length
                                : 2;
                              const visibleBadDays = row.badDays.slice(
                                0,
                                visibleDayCount,
                              );
                              const hiddenDayCount = Math.max(
                                row.badDays.length - visibleDayCount,
                                0,
                              );

                              return (
                                <tr
                                  key={`bad-days-${row.value}`}
                                  className="border-t border-gray-100 align-top"
                                >
                                  <td className="px-4 py-3">
                                    <p className="font-semibold text-gray-800">
                                      {row.value || row.meta?.shortName}
                                    </p>
                                    <p className="text-xs text-gray-500">
                                      Short Name: {row.meta?.shortName || "N/A"}
                                    </p>
                                    <p className="text-xs text-gray-500">
                                      Cell ID: {row.meta?.cellId || row.value}
                                    </p>
                                    <p className="text-xs text-gray-500">
                                      Site: {row.meta?.siteId || "N/A"} | Tech:{" "}
                                      {row.meta?.tech || "N/A"} | Sector:{" "}
                                      {row.meta?.sector || "N/A"}
                                    </p>
                                  </td>
                                  <td className="px-4 py-3">
                                    <span className="inline-flex px-2 py-1 rounded-full font-bold text-xs bg-rose-100 text-rose-700">
                                      {row.badDayCount} day
                                      {row.badDayCount > 1 ? "s" : ""}
                                    </span>
                                  </td>
                                  <td className="px-4 py-3">
                                    <p className="text-xs font-semibold text-gray-700">
                                      Unique KPIs: {row.degradedKpiUniqueCount}
                                    </p>
                                    <p className="text-xs font-semibold text-gray-700">
                                      Total KPI hits:{" "}
                                      {row.degradedKpiEventCount}
                                    </p>
                                  </td>
                                  <td className="px-4 py-3">
                                    <div className="flex flex-wrap gap-2 max-w-[360px]">
                                      {row.degradedKpiNames.map((kpiName) => (
                                        <span
                                          key={`${row.value}-${kpiName}`}
                                          className="inline-flex items-center px-2 py-1 rounded-md bg-indigo-50 border border-indigo-200 text-indigo-700 text-xs font-semibold"
                                        >
                                          {kpiName}
                                        </span>
                                      ))}
                                    </div>
                                  </td>
                                  <td className="px-4 py-3">
                                    <div className="space-y-2 max-w-[520px]">
                                      {visibleBadDays.map((dayItem) => {
                                        const dayKey = `${rowKey}-${dayItem.date}`;
                                        const isDayExpanded = Boolean(
                                          expandedDayKpiLists[dayKey],
                                        );
                                        const visibleKpiCount = isDayExpanded
                                          ? dayItem.degradedKpis.length
                                          : 2;
                                        const visibleKpis =
                                          dayItem.degradedKpis.slice(
                                            0,
                                            visibleKpiCount,
                                          );
                                        const hiddenKpiCount = Math.max(
                                          dayItem.degradedKpis.length -
                                            visibleKpiCount,
                                          0,
                                        );

                                        return (
                                          <div
                                            key={`${row.value}-${dayItem.date}`}
                                            className="rounded-md border border-rose-100 bg-rose-50 p-2"
                                          >
                                            <p className="text-xs font-semibold text-rose-700">
                                              {dayItem.date} (
                                              {dayItem.degradedKpiCount} KPI)
                                            </p>
                                            <div className="mt-1 space-y-1">
                                              {visibleKpis.map((kpiItem) => (
                                                <p
                                                  key={`${dayKey}-${kpiItem.metricKey}-${kpiItem.thresholdKey}`}
                                                  className="text-xs text-gray-700"
                                                >
                                                  <span className="font-semibold">
                                                    {kpiItem.metricLabel}
                                                  </span>
                                                  : avg{" "}
                                                  <span className="font-semibold">
                                                    {formatMetricNumber(
                                                      kpiItem.averageValue,
                                                    )}
                                                  </span>{" "}
                                                  {kpiItem.thresholdOperator}{" "}
                                                  <span className="font-semibold">
                                                    {formatMetricNumber(
                                                      kpiItem.thresholdValue,
                                                    )}
                                                  </span>
                                                </p>
                                              ))}
                                            </div>
                                            {hiddenKpiCount > 0 && (
                                              <button
                                                type="button"
                                                onClick={() =>
                                                  toggleDayKpiExpansion(dayKey)
                                                }
                                                className="mt-1 text-[11px] font-semibold text-rose-700 hover:text-rose-800"
                                              >
                                                {isDayExpanded
                                                  ? "Show fewer KPI details"
                                                  : `Show ${hiddenKpiCount} more KPI detail${
                                                      hiddenKpiCount > 1
                                                        ? "s"
                                                        : ""
                                                    }`}
                                              </button>
                                            )}
                                            {isDayExpanded &&
                                              dayItem.degradedKpis.length >
                                                2 && (
                                                <button
                                                  type="button"
                                                  onClick={() =>
                                                    toggleDayKpiExpansion(
                                                      dayKey,
                                                    )
                                                  }
                                                  className="ml-2 text-[11px] font-semibold text-rose-700 hover:text-rose-800"
                                                >
                                                  Collapse KPI list
                                                </button>
                                              )}
                                          </div>
                                        );
                                      })}
                                      {hiddenDayCount > 0 && (
                                        <button
                                          type="button"
                                          onClick={() =>
                                            toggleBadDayRowExpansion(rowKey)
                                          }
                                          className="text-xs font-semibold text-rose-700 hover:text-rose-800"
                                        >
                                          {isRowExpanded
                                            ? "Show fewer bad days"
                                            : `Show ${hiddenDayCount} more bad day${
                                                hiddenDayCount > 1 ? "s" : ""
                                              }`}
                                        </button>
                                      )}
                                      {isRowExpanded &&
                                        row.badDays.length > 2 && (
                                          <button
                                            type="button"
                                            onClick={() =>
                                              toggleBadDayRowExpansion(rowKey)
                                            }
                                            className="ml-2 text-xs font-semibold text-rose-700 hover:text-rose-800"
                                          >
                                            Collapse day list
                                          </button>
                                        )}
                                    </div>
                                  </td>
                                </tr>
                              );
                            })}
                          </tbody>
                        </table>
                      </div>
                    ) : (
                      <div className="space-y-4 px-4 pb-4">
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <input
                            type="text"
                            value={cellHeatmapSearch}
                            onChange={(e) => setCellHeatmapSearch(e.target.value)}
                            placeholder="Search Cell ID / Short Name..."
                            className="w-full max-w-sm border border-gray-200 rounded-md px-3 py-2 text-xs bg-white"
                          />
                          <p className="text-xs text-gray-500">
                            Showing {filteredCellBadDaysHeatmapModels.length} of{" "}
                            {cellBadDaysHeatmapModels.length} heatmaps
                          </p>
                        </div>
                        {filteredCellBadDaysHeatmapModels.map((model) => {
                          const perCellMode =
                            cellHeatModeByCell[model.rowKey] || "average";
                          const perCellValueMode =
                            cellHeatValueModeByCell[model.rowKey] || "day_avg";
                          const comparisonBase =
                            cellComparisonBaseByCell[model.rowKey] || "pre_avg";
                          const isBaselineExpanded = Boolean(
                            expandedBaselineByCell[model.rowKey],
                          );
                          const baselineRows = model.metricRows.map((metric) => {
                            const preAvg = Number(
                              model.preAverageByMetric?.[metric.metricKey],
                            );
                            const postAvg = Number(
                              model.postAverageByMetric?.[metric.metricKey],
                            );
                            const cmpValue =
                              comparisonBase === "threshold"
                                ? Number(metric.thresholdValue)
                                : preAvg;
                            const status = evaluateComparison(
                              postAvg,
                              cmpValue,
                              metric.thresholdOperator,
                            );
                            return {
                              metric,
                              preAvg: Number.isFinite(preAvg) ? preAvg : null,
                              postAvg: Number.isFinite(postAvg) ? postAvg : null,
                              status,
                            };
                          });
                          const visibleBaselineRows = isBaselineExpanded
                            ? baselineRows
                            : baselineRows.slice(0, 5);
                          const hiddenBaselineCount = Math.max(
                            baselineRows.length - visibleBaselineRows.length,
                            0,
                          );
                          return (
                            <div
                              key={`heatmap-${model.rowKey}`}
                              className="rounded-xl border border-gray-200 bg-white p-4"
                            >
                              <div className="flex flex-wrap items-center justify-between gap-3">
                                <div>
                                  <p className="text-sm font-semibold text-gray-800">
                                    {model.row.value || model.row.meta?.shortName}
                                  </p>
                                  <p className="text-xs text-gray-500">
                                    Short Name: {model.row.meta?.shortName || "N/A"}
                                  </p>
                                  <p className="text-xs text-gray-500">
                                    Cell ID:{" "}
                                    {model.row.meta?.cellId || model.row.value}
                                  </p>
                                </div>
                                <div className="flex flex-wrap items-center gap-2">
                                  <div className="inline-flex rounded-lg border border-gray-200 bg-white p-1">
                                    <button
                                      type="button"
                                      onClick={() =>
                                        setCellHeatModeByCell((prev) => ({
                                          ...prev,
                                          [model.rowKey]: "average",
                                        }))
                                      }
                                      className={`px-3 py-1 text-xs font-semibold rounded-md ${
                                        perCellMode === "average"
                                          ? "bg-indigo-600 text-white"
                                          : "text-gray-600 hover:bg-gray-100"
                                      }`}
                                    >
                                      Average
                                    </button>
                                    <button
                                      type="button"
                                      onClick={() =>
                                        setCellHeatModeByCell((prev) => ({
                                          ...prev,
                                          [model.rowKey]: "deviation",
                                        }))
                                      }
                                      className={`px-3 py-1 text-xs font-semibold rounded-md ${
                                        perCellMode === "deviation"
                                          ? "bg-indigo-600 text-white"
                                          : "text-gray-600 hover:bg-gray-100"
                                      }`}
                                    >
                                      Deviation %
                                    </button>
                                  </div>
                                  <div className="inline-flex rounded-lg border border-gray-200 bg-white p-1">
                                    <button
                                      type="button"
                                      onClick={() =>
                                        setCellHeatValueModeByCell((prev) => ({
                                          ...prev,
                                          [model.rowKey]: "none",
                                        }))
                                      }
                                      className={`px-3 py-1 text-xs font-semibold rounded-md ${
                                        perCellValueMode === "none"
                                          ? "bg-gray-800 text-white"
                                          : "text-gray-600 hover:bg-gray-100"
                                      }`}
                                    >
                                      No Values
                                    </button>
                                    <button
                                      type="button"
                                      onClick={() =>
                                        setCellHeatValueModeByCell((prev) => ({
                                          ...prev,
                                          [model.rowKey]: "day_avg",
                                        }))
                                      }
                                      className={`px-3 py-1 text-xs font-semibold rounded-md ${
                                        perCellValueMode === "day_avg"
                                          ? "bg-gray-800 text-white"
                                          : "text-gray-600 hover:bg-gray-100"
                                      }`}
                                    >
                                      Day Average
                                    </button>
                                    <button
                                      type="button"
                                      onClick={() =>
                                        setCellHeatValueModeByCell((prev) => ({
                                          ...prev,
                                          [model.rowKey]: "deviation",
                                        }))
                                      }
                                      className={`px-3 py-1 text-xs font-semibold rounded-md ${
                                        perCellValueMode === "deviation"
                                          ? "bg-gray-800 text-white"
                                          : "text-gray-600 hover:bg-gray-100"
                                      }`}
                                    >
                                      Deviation
                                    </button>
                                  </div>
                                  {filterMode === "pre_post" && (
                                    <div className="inline-flex rounded-lg border border-gray-200 bg-white p-1">
                                      <button
                                        type="button"
                                        onClick={() =>
                                          setCellComparisonBaseByCell((prev) => ({
                                            ...prev,
                                            [model.rowKey]: "pre_avg",
                                          }))
                                        }
                                        className={`px-3 py-1 text-xs font-semibold rounded-md ${
                                          comparisonBase === "pre_avg"
                                            ? "bg-amber-600 text-white"
                                            : "text-gray-600 hover:bg-gray-100"
                                        }`}
                                      >
                                        Pre Avg Base
                                      </button>
                                      <button
                                        type="button"
                                        onClick={() =>
                                          setCellComparisonBaseByCell((prev) => ({
                                            ...prev,
                                            [model.rowKey]: "threshold",
                                          }))
                                        }
                                        className={`px-3 py-1 text-xs font-semibold rounded-md ${
                                          comparisonBase === "threshold"
                                            ? "bg-amber-600 text-white"
                                            : "text-gray-600 hover:bg-gray-100"
                                        }`}
                                      >
                                        Threshold Base
                                      </button>
                                    </div>
                                  )}
                                  <button
                                    type="button"
                                    onClick={() => handleExportCellHeatmapPdf(model)}
                                    disabled={exportingHeatmapPdfKey === model.rowKey}
                                    className="inline-flex items-center gap-2 rounded-lg border border-gray-200 px-3 py-1 text-xs font-semibold text-gray-700 hover:bg-gray-50 disabled:bg-gray-100 disabled:text-gray-400"
                                  >
                                    <Download className="w-3.5 h-3.5" />
                                    {exportingHeatmapPdfKey === model.rowKey
                                      ? "Exporting..."
                                      : "Export PDF"}
                                  </button>
                                </div>
                              </div>

                              {filterMode === "pre_post" && (
                                <div className="mt-3 space-y-2">
                                  <div className="rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2">
                                    <p className="text-[11px] font-semibold text-emerald-800">
                                      Pre Range Baseline (
                                      {formatRangeText(
                                        compareRanges.preStart,
                                        compareRanges.preEnd,
                                      )}
                                      )
                                    </p>
                                    <div className="mt-2 flex flex-wrap gap-2">
                                      {visibleBaselineRows.map((item) => (
                                        <span
                                          key={`pre-baseline-${model.rowKey}-${item.metric.metricKey}`}
                                          className="inline-flex items-center gap-1 rounded-md border border-emerald-200 bg-white px-2 py-1 text-[10px] font-semibold text-emerald-800"
                                        >
                                          {item.metric.metricLabel}:{" "}
                                          {formatMetricNumber(item.preAvg)}
                                        </span>
                                      ))}
                                    </div>
                                  </div>
                                  <div className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2">
                                    <p className="text-[11px] font-semibold text-amber-800">
                                      Post Range Baseline (
                                      {formatRangeText(
                                        compareRanges.postStart,
                                        compareRanges.postEnd,
                                      )}
                                      )
                                    </p>
                                    <div className="mt-2 flex flex-wrap gap-2">
                                      {visibleBaselineRows.map((item) => (
                                        <span
                                          key={`post-baseline-${model.rowKey}-${item.metric.metricKey}`}
                                          className={`inline-flex items-center gap-1 rounded-md border px-2 py-1 text-[10px] font-semibold ${
                                            item.status === true
                                              ? "border-emerald-200 bg-emerald-50 text-emerald-800"
                                              : item.status === false
                                                ? "border-rose-200 bg-rose-50 text-rose-800"
                                                : "border-gray-200 bg-white text-gray-700"
                                          }`}
                                        >
                                          {item.metric.metricLabel}:{" "}
                                          {formatMetricNumber(item.postAvg)}
                                        </span>
                                      ))}
                                      {hiddenBaselineCount > 0 && (
                                        <button
                                          type="button"
                                          onClick={() =>
                                            toggleBaselineExpansion(model.rowKey)
                                          }
                                          className="inline-flex items-center rounded-md border border-amber-200 bg-white px-2 py-1 text-[10px] font-semibold text-amber-700 hover:bg-amber-100"
                                        >
                                          {isBaselineExpanded
                                            ? "Show less"
                                            : `Show ${hiddenBaselineCount} more`}
                                        </button>
                                      )}
                                    </div>
                                  </div>
                                </div>
                              )}

                              <div className="mt-3 overflow-auto rounded-lg border border-gray-100">
                                <div
                                  id={`cell-heatmap-canvas-${model.rowKey}`}
                                  className="grid"
                                  style={{
                                    gridTemplateColumns: `220px repeat(${model.dateList.length}, minmax(72px, 1fr))`,
                                  }}
                                >
                                  <div className="sticky left-0 z-20 bg-gray-900 px-3 py-2 text-[11px] font-semibold text-gray-200">
                                    KPI / Date
                                  </div>
                                  {model.dateList.map((date, dateIndex) => {
                                    const hasDivider =
                                      model.dividerAfterIndex >= 0 &&
                                      dateIndex === model.dividerAfterIndex + 1;
                                    return (
                                      <div
                                        key={`cell-h-x-${model.rowKey}-${date}`}
                                        className="bg-gray-900 px-2 py-2 text-center text-[11px] font-semibold text-white"
                                        style={{
                                          borderLeft: hasDivider
                                            ? "2px dashed #0f172a"
                                            : undefined,
                                        }}
                                      >
                                        {date}
                                      </div>
                                    );
                                  })}

                                  {model.matrix.map((metricRow) => (
                                    <React.Fragment
                                      key={`cell-h-row-${model.rowKey}-${metricRow.metric.metricKey}`}
                                    >
                                      <div className="sticky left-0 z-10 bg-white px-3 py-2 text-[11px] font-semibold text-gray-700 border-t border-gray-100">
                                        {metricRow.metric.metricLabel}
                                      </div>
                                      {metricRow.values.map((value, dateIndex) => {
                                        const baselineValue =
                                          comparisonBase === "threshold"
                                            ? value.thresholdValue
                                            : value.preAverageValue;
                                        const bgColor =
                                          perCellMode === "deviation"
                                            ? getDeviationHeatColor(
                                                value.deviationBadnessPct,
                                                model.maxAbsBadness,
                                                value.isMissing,
                                              )
                                            : filterMode === "pre_post"
                                              ? getDirectionalAverageHeatColor(
                                                  value.averageValue,
                                                  baselineValue,
                                                  value.thresholdOperator,
                                                  model.maxAbsAverageDeltaPct,
                                                  value.isMissing,
                                                )
                                              : getAverageHeatColor(
                                                value.averageValue,
                                                null,
                                                null,
                                                value.isMissing,
                                                );

                                        const deviationFromPre = Number.isFinite(
                                          Number(value.averageValue),
                                        ) &&
                                          Number.isFinite(Number(value.preAverageValue))
                                          ? (() => {
                                              const base = Math.abs(
                                                Number(value.preAverageValue),
                                              ) > 1e-9
                                                ? Math.abs(
                                                    Number(value.preAverageValue),
                                                  )
                                                : 1;
                                              return (
                                                ((Number(value.averageValue) -
                                                  Number(value.preAverageValue)) /
                                                  base) *
                                                100
                                              );
                                            })()
                                          : null;

                                        const tooltipText = value.isMissing
                                          ? `${value.metricLabel} on ${value.date}: NA`
                                          : `${value.metricLabel} on ${value.date}\nAvg: ${formatMetricNumber(
                                              value.averageValue,
                                            )}\nThreshold: ${value.thresholdOperator} ${formatMetricNumber(
                                              value.thresholdValue,
                                            )}\nPre Avg: ${formatMetricNumber(
                                              value.preAverageValue,
                                            )}\nDeviation(Thr): ${formatMetricNumber(
                                              value.deviationBadnessPct,
                                            )}%\nDeviation(Pre): ${formatMetricNumber(
                                              deviationFromPre,
                                            )}%`;

                                        const cellLabel =
                                          perCellValueMode === "none"
                                            ? ""
                                            : perCellValueMode === "deviation"
                                              ? `${formatMetricNumber(
                                                  deviationFromPre,
                                                )}%`
                                              : formatMetricNumber(
                                                  value.averageValue,
                                                );
                                        const hasDivider =
                                          model.dividerAfterIndex >= 0 &&
                                          dateIndex === model.dividerAfterIndex + 1;

                                        return (
                                          <div
                                            key={`cell-h-cell-${model.rowKey}-${value.metricKey}-${value.date}`}
                                            className="h-9 border-l border-t border-gray-100 px-1 text-[10px] font-semibold text-slate-800 flex items-center justify-center"
                                            style={{ background: bgColor }}
                                            title={tooltipText}
                                          >
                                            <div
                                              style={{
                                                width: "100%",
                                                height: "100%",
                                                display: "flex",
                                                alignItems: "center",
                                                justifyContent: "center",
                                                borderLeft: hasDivider
                                                  ? "2px dashed #0f172a"
                                                  : undefined,
                                              }}
                                            >
                                              {cellLabel}
                                            </div>
                                          </div>
                                        );
                                      })}
                                    </React.Fragment>
                                  ))}
                                </div>
                              </div>

                              <div className="mt-2 text-[11px] text-gray-600">
                                {perCellMode === "deviation"
                                  ? "Deviation mode: red indicates worse direction and green indicates better direction based on each KPI threshold /operator."
                                  : filterMode === "pre_post"
                                    ? "Average mode: directional green/red based on selected comparison base and KPI operator."
                                    : "Average mode: darker shade indicates higher average value within each KPI row."}
                              </div>
                            </div>
                          );
                        })}
                      </div>
                    )}
                  </div>
                )
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
                          {filterMode === "date_range"
                            ? "Threshold"
                            : "Operator"}
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
                        <tr
                          key={row.metricKey}
                          className="border-t border-gray-100 align-top"
                        >
                          <td className="px-4 py-3 font-semibold text-gray-800">
                            {row.metricLabel}
                          </td>
                          <td className="px-4 py-3">
                            {filterMode === "date_range" ? (
                              (() => {
                                const hasThresholdKey = Boolean(
                                  row.thresholdKey,
                                );
                                const isRowSaving =
                                  hasThresholdKey &&
                                  updatingThresholdKey === row.thresholdKey;

                                return (
                                  <div className="flex items-center gap-2">
                                    <span className="inline-flex px-2 py-1 rounded-md bg-amber-50 border border-amber-200 text-amber-700 text-xs font-semibold">
                                      {hasThresholdKey
                                        ? row.thresholdOperator
                                        : "--"}
                                    </span>
                                    <input
                                      type="number"
                                      step="any"
                                      value={
                                        hasThresholdKey
                                          ? (thresholdDrafts?.[
                                              row.thresholdKey
                                            ] ??
                                            (typeof row.thresholdValue ===
                                            "number"
                                              ? String(row.thresholdValue)
                                              : ""))
                                          : ""
                                      }
                                      onChange={(e) =>
                                        handleThresholdDraftChange(
                                          row.thresholdKey,
                                          e.target.value,
                                        )
                                      }
                                      disabled={!hasThresholdKey || isRowSaving}
                                      placeholder={
                                        hasThresholdKey
                                          ? "Threshold"
                                          : "No threshold"
                                      }
                                      className="w-24 border border-gray-200 rounded-md px-2 py-1 bg-white text-xs font-semibold text-gray-700 disabled:bg-gray-100 disabled:text-gray-400"
                                    />
                                    <button
                                      type="button"
                                      onClick={() =>
                                        updateThresholdFromWorstCell(row)
                                      }
                                      disabled={
                                        !hasThresholdKey ||
                                        Boolean(updatingThresholdKey)
                                      }
                                      className={`px-2 py-1 rounded-md text-xs font-semibold ${
                                        !hasThresholdKey ||
                                        Boolean(updatingThresholdKey)
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
                                    handleOperatorChange(
                                      row.metricKey,
                                      e.target.value,
                                    )
                                  }
                                  disabled={Boolean(
                                    updatingMetricKeys[row.metricKey],
                                  )}
                                  className="border border-gray-200 rounded-md px-2 py-1 bg-white text-xs font-semibold disabled:bg-gray-100 disabled:text-gray-400"
                                >
                                  {OPERATOR_OPTIONS.map((option) => (
                                    <option
                                      key={`${row.metricKey}-${option}`}
                                      value={option}
                                    >
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
            </>
          )}
        </div>

        <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-4 text-xs text-gray-500 flex items-center gap-2">
          <Calendar className="w-4 h-4" />
          {isCellBadDaysView ? (
            <span>
              Cell Bad Days format marks a day as bad when any KPI fails
              threshold. It shows both unique degraded KPIs and total KPI
              degradation hits.
            </span>
          ) : filterMode === "date_range" ? (
            <span>
              Date Range mode compares cell averages against dashboard
              thresholds.
            </span>
          ) : (
            <span>
              Operators on this page are independent from KPI View. Default rule
              is{" "}
              <span className="font-semibold text-gray-700">
                Post &gt;= Pre
              </span>
              .
            </span>
          )}
        </div>
      </div>
    </div>
  );
}

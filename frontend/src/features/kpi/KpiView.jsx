import React, {
  useContext,
  useEffect,
  useState,
  useMemo,
  useCallback,
  useRef,
} from "react";
import {
  fetchKpiUploadHistory,
  fetchDynamicKpiBatchData,
  fetchDynamicKpiColumns,
  updateDynamicKpiSelection,
  downloadDynamicKpiReport,
} from "./kpiService";

import { Card, CardHeader, CardTitle, CardContent } from "./components/card";
import {
  Loader2,
  X,
  Search,
  Filter,
  TrendingUp,
  BarChart3,
  Calendar,
  MapPin,
  Activity,
  ChevronDown,
  Zap,
  Eye,
  EyeOff,
  Download,
} from "lucide-react";
import {
  ResponsiveContainer,
  LineChart,
  Line,
  BarChart,
  Bar,
  AreaChart,
  Area,
  Tooltip,
  XAxis,
  YAxis,
  CartesianGrid,
  ReferenceLine,
} from "recharts";
import UserContext from "../../context/fileContext";

// Helper to create default KPI state
const createKpiState = () => ({
  data: [],
  loading: true,
  kpiName: "",
  statistics: null,
});

// Helper function to format KPI names (replace underscores with spaces)
const formatKpiName = (name) => {
  if (!name) return "";
  return name.replace(/_/g, " ");
};

const formatUploadDateTime = (value) => {
  if (!value) return "Unknown time";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "Unknown time";
  return date.toLocaleString("en-US", {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
};

const FILTER_FIELD_PREFERENCES = [
  { key: "tech", label: "Technology" },
  { key: "band", label: "Band" },
  { key: "groups", label: "Groups / Region" },
  { key: "site", label: "Site" },
  { key: "sectorname", label: "Sector" },
  { key: "sectorid", label: "Sector ID" },
  { key: "cellname", label: "Cell Name" },
  { key: "cellName", label: "Cell Name" },
  { key: "date", label: "Date" },
];

const EXCLUDED_FILTER_KEYS = new Set(["value", "index"]);

const KPI_COMPARISON_OPERATORS = [
  { value: ">", label: "Post > Pre" },
  { value: ">=", label: "Post >= Pre" },
  { value: "<", label: "Post < Pre" },
  { value: "<=", label: "Post <= Pre" },
];

const DEFAULT_KPI_COMPARISON_OPERATOR = ">=";
const KPI_VIEW_PAGE_STATE_KEY = "kpiViewPageState:v1";
const createKpiViewRuntimeCache = () => ({
  fileOptions: [],
  kpisByFileId: new Map(),
  columnsByFileId: new Map(),
  pendingFileOptionsPromise: null,
  pendingKpisByFileId: new Map(),
  pendingColumnsByFileId: new Map(),
});
let kpiViewRuntimeCache = createKpiViewRuntimeCache();

const getKpiViewRuntimeCache = () => {
  if (!kpiViewRuntimeCache || typeof kpiViewRuntimeCache !== "object") {
    kpiViewRuntimeCache = createKpiViewRuntimeCache();
  }
  if (!(kpiViewRuntimeCache.kpisByFileId instanceof Map)) {
    kpiViewRuntimeCache.kpisByFileId = new Map();
  }
  if (!(kpiViewRuntimeCache.columnsByFileId instanceof Map)) {
    kpiViewRuntimeCache.columnsByFileId = new Map();
  }
  if (!(kpiViewRuntimeCache.pendingKpisByFileId instanceof Map)) {
    kpiViewRuntimeCache.pendingKpisByFileId = new Map();
  }
  if (!(kpiViewRuntimeCache.pendingColumnsByFileId instanceof Map)) {
    kpiViewRuntimeCache.pendingColumnsByFileId = new Map();
  }
  if (!Array.isArray(kpiViewRuntimeCache.fileOptions)) {
    kpiViewRuntimeCache.fileOptions = [];
  }
  return kpiViewRuntimeCache;
};

const formatFilterLabel = (key) => {
  const preferred = FILTER_FIELD_PREFERENCES.find((item) => item.key === key);
  if (preferred) return preferred.label;

  return key
    .replace(/[_-]+/g, " ")
    .replace(/([a-z])([A-Z])/g, "$1 $2")
    .replace(/\s+/g, " ")
    .trim()
    .replace(/\b\w/g, (char) => char.toUpperCase());
};

const toFilterValue = (value) => {
  if (value === null || value === undefined) return "";
  return String(value).trim();
};

const canonicalizeFilterKey = (key) =>
  String(key || "")
    .toLowerCase()
    .replace(/[^a-z0-9]/g, "");

const readRowValueByFilterKey = (row, key) => {
  if (!row || !key) return undefined;
  if (Object.prototype.hasOwnProperty.call(row, key)) return row[key];

  const target = canonicalizeFilterKey(key);
  const matchEntry = Object.entries(row).find(([rowKey]) => {
    return canonicalizeFilterKey(rowKey) === target;
  });
  return matchEntry ? matchEntry[1] : undefined;
};

const findMatchingRowKey = (rows, targetKey) => {
  const target = canonicalizeFilterKey(targetKey);
  for (let i = 0; i < rows.length; i += 1) {
    const row = rows[i];
    if (!row || typeof row !== "object") continue;
    const match = Object.keys(row).find(
      (rowKey) => canonicalizeFilterKey(rowKey) === target,
    );
    if (match) return match;
  }
  return null;
};

const isKpiDebugEnabled = () => {
  if (!import.meta.env.DEV || typeof window === "undefined") return false;
  return window.localStorage.getItem("kpiDebug") === "1";
};

const kpiDebugLog = (...args) => {
  if (isKpiDebugEnabled()) {
    console.info(...args);
  }
};

const evaluateComparisonOperator = (postValue, preValue, operator) => {
  const post = Number(postValue);
  const pre = Number(preValue);
  if (Number.isNaN(post) || Number.isNaN(pre)) return null;

  switch (operator) {
    case ">":
      return post > pre;
    case ">=":
      return post >= pre;
    case "<":
      return post < pre;
    case "<=":
      return post <= pre;
    default:
      return post >= pre;
  }
};

export default function KpiView() {
  const hasHydratedRef = useRef(false);
  const { selectedFileId, setSelectedFileId } = useContext(UserContext);
  const [kpiFileOptions, setKpiFileOptions] = useState([]);
  const [kpiColumns, setKpiColumns] = useState([]);
  const [selectedKpiColumnKeys, setSelectedKpiColumnKeys] = useState([]);
  const [selectedFilterColumnKeys, setSelectedFilterColumnKeys] = useState([]);
  const [metricConfigOpen, setMetricConfigOpen] = useState(false);
  const [filterConfigOpen, setFilterConfigOpen] = useState(false);
  const [savingMetricSelection, setSavingMetricSelection] = useState(false);
  const [savingFilterSelection, setSavingFilterSelection] = useState(false);
  const [downloadingReport, setDownloadingReport] = useState(false);
  const [generatingPdfReport, setGeneratingPdfReport] = useState(false);
  const [isLoadingKpis, setIsLoadingKpis] = useState(false);
  const [globalXAxisType, setGlobalXAxisType] = useState("");
  const [globalXAxisApplyVersion, setGlobalXAxisApplyVersion] = useState(0);
  const [performanceMode, setPerformanceMode] = useState(true);
  const [kpiComparisonOperators, setKpiComparisonOperators] = useState({});
  // const [loading, setLoading] = useState(false);

  const [filters, setFilters] = useState({});
  const [searchTerms, setSearchTerms] = useState({});

  const [pendingComparisonRanges, setPendingComparisonRanges] = useState({
    preStart: "",
    preEnd: "",
    postStart: "",
    postEnd: "",
  });

  const [appliedComparisonRanges, setAppliedComparisonRanges] = useState({
    preStart: "",
    preEnd: "",
    postStart: "",
    postEnd: "",
  });

  const [kpis, setKpis] = useState([]);
  const selectedKpiFileId = String(selectedFileId || "");
  const setSelectedKpiFileId = setSelectedFileId;

  useEffect(() => {
    if (hasHydratedRef.current) return;
    hasHydratedRef.current = true;
    if (typeof window === "undefined") return;
    try {
      const raw = window.localStorage.getItem(KPI_VIEW_PAGE_STATE_KEY);
      if (!raw) return;
      const saved = JSON.parse(raw);
      if (!saved || typeof saved !== "object") return;

      setSelectedKpiColumnKeys(
        Array.isArray(saved.selectedKpiColumnKeys)
          ? saved.selectedKpiColumnKeys
          : [],
      );
      setSelectedFilterColumnKeys(
        Array.isArray(saved.selectedFilterColumnKeys)
          ? saved.selectedFilterColumnKeys
          : [],
      );
      setMetricConfigOpen(Boolean(saved.metricConfigOpen));
      setFilterConfigOpen(Boolean(saved.filterConfigOpen));
      setGlobalXAxisType(saved.globalXAxisType || "");
      setPerformanceMode(saved.performanceMode !== false);
      setKpiComparisonOperators(saved.kpiComparisonOperators || {});
      setFilters(saved.filters || {});
      setSearchTerms(saved.searchTerms || {});
      setPendingComparisonRanges(
        saved.pendingComparisonRanges || {
          preStart: "",
          preEnd: "",
          postStart: "",
          postEnd: "",
        },
      );
      setAppliedComparisonRanges(
        saved.appliedComparisonRanges || {
          preStart: "",
          preEnd: "",
          postStart: "",
          postEnd: "",
        },
      );
    } catch {
      // ignore invalid persisted state
    }
  }, []);

  useEffect(() => {
    if (typeof window === "undefined") return;
    try {
      window.localStorage.setItem(
        KPI_VIEW_PAGE_STATE_KEY,
        JSON.stringify({
          selectedKpiColumnKeys,
          selectedFilterColumnKeys,
          metricConfigOpen,
          filterConfigOpen,
          globalXAxisType,
          performanceMode,
          kpiComparisonOperators,
          filters,
          searchTerms,
          pendingComparisonRanges,
          appliedComparisonRanges,
        }),
      );
    } catch {
      // ignore storage full / serialization failures
    }
  }, [
    selectedKpiColumnKeys,
    selectedFilterColumnKeys,
    metricConfigOpen,
    filterConfigOpen,
    globalXAxisType,
    performanceMode,
    kpiComparisonOperators,
    filters,
    searchTerms,
    pendingComparisonRanges,
    appliedComparisonRanges,
  ]);

  useEffect(() => {
    const loadKpiUploads = async () => {
      const runtimeCache = getKpiViewRuntimeCache();
      if (runtimeCache.fileOptions.length) {
        setKpiFileOptions(runtimeCache.fileOptions);
        if (runtimeCache.fileOptions.length > 0) {
          setSelectedKpiFileId((prev) => prev || String(runtimeCache.fileOptions[0].id));
        }
        return;
      }

      if (!runtimeCache.pendingFileOptionsPromise) {
        runtimeCache.pendingFileOptionsPromise = fetchKpiUploadHistory()
          .then((response) => {
            const uploads = response?.success ? response.data || [] : [];
            runtimeCache.fileOptions = Array.isArray(uploads) ? uploads : [];
            runtimeCache.pendingFileOptionsPromise = null;
            return runtimeCache.fileOptions;
          })
          .catch((error) => {
            runtimeCache.pendingFileOptionsPromise = null;
            throw error;
          });
      }

      const uploads = await runtimeCache.pendingFileOptionsPromise;
      setKpiFileOptions(uploads);
      if (uploads.length > 0) {
        setSelectedKpiFileId((prev) => prev || String(uploads[0].id));
      }
    };

    loadKpiUploads();
  }, []);

  const loadDynamicKpis = useCallback(async () => {
    if (!selectedKpiFileId) {
      setKpis([]);
      setIsLoadingKpis(false);
      return;
    }

    const runtimeCache = getKpiViewRuntimeCache();
    const cacheKey = String(selectedKpiFileId);
    if (runtimeCache.kpisByFileId.has(cacheKey)) {
      setKpis(runtimeCache.kpisByFileId.get(cacheKey) || []);
      setIsLoadingKpis(false);
      return;
    }

    setIsLoadingKpis(true);
    try {
      if (!runtimeCache.pendingKpisByFileId.has(cacheKey)) {
        const requestPromise = fetchDynamicKpiBatchData(selectedKpiFileId)
          .then((batchResponse) => {
            const metrics = batchResponse?.success ? batchResponse.data || [] : [];
            kpiDebugLog("[KPI DEBUG][UI] batch metric data", {
              fileId: selectedKpiFileId,
              metricsCount: metrics.length,
              responses: metrics.map((metric) => ({
                metricKey: metric.key || metric.metricKey,
                points: Array.isArray(metric?.data) ? metric.data.length : 0,
              })),
            });

            const nextKpis = metrics.map((metric, index) => {
              const metricKey = metric.key || metric.metricKey;
              return {
                metricKey,
                kpiName:
                  formatKpiName(metric?.kpi || metric.label || metricKey) ||
                  `KPI ${index + 1}`,
                data: Array.isArray(metric?.data) ? metric.data : [],
                loading: false,
                statistics: metric?.statistics || null,
              };
            });

            runtimeCache.kpisByFileId.set(cacheKey, nextKpis);
            runtimeCache.pendingKpisByFileId.delete(cacheKey);
            return { metrics, nextKpis };
          })
          .catch((error) => {
            runtimeCache.pendingKpisByFileId.delete(cacheKey);
            throw error;
          });
        runtimeCache.pendingKpisByFileId.set(cacheKey, requestPromise);
      }

      const { metrics, nextKpis } =
        await runtimeCache.pendingKpisByFileId.get(cacheKey);

      if (!metrics.length) {
        setKpis([]);
        return;
      }

      // Preserve existing per-KPI rule operators across refreshes/date-range applies.
      // Only initialize missing metric keys with default operator.
      setKpiComparisonOperators((prev) => {
        const next = { ...(prev || {}) };
        metrics.forEach((metric) => {
          const key = String(metric?.key || metric?.metricKey || "").trim();
          if (!key) return;
          if (!next[key]) {
            next[key] = DEFAULT_KPI_COMPARISON_OPERATOR;
          }
        });
        return next;
      });

      setKpis(nextKpis);
    } finally {
      setIsLoadingKpis(false);
    }
  }, [selectedKpiFileId]);

  useEffect(() => {
    loadDynamicKpis();
  }, [loadDynamicKpis]);

  useEffect(() => {
    if (!selectedKpiFileId) {
      setKpiComparisonOperators({});
      return;
    }

    try {
      const raw = window.localStorage.getItem(
        `kpiComparisonOperators:${selectedKpiFileId}`,
      );
      const parsed = raw ? JSON.parse(raw) : {};
      setKpiComparisonOperators(
        parsed && typeof parsed === "object" ? parsed : {},
      );
      kpiDebugLog("[KPI DEBUG][UI] loaded KPI comparison operators", {
        fileId: selectedKpiFileId,
        operators: parsed,
      });
    } catch (error) {
      console.warn("Failed to load KPI comparison operators", error);
      setKpiComparisonOperators({});
    }
  }, [selectedKpiFileId]);

  useEffect(() => {
    if (!selectedKpiFileId) return;
    try {
      window.localStorage.setItem(
        `kpiComparisonOperators:${selectedKpiFileId}`,
        JSON.stringify(kpiComparisonOperators || {}),
      );
    } catch (error) {
      console.warn("Failed to persist KPI comparison operators", error);
    }
  }, [selectedKpiFileId, kpiComparisonOperators]);

  const syncColumnConfig = useCallback(async (fileId) => {
    if (!fileId) {
      setKpiColumns([]);
      setSelectedKpiColumnKeys([]);
      setSelectedFilterColumnKeys([]);
      return;
    }

    const runtimeCache = getKpiViewRuntimeCache();
    const cacheKey = String(fileId);

    if (!runtimeCache.columnsByFileId.has(cacheKey)) {
      if (!runtimeCache.pendingColumnsByFileId.has(cacheKey)) {
        const requestPromise = fetchDynamicKpiColumns(fileId)
          .then((response) => {
            const nextValue = response?.success
              ? {
                  columns: Array.isArray(response.columns) ? response.columns : [],
                  selectedMetricKeys: Array.isArray(response.selectedMetricKeys)
                    ? response.selectedMetricKeys
                    : [],
                  selectedFilterKeys: Array.isArray(response.selectedFilterKeys)
                    ? response.selectedFilterKeys
                    : [],
                }
              : null;
            if (nextValue) {
              runtimeCache.columnsByFileId.set(cacheKey, nextValue);
            }
            runtimeCache.pendingColumnsByFileId.delete(cacheKey);
            return response;
          })
          .catch((error) => {
            runtimeCache.pendingColumnsByFileId.delete(cacheKey);
            throw error;
          });
        runtimeCache.pendingColumnsByFileId.set(cacheKey, requestPromise);
      }
      await runtimeCache.pendingColumnsByFileId.get(cacheKey);
    }

    const cachedColumns = runtimeCache.columnsByFileId.get(cacheKey);
    const response = cachedColumns
      ? { success: true, ...cachedColumns }
      : await fetchDynamicKpiColumns(fileId);
    if (!response?.success) {
      kpiDebugLog("[KPI DEBUG][UI] columns config load failed", {
        fileId,
        response,
      });
      setKpiColumns([]);
      setSelectedKpiColumnKeys([]);
      setSelectedFilterColumnKeys([]);
      return;
    }

    setKpiColumns(Array.isArray(response.columns) ? response.columns : []);
    setSelectedKpiColumnKeys(
      Array.isArray(response.selectedMetricKeys)
        ? response.selectedMetricKeys
        : [],
    );
    setSelectedFilterColumnKeys(
      Array.isArray(response.selectedFilterKeys)
        ? response.selectedFilterKeys
        : [],
    );
    kpiDebugLog("[KPI DEBUG][UI] columns config loaded", {
      fileId,
      columnsCount: Array.isArray(response.columns)
        ? response.columns.length
        : 0,
      columnKeysPreview: Array.isArray(response.columns)
        ? response.columns.slice(0, 20).map((col) => col?.key)
        : [],
      selectedMetricKeys: Array.isArray(response.selectedMetricKeys)
        ? response.selectedMetricKeys
        : [],
      selectedFilterKeys: Array.isArray(response.selectedFilterKeys)
        ? response.selectedFilterKeys
        : [],
    });
  }, []);

  useEffect(() => {
    syncColumnConfig(selectedKpiFileId);
  }, [selectedKpiFileId, syncColumnConfig]);

  // Combine all data for filters
  const allData = useMemo(() => kpis.flatMap((k) => k.data), [kpis]);

  const filterOptionsMap = useMemo(() => {
    const optionsByKey = {};

    const candidateKeys = selectedFilterColumnKeys.length
      ? selectedFilterColumnKeys
      : null;

    allData.forEach((row) => {
      const keys = candidateKeys || Object.keys(row || {});
      keys.forEach((key) => {
        const rawValue = readRowValueByFilterKey(row, key);
        if (EXCLUDED_FILTER_KEYS.has(key)) return;
        const value = toFilterValue(rawValue);
        if (!value) return;
        if (!optionsByKey[key]) optionsByKey[key] = new Set();
        optionsByKey[key].add(value);
      });
    });

    const resolved = Object.fromEntries(
      Object.entries(optionsByKey).map(([key, values]) => [
        key,
        [...values].sort(),
      ]),
    );
    if (candidateKeys?.length) {
      const missingConfiguredKeys = candidateKeys.filter(
        (key) => !resolved[key] || resolved[key].length === 0,
      );
      const keyMatchDiagnostics = candidateKeys.map((key) => ({
        configuredKey: key,
        matchedRowKey: findMatchingRowKey(allData, key),
      }));
      kpiDebugLog("[KPI DEBUG][UI] filter options map", {
        fileId: selectedKpiFileId,
        totalRows: allData.length,
        sampleRowKeys: Object.keys(allData[0] || {}).slice(0, 30),
        configuredFilterKeys: candidateKeys,
        resolvedFilterKeys: Object.keys(resolved),
        missingConfiguredKeys,
        keyMatchDiagnostics,
      });
    }
    return resolved;
  }, [allData, selectedFilterColumnKeys, selectedKpiFileId]);

  const dynamicFilterFields = useMemo(() => {
    const allowedBySelection = selectedFilterColumnKeys.length
      ? new Set(selectedFilterColumnKeys)
      : null;
    const keys = Object.keys(filterOptionsMap)
      .filter((key) =>
        allowedBySelection ? allowedBySelection.has(key) : true,
      )
      .filter((key) => filterOptionsMap[key]?.length > 1);

    const preferredOrder = FILTER_FIELD_PREFERENCES.map((item) => item.key);
    const preferredKeys = preferredOrder.filter((key) => keys.includes(key));
    const remainingKeys = keys
      .filter((key) => !preferredOrder.includes(key))
      .sort();
    const orderedKeys = [...preferredKeys, ...remainingKeys];

    const fields = orderedKeys.map((key) => ({
      key,
      label: formatFilterLabel(key),
      options: filterOptionsMap[key] || [],
    }));
    kpiDebugLog("[KPI DEBUG][UI] dynamic filter fields computed", {
      fileId: selectedKpiFileId,
      selectedFilterColumnKeys,
      renderedFilterKeys: fields.map((field) => field.key),
      renderedFilterOptionCounts: fields.map((field) => ({
        key: field.key,
        optionCount: field.options.length,
      })),
    });
    return fields;
  }, [filterOptionsMap, selectedFilterColumnKeys, selectedKpiFileId]);

  useEffect(() => {
    const allowedKeys = new Set(dynamicFilterFields.map((field) => field.key));

    setFilters((prev) => {
      const next = {};
      Object.entries(prev).forEach(([key, values]) => {
        if (allowedKeys.has(key)) next[key] = values;
      });
      return next;
    });

    setSearchTerms((prev) => {
      const next = {};
      Object.entries(prev).forEach(([key, value]) => {
        if (allowedKeys.has(key)) next[key] = value;
      });
      return next;
    });
  }, [dynamicFilterFields]);

  const activeFilters = useMemo(
    () =>
      Object.entries(filters).filter(
        ([, values]) => Array.isArray(values) && values.length > 0,
      ),
    [filters],
  );

  // Apply multiple filters
  const applyFilters = useCallback(
    (data) => {
      if (!activeFilters.length) return data;

      return data.filter((d) => {
        return activeFilters.every(([key, selectedValues]) =>
          selectedValues.includes(
            toFilterValue(readRowValueByFilterKey(d, key)),
          ),
        );
      });
    },
    [activeFilters],
  );

  const filteredKpis = useMemo(
    () =>
      kpis.map((kpi) => ({
        ...kpi,
        data: applyFilters(kpi.data),
      })),
    [kpis, applyFilters],
  );

  const totalFiltersActive = useMemo(() => {
    const selectedValuesCount = Object.values(filters).reduce(
      (count, values) => count + (Array.isArray(values) ? values.length : 0),
      0,
    );
    return (
      (selectedKpiFileId ? 1 : 0) +
      selectedValuesCount +
      (appliedComparisonRanges.preStart || appliedComparisonRanges.preEnd
        ? 1
        : 0) +
      (appliedComparisonRanges.postStart || appliedComparisonRanges.postEnd
        ? 1
        : 0)
    );
  }, [filters, selectedKpiFileId, appliedComparisonRanges]);

  const toggleKpiColumn = (key) => {
    setSelectedKpiColumnKeys((prev) =>
      prev.includes(key) ? prev.filter((item) => item !== key) : [...prev, key],
    );
  };

  const saveKpiMetricSelection = async () => {
    if (!selectedKpiFileId) return;
    setSavingMetricSelection(true);
    try {
      await updateDynamicKpiSelection(selectedKpiFileId, {
        metricKeys: selectedKpiColumnKeys,
      });
      const runtimeCache = getKpiViewRuntimeCache();
      const cacheKey = String(selectedKpiFileId);
      runtimeCache.kpisByFileId.delete(cacheKey);
      runtimeCache.columnsByFileId.delete(cacheKey);
      runtimeCache.pendingKpisByFileId.delete(cacheKey);
      runtimeCache.pendingColumnsByFileId.delete(cacheKey);
      kpiDebugLog("[KPI DEBUG][UI] saved selection", {
        fileId: selectedKpiFileId,
        selectedMetricKeys: selectedKpiColumnKeys,
      });
      await syncColumnConfig(selectedKpiFileId);
      await loadDynamicKpis();
    } finally {
      setSavingMetricSelection(false);
    }
  };

  const saveFilterColumnSelection = async () => {
    if (!selectedKpiFileId) return;
    setSavingFilterSelection(true);
    try {
      kpiDebugLog("[KPI DEBUG][UI] save filter selection request", {
        fileId: selectedKpiFileId,
        selectedFilterKeys: selectedFilterColumnKeys,
      });
      const saveResponse = await updateDynamicKpiSelection(selectedKpiFileId, {
        filterKeys: selectedFilterColumnKeys,
      });
      const runtimeCache = getKpiViewRuntimeCache();
      const cacheKey = String(selectedKpiFileId);
      runtimeCache.columnsByFileId.delete(cacheKey);
      runtimeCache.pendingColumnsByFileId.delete(cacheKey);
      kpiDebugLog("[KPI DEBUG][UI] saved filter selection", {
        fileId: selectedKpiFileId,
        selectedFilterKeys: selectedFilterColumnKeys,
        saveResponse,
      });
      await syncColumnConfig(selectedKpiFileId);
    } finally {
      setSavingFilterSelection(false);
    }
  };

  const toggleFilterColumn = (key) => {
    setSelectedFilterColumnKeys((prev) =>
      prev.includes(key) ? prev.filter((item) => item !== key) : [...prev, key],
    );
  };

  const toggleFilter = (key, value) => {
    setFilters((prev) => {
      const current = Array.isArray(prev[key]) ? prev[key] : [];
      const selected = current.includes(value)
        ? current.filter((v) => v !== value)
        : [...current, value];
      return { ...prev, [key]: selected };
    });
  };

  const toggleSelectAll = (key, options) => {
    setFilters((prev) => {
      const current = Array.isArray(prev[key]) ? prev[key] : [];
      const allSelected = current.length === options.length;
      return { ...prev, [key]: allSelected ? [] : [...options] };
    });
  };

  const clearFilters = () => {
    setSelectedKpiFileId("");
    setMetricConfigOpen(false);
    setFilterConfigOpen(false);
    setKpiColumns([]);
    setSelectedKpiColumnKeys([]);
    setSelectedFilterColumnKeys([]);
    setFilters({});
    setSearchTerms({});
    const emptyRanges = {
      preStart: "",
      preEnd: "",
      postStart: "",
      postEnd: "",
    };
    setPendingComparisonRanges(emptyRanges);
    setAppliedComparisonRanges(emptyRanges);
  };

  const exportKpiReport = async (exportFormat = "standard") => {
    if (!selectedKpiFileId) return;
    setDownloadingReport(true);
    try {
      const activeFiltersPayload = Object.fromEntries(
        activeFilters.map(([key, values]) => [key, values]),
      );
      const groupByKeysForExport = selectedFilterColumnKeys.length
        ? selectedFilterColumnKeys
        : Object.keys(activeFiltersPayload);
      const payload = {
        metricKeys: selectedKpiColumnKeys,
        groupByKeys: groupByKeysForExport,
        activeFilters: activeFiltersPayload,
        compareRanges: appliedComparisonRanges,
        exportFormat,
      };
      kpiDebugLog("[KPI DEBUG][UI] export report request", {
        fileId: selectedKpiFileId,
        payload,
      });

      const { blob, fileName } = await downloadDynamicKpiReport(
        selectedKpiFileId,
        payload,
      );
      const blobUrl = window.URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = blobUrl;
      link.download = fileName;
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(blobUrl);

      kpiDebugLog("[KPI DEBUG][UI] export report success", {
        fileId: selectedKpiFileId,
        fileName,
        size: blob.size,
      });
    } catch (error) {
      console.error("Failed to export KPI report:", error);
      alert(
        `Failed to export KPI report: ${error?.message || "Unknown error"}`,
      );
    } finally {
      setDownloadingReport(false);
    }
  };

  const exportKpiPdfReport = async () => {
    try {
      setGeneratingPdfReport(true);

      const chartElements = Array.from(
        document.querySelectorAll("[data-kpi-pdf-card='true']"),
      );

      if (!chartElements.length) {
        alert("No KPI charts are available to export.");
        return;
      }

      const [{ toPng }, { jsPDF }] = await Promise.all([
        import("html-to-image"),
        import("jspdf"),
      ]);

      const doc = new jsPDF({
        orientation: "portrait",
        unit: "mm",
        format: "a4",
      });

      const pageWidth = doc.internal.pageSize.getWidth();
      const pageHeight = doc.internal.pageSize.getHeight();
      const margin = 10;
      const contentWidth = pageWidth - margin * 2;
      let cursorY = margin;

      const selectedFilterSummary = Object.entries(filters)
        .flatMap(([key, values]) =>
          (values || []).map((v) => `${formatFilterLabel(key)}: ${v}`),
        )
        .join(" | ");

      const preRangeSummary = hasPreRange
        ? `${appliedComparisonRanges.preStart || "-"} to ${
            appliedComparisonRanges.preEnd || "-"
          }`
        : "Not applied";
      const postRangeSummary = hasPostRange
        ? `${appliedComparisonRanges.postStart || "-"} to ${
            appliedComparisonRanges.postEnd || "-"
          }`
        : "Not applied";

      doc.setFont("helvetica", "bold");
      doc.setFontSize(16);
      doc.text("KPI Analytics Report", margin, cursorY);
      cursorY += 7;

      doc.setFont("helvetica", "normal");
      doc.setFontSize(10);
      doc.text(`Generated: ${new Date().toLocaleString()}`, margin, cursorY);
      cursorY += 5;
      doc.text(
        `File: ${selectedKpiFileLabel || "All KPI uploads"}`,
        margin,
        cursorY,
      );
      cursorY += 5;
      doc.text(
        `Pre Range: ${preRangeSummary} | Post Range: ${postRangeSummary}`,
        margin,
        cursorY,
      );
      cursorY += 5;

      if (selectedFilterSummary) {
        const wrappedFilterLines = doc.splitTextToSize(
          `Selected Filters: ${selectedFilterSummary}`,
          contentWidth,
        );
        doc.text(wrappedFilterLines, margin, cursorY);
        cursorY += wrappedFilterLines.length * 4 + 1;
      } else {
        doc.text("Selected Filters: None", margin, cursorY);
        cursorY += 6;
      }

      for (let i = 0; i < chartElements.length; i += 1) {
        const chartElement = chartElements[i];
        const chartTitle =
          chartElement.getAttribute("data-kpi-title") || `KPI ${i + 1}`;

        const rect = chartElement.getBoundingClientRect();
        if (!rect.width || !rect.height) continue;

        const imageData = await toPng(chartElement, {
          cacheBust: true,
          pixelRatio: 2,
          backgroundColor: "#ffffff",
        });

        const imgWidth = rect.width;
        const imgHeight = rect.height;
        const renderedHeight = (imgHeight * contentWidth) / imgWidth;
        const maxPageImageHeight = pageHeight - margin * 2 - 6;
        const finalImageHeight = Math.min(renderedHeight, maxPageImageHeight);

        if (cursorY + 5 + finalImageHeight > pageHeight - margin) {
          doc.addPage();
          cursorY = margin;
        }

        doc.setFont("helvetica", "bold");
        doc.setFontSize(11);
        doc.text(chartTitle, margin, cursorY);
        cursorY += 4;

        doc.addImage(
          imageData,
          "PNG",
          margin,
          cursorY,
          contentWidth,
          finalImageHeight,
          undefined,
          "FAST",
        );
        cursorY += finalImageHeight + 5;
      }

      const safeNamePart = selectedKpiFileId
        ? String(selectedKpiFileLabel || `file_${selectedKpiFileId}`)
            .replace(/[^a-z0-9]+/gi, "_")
            .replace(/^_+|_+$/g, "")
        : "all_uploads";
      const timestamp = new Date()
        .toISOString()
        .replace(/[:.]/g, "-")
        .slice(0, 19);
      const fileName = `kpi_charts_${safeNamePart}_${timestamp}.pdf`;
      doc.save(fileName);
    } catch (error) {
      console.error("Failed to export KPI PDF report:", error);
      alert(
        `Failed to export KPI PDF report: ${error?.message || "Unknown error"}`,
      );
    } finally {
      setGeneratingPdfReport(false);
    }
  };

  const hasPreRange =
    appliedComparisonRanges.preStart || appliedComparisonRanges.preEnd;
  const hasPostRange =
    appliedComparisonRanges.postStart || appliedComparisonRanges.postEnd;
  const hasPendingDateChanges =
    JSON.stringify(pendingComparisonRanges) !==
    JSON.stringify(appliedComparisonRanges);
  const selectedKpiFile = kpiFileOptions.find(
    (file) => String(file.id) === String(selectedKpiFileId),
  );
  const selectedKpiFileLabel = selectedKpiFile?.fileName || "";
  const hasSelectedFile = Boolean(selectedKpiFileId);

  const visibleKpis = useMemo(() => {
    const indexedKpis = filteredKpis.map((kpi, index) => ({ kpi, index }));

    if (!hasSelectedFile) {
      return [];
    }

    if (performanceMode) {
      return indexedKpis.slice(0, 12);
    }

    return indexedKpis;
  }, [hasSelectedFile, filteredKpis, performanceMode]);

  const isKpiFetchComplete = kpis.every((kpi) => !kpi.loading);
  const noKpisForSelectedFile =
    hasSelectedFile &&
    !isLoadingKpis &&
    isKpiFetchComplete &&
    visibleKpis.length === 0;

  useEffect(() => {
    if (!hasSelectedFile) return;
    kpiDebugLog("[KPI DEBUG][UI] render snapshot", {
      fileId: selectedKpiFileId,
      kpiCards: kpis.length,
      allDataRows: allData.length,
      dynamicFilterFields: dynamicFilterFields.map((field) => field.key),
      activeFilters: Object.fromEntries(
        Object.entries(filters).filter(
          ([, values]) => Array.isArray(values) && values.length > 0,
        ),
      ),
    });
  }, [
    hasSelectedFile,
    selectedKpiFileId,
    kpis.length,
    allData.length,
    dynamicFilterFields,
    filters,
  ]);

  const chartTypes = ["bar", "line", "area"];
  const chartColors = [
    "#2563eb",
    "#16a34a",
    "#ea580c",
    "#7c3aed",
    "#0891b2",
    "#dc2626",
    "#65a30d",
    "#0f766e",
  ];

  const globalXAxisOptions = useMemo(() => {
    const preferred = [
      "cellName",
      "date",
      "tech",
      "band",
      "groups",
      "site",
      "sectorname",
    ];
    const excluded = new Set([
      "value",
      "count",
      "index",
      "__period",
      "__separator",
    ]);
    const availableKeys = new Set();

    allData.forEach((row) => {
      Object.entries(row || {}).forEach(([key, rawValue]) => {
        if (excluded.has(key)) return;
        const value = toFilterValue(rawValue);
        if (!value) return;
        availableKeys.add(key);
      });
    });

    const ordered = [
      ...preferred.filter((key) => availableKeys.has(key)),
      ...[...availableKeys]
        .filter((key) => !preferred.includes(key))
        .sort((a, b) => a.localeCompare(b)),
    ];

    return ordered.map((key) => ({
      value: key,
      label: formatFilterLabel(key),
    }));
  }, [allData]);

  useEffect(() => {
    if (!globalXAxisOptions.length) {
      if (globalXAxisType !== "") setGlobalXAxisType("");
      return;
    }
    const hasCurrent = globalXAxisOptions.some(
      (option) => option.value === globalXAxisType,
    );
    if (!hasCurrent) {
      setGlobalXAxisType(globalXAxisOptions[0].value);
    }
  }, [globalXAxisOptions, globalXAxisType]);

  const applyGlobalXAxisToAllCharts = () => {
    if (!globalXAxisType) return;
    kpiDebugLog("[KPI DEBUG][UI] apply global x-axis", {
      fileId: selectedKpiFileId,
      globalXAxisType,
      cards: visibleKpis.length,
    });
    setGlobalXAxisApplyVersion((prev) => prev + 1);
  };

  const handleKpiComparisonOperatorChange = useCallback(
    (metricKey, operator) => {
      if (!metricKey) return;
      const safeOperator = KPI_COMPARISON_OPERATORS.some(
        (item) => item.value === operator,
      )
        ? operator
        : DEFAULT_KPI_COMPARISON_OPERATOR;

      setKpiComparisonOperators((prev) => ({
        ...prev,
        [metricKey]: safeOperator,
      }));
    },
    [],
  );

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 via-blue-50 to-indigo-50">
      {/* Header */}
      <div className="bg-white/80 backdrop-blur-lg border-b border-gray-200 sticky top-0 z-50">
        <div className="max-w-[1600px] mx-auto px-6 py-2">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-4">
              <div className="p-3 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-xl">
                <BarChart3 className="w-7 h-6 text-white" />
              </div>
              <div>
                <h1 className="text-xl font-semibold bg-gradient-to-r from-gray-800 to-gray-600 bg-clip-text text-transparent">
                  KPI Analytics Dashboard
                </h1>
                <p className="text-base text-gray-500 mt-0.5">
                  Real-time network performance metrics
                </p>
              </div>
            </div>
            <div className="flex items-center gap-3">
              <button
                onClick={() => setPerformanceMode((prev) => !prev)}
                className={`px-4 py-2 rounded-lg text-sm font-semibold border transition-colors ${
                  performanceMode
                    ? "bg-emerald-50 border-emerald-200 text-emerald-700 hover:bg-emerald-100"
                    : "bg-gray-50 border-gray-200 text-gray-600 hover:bg-gray-100"
                }`}
                title="Performance mode limits rendering workload for large KPI files"
              >
                Performance: {performanceMode ? "On" : "Off"}
              </button>
              <div className="bg-blue-50 px-5 py-3 rounded-lg border border-blue-100">
                <p className="text-sm text-gray-500 font-medium">
                  Active Filters
                </p>
                <p className="text-2xl font-bold text-blue-600">
                  {totalFiltersActive}
                </p>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div className="max-w-[1600px] mx-auto px-6 py-6 space-y-6">
        {/* Filter Section */}
        <Card className="border-0 shadow-xl shadow-blue-100/50 bg-white/90 backdrop-blur-sm">
          <CardHeader className="border-b border-gray-100/50">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <Filter className="w-6 h-6 text-blue-600" />
                <CardTitle className="text-xl font-bold text-gray-800">
                  Filters
                </CardTitle>
              </div>
              <div className="flex items-center gap-2">
                {hasSelectedFile && (
                  <button
                    onClick={() => exportKpiReport("standard")}
                    disabled={!hasSelectedFile || downloadingReport}
                    className={`px-5 py-2.5 text-white text-base font-semibold rounded-lg transition-all duration-200 shadow-md hover:shadow-lg inline-flex items-center gap-2 ${
                      downloadingReport
                        ? "bg-emerald-400 cursor-not-allowed"
                        : "bg-gradient-to-r from-emerald-500 to-emerald-600 hover:from-emerald-600 hover:to-emerald-700"
                    }`}
                  >
                    {downloadingReport ? (
                      <Loader2 className="w-4 h-4 animate-spin" />
                    ) : (
                      <Download className="w-4 h-4" />
                    )}
                    Export Excel (Format 1)
                  </button>
                )}
                {hasSelectedFile && (
                  <button
                    onClick={() => exportKpiReport("date_matrix")}
                    disabled={!hasSelectedFile || downloadingReport}
                    className={`px-5 py-2.5 text-white text-base font-semibold rounded-lg transition-all duration-200 shadow-md hover:shadow-lg inline-flex items-center gap-2 ${
                      downloadingReport
                        ? "bg-teal-400 cursor-not-allowed"
                        : "bg-gradient-to-r from-teal-500 to-cyan-600 hover:from-teal-600 hover:to-cyan-700"
                    }`}
                  >
                    {downloadingReport ? (
                      <Loader2 className="w-4 h-4 animate-spin" />
                    ) : (
                      <Download className="w-4 h-4" />
                    )}
                    Export Excel (Format 2)
                  </button>
                )}
                <button
                  onClick={exportKpiPdfReport}
                  disabled={!visibleKpis.length || generatingPdfReport}
                  className={`px-5 py-2.5 text-white text-base font-semibold rounded-lg transition-all duration-200 shadow-md hover:shadow-lg inline-flex items-center gap-2 ${
                    !visibleKpis.length || generatingPdfReport
                      ? "bg-rose-300 cursor-not-allowed"
                      : "bg-gradient-to-r from-rose-500 to-pink-600 hover:from-rose-600 hover:to-pink-700"
                  }`}
                >
                  {generatingPdfReport ? (
                    <Loader2 className="w-4 h-4 animate-spin" />
                  ) : (
                    <Download className="w-4 h-4" />
                  )}
                  Download PDF
                </button>
                {totalFiltersActive > 0 && (
                  <button
                    onClick={clearFilters}
                    className="px-5 py-2.5 bg-gradient-to-r from-blue-500 to-blue-600 text-white text-base font-semibold rounded-lg hover:from-blue-600 hover:to-blue-700 transition-all duration-200 shadow-md hover:shadow-lg"
                  >
                    Clear All ({totalFiltersActive})
                  </button>
                )}
              </div>
            </div>
          </CardHeader>
          <CardContent className="pt-6">
            {/* KPI Upload Selection */}
            <div className="mb-6 p-5 bg-gradient-to-r from-slate-50 to-blue-50 rounded-xl border border-slate-200">
              <div className="flex items-center gap-2 mb-3">
                <Activity className="w-5 h-5 text-blue-600" />
                <h3 className="text-base font-bold text-gray-700">
                  KPI Upload Source
                </h3>
              </div>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3 items-end">
                <div>
                  <label className="text-sm text-gray-600 mb-2 block font-medium">
                    Select Uploaded KPI File
                  </label>
                  <select
                    value={selectedKpiFileId}
                    onChange={(e) => setSelectedKpiFileId(e.target.value)}
                    className="w-full px-4 py-2.5 text-base border border-gray-200 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all bg-white"
                  >
                    <option value="">All KPI uploads</option>
                    {kpiFileOptions.map((file) => (
                      <option key={file.id} value={file.id}>
                        #{file.id} - {file.fileName || "Unnamed file"} |{" "}
                        {file.uploadedBy || "anonymous"} |{" "}
                        {formatUploadDateTime(file.createdAt)}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="flex flex-col gap-2">
                  <p className="text-sm text-gray-500">
                    Selecting a file reloads KPI cards for only that upload.
                  </p>
                  {selectedKpiFileId && (
                    <div className="flex flex-wrap gap-2">
                      <button
                        onClick={() => setMetricConfigOpen((prev) => !prev)}
                        className="self-start px-3 py-2 text-sm font-semibold rounded-lg border border-blue-200 text-blue-700 bg-blue-50 hover:bg-blue-100 transition-colors"
                      >
                        {metricConfigOpen
                          ? "Hide KPI Metrics Config"
                          : "Configure KPI Metrics"}
                      </button>
                      <button
                        onClick={() => setFilterConfigOpen((prev) => !prev)}
                        className="self-start px-3 py-2 text-sm font-semibold rounded-lg border border-indigo-200 text-indigo-700 bg-indigo-50 hover:bg-indigo-100 transition-colors"
                      >
                        {filterConfigOpen
                          ? "Hide Filter Headers Config"
                          : "Configure Filter Headers"}
                      </button>
                    </div>
                  )}
                </div>
              </div>
              {metricConfigOpen && selectedKpiFileId && (
                <div className="mt-4 p-4 bg-white rounded-lg border border-blue-100">
                  <div className="flex items-center justify-between mb-3">
                    <p className="text-sm font-bold text-gray-700">
                      Select which columns should be treated as KPIs
                    </p>
                    <button
                      onClick={saveKpiMetricSelection}
                      disabled={savingMetricSelection}
                      className={`px-3 py-1.5 text-sm font-semibold rounded-lg ${
                        savingMetricSelection
                          ? "bg-gray-200 text-gray-500 cursor-not-allowed"
                          : "bg-blue-600 text-white hover:bg-blue-700"
                      }`}
                    >
                      {savingMetricSelection ? "Saving..." : "Save KPI Metrics"}
                    </button>
                  </div>
                  <div className="max-h-52 overflow-y-auto border border-gray-200 rounded-lg">
                    {kpiColumns.length === 0 ? (
                      <p className="px-3 py-3 text-sm text-gray-500">
                        No columns found.
                      </p>
                    ) : (
                      kpiColumns
                        .filter((column) => !column.isDimension)
                        .map((column) => (
                          <label
                            key={column.key}
                            className="flex items-center justify-between gap-3 px-3 py-2 border-b border-gray-100 last:border-b-0 hover:bg-blue-50"
                          >
                            <div className="flex items-center gap-3">
                              <input
                                type="checkbox"
                                checked={selectedKpiColumnKeys.includes(
                                  column.key,
                                )}
                                onChange={() => toggleKpiColumn(column.key)}
                                className="w-4 h-4"
                              />
                              <span className="text-sm text-gray-700">
                                {column.label}
                              </span>
                            </div>
                            <span className="text-xs text-gray-500">
                              {column.numericRatio}% numeric
                            </span>
                          </label>
                        ))
                    )}
                  </div>
                </div>
              )}

              {filterConfigOpen && selectedKpiFileId && (
                <div className="mt-4 p-4 bg-white rounded-lg border border-indigo-100">
                  <div className="mt-4">
                    <p className="text-sm font-bold text-gray-700 mb-2">
                      Select which headers should be used as filters
                    </p>
                    <p className="text-xs text-gray-500 mb-2">
                      Filter options will be created only from these selected
                      headers.
                    </p>
                    <div className="max-h-52 overflow-y-auto border border-gray-200 rounded-lg">
                      {kpiColumns.length === 0 ? (
                        <p className="px-3 py-3 text-sm text-gray-500">
                          No columns found.
                        </p>
                      ) : (
                        kpiColumns.map((column) => (
                          <label
                            key={`filter-${column.key}`}
                            className="flex items-center justify-between gap-3 px-3 py-2 border-b border-gray-100 last:border-b-0 hover:bg-blue-50"
                          >
                            <div className="flex items-center gap-3">
                              <input
                                type="checkbox"
                                checked={selectedFilterColumnKeys.includes(
                                  column.key,
                                )}
                                onChange={() => toggleFilterColumn(column.key)}
                                className="w-4 h-4"
                              />
                              <span className="text-sm text-gray-700">
                                {column.label}
                              </span>
                            </div>
                            <span className="text-xs text-gray-500">
                              {column.nonEmptyCount} non-empty
                            </span>
                          </label>
                        ))
                      )}
                    </div>
                  </div>
                  <div className="mt-3 flex justify-end">
                    <button
                      onClick={saveFilterColumnSelection}
                      disabled={savingFilterSelection}
                      className={`px-3 py-1.5 text-sm font-semibold rounded-lg ${
                        savingFilterSelection
                          ? "bg-gray-200 text-gray-500 cursor-not-allowed"
                          : "bg-indigo-600 text-white hover:bg-indigo-700"
                      }`}
                    >
                      {savingFilterSelection
                        ? "Saving..."
                        : "Save Filter Headers"}
                    </button>
                  </div>
                </div>
              )}
            </div>

            {/* Pre/Post Date Range Filter */}
            <div className="mb-6 p-5 bg-gradient-to-r from-blue-50 to-indigo-50 rounded-xl border border-blue-100">
              <div className="flex items-center gap-2 mb-4">
                <Calendar className="w-5 h-5 text-blue-600" />
                <h3 className="text-base font-bold text-gray-700">
                  Pre vs Post Date Ranges
                </h3>
              </div>
              <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
                <div className="p-4 bg-white rounded-lg border border-blue-200">
                  <p className="text-sm font-bold text-blue-700 mb-3">
                    Pre Range
                  </p>
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                    <div>
                      <label className="text-sm text-gray-600 mb-2 block font-medium">
                        Start Date
                      </label>
                      <input
                        type="date"
                        value={pendingComparisonRanges.preStart}
                        onChange={(e) =>
                          setPendingComparisonRanges((prev) => ({
                            ...prev,
                            preStart: e.target.value,
                          }))
                        }
                        className="w-full px-4 py-2.5 text-base border border-gray-200 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all bg-white"
                      />
                    </div>
                    <div>
                      <label className="text-sm text-gray-600 mb-2 block font-medium">
                        End Date
                      </label>
                      <input
                        type="date"
                        value={pendingComparisonRanges.preEnd}
                        onChange={(e) =>
                          setPendingComparisonRanges((prev) => ({
                            ...prev,
                            preEnd: e.target.value,
                          }))
                        }
                        className="w-full px-4 py-2.5 text-base border border-gray-200 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all bg-white"
                      />
                    </div>
                  </div>
                </div>
                <div className="p-4 bg-white rounded-lg border border-indigo-200">
                  <p className="text-sm font-bold text-indigo-700 mb-3">
                    Post Range
                  </p>
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                    <div>
                      <label className="text-sm text-gray-600 mb-2 block font-medium">
                        Start Date
                      </label>
                      <input
                        type="date"
                        value={pendingComparisonRanges.postStart}
                        onChange={(e) =>
                          setPendingComparisonRanges((prev) => ({
                            ...prev,
                            postStart: e.target.value,
                          }))
                        }
                        className="w-full px-4 py-2.5 text-base border border-gray-200 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all bg-white"
                      />
                    </div>
                    <div>
                      <label className="text-sm text-gray-600 mb-2 block font-medium">
                        End Date
                      </label>
                      <input
                        type="date"
                        value={pendingComparisonRanges.postEnd}
                        onChange={(e) =>
                          setPendingComparisonRanges((prev) => ({
                            ...prev,
                            postEnd: e.target.value,
                          }))
                        }
                        className="w-full px-4 py-2.5 text-base border border-gray-200 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all bg-white"
                      />
                    </div>
                  </div>
                </div>
              </div>
              <div className="mt-4 flex items-center justify-end">
                <button
                  onClick={() =>
                    setAppliedComparisonRanges({ ...pendingComparisonRanges })
                  }
                  disabled={!hasPendingDateChanges}
                  className={`px-5 py-2.5 text-sm font-semibold rounded-lg transition-all ${
                    hasPendingDateChanges
                      ? "bg-gradient-to-r from-indigo-500 to-blue-600 text-white shadow-md hover:from-indigo-600 hover:to-blue-700"
                      : "bg-gray-200 text-gray-500 cursor-not-allowed"
                  }`}
                >
                  Apply Date Ranges
                </button>
              </div>
            </div>

            {/* Multi-Select Filters */}
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-5 mb-4">
              {dynamicFilterFields.map((field) => (
                <MultiSelect
                  key={field.key}
                  label={field.label}
                  options={field.options}
                  selected={filters[field.key] || []}
                  toggle={(v) => toggleFilter(field.key, v)}
                  toggleSelectAll={() =>
                    toggleSelectAll(field.key, field.options)
                  }
                  searchTerm={searchTerms[field.key] || ""}
                  onSearchChange={(v) =>
                    setSearchTerms((prev) => ({ ...prev, [field.key]: v }))
                  }
                />
              ))}
            </div>

            {/* Active Filters Pills */}
            {totalFiltersActive > 0 && (
              <div className="flex flex-wrap gap-2 pt-4 border-t border-gray-100">
                {hasPreRange && (
                  <span className="inline-flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-purple-50 to-pink-50 text-purple-700 text-sm font-semibold rounded-full border border-purple-200">
                    <Calendar size={14} />
                    Pre:
                    {appliedComparisonRanges.preStart &&
                      ` ${appliedComparisonRanges.preStart}`}
                    {appliedComparisonRanges.preStart &&
                      appliedComparisonRanges.preEnd &&
                      " to "}
                    {appliedComparisonRanges.preEnd &&
                      ` ${appliedComparisonRanges.preEnd}`}
                    <button
                      onClick={() => {
                        setAppliedComparisonRanges((prev) => ({
                          ...prev,
                          preStart: "",
                          preEnd: "",
                        }));
                        setPendingComparisonRanges((prev) => ({
                          ...prev,
                          preStart: "",
                          preEnd: "",
                        }));
                      }}
                      className="ml-1 hover:bg-purple-200 rounded-full p-1 transition-colors"
                    >
                      <X size={14} />
                    </button>
                  </span>
                )}
                {hasPostRange && (
                  <span className="inline-flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-indigo-50 to-blue-50 text-indigo-700 text-sm font-semibold rounded-full border border-indigo-200">
                    <Calendar size={14} />
                    Post:
                    {appliedComparisonRanges.postStart &&
                      ` ${appliedComparisonRanges.postStart}`}
                    {appliedComparisonRanges.postStart &&
                      appliedComparisonRanges.postEnd &&
                      " to "}
                    {appliedComparisonRanges.postEnd &&
                      ` ${appliedComparisonRanges.postEnd}`}
                    <button
                      onClick={() => {
                        setAppliedComparisonRanges((prev) => ({
                          ...prev,
                          postStart: "",
                          postEnd: "",
                        }));
                        setPendingComparisonRanges((prev) => ({
                          ...prev,
                          postStart: "",
                          postEnd: "",
                        }));
                      }}
                      className="ml-1 hover:bg-indigo-200 rounded-full p-1 transition-colors"
                    >
                      <X size={14} />
                    </button>
                  </span>
                )}
                {selectedKpiFileId && (
                  <span className="inline-flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-slate-50 to-gray-50 text-gray-700 text-sm font-semibold rounded-full border border-slate-200">
                    File: {selectedKpiFileLabel || `#${selectedKpiFileId}`} (
                    {selectedKpiFile?.uploadedBy || "anonymous"},{" "}
                    {formatUploadDateTime(selectedKpiFile?.createdAt)})
                    <button
                      onClick={() => setSelectedKpiFileId("")}
                      className="ml-1 hover:bg-slate-200 rounded-full p-1 transition-colors"
                    >
                      <X size={14} />
                    </button>
                  </span>
                )}

                {Object.entries(filters).map(([key, values]) =>
                  (values || []).map((v) => (
                    <span
                      key={key + v}
                      className="inline-flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-blue-50 to-indigo-50 text-blue-700 text-sm font-semibold rounded-full border border-blue-200 hover:border-blue-300 transition-colors"
                    >
                      <span className="capitalize text-gray-500">
                        {formatFilterLabel(key)}:
                      </span>
                      {v}
                      <button
                        onClick={() => toggleFilter(key, v)}
                        className="ml-1 hover:bg-blue-200 rounded-full p-1 transition-colors"
                      >
                        <X size={14} />
                      </button>
                    </span>
                  )),
                )}
              </div>
            )}
          </CardContent>
        </Card>

        {hasSelectedFile && (
          <Card className="border border-indigo-200 bg-indigo-50/60">
            <CardContent className="py-4">
              <div className="flex flex-col gap-3 md:flex-row md:items-end md:justify-between">
                <div>
                  <p className="text-sm font-bold text-indigo-800">
                    Global X-Axis (Apply to All Charts)
                  </p>
                  <p className="text-xs text-indigo-700/80">
                    Bulk-set the X-axis once. Individual chart dropdowns will
                    still work for overrides.
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  <div className="relative">
                    <select
                      value={globalXAxisType}
                      onChange={(e) => setGlobalXAxisType(e.target.value)}
                      className="appearance-none bg-white border border-indigo-200 text-gray-700 py-2 pl-3 pr-8 rounded-lg text-sm font-semibold focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent cursor-pointer hover:border-indigo-300 transition-all min-w-[200px]"
                    >
                      {globalXAxisOptions.map((option) => (
                        <option key={option.value} value={option.value}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                    <ChevronDown className="absolute right-2 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400 pointer-events-none" />
                  </div>
                  <button
                    onClick={applyGlobalXAxisToAllCharts}
                    disabled={!globalXAxisType || !visibleKpis.length}
                    className={`px-4 py-2 rounded-lg text-sm font-semibold text-white transition-all ${
                      !globalXAxisType || !visibleKpis.length
                        ? "bg-indigo-300 cursor-not-allowed"
                        : "bg-gradient-to-r from-indigo-500 to-blue-600 hover:from-indigo-600 hover:to-blue-700"
                    }`}
                  >
                    Apply to All
                  </button>
                </div>
              </div>
            </CardContent>
          </Card>
        )}

        {/* KPI Charts Grid */}
        {hasSelectedFile && isLoadingKpis && (
          <Card className="border border-blue-200 bg-blue-50">
            <CardContent className="py-5">
              <div className="flex items-center gap-3 text-blue-800">
                <Loader2 className="h-5 w-5 animate-spin" />
                <p className="text-sm font-semibold">Loading KPI metrics...</p>
              </div>
            </CardContent>
          </Card>
        )}

        {noKpisForSelectedFile && (
          <Card className="border border-amber-200 bg-amber-50">
            <CardContent className="py-5">
              <p className="text-sm font-semibold text-amber-800">
                No KPI metrics with data were found in this uploaded file.
              </p>
            </CardContent>
          </Card>
        )}

        <div className="grid gap-6 grid-cols-1 lg:grid-cols-2">
          {performanceMode && filteredKpis.length > visibleKpis.length && (
            <Card className="lg:col-span-2 border border-emerald-200 bg-emerald-50">
              <CardContent className="py-4">
                <p className="text-sm font-semibold text-emerald-800">
                  Performance mode is active. Showing {visibleKpis.length} of{" "}
                  {filteredKpis.length} KPI cards.
                </p>
              </CardContent>
            </Card>
          )}
          {visibleKpis.map(({ kpi, index: i }) => (
            <KpiChart
              key={kpi.metricKey || i}
              title={kpi.kpiName}
              type={chartTypes[i % chartTypes.length]}
              color={chartColors[i % chartColors.length]}
              kpi={kpi}
              compareRanges={appliedComparisonRanges}
              statistics={kpi.statistics}
              index={i}
              performanceMode={performanceMode}
              globalXAxisType={globalXAxisType}
              globalXAxisApplyVersion={globalXAxisApplyVersion}
              comparisonOperator={
                kpiComparisonOperators[kpi.metricKey] ||
                DEFAULT_KPI_COMPARISON_OPERATOR
              }
              onComparisonOperatorChange={handleKpiComparisonOperatorChange}
            />
          ))}
        </div>
      </div>
    </div>
  );
}

/* ==============================
   MULTI SELECT COMPONENT
============================== */
function MultiSelect({
  label,
  options,
  selected,
  toggle,
  toggleSelectAll,
  searchTerm,
  onSearchChange,
}) {
  const filteredOptions = options.filter((opt) =>
    opt?.toLowerCase().includes(searchTerm.toLowerCase()),
  );

  const allSelected =
    filteredOptions.length > 0 &&
    filteredOptions.every((opt) => selected.includes(opt));

  const someSelected =
    filteredOptions.some((opt) => selected.includes(opt)) && !allSelected;

  return (
    <div className="space-y-2">
      <label className="text-base font-bold text-gray-700">{label}</label>

      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-400" />
        <input
          type="text"
          value={searchTerm}
          onChange={(e) => onSearchChange(e.target.value)}
          placeholder={`Search ${label.toLowerCase()}...`}
          className="w-full pl-10 pr-4 py-2.5 text-base border border-gray-200 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all"
        />
      </div>

      <div className="border border-gray-200 rounded-lg bg-white max-h-40 overflow-y-auto shadow-sm">
        {filteredOptions.length > 0 && (
          <label className="flex items-center gap-3 px-4 py-2.5 cursor-pointer bg-blue-50 border-b border-blue-100 sticky top-0 z-10 hover:bg-blue-100 transition-colors">
            <input
              type="checkbox"
              checked={allSelected}
              ref={(el) => {
                if (el) el.indeterminate = someSelected;
              }}
              onChange={toggleSelectAll}
              className="w-5 h-5 text-blue-600 border-gray-300 rounded focus:ring-blue-500"
            />
            <span className="text-base font-bold text-blue-700">
              Select All ({filteredOptions.length})
            </span>
          </label>
        )}

        {filteredOptions.length > 0 ? (
          filteredOptions.map((opt) => (
            <label
              key={opt}
              className={`flex items-center gap-3 px-4 py-2.5 cursor-pointer hover:bg-blue-50 transition-colors ${
                selected.includes(opt) ? "bg-blue-50" : ""
              }`}
            >
              <input
                type="checkbox"
                checked={selected.includes(opt)}
                onChange={() => toggle(opt)}
                className="w-5 h-5 text-blue-600 border-gray-300 rounded focus:ring-blue-500"
              />
              <span className="text-base text-gray-700">{opt}</span>
            </label>
          ))
        ) : (
          <div className="px-4 py-5 text-center text-base text-gray-400">
            No results found
          </div>
        )}
      </div>

      {selected.length > 0 && (
        <p className="text-sm text-blue-600 font-bold">
          {selected.length} selected
        </p>
      )}
    </div>
  );
}

/* ==============================
   DATA SAMPLING ALGORITHMS
============================== */

// Largest Triangle Three Buckets (LTTB) - Smart downsampling algorithm
function downsampleLTTB(data, threshold, yKey = "value") {
  if (data.length <= threshold) return data;

  const sampled = [];
  const bucketSize = (data.length - 2) / (threshold - 2);

  // Always include first point
  sampled.push(data[0]);

  let a = 0;
  for (let i = 0; i < threshold - 2; i++) {
    const avgRangeStart = Math.floor((i + 1) * bucketSize) + 1;
    const avgRangeEnd = Math.floor((i + 2) * bucketSize) + 1;
    const avgRangeLength = avgRangeEnd - avgRangeStart;

    // Calculate average point in next bucket
    let avgX = 0;
    let avgY = 0;
    for (let j = avgRangeStart; j < avgRangeEnd && j < data.length; j++) {
      avgX += j;
      avgY += Number(data[j][yKey]) || 0;
    }
    avgX /= avgRangeLength;
    avgY /= avgRangeLength;

    // Get current bucket
    const rangeStart = Math.floor(i * bucketSize) + 1;
    const rangeEnd = Math.floor((i + 1) * bucketSize) + 1;

    // Find point with largest triangle area
    let maxArea = -1;
    let maxAreaIndex = rangeStart;

    const pointAX = a;
    const pointAY = Number(data[a][yKey]) || 0;

    for (let j = rangeStart; j < rangeEnd && j < data.length; j++) {
      const pointBX = j;
      const pointBY = Number(data[j][yKey]) || 0;

      const area = Math.abs(
        (pointAX - avgX) * (pointBY - pointAY) -
          (pointAX - pointBX) * (avgY - pointAY),
      );

      if (area > maxArea) {
        maxArea = area;
        maxAreaIndex = j;
      }
    }

    sampled.push(data[maxAreaIndex]);
    a = maxAreaIndex;
  }

  // Always include last point
  sampled.push(data[data.length - 1]);

  return sampled;
}

// Simple interval sampling (faster but less accurate)
function downsampleInterval(data, targetSize) {
  if (data.length <= targetSize) return data;

  const interval = Math.ceil(data.length / targetSize);
  const sampled = [];

  for (let i = 0; i < data.length; i += interval) {
    sampled.push(data[i]);
  }

  // Always include last point if not already included
  if (sampled[sampled.length - 1] !== data[data.length - 1]) {
    sampled.push(data[data.length - 1]);
  }

  return sampled;
}

/* ==============================
   DATA AGGREGATION FUNCTION
============================== */
// Aggregate data by grouping based on x-axis field and averaging values
function aggregateDataByXAxis(data, xAxisField) {
  if (!data || data.length === 0) return [];

  // Group data by x-axis field
  const grouped = data.reduce((acc, item) => {
    const key = item[xAxisField];
    if (!key) return acc;

    if (!acc[key]) {
      acc[key] = {
        items: [],
        ...item, // Keep all fields from first item
      };
    }
    acc[key].items.push(item);
    return acc;
  }, {});

  // Calculate averages for each group
  const aggregated = Object.entries(grouped).map(([key, group]) => {
    const items = group.items;
    const avgValue =
      items.reduce((sum, item) => sum + (Number(item.value) || 0), 0) /
      items.length;

    // Return aggregated data point with all original fields
    return {
      ...group,
      [xAxisField]: key,
      value: avgValue,
      count: items.length, // Track how many items were aggregated
      items: undefined, // Remove the items array
    };
  });

  return aggregated;
}

/* ==============================
   KPI CHART COMPONENT WITH OPTIMIZATION
============================== */
function KpiChart({
  title,
  type,
  kpi,
  color,
  index,
  compareRanges,
  performanceMode = false,
  globalXAxisType = "",
  globalXAxisApplyVersion = 0,
  comparisonOperator = DEFAULT_KPI_COMPARISON_OPERATOR,
  onComparisonOperatorChange,
}) {
  const [xAxisType, setXAxisType] = useState("");
  const [chartType, setChartType] = useState(type);
  const [currentPage, setCurrentPage] = useState(1);
  const [samplingEnabled, setSamplingEnabled] = useState(performanceMode);
  const [samplingMethod, _setSamplingMethod] = useState("smart");

  const itemsPerPage = performanceMode ? 60 : 100;
  const maxChartPoints = performanceMode ? 300 : 500;

  const xAxisOptions = useMemo(() => {
    const preferred = [
      "cellName",
      "date",
      "tech",
      "band",
      "groups",
      "site",
      "sectorname",
    ];
    const excluded = new Set([
      "value",
      "count",
      "index",
      "__period",
      "__separator",
    ]);
    const availableKeys = new Set();

    (kpi?.data || []).forEach((row) => {
      Object.entries(row || {}).forEach(([key, rawValue]) => {
        if (excluded.has(key)) return;
        const value = toFilterValue(rawValue);
        if (!value) return;
        availableKeys.add(key);
      });
    });

    const ordered = [
      ...preferred.filter((key) => availableKeys.has(key)),
      ...[...availableKeys].filter((key) => !preferred.includes(key)).sort(),
    ];

    return ordered.map((key) => ({
      value: key,
      label: formatFilterLabel(key),
    }));
  }, [kpi?.data]);

  // Chart type options
  const chartTypeOptions = [
    { value: "bar", label: "Bar Chart" },
    { value: "line", label: "Line Chart" },
    { value: "area", label: "Area Chart" },
  ];

  useEffect(() => {
    if (!kpi?.loading && Array.isArray(kpi?.data) && kpi.data.length === 0) {
      kpiDebugLog("[KPI DEBUG][UI] chart has no data", {
        title,
        index,
        xAxisType,
      });
    }
  }, [kpi?.loading, kpi?.data, title, index, xAxisType]);

  useEffect(() => {
    if (performanceMode) {
      setSamplingEnabled(true);
    }
  }, [performanceMode]);

  useEffect(() => {
    if (xAxisOptions.length === 0) {
      if (xAxisType !== "") setXAxisType("");
      return;
    }
    const hasCurrent = xAxisOptions.some(
      (option) => option.value === xAxisType,
    );
    if (!hasCurrent) {
      setXAxisType(xAxisOptions[0].value);
    }
  }, [xAxisOptions, xAxisType]);

  useEffect(() => {
    if (!globalXAxisApplyVersion || !globalXAxisType) return;
    const hasGlobalOption = xAxisOptions.some(
      (option) => option.value === globalXAxisType,
    );
    if (hasGlobalOption) {
      setXAxisType(globalXAxisType);
    }
  }, [globalXAxisApplyVersion, globalXAxisType, xAxisOptions]);

  // Format date for display (date only, no time)
  const formatDate = (dateString) => {
    if (!dateString) return "";
    const date = new Date(dateString);

    if (isNaN(date.getTime())) return dateString;

    return date.toLocaleDateString("en-US", {
      month: "short",
      day: "numeric",
      year: "2-digit",
    });
  };

  // Helper function to safely format numbers
  const formatValue = (value) => {
    if (value === null || value === undefined) return "N/A";
    const numValue = Number(value);
    if (isNaN(numValue)) return value;
    return numValue.toFixed(2);
  };

  const separatorValue = "__POST_PERIOD_SEPARATOR__";
  const hasPreRange = compareRanges?.preStart || compareRanges?.preEnd;
  const hasPostRange = compareRanges?.postStart || compareRanges?.postEnd;
  const isComparisonMode = Boolean(hasPreRange || hasPostRange);
  const isDualRangeComparison = Boolean(hasPreRange && hasPostRange);

  const isWithinRange = (dateValue, start, end) => {
    if (!start && !end) return true;

    const toDatePart = (value) => {
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

    const datePart = toDatePart(dateValue);
    if (!datePart) return false;
    const startPart = toDatePart(start);
    const endPart = toDatePart(end);

    if (startPart && datePart < startPart) return false;
    if (endPart && datePart > endPart) return false;
    return true;
  };

  const sortAggregated = (data) => {
    if (!xAxisType) return [];
    if (xAxisType === "date") {
      return [...data].sort((a, b) => new Date(a.date) - new Date(b.date));
    }
    return [...data].sort((a, b) =>
      String(a[xAxisType] || "").localeCompare(String(b[xAxisType] || "")),
    );
  };

  // Aggregate and sort data based on x-axis + pre/post ranges
  const sortedData = useMemo(() => {
    if (!kpi.data || kpi.data.length === 0 || !xAxisType) return [];

    if (!isComparisonMode) {
      return sortAggregated(aggregateDataByXAxis(kpi.data, xAxisType));
    }

    const preRaw = hasPreRange
      ? kpi.data.filter((d) =>
          isWithinRange(d.date, compareRanges.preStart, compareRanges.preEnd),
        )
      : [];
    const postRaw = hasPostRange
      ? kpi.data.filter((d) =>
          isWithinRange(d.date, compareRanges.postStart, compareRanges.postEnd),
        )
      : [];

    const preData = sortAggregated(aggregateDataByXAxis(preRaw, xAxisType)).map(
      (item, idx) => ({
        ...item,
        __period: "pre",
        __xLabel: item[xAxisType],
        __chartX: `pre__${String(item[xAxisType])}__${idx}`,
      }),
    );

    const preLookup = new Map(
      preData.map((item) => [String(item[xAxisType]), Number(item.value)]),
    );
    const prePeriodAverage =
      preData.length > 0
        ? preData.reduce((sum, item) => sum + (Number(item.value) || 0), 0) /
          preData.length
        : null;
    console.log(preData);

    const postData = sortAggregated(
      aggregateDataByXAxis(postRaw, xAxisType),
    ).map((item, idx) => {
      const directPreValue = preLookup.get(String(item[xAxisType]));
      const preValue =
        directPreValue !== undefined
          ? directPreValue
          : xAxisType === "date" && prePeriodAverage !== null
            ? prePeriodAverage
            : undefined;
      const comparisonPass =
        preValue === undefined
          ? null
          : evaluateComparisonOperator(
              Number(item.value),
              preValue,
              comparisonOperator,
            );
      return {
        ...item,
        __period: "post",
        __xLabel: item[xAxisType],
        __chartX: `post__${String(item[xAxisType])}__${idx}`,
        __comparisonPass: comparisonPass,
        __comparisonPreValue: preValue,
        __comparisonBasis:
          directPreValue !== undefined
            ? "paired_x_axis"
            : xAxisType === "date" && prePeriodAverage !== null
              ? "pre_period_average"
              : "none",
      };
    });

    if (preData.length > 0 && postData.length > 0) {
      return [
        ...preData,
        {
          [xAxisType]: separatorValue,
          value: null,
          count: 0,
          __separator: true,
          __period: "separator",
          __xLabel: separatorValue,
          __chartX: separatorValue,
        },
        ...postData,
      ];
    }

    return [...preData, ...postData].map((item, idx) => ({
      ...item,
      __chartX:
        item.__chartX ||
        `${item.__period || "all"}__${String(item[xAxisType])}__${idx}`,
      __xLabel: item.__xLabel || item[xAxisType],
    }));
  }, [
    kpi.data,
    xAxisType,
    compareRanges,
    hasPreRange,
    hasPostRange,
    isComparisonMode,
    isDualRangeComparison,
    comparisonOperator,
  ]);

  const comparisonSummary = useMemo(() => {
    if (
      !isComparisonMode ||
      !Array.isArray(kpi.data) ||
      kpi.data.length === 0
    ) {
      return null;
    }
    const preRaw = hasPreRange
      ? kpi.data.filter((d) =>
          isWithinRange(d.date, compareRanges.preStart, compareRanges.preEnd),
        )
      : [];
    const postRaw = hasPostRange
      ? kpi.data.filter((d) =>
          isWithinRange(d.date, compareRanges.postStart, compareRanges.postEnd),
        )
      : [];

    if (!preRaw.length || !postRaw.length) return null;

    const preNumeric = preRaw
      .map((row) => Number(row.value))
      .filter((value) => Number.isFinite(value));
    const postNumeric = postRaw
      .map((row) => Number(row.value))
      .filter((value) => Number.isFinite(value));

    if (!preNumeric.length || !postNumeric.length) return null;

    const preAverage =
      preNumeric.reduce((sum, value) => sum + value, 0) / preNumeric.length;
    const postAverage =
      postNumeric.reduce((sum, value) => sum + value, 0) / postNumeric.length;

    const comparisonResult = evaluateComparisonOperator(
      postAverage,
      preAverage,
      comparisonOperator,
    );

    return {
      preAverage,
      postAverage,
      preCount: preNumeric.length,
      postCount: postNumeric.length,
      comparisonResult,
    };
  }, [
    isComparisonMode,
    kpi.data,
    hasPreRange,
    hasPostRange,
    compareRanges,
    comparisonOperator,
  ]);

  const derivedStats = useMemo(() => {
    if (!Array.isArray(kpi.data) || kpi.data.length === 0) return null;

    const preRaw = hasPreRange
      ? kpi.data.filter((d) =>
          isWithinRange(d.date, compareRanges.preStart, compareRanges.preEnd),
        )
      : [];
    const postRaw = hasPostRange
      ? kpi.data.filter((d) =>
          isWithinRange(d.date, compareRanges.postStart, compareRanges.postEnd),
        )
      : [];

    const sourceRows = isComparisonMode ? [...preRaw, ...postRaw] : kpi.data;

    const values = sourceRows
      .map((row) => Number(row?.value))
      .filter((value) => Number.isFinite(value))
      .sort((a, b) => a - b);

    if (!values.length) return null;

    const count = values.length;
    const average = values.reduce((sum, value) => sum + value, 0) / count;
    const minimum = values[0];
    const maximum = values[count - 1];
    const middle = Math.floor(count / 2);
    const median =
      count % 2 === 0
        ? (values[middle - 1] + values[middle]) / 2
        : values[middle];

    return { average, minimum, maximum, median, count };
  }, [
    kpi.data,
    isComparisonMode,
    hasPreRange,
    hasPostRange,
    compareRanges.preStart,
    compareRanges.preEnd,
    compareRanges.postStart,
    compareRanges.postEnd,
  ]);

  // Apply smart sampling for chart visualization
  const chartData = useMemo(() => {
    if (sortedData.length === 0) return [];
    if (isComparisonMode) {
      return sortedData.map((item) => {
        if (item.__separator) {
          return {
            ...item,
            preValue: null,
            postPassValue: null,
            postFailValue: null,
          };
        }

        if (item.__period === "pre") {
          return {
            ...item,
            preValue: item.value,
            postPassValue: null,
            postFailValue: null,
          };
        }

        if (item.__period === "post") {
          const shouldColorByRule =
            isDualRangeComparison && item.__comparisonPass !== null;
          const isPass = shouldColorByRule
            ? item.__comparisonPass === true
            : true;
          return {
            ...item,
            preValue: null,
            postPassValue: isPass ? item.value : null,
            postFailValue: isPass ? null : item.value,
          };
        }

        return {
          ...item,
          preValue: item.value,
          postPassValue: null,
          postFailValue: null,
        };
      });
    }

    // If sampling is disabled or data is small, return paginated data
    if (!samplingEnabled || sortedData.length <= maxChartPoints) {
      const startIndex = (currentPage - 1) * itemsPerPage;
      const endIndex = startIndex + itemsPerPage;
      return sortedData.slice(startIndex, endIndex);
    }

    // Apply sampling to entire dataset for visualization
    if (samplingMethod === "smart") {
      return downsampleLTTB(sortedData, maxChartPoints, "value");
    } else {
      return downsampleInterval(sortedData, maxChartPoints);
    }
  }, [
    sortedData,
    samplingEnabled,
    samplingMethod,
    currentPage,
    isComparisonMode,
    isDualRangeComparison,
  ]);

  // Calculate pagination
  const totalPages = Math.ceil(sortedData.length / itemsPerPage);
  const startIndex = (currentPage - 1) * itemsPerPage;
  const endIndex = Math.min(startIndex + itemsPerPage, sortedData.length);

  // Determine if sampling is active
  const isSampled =
    !isComparisonMode && samplingEnabled && sortedData.length > maxChartPoints;
  const visibleCount = sortedData.filter((d) => !d.__separator).length;

  const xLabelMap = useMemo(() => {
    const map = new Map();
    chartData.forEach((item) => {
      if (item?.__chartX) map.set(item.__chartX, item.__xLabel);
    });
    return map;
  }, [chartData]);

  // Reset to page 1 when x-axis changes
  useEffect(() => {
    setCurrentPage(1);
  }, [xAxisType, compareRanges]);

  const goToNextPage = () => {
    setCurrentPage((prev) => Math.min(prev + 1, totalPages));
  };

  const goToPreviousPage = () => {
    setCurrentPage((prev) => Math.max(prev - 1, 1));
  };

  const goToFirstPage = () => {
    setCurrentPage(1);
  };

  const goToLastPage = () => {
    setCurrentPage(totalPages);
  };

  const CustomTooltip = ({ active, payload }) => {
    if (active && payload && payload.length) {
      const data = payload[0].payload;
      const metricPoint =
        payload.find(
          (entry) => entry?.value !== null && entry?.value !== undefined,
        ) || payload[0];
      const value = metricPoint?.value;

      if (data.__separator) {
        return (
          <div className="bg-white/95 backdrop-blur-sm border border-gray-200 rounded-lg shadow-xl p-4">
            <p className="text-sm font-bold text-gray-700">
              Post Period Starts
            </p>
          </div>
        );
      }

      return (
        <div className="bg-white/95 backdrop-blur-sm border border-gray-200 rounded-lg shadow-xl p-4">
          <p className="text-sm font-bold text-gray-700 mb-2">{title}</p>
          <div className="space-y-1.5">
            <p className="text-base text-gray-600">
              <span className="font-bold" style={{ color }}>
                Avg Value: {formatValue(value)}
              </span>
            </p>
            {isComparisonMode && data.__period && (
              <p
                className={`text-sm font-semibold ${
                  data.__period === "pre"
                    ? "text-blue-700"
                    : data.__period === "post"
                      ? data.__comparisonPass === false && isDualRangeComparison
                        ? "text-rose-700"
                        : "text-emerald-700"
                      : "text-gray-600"
                }`}
              >
                Period:{" "}
                {data.__period === "pre"
                  ? "Pre"
                  : data.__period === "post"
                    ? "Post"
                    : data.__period}
              </p>
            )}
            {isDualRangeComparison && data.__period === "post" && (
              <p
                className={`text-sm font-semibold ${
                  data.__comparisonPass === false
                    ? "text-rose-700"
                    : "text-emerald-700"
                }`}
              >
                Rule (Post {comparisonOperator} Pre):{" "}
                {data.__comparisonPass === false ? "Failed" : "Passed"}
              </p>
            )}
            {data.count && (
              <p className="text-sm text-gray-500">
                Aggregated from {data.count} record{data.count > 1 ? "s" : ""}
              </p>
            )}
            {data.cellName && (
              <p className="text-sm text-gray-500">Cell: {data.cellName}</p>
            )}
            {data.site && (
              <p className="text-sm text-gray-500">Site: {data.site}</p>
            )}
            {data.sectorname && (
              <p className="text-sm text-gray-500">Sector: {data.sectorname}</p>
            )}
            {data.groups && (
              <p className="text-sm text-gray-500">Group: {data.groups}</p>
            )}
            {data.tech && (
              <p className="text-sm text-gray-500">Tech: {data.tech}</p>
            )}
            {data.band && (
              <p className="text-sm text-gray-500">Band: {data.band}</p>
            )}
            {data.date && (
              <p className="text-sm text-gray-500">
                Date: {formatDate(data.date)}
              </p>
            )}
          </div>
        </div>
      );
    }
    return null;
  };

  const xAxisTickFormatter = (value) => {
    if (value === separatorValue) return "POST PERIOD";
    const label = xLabelMap.get(value) ?? value;
    return xAxisType === "date" ? formatDate(label) : label;
  };

  useEffect(() => {
    if (!kpi.loading && (kpi.data?.length || 0) > 0 && chartData.length === 0) {
      kpiDebugLog("[KPI DEBUG][UI] chart empty after transform", {
        title,
        xAxisType,
        sourcePoints: kpi.data.length,
        xAxisOptions: xAxisOptions.map((item) => item.value),
      });
    }
  }, [kpi.loading, kpi.data, chartData.length, title, xAxisType, xAxisOptions]);

  return (
    <Card
      className="border-0 shadow-xl shadow-gray-200/50 bg-white hover:shadow-2xl transition-all duration-300 group overflow-hidden"
      data-kpi-pdf-card="true"
      data-kpi-title={title || "KPI Chart"}
    >
      <CardHeader className="bg-gradient-to-r from-gray-50 to-white border-b border-b-white p-6 mb-10">
        <div className="flex items-start justify-between">
          <div className="flex-1">
            <CardTitle className="text-xl font-bold text-gray-800 mb-2">
              {title || "Loading..."}
            </CardTitle>
            <div className="flex items-center gap-4 flex-wrap">
              <p className="text-sm text-gray-500 font-medium">
                {visibleCount.toLocaleString()} unique{" "}
                {xAxisOptions
                  .find((opt) => opt.value === xAxisType)
                  ?.label.toLowerCase()}{" "}
                values
              </p>
              {isComparisonMode && (
                <div className="flex items-center gap-2 text-xs font-semibold">
                  <span className="px-2 py-1 rounded-full bg-blue-50 text-blue-700 border border-blue-200">
                    Pre
                  </span>
                  <span className="text-gray-400">then</span>
                  <span className="px-2 py-1 rounded-full bg-indigo-50 text-indigo-700 border border-indigo-200">
                    Post
                  </span>
                </div>
              )}
            </div>
          </div>

          {/* Dropdowns Section */}
          <div className="flex items-center gap-3 flex-wrap">
            {!kpi.loading && kpi.data.length > 0 && (
              <>
                {/* Sampling Toggle */}
                <div className="relative">
                  <label className="text-xs text-gray-500 font-medium mb-1 block">
                    Optimization
                  </label>
                  <button
                    onClick={() =>
                      !performanceMode && setSamplingEnabled(!samplingEnabled)
                    }
                    disabled={performanceMode}
                    className={`flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-semibold transition-all ${
                      samplingEnabled
                        ? "bg-gradient-to-r from-green-500 to-emerald-600 text-white shadow-md"
                        : "bg-gray-200 text-gray-600"
                    } ${performanceMode ? "opacity-80 cursor-not-allowed" : ""}`}
                    title={
                      performanceMode
                        ? "Locked on in Performance mode"
                        : samplingEnabled
                          ? "Smart sampling enabled for better performance"
                          : "Showing all data (may be slow)"
                    }
                  >
                    {samplingEnabled ? (
                      <>
                        <Zap className="w-4 h-4" />
                        Fast
                      </>
                    ) : (
                      <>
                        <Eye className="w-4 h-4" />
                        Full
                      </>
                    )}
                  </button>
                </div>

                {/* Chart Type Dropdown */}
                <div className="relative">
                  <label className="text-xs text-gray-500 font-medium mb-1 block">
                    Chart Type
                  </label>
                  <div className="relative">
                    <select
                      value={chartType}
                      onChange={(e) => setChartType(e.target.value)}
                      className="appearance-none bg-white border border-gray-200 text-gray-700 py-2 pl-3 pr-8 rounded-lg text-sm font-semibold focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent cursor-pointer hover:border-gray-300 transition-all"
                    >
                      {chartTypeOptions.map((option) => (
                        <option key={option.value} value={option.value}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                    <ChevronDown className="absolute right-2 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400 pointer-events-none" />
                  </div>
                </div>

                {/* X-Axis Dropdown */}
                <div className="relative">
                  <label className="text-xs text-gray-500 font-medium mb-1 block">
                    X-Axis
                  </label>
                  <div className="relative">
                    <select
                      value={xAxisType}
                      onChange={(e) => setXAxisType(e.target.value)}
                      className="appearance-none bg-white border border-gray-200 text-gray-700 py-2 pl-3 pr-8 rounded-lg text-sm font-semibold focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent cursor-pointer hover:border-gray-300 transition-all"
                    >
                      {xAxisOptions.map((option) => (
                        <option key={option.value} value={option.value}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                    <ChevronDown className="absolute right-2 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400 pointer-events-none" />
                  </div>
                </div>

                {/* KPI Comparison Operator */}
                <div className="relative">
                  <label className="text-xs text-gray-500 font-medium mb-1 block">
                    KPI Rule
                  </label>
                  <div className="relative">
                    <select
                      value={comparisonOperator}
                      onChange={(e) =>
                        onComparisonOperatorChange?.(
                          kpi.metricKey,
                          e.target.value,
                        )
                      }
                      className="appearance-none bg-white border border-gray-200 text-gray-700 py-2 pl-3 pr-8 rounded-lg text-sm font-semibold focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent cursor-pointer hover:border-gray-300 transition-all"
                    >
                      {KPI_COMPARISON_OPERATORS.map((option) => (
                        <option key={option.value} value={option.value}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                    <ChevronDown className="absolute right-2 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400 pointer-events-none" />
                  </div>
                </div>
              </>
            )}
          </div>
        </div>
        {/* Statistics badges */}
        {(derivedStats || comparisonSummary) && (
          <div className="flex flex-row items-center gap-2 flex-wrap py-2">
            {comparisonSummary && (
              <>
                <div className="px-3 py-1 bg-slate-50 rounded-full border border-slate-200">
                  <span className="text-xs font-semibold text-slate-700">
                    Pre Avg: {formatValue(comparisonSummary.preAverage)}
                  </span>
                </div>
                <div
                  className={`px-3 py-1 rounded-full border ${
                    comparisonSummary.comparisonResult === null
                      ? "bg-gray-50 border-gray-200"
                      : comparisonSummary.comparisonResult
                        ? "bg-emerald-50 border-emerald-200"
                        : "bg-rose-50 border-rose-200"
                  }`}
                >
                  <span
                    className={`text-xs font-semibold ${
                      comparisonSummary.comparisonResult === null
                        ? "text-gray-700"
                        : comparisonSummary.comparisonResult
                          ? "text-emerald-700"
                          : "text-rose-700"
                    }`}
                  >
                    Post Avg: {formatValue(comparisonSummary.postAverage)}
                  </span>
                </div>
                <div className="px-3 py-1 bg-indigo-50 rounded-full border border-indigo-200">
                  <span className="text-xs font-semibold text-indigo-700">
                    Rule: Post {comparisonOperator} Pre
                  </span>
                </div>
              </>
            )}
            {/* Sampling Indicator */}
            {isSampled && (
              <div className="flex items-center gap-2 px-3 py-2 bg-gradient-to-r from-yellow-50 to-amber-50 border border-yellow-200 rounded-full">
                <Zap className="w-4 h-4 text-yellow-600" />
                <span className="text-xs font-bold text-yellow-700">
                  Showing {chartData.length} sampled points
                </span>
              </div>
            )}
            {derivedStats?.average !== undefined && (
              <div className="px-3 py-1 bg-blue-50 rounded-full border border-blue-200">
                <span className="text-xs font-semibold text-blue-700">
                  Avg: {formatValue(derivedStats.average)}
                </span>
              </div>
            )}
            {derivedStats?.minimum !== undefined && (
              <div className="px-3 py-1 bg-green-50 rounded-full border border-green-200">
                <span className="text-xs font-semibold text-green-700">
                  Min: {formatValue(derivedStats.minimum)}
                </span>
              </div>
            )}
            {derivedStats?.maximum !== undefined && (
              <div className="px-3 py-1 bg-purple-50 rounded-full border border-purple-200">
                <span className="text-xs font-semibold text-purple-700">
                  Max: {formatValue(derivedStats.maximum)}
                </span>
              </div>
            )}
            {derivedStats?.median !== undefined && (
              <div className="px-3 py-1 bg-orange-50 rounded-full border border-orange-200">
                <span className="text-xs font-semibold text-orange-700">
                  Median: {formatValue(derivedStats.median)}
                </span>
              </div>
            )}
          </div>
        )}
      </CardHeader>
      <CardContent className="pt-6 p-6">
        {kpi.loading ? (
          <div className="flex flex-col items-center justify-center h-80 gap-4">
            <Loader2 className="w-12 h-12 animate-spin" style={{ color }} />
            <p className="text-base text-gray-400 font-medium">
              Loading data...
            </p>
          </div>
        ) : chartData.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-80 gap-3">
            <div
              className="p-4 rounded-full"
              style={{ backgroundColor: `${color}15` }}
            >
              <BarChart3 className="w-10 h-10" style={{ color }} />
            </div>
            <p className="text-base text-gray-500 font-medium">
              No data available
            </p>
          </div>
        ) : (
          <>
            <ResponsiveContainer width="100%" height={350}>
              {chartType === "bar" && (
                <BarChart data={chartData}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                  <XAxis
                    dataKey={isComparisonMode ? "__chartX" : xAxisType}
                    tick={{ fontSize: 11, fill: "#6b7280" }}
                    stroke="#e5e7eb"
                    tickFormatter={xAxisTickFormatter}
                    angle={-45}
                    textAnchor="end"
                    height={80}
                    interval="preserveStartEnd"
                  />
                  <YAxis
                    tick={{ fontSize: 12, fill: "#6b7280" }}
                    stroke="#e5e7eb"
                  />
                  {isComparisonMode && (
                    <ReferenceLine
                      x={separatorValue}
                      stroke="#9ca3af"
                      strokeDasharray="6 6"
                      label={{
                        value: "POST",
                        fill: "#4b5563",
                        fontSize: 11,
                        position: "insideTop",
                      }}
                    />
                  )}
                  <Tooltip content={<CustomTooltip />} />
                  {isComparisonMode ? (
                    <>
                      <Bar
                        dataKey="preValue"
                        fill="#3b82f6"
                        radius={[8, 8, 0, 0]}
                        name="Pre"
                      />
                      <Bar
                        dataKey="postPassValue"
                        fill="#16a34a"
                        radius={[8, 8, 0, 0]}
                        name="Post (Pass)"
                      />
                      <Bar
                        dataKey="postFailValue"
                        fill="#dc2626"
                        radius={[8, 8, 0, 0]}
                        name="Post (Fail)"
                      />
                    </>
                  ) : (
                    <Bar dataKey="value" fill={color} radius={[8, 8, 0, 0]} />
                  )}
                </BarChart>
              )}
              {chartType === "line" && (
                <LineChart data={chartData}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                  <XAxis
                    dataKey={isComparisonMode ? "__chartX" : xAxisType}
                    tick={{ fontSize: 11, fill: "#6b7280" }}
                    stroke="#e5e7eb"
                    tickFormatter={xAxisTickFormatter}
                    angle={-45}
                    textAnchor="end"
                    height={80}
                    interval="preserveStartEnd"
                  />
                  <YAxis
                    tick={{ fontSize: 12, fill: "#6b7280" }}
                    stroke="#e5e7eb"
                  />
                  {isComparisonMode && (
                    <ReferenceLine
                      x={separatorValue}
                      stroke="#9ca3af"
                      strokeDasharray="6 6"
                      label={{
                        value: "POST",
                        fill: "#4b5563",
                        fontSize: 11,
                        position: "insideTop",
                      }}
                    />
                  )}
                  <Tooltip content={<CustomTooltip />} />
                  {isComparisonMode ? (
                    <>
                      <Line
                        type="monotone"
                        dataKey="preValue"
                        stroke="#3b82f6"
                        strokeWidth={2}
                        dot={{ r: 2.5, strokeWidth: 1, fill: "#ffffff" }}
                        activeDot={{ r: 5, strokeWidth: 0 }}
                        connectNulls={false}
                        name="Pre"
                      />
                      <Line
                        type="monotone"
                        dataKey="postPassValue"
                        stroke="#16a34a"
                        strokeWidth={2}
                        dot={{ r: 2.5, strokeWidth: 1, fill: "#ffffff" }}
                        activeDot={{ r: 5, strokeWidth: 0 }}
                        connectNulls={false}
                        name="Post (Pass)"
                      />
                      <Line
                        type="monotone"
                        dataKey="postFailValue"
                        stroke="#dc2626"
                        strokeWidth={2}
                        dot={{ r: 2.5, strokeWidth: 1, fill: "#ffffff" }}
                        activeDot={{ r: 5, strokeWidth: 0 }}
                        connectNulls={false}
                        name="Post (Fail)"
                      />
                    </>
                  ) : (
                    <Line
                      type="monotone"
                      dataKey="value"
                      stroke={color}
                      strokeWidth={2}
                      dot={{ r: 2.5, strokeWidth: 1, fill: "#ffffff" }}
                      activeDot={{ r: 5, strokeWidth: 0 }}
                    />
                  )}
                </LineChart>
              )}
              {chartType === "area" && (
                <AreaChart data={chartData}>
                  <defs>
                    <linearGradient
                      id={`gradient-${index}`}
                      x1="0"
                      y1="0"
                      x2="0"
                      y2="1"
                    >
                      <stop offset="5%" stopColor={color} stopOpacity={0.3} />
                      <stop offset="95%" stopColor={color} stopOpacity={0} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                  <XAxis
                    dataKey={isComparisonMode ? "__chartX" : xAxisType}
                    tick={{ fontSize: 11, fill: "#6b7280" }}
                    stroke="#e5e7eb"
                    tickFormatter={xAxisTickFormatter}
                    angle={-45}
                    textAnchor="end"
                    height={80}
                    interval="preserveStartEnd"
                  />
                  <YAxis
                    tick={{ fontSize: 12, fill: "#6b7280" }}
                    stroke="#e5e7eb"
                  />
                  {isComparisonMode && (
                    <ReferenceLine
                      x={separatorValue}
                      stroke="#9ca3af"
                      strokeDasharray="6 6"
                      label={{
                        value: "POST",
                        fill: "#4b5563",
                        fontSize: 11,
                        position: "insideTop",
                      }}
                    />
                  )}
                  <Tooltip content={<CustomTooltip />} />
                  {isComparisonMode ? (
                    <>
                      <Area
                        type="monotone"
                        dataKey="preValue"
                        stroke="#3b82f6"
                        strokeWidth={2}
                        fillOpacity={0.7}
                        fill="rgba(59, 130, 246, 0.25)"
                        dot={{ r: 2.5, strokeWidth: 1, fill: "#ffffff" }}
                        activeDot={{ r: 5, strokeWidth: 0 }}
                        connectNulls={false}
                        name="Pre"
                      />
                      <Area
                        type="monotone"
                        dataKey="postPassValue"
                        stroke="#16a34a"
                        strokeWidth={2}
                        fillOpacity={0.7}
                        fill="rgba(22, 163, 74, 0.22)"
                        dot={{ r: 2.5, strokeWidth: 1, fill: "#ffffff" }}
                        activeDot={{ r: 5, strokeWidth: 0 }}
                        connectNulls={false}
                        name="Post (Pass)"
                      />
                      <Area
                        type="monotone"
                        dataKey="postFailValue"
                        stroke="#dc2626"
                        strokeWidth={2}
                        fillOpacity={0.7}
                        fill="rgba(220, 38, 38, 0.22)"
                        dot={{ r: 2.5, strokeWidth: 1, fill: "#ffffff" }}
                        activeDot={{ r: 5, strokeWidth: 0 }}
                        connectNulls={false}
                        name="Post (Fail)"
                      />
                    </>
                  ) : (
                    <Area
                      type="monotone"
                      dataKey="value"
                      stroke={color}
                      strokeWidth={2}
                      fillOpacity={1}
                      fill={`url(#gradient-${index})`}
                      dot={{ r: 2.5, strokeWidth: 1, fill: "#ffffff" }}
                      activeDot={{ r: 5, strokeWidth: 0 }}
                    />
                  )}
                </AreaChart>
              )}
            </ResponsiveContainer>

            {/* Pagination Controls - Only show when sampling is disabled */}
            {!isComparisonMode &&
              !isSampled &&
              sortedData.length > itemsPerPage && (
                <div className="mt-6 flex items-center justify-between border-t border-gray-100 pt-4">
                  <div className="flex items-center gap-2">
                    <p className="text-sm text-gray-600">
                      Showing{" "}
                      <span className="font-semibold text-gray-800">
                        {startIndex + 1}-{endIndex}
                      </span>{" "}
                      of{" "}
                      <span className="font-semibold text-gray-800">
                        {sortedData.length.toLocaleString()}
                      </span>{" "}
                      entries
                    </p>
                  </div>

                  <div className="flex items-center gap-2">
                    <button
                      onClick={goToFirstPage}
                      disabled={currentPage === 1}
                      className={`px-3 py-1.5 rounded-lg text-sm font-semibold transition-all ${
                        currentPage === 1
                          ? "bg-gray-100 text-gray-400 cursor-not-allowed"
                          : "bg-white border border-gray-200 text-gray-700 hover:bg-gray-50 hover:border-gray-300"
                      }`}
                    >
                      First
                    </button>

                    <button
                      onClick={goToPreviousPage}
                      disabled={currentPage === 1}
                      className={`px-3 py-1.5 rounded-lg text-sm font-semibold transition-all ${
                        currentPage === 1
                          ? "bg-gray-100 text-gray-400 cursor-not-allowed"
                          : "bg-white border border-gray-200 text-gray-700 hover:bg-gray-50 hover:border-gray-300"
                      }`}
                    >
                      Previous
                    </button>

                    <div className="px-4 py-1.5 bg-gradient-to-r from-blue-50 to-indigo-50 border border-blue-200 rounded-lg">
                      <span className="text-sm font-bold text-blue-700">
                        Page {currentPage} of {totalPages}
                      </span>
                    </div>

                    <button
                      onClick={goToNextPage}
                      disabled={currentPage === totalPages}
                      className={`px-3 py-1.5 rounded-lg text-sm font-semibold transition-all ${
                        currentPage === totalPages
                          ? "bg-gray-100 text-gray-400 cursor-not-allowed"
                          : "bg-white border border-gray-200 text-gray-700 hover:bg-gray-50 hover:border-gray-300"
                      }`}
                    >
                      Next
                    </button>

                    <button
                      onClick={goToLastPage}
                      disabled={currentPage === totalPages}
                      className={`px-3 py-1.5 rounded-lg text-sm font-semibold transition-all ${
                        currentPage === totalPages
                          ? "bg-gray-100 text-gray-400 cursor-not-allowed"
                          : "bg-white border border-gray-200 text-gray-700 hover:bg-gray-50 hover:border-gray-300"
                      }`}
                    >
                      Last
                    </button>
                  </div>
                </div>
              )}
          </>
        )}
      </CardContent>
    </Card>
  );
}

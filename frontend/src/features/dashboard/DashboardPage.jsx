import { useEffect, useState, useRef, useCallback, useContext } from "react";
import {
  Activity,
  Radio,
  Layers,
  MapPin,
  Sliders,
  Bell,
  Loader2,
  Settings,
  CheckCircle,
  TrendingUp,
  AlertCircle,
  Eye,
  EyeOff,
  Zap,
  ArrowUp,
  ArrowDown,
  Download,
  Upload,
  Minus,
  RefreshCw,
} from "lucide-react";
import {
  exportThresholdWorkbook,
  getDashboardData,
  getPerformanceData,
  getThresholdSetting,
  importThresholdWorkbook,
  postThresholdSetting,
} from "./services/dashboardService";
import {
  fetchDynamicKpiSummary,
  fetchKpiUploadHistory,
} from "../kpi/kpiService";
import UserContext from "../../context/fileContext";

const THRESHOLD_STORAGE_PREFIX = "dashboardThresholdProfile";
const THRESHOLD_OPERATOR_STORAGE_PREFIX = "dashboardThresholdOperators";
const DEFAULT_THRESHOLD_OPERATOR = ">=";
const THRESHOLD_OPERATOR_OPTIONS = [
  { value: ">", label: ">" },
  { value: ">=", label: ">=" },
  { value: "<", label: "<" },
  { value: "<=", label: "<=" },
];
const THRESHOLD_OPERATOR_SET = new Set(
  THRESHOLD_OPERATOR_OPTIONS.map((item) => item.value),
);
const getDashboardScopeCacheKey = (fileId) =>
  fileId ? `file:${String(fileId)}` : "all";
let dashboardRuntimeCacheByScope = {};

export default function DashboardPage() {
  const { selectedFileId, setSelectedFileId } = useContext(UserContext);
  const [dashboardData, setDashboardData] = useState(null);
  const [performanceData, setPerformanceData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [showThresholds, setShowThresholds] = useState(false);
  const [thresholds, setThresholds] = useState({});
  const [thresholdOperators, setThresholdOperators] = useState({});
  const [defaultThresholds, setDefaultThresholds] = useState({});
  const [defaultThresholdOperators, setDefaultThresholdOperators] = useState(
    {},
  );
  const [dashboardFileOptions, setDashboardFileOptions] = useState([]);
  const [dynamicPerformanceEntries, setDynamicPerformanceEntries] = useState(
    [],
  );
  const [dynamicPerformanceLoading, setDynamicPerformanceLoading] =
    useState(false);
  const [saving, setSaving] = useState(false);
  const [saveSuccess, setSaveSuccess] = useState(false);
  const [exportingThresholds, setExportingThresholds] = useState(false);
  const [importingThresholds, setImportingThresholds] = useState(false);
  const [refreshingData, setRefreshingData] = useState(false);
  const [isScopeHydrated, setIsScopeHydrated] = useState(false);
  const [thresholdTransferMessage, setThresholdTransferMessage] =
    useState(null);
  const selectedDashboardFileId = String(selectedFileId || "");
  const setSelectedDashboardFileId = setSelectedFileId;

  const isInitialMount = useRef(true);
  const hasHydratedSelectionRef = useRef(false);
  const hasLoadedDashboardFilesRef = useRef(false);
  const isRestoringDashboardCacheRef = useRef(false);
  const thresholdFetchRequestIdRef = useRef(0);
  const saveTimeout = useRef(null);
  const skipThresholdAutosaveRef = useRef(false);
  const thresholdImportInputRef = useRef(null);

  const performanceToThresholdMap = {
    ulPrbUtilization: "UL_PRB_Utilization_Rate",
    dlPrbUtilization: "DL_PRB_Utilization_Rate",
    erabDropRate: "E_RAB_Drop_Rate",
    rrcDropRate: "RRC_Drop_Rate",
    erabSuccessRate: "Initial_ERAB_Establishment_Success_Rate",
    rrcSuccessRate: "RRC_Establishment_Success_Rate",
    erabSetupRate: "E_RAB_Setup_Success_Rate",
    volteCssr: "VOLTE_CSSR_Eric",
    volteDcr: "VOLTE_DCR_Eric",
    interFreqHosr: "Inter_Freq_HOSR",
    intraFreqHosr: "Intra_Freq_HOSR",
    csfbSuccessRate: "CSFB_Success_Rate",
    maxUlUtilization: "UL_PRB_Utilization_Rate",
    maxDlUtilization: "DL_PRB_Utilization_Rate",
    maxRrcUsers: "Max_RRC_Users",
    minErabDropRate: "E_RAB_Drop_Rate",
    minRrcDropRate: "RRC_Drop_Rate",
  };

  const getThresholdStorageKey = useCallback(
    (fileId) => `${THRESHOLD_STORAGE_PREFIX}:${String(fileId)}`,
    [],
  );

  const getThresholdOperatorStorageKey = useCallback(
    (fileId) =>
      fileId
        ? `${THRESHOLD_OPERATOR_STORAGE_PREFIX}:${String(fileId)}`
        : `${THRESHOLD_OPERATOR_STORAGE_PREFIX}:global`,
    [],
  );

  const getDefaultOperatorForMetric = useCallback((performanceKey) => {
    const lowerIsBetter = new Set([
      "erabDropRate",
      "rrcDropRate",
      "volteDcr",
      "minErabDropRate",
      "minRrcDropRate",
      "E_RAB_Drop_Rate",
      "RRC_Drop_Rate",
      "VOLTE_DCR_Eric",
    ]);
    return lowerIsBetter.has(performanceKey)
      ? "<="
      : DEFAULT_THRESHOLD_OPERATOR;
  }, []);

  const buildOperatorFallbacks = useCallback(
    (baseThresholds = {}) =>
      Object.keys(baseThresholds || {}).reduce((acc, key) => {
        acc[key] = getDefaultOperatorForMetric(key);
        return acc;
      }, {}),
    [getDefaultOperatorForMetric],
  );

  const loadThresholdOperators = useCallback(
    (fileId, fallbackOperators = {}) => {
      const base = { ...fallbackOperators };
      try {
        const raw = window.localStorage.getItem(
          getThresholdOperatorStorageKey(fileId),
        );
        if (!raw) return base;
        const parsed = JSON.parse(raw);
        if (!parsed || typeof parsed !== "object") return base;
        return {
          ...base,
          ...parsed,
        };
      } catch {
        return base;
      }
    },
    [getThresholdOperatorStorageKey],
  );

  const persistThresholdProfile = useCallback(
    (fileId, values) => {
      if (!fileId || !values || typeof values !== "object") return;
      try {
        window.localStorage.setItem(
          getThresholdStorageKey(fileId),
          JSON.stringify(values),
        );
      } catch {
        console.error("Error saving threshold profile to localStorage");
      }
    },
    [getThresholdStorageKey],
  );

  const persistThresholdOperators = useCallback(
    (fileId, values) => {
      if (!values || typeof values !== "object") return;
      try {
        window.localStorage.setItem(
          getThresholdOperatorStorageKey(fileId),
          JSON.stringify(values),
        );
      } catch {
        console.error("Error saving threshold operators to localStorage");
      }
    },
    [getThresholdOperatorStorageKey],
  );

  const loadDynamicPerformanceData = useCallback(async (fileId) => {
    if (!fileId) {
      setDynamicPerformanceEntries([]);
      return;
    }

    try {
      setDynamicPerformanceLoading(true);
      const summaryResponse = await fetchDynamicKpiSummary(fileId);
      const metrics = summaryResponse?.success
        ? summaryResponse.data || []
        : [];

      if (!metrics.length) {
        setDynamicPerformanceEntries([]);
        return;
      }

      const entries = metrics
        .map((metric) => {
          const statistics = metric?.statistics || {};
          const average =
            typeof statistics.average === "number"
              ? statistics.average
              : Number.NaN;
          const value = Number.isNaN(average) ? 0 : average;

          return {
            key: metric.key,
            label:
              metric.label ||
              metric.key
                .replace(/([A-Z])/g, " $1")
                .replace(/[_-]+/g, " ")
                .trim(),
            value,
            count:
              typeof statistics.count === "number"
                ? statistics.count
                : metric.count || 0,
          };
        })
        .filter((entry) => entry && entry.key);

      setDynamicPerformanceEntries(entries);
    } catch (error) {
      console.error("Error loading dynamic performance metrics:", error);
      setDynamicPerformanceEntries([]);
    } finally {
      setDynamicPerformanceLoading(false);
    }
  }, []);

  async function handleGetDashboardData(fileId) {
    try {
      setLoading(true);
      const res = await getDashboardData(fileId);
      if (res?.success && res?.data) setDashboardData(res.data);
    } catch (error) {
      console.error("Error fetching dashboard data:", error);
    } finally {
      setLoading(false);
    }
  }

  async function handleGetPerformanceData(fileId) {
    try {
      const res = await getPerformanceData(fileId);
      if (res?.success && res?.data) setPerformanceData(res.data);
    } catch (error) {
      console.error("Error fetching performance data:", error);
    }
  }

  async function handleGetThresholdSetting(fileId) {
    const requestId = ++thresholdFetchRequestIdRef.current;
    try {
      const res = await getThresholdSetting(fileId);
      if (requestId !== thresholdFetchRequestIdRef.current) return;
      if (res?.success && res?.data) {
        const thresholdData = { ...res.data };
        const operatorPayload =
          thresholdData?.thresholdOperators &&
          typeof thresholdData.thresholdOperators === "object"
            ? thresholdData.thresholdOperators
            : {};
        delete thresholdData.id;
        delete thresholdData.fileId;
        delete thresholdData.message;
        delete thresholdData.success;
        delete thresholdData.thresholdOperators;
        setDefaultThresholds(thresholdData);
        setDefaultThresholdOperators(operatorPayload);
      }
    } catch (error) {
      if (requestId !== thresholdFetchRequestIdRef.current) return;
      console.error("Error fetching threshold settings:", error);
    }
  }

  const handleRefreshDashboardData = useCallback(async () => {
    const fileId = selectedDashboardFileId || undefined;
    setRefreshingData(true);
    try {
      await handleGetDashboardData(fileId);
      await handleGetThresholdSetting(fileId);
      if (selectedDashboardFileId) {
        setPerformanceData(null);
        await loadDynamicPerformanceData(selectedDashboardFileId);
      } else {
        setDynamicPerformanceEntries([]);
        await handleGetPerformanceData(undefined);
      }
    } finally {
      setRefreshingData(false);
    }
  }, [selectedDashboardFileId, loadDynamicPerformanceData]);

  async function handleUpdateThresholdSetting() {
    try {
      setSaving(true);
      setSaveSuccess(false);

      const body = Object.fromEntries(
        Object.entries(thresholds || {}).map(([key, rawValue]) => [
          key,
          parseFloat(rawValue) || 0,
        ]),
      );

      const res = await postThresholdSetting(
        {
          ...body,
          thresholdOperators,
        },
        selectedDashboardFileId || undefined,
      );
      if (res?.success) {
        setDefaultThresholds(body);
        setDefaultThresholdOperators(thresholdOperators);
        persistThresholdProfile(selectedDashboardFileId || undefined, body);
        persistThresholdOperators(
          selectedDashboardFileId || undefined,
          thresholdOperators,
        );
        setSaveSuccess(true);
        setTimeout(() => setSaveSuccess(false), 2000);
      }
    } catch (error) {
      console.error("Error updating threshold settings:", error);
    } finally {
      setSaving(false);
    }
  }

  useEffect(() => {
    if (isInitialMount.current) {
      isInitialMount.current = false;
      return;
    }

    if (skipThresholdAutosaveRef.current) {
      skipThresholdAutosaveRef.current = false;
      return;
    }

    if (Object.keys(thresholds).length === 0) return;

    if (saveTimeout.current) {
      clearTimeout(saveTimeout.current);
    }

    saveTimeout.current = setTimeout(() => {
      handleUpdateThresholdSetting();
    }, 800);

    return () => {
      if (saveTimeout.current) {
        clearTimeout(saveTimeout.current);
      }
    };
  }, [thresholds, thresholdOperators]);

  useEffect(() => {
    if (hasHydratedSelectionRef.current) return;
    hasHydratedSelectionRef.current = true;
    setIsScopeHydrated(true);
  }, []);

  useEffect(() => {
    if (!hasLoadedDashboardFilesRef.current) return;
    if (!selectedDashboardFileId) return;
    const exists = dashboardFileOptions.some(
      (file) => String(file.id) === String(selectedDashboardFileId),
    );
    if (!exists) {
      setSelectedDashboardFileId("");
    }
  }, [dashboardFileOptions, selectedDashboardFileId]);

  useEffect(() => {
    const loadDashboardFiles = async () => {
      const response = await fetchKpiUploadHistory();
      hasLoadedDashboardFilesRef.current = true;
      if (response?.success) {
        const files = response.data || [];
        setDashboardFileOptions(files);
      }
    };
    loadDashboardFiles();
  }, []);

  useEffect(() => {
    if (!isScopeHydrated) return;
    const fileId = selectedDashboardFileId || undefined;
    const scopeKey = getDashboardScopeCacheKey(selectedDashboardFileId);
    const cached = dashboardRuntimeCacheByScope[scopeKey];
    if (cached) {
      isRestoringDashboardCacheRef.current = true;
      skipThresholdAutosaveRef.current = true;
      setDashboardData(cached.dashboardData || null);
      setPerformanceData(cached.performanceData || null);
      setDynamicPerformanceEntries(
        Array.isArray(cached.dynamicPerformanceEntries)
          ? cached.dynamicPerformanceEntries
          : [],
      );
      setDefaultThresholds(cached.defaultThresholds || {});
      setDefaultThresholdOperators(cached.defaultThresholdOperators || {});
      setThresholds(cached.thresholds || {});
      setThresholdOperators(cached.thresholdOperators || {});
      setLoading(false);
      setDynamicPerformanceLoading(false);
      return;
    }

    handleGetDashboardData(fileId);
    handleGetThresholdSetting(fileId);
    if (selectedDashboardFileId) {
      setPerformanceData(null);
      loadDynamicPerformanceData(selectedDashboardFileId);
    } else {
      setDynamicPerformanceEntries([]);
      handleGetPerformanceData(undefined);
    }
  }, [selectedDashboardFileId, loadDynamicPerformanceData, isScopeHydrated]);

  useEffect(() => {
    const scopeKey = getDashboardScopeCacheKey(selectedDashboardFileId);
    if (
      !dashboardData &&
      !performanceData &&
      !dynamicPerformanceEntries.length &&
      Object.keys(defaultThresholds || {}).length === 0 &&
      Object.keys(thresholds || {}).length === 0
    ) {
      return;
    }
    dashboardRuntimeCacheByScope[scopeKey] = {
      dashboardData,
      performanceData,
      dynamicPerformanceEntries: Array.isArray(dynamicPerformanceEntries)
        ? [...dynamicPerformanceEntries]
        : [],
      defaultThresholds: { ...(defaultThresholds || {}) },
      defaultThresholdOperators: { ...(defaultThresholdOperators || {}) },
      thresholds: { ...(thresholds || {}) },
      thresholdOperators: { ...(thresholdOperators || {}) },
      updatedAt: Date.now(),
    };
  }, [
    selectedDashboardFileId,
    dashboardData,
    performanceData,
    dynamicPerformanceEntries,
    defaultThresholds,
    defaultThresholdOperators,
    thresholds,
    thresholdOperators,
  ]);

  useEffect(() => {
    if (isRestoringDashboardCacheRef.current) {
      isRestoringDashboardCacheRef.current = false;
      return;
    }
    if (Object.keys(defaultThresholds).length === 0) return;
    const nextThresholds = { ...defaultThresholds };
    const operatorFallbacks = buildOperatorFallbacks(nextThresholds);
    const nextThresholdOperators = loadThresholdOperators(
      selectedDashboardFileId || undefined,
      operatorFallbacks,
    );
    const mergedThresholdOperators = {
      ...nextThresholdOperators,
      ...(defaultThresholdOperators || {}),
    };
    skipThresholdAutosaveRef.current = true;
    setThresholds(nextThresholds);
    setThresholdOperators(mergedThresholdOperators);
  }, [
    selectedDashboardFileId,
    defaultThresholds,
    defaultThresholdOperators,
    loadThresholdOperators,
    buildOperatorFallbacks,
  ]);

  const icons = [Activity, Radio, Layers, MapPin, Sliders, Bell];
  const iconColors = [
    { bg: "bg-gradient-to-br from-blue-500 to-blue-600", text: "text-white" },
    { bg: "bg-gradient-to-br from-green-500 to-green-600", text: "text-white" },
    {
      bg: "bg-gradient-to-br from-purple-500 to-purple-600",
      text: "text-white",
    },
    {
      bg: "bg-gradient-to-br from-orange-500 to-orange-600",
      text: "text-white",
    },
    { bg: "bg-gradient-to-br from-pink-500 to-pink-600", text: "text-white" },
    { bg: "bg-gradient-to-br from-cyan-500 to-cyan-600", text: "text-white" },
  ];

  const metricList = [
    { label: "Total Cells", value: dashboardData?.totalCells },
    { label: "Total Sites", value: dashboardData?.totalSites },
    { label: "Total Bands", value: dashboardData?.totalBands },
    { label: "Technologies", value: dashboardData?.totalTech },
    { label: "Sectors", value: dashboardData?.totalSectors },
  ];

  const getThresholdForKey = (performanceKey) => {
    const thresholdKey =
      performanceToThresholdMap[performanceKey] || performanceKey;
    return Object.prototype.hasOwnProperty.call(thresholds, thresholdKey)
      ? thresholds[thresholdKey]
      : null;
  };

  const handleThresholdChange = (performanceKey, value) => {
    const thresholdKey =
      performanceToThresholdMap[performanceKey] || performanceKey;

    setThresholds((prev) => ({
      ...prev,
      [thresholdKey]: value === "" ? 0 : parseFloat(value) || 0,
    }));
  };

  const getOperatorForKey = (performanceKey) => {
    const thresholdKey =
      performanceToThresholdMap[performanceKey] || performanceKey;
    return (
      thresholdOperators[thresholdKey] ||
      getDefaultOperatorForMetric(thresholdKey)
    );
  };

  const handleThresholdOperatorChange = (performanceKey, operator) => {
    const thresholdKey =
      performanceToThresholdMap[performanceKey] || performanceKey;
    setThresholdOperators((prev) => ({
      ...prev,
      [thresholdKey]: operator,
    }));
  };

  const triggerWorkbookDownload = (blob, fileName) => {
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = fileName || "dashboard_thresholds.xlsx";
    document.body.appendChild(link);
    link.click();
    link.remove();
    window.URL.revokeObjectURL(url);
  };

  const handleExportThresholds = async () => {
    try {
      setThresholdTransferMessage(null);

      const exportThresholdKeys = selectedDashboardFileId
        ? Array.from(
            new Set([
              ...(dynamicPerformanceEntries || [])
                .map((entry) => String(entry?.key || "").trim())
                .filter(Boolean),
              ...Object.keys(thresholds || {})
                .map((key) => String(key || "").trim())
                .filter(Boolean),
            ]),
          )
        : Object.keys(thresholds || {})
            .map((key) => String(key || "").trim())
            .filter(Boolean);

      if (!exportThresholdKeys.length) {
        setThresholdTransferMessage({
          type: "error",
          text: selectedDashboardFileId
            ? "No KPI metrics loaded for selected file. Please wait and try export again."
            : "No threshold values available to export.",
        });
        return;
      }

      const sanitizedThresholds = Object.fromEntries(
        exportThresholdKeys.map((key) => {
          const numericThreshold = Number(thresholds?.[key]);
          return [
            key,
            Number.isFinite(numericThreshold) ? numericThreshold : 0,
          ];
        }),
      );

      if (!Object.keys(sanitizedThresholds).length) {
        setThresholdTransferMessage({
          type: "error",
          text: "No threshold values available to export.",
        });
        return;
      }

      const sanitizedOperators = Object.fromEntries(
        exportThresholdKeys.map((key) => {
          const operatorRaw = String(thresholdOperators?.[key] || "").trim();
          return [
            key,
            THRESHOLD_OPERATOR_SET.has(operatorRaw)
              ? operatorRaw
              : getDefaultOperatorForMetric(key),
          ];
        }),
      );

      setExportingThresholds(true);
      const response = await exportThresholdWorkbook({
        thresholds: sanitizedThresholds,
        operators: sanitizedOperators,
        scopeFileId: selectedDashboardFileId || undefined,
      });

      if (!response?.blob) {
        throw new Error("Failed to generate threshold workbook.");
      }

      triggerWorkbookDownload(response.blob, response.fileName);
      setThresholdTransferMessage({
        type: "success",
        text: "Threshold workbook exported successfully.",
      });
    } catch (error) {
      setThresholdTransferMessage({
        type: "error",
        text: error?.message || "Failed to export threshold workbook.",
      });
    } finally {
      setExportingThresholds(false);
    }
  };

  const handleImportThresholdClick = () => {
    thresholdImportInputRef.current?.click();
  };

  const handleImportThresholdFile = async (event) => {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;

    try {
      setThresholdTransferMessage(null);
      setImportingThresholds(true);

      const response = await importThresholdWorkbook(file);
      if (!response?.success || !response?.data) {
        throw new Error(
          response?.message || "Failed to import threshold workbook.",
        );
      }

      const importedThresholds = Object.fromEntries(
        Object.entries(response.data.thresholds || {})
          .map(([key, rawValue]) => [
            String(key || "").trim(),
            Number(rawValue),
          ])
          .filter(([key, value]) => key && Number.isFinite(value)),
      );

      if (!Object.keys(importedThresholds).length) {
        throw new Error("No valid threshold rows found in selected file.");
      }

      const importedOperators = Object.fromEntries(
        Object.entries(response.data.operators || {})
          .map(([key, rawOperator]) => [
            String(key || "").trim(),
            String(rawOperator || "").trim(),
          ])
          .filter(
            ([key, operator]) => key && THRESHOLD_OPERATOR_SET.has(operator),
          ),
      );

      const nextThresholds = {
        ...(thresholds || {}),
        ...importedThresholds,
      };
      const nextThresholdOperators = {
        ...buildOperatorFallbacks(nextThresholds),
        ...(thresholdOperators || {}),
        ...importedOperators,
      };

      setThresholds(nextThresholds);
      setThresholdOperators(nextThresholdOperators);

      const importedCount =
        Number(response.data.importedCount) ||
        Object.keys(importedThresholds).length;
      setThresholdTransferMessage({
        type: "success",
        text: `Imported ${importedCount} threshold row${
          importedCount === 1 ? "" : "s"
        } from ${file.name}.`,
      });
    } catch (error) {
      setThresholdTransferMessage({
        type: "error",
        text: error?.message || "Failed to import threshold workbook.",
      });
    } finally {
      setImportingThresholds(false);
    }
  };

  const evaluateOperator = (value, threshold, operator) => {
    if (typeof value !== "number" || Number.isNaN(value)) return false;
    if (typeof threshold !== "number" || Number.isNaN(threshold)) return false;

    switch (operator) {
      case ">":
        return value > threshold;
      case ">=":
        return value >= threshold;
      case "<":
        return value < threshold;
      case "<=":
        return value <= threshold;
      default:
        return value >= threshold;
    }
  };

  const getMetricStatus = (performanceKey, value) => {
    const threshold = getThresholdForKey(performanceKey);
    const operator = getOperatorForKey(performanceKey);

    if (threshold === undefined || threshold === null || threshold === 0) {
      return {
        color: "from-slate-50 to-slate-100",
        border: "border-slate-200",
        status: "neutral",
        icon: <Minus className="w-4 h-4 text-slate-500" />,
        badge: "bg-slate-100 text-slate-600",
      };
    }

    const isGood = evaluateOperator(value, threshold, operator);

    if (isGood) {
      return {
        color: "from-emerald-50 to-green-50",
        border: "border-emerald-300",
        status: "good",
        icon: <ArrowUp className="w-4 h-4 text-emerald-600" />,
        badge: "bg-emerald-100 text-emerald-700",
      };
    } else {
      return {
        color: "from-rose-50 to-red-50",
        border: "border-rose-300",
        status: "bad",
        icon: <ArrowDown className="w-4 h-4 text-rose-600" />,
        badge: "bg-rose-100 text-rose-700",
      };
    }
  };

  const formatKeyName = (key) => {
    return key
      .replace(/([A-Z])/g, " $1")
      .replace(/^./, (str) => str.toUpperCase())
      .trim();
  };

  const isExportDisabled =
    exportingThresholds ||
    importingThresholds ||
    (Boolean(selectedDashboardFileId) && dynamicPerformanceLoading);

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 via-blue-50 to-indigo-50">
      {/* Modern Header */}
      <div className="bg-white border-b-1 border-gray-200 sticky top-0 z-50">
        <div className="max-w-[1800px] mx-auto px-8 py-3">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-4">
              <div className="relative">
                <div className="absolute inset-0 bg-gradient-to-r from-blue-600 to-indigo-600 rounded-2xl blur opacity-40"></div>
                <div className="relative p-4 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-2xl">
                  <Zap className="w-8 h-8 text-white" />
                </div>
              </div>
              <div>
                <h1 className="text-3xl font-extrabold text-transparent bg-clip-text bg-gradient-to-r from-slate-900 via-blue-900 to-indigo-900">
                  Network Dashboard
                </h1>
                <p className="text-base text-slate-600 mt-1 font-medium">
                  Real-time performance monitoring & analytics
                </p>
              </div>
            </div>

            <div className="flex items-center gap-4">
              <div className="flex flex-col">
                <label className="text-xs font-semibold text-slate-500 mb-1">
                  KPI File Scope
                </label>
                <select
                  value={selectedDashboardFileId}
                  onChange={(e) => setSelectedDashboardFileId(e.target.value)}
                  className="min-w-[320px] px-3 py-2 text-sm border border-slate-200 rounded-lg bg-white text-slate-700"
                >
                  <option value="">All KPI uploads</option>
                  {dashboardFileOptions.map((file) => (
                    <option key={file.id} value={String(file.id)}>
                      #{file.id} - {file.fileName || "Unnamed file"}
                    </option>
                  ))}
                </select>
              </div>
              <button
                type="button"
                onClick={handleRefreshDashboardData}
                disabled={refreshingData}
                className={`inline-flex items-center gap-2 rounded-lg border px-3 py-2 text-sm font-semibold transition ${
                  refreshingData
                    ? "cursor-not-allowed border-slate-200 bg-slate-100 text-slate-400"
                    : "border-slate-300 bg-white text-slate-700 hover:bg-slate-50"
                }`}
              >
                <RefreshCw
                  className={`w-4 h-4 ${refreshingData ? "animate-spin" : ""}`}
                />
                {refreshingData ? "Refreshing..." : "Refresh"}
              </button>
              {saving && (
                <div className="flex items-center gap-3 px-6 py-3 bg-blue-50 text-blue-700 rounded-xl border-2 border-blue-200 shadow-sm">
                  <Loader2 className="w-5 h-5 animate-spin" />
                  <span className="text-base font-semibold">Saving...</span>
                </div>
              )}
              {saveSuccess && (
                <div className="flex items-center gap-3 px-6 py-3 bg-emerald-50 text-emerald-700 rounded-xl border-2 border-emerald-200 shadow-sm animate-in fade-in duration-300">
                  <CheckCircle className="w-5 h-5" />
                  <span className="text-base font-semibold">Saved!</span>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      <div className="max-w-[1800px] mx-auto px-4 py-4 space-y-8">
        <div className="bg-white border border-slate-200 rounded-xl px-4 py-3 text-sm text-slate-700">
          Data scope:{" "}
          <span className="font-semibold">
            {selectedDashboardFileId
              ? `File #${selectedDashboardFileId} - ${
                  dashboardFileOptions.find(
                    (file) =>
                      String(file.id) === String(selectedDashboardFileId),
                  )?.fileName || "Selected upload"
                }`
              : "All KPI uploads"}
          </span>
        </div>

        {/* Stats Grid - Changed to 4 columns */}
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-6">
          {metricList.map((metric, i) => {
            const Icon = icons[i];
            const colors = iconColors[i];
            return (
              <div
                key={metric.label}
                className="group relative overflow-hidden bg-white rounded-3xl shadow-lg hover:shadow-2xl transition-all duration-500 border border-gray-100"
              >
                {/* Background Decoration */}
                <div className="absolute top-0 right-0 w-40 h-40 bg-gradient-to-br from-blue-50 to-indigo-50 rounded-full -mr-20 -mt-20 opacity-50 group-hover:scale-150 transition-transform duration-700"></div>

                <div className="relative px-5 py-4">
                  <div className="flex items-center justify-between mb-3">
                    <div
                      className={`${colors.bg} ${colors.text} p-4 rounded-2xl shadow-lg group-hover:scale-110 group-hover:rotate-6 transition-transform duration-300`}
                    >
                      <Icon className="w-4 h-4" />
                    </div>
                    <p className="text-md font-bold text-slate-600 uppercase tracking-wide mb-1">
                      {metric.label}
                    </p>
                    <div className="opacity-0 group-hover:opacity-100 transition-opacity duration-300">
                      <TrendingUp className="w-6 h-6 text-emerald-500" />
                    </div>
                  </div>

                  {loading ? (
                    <div className="flex items-center gap-3">
                      <Loader2 className="w-8 h-8 text-blue-500 animate-spin" />
                      <span className="text-slate-400">Loading...</span>
                    </div>
                  ) : (
                    <div className="text-3xl flex justify-center font-extrabold text-transparent bg-clip-text bg-gradient-to-r from-slate-900 to-slate-700 leading-tight">
                      {metric.value?.toLocaleString() ?? "—"}
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>

        {/* Performance Section */}
        <div className="bg-white rounded-3xl shadow-2xl border border-gray-100 overflow-hidden">
          {/* Header */}
          <div className="relative bg-gradient-to-r from-slate-50 via-blue-50 to-indigo-50 border-b border-gray-200 px-8 py-4">
            <div className="absolute inset-0 bg-grid-slate-100 [mask-image:linear-gradient(0deg,white,rgba(255,255,255,0.5))] opacity-30"></div>

            <div className="relative flex flex-wrap items-center justify-between gap-6">
              <div className="flex items-center gap-5">
                <div className="p-4 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-2xl shadow-xl">
                  <Sliders className="w-7 h-7 text-white" />
                </div>
                <div>
                  <h2 className="text-2xl font-extrabold text-slate-900">
                    Performance Metrics
                  </h2>
                  <p className="text-base text-slate-600 mt-1 font-medium">
                    Key performance indicators with threshold monitoring
                  </p>
                </div>
              </div>

              <div className="flex flex-wrap items-center gap-3">
                <input
                  ref={thresholdImportInputRef}
                  type="file"
                  accept=".xlsx,.xls,.csv"
                  className="hidden"
                  onChange={handleImportThresholdFile}
                />

                <button
                  onClick={handleExportThresholds}
                  disabled={isExportDisabled}
                  className={`flex items-center gap-2 px-4 py-3 rounded-xl text-sm font-semibold transition-all duration-200 border ${
                    isExportDisabled
                      ? "bg-slate-100 text-slate-400 border-slate-200 cursor-not-allowed"
                      : "bg-white text-slate-700 border-slate-300 hover:bg-slate-50"
                  }`}
                >
                  {exportingThresholds ||
                  (Boolean(selectedDashboardFileId) &&
                    dynamicPerformanceLoading) ? (
                    <Loader2 className="w-4 h-4 animate-spin" />
                  ) : (
                    <Download className="w-4 h-4" />
                  )}
                  {Boolean(selectedDashboardFileId) && dynamicPerformanceLoading
                    ? "Loading KPIs..."
                    : exportingThresholds
                      ? "Exporting..."
                      : "Export"}
                </button>

                <button
                  onClick={handleImportThresholdClick}
                  disabled={importingThresholds || exportingThresholds}
                  className={`flex items-center gap-2 px-4 py-3 rounded-xl text-sm font-semibold transition-all duration-200 border ${
                    importingThresholds || exportingThresholds
                      ? "bg-slate-100 text-slate-400 border-slate-200 cursor-not-allowed"
                      : "bg-white text-slate-700 border-slate-300 hover:bg-slate-50"
                  }`}
                >
                  {importingThresholds ? (
                    <Loader2 className="w-4 h-4 animate-spin" />
                  ) : (
                    <Upload className="w-4 h-4" />
                  )}
                  {importingThresholds ? "Importing..." : "Import"}
                </button>

                <button
                  onClick={() => setShowThresholds((prev) => !prev)}
                  className={`flex items-center gap-3 px-8 py-4 rounded-2xl text-base font-bold transition-all duration-300 shadow-lg hover:shadow-xl ${
                    showThresholds
                      ? "bg-gradient-to-r from-blue-600 to-indigo-600 text-white scale-105"
                      : "bg-white text-slate-700 hover:bg-slate-50 border-2 border-slate-200"
                  }`}
                >
                  {showThresholds ? <EyeOff size={20} /> : <Eye size={20} />}
                  {showThresholds ? "Hide Thresholds" : "Set Thresholds"}
                </button>
              </div>
            </div>

            {thresholdTransferMessage && (
              <div
                className={`relative mt-4 rounded-lg border px-4 py-3 text-sm font-medium ${
                  thresholdTransferMessage.type === "error"
                    ? "bg-rose-50 text-rose-700 border-rose-200"
                    : "bg-emerald-50 text-emerald-700 border-emerald-200"
                }`}
              >
                {thresholdTransferMessage.text}
              </div>
            )}
          </div>

          {/* Content - 5 Column Grid with Reduced Height */}
          <div className="p-8 bg-gradient-to-br from-white to-slate-50">
            {selectedDashboardFileId && dynamicPerformanceLoading ? (
              <div className="flex flex-col items-center justify-center py-24 gap-5">
                <Loader2 className="w-16 h-16 text-blue-500 animate-spin" />
                <p className="text-lg text-slate-500 font-semibold">
                  Loading file-specific performance metrics...
                </p>
              </div>
            ) : selectedDashboardFileId && !dynamicPerformanceEntries.length ? (
              <div className="flex flex-col items-center justify-center py-24 gap-5">
                <AlertCircle className="w-14 h-14 text-slate-400" />
                <p className="text-lg text-slate-500 font-semibold">
                  No KPI metrics available for this selected file.
                </p>
              </div>
            ) : selectedDashboardFileId || performanceData ? (
              <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-6">
                {(selectedDashboardFileId
                  ? dynamicPerformanceEntries.map((entry) => [entry.key, entry])
                  : Object.entries({
                      ...(performanceData?.averages || {}),
                      ...(performanceData?.peaks || {}),
                    }).map(([key, value]) => [
                      key,
                      { key, label: formatKeyName(key), value },
                    ])
                ).map(([key, entry]) => {
                  const value = entry?.value;
                  const thresholdValue = getThresholdForKey(key);
                  const hasThreshold =
                    selectedDashboardFileId ||
                    performanceToThresholdMap[key] !== undefined;
                  const status = getMetricStatus(key, value);

                  return (
                    <div
                      key={key}
                      className={`group relative overflow-hidden bg-gradient-to-br ${status.color} rounded-2xl p-4 border-2 ${status.border} transition-all duration-300 hover:shadow-2xl hover:scale-[1.02] flex flex-col`}
                    >
                      {/* Status Badge - Icon Only */}
                      <div className="absolute top-3 right-3">
                        <div
                          className={`${status.badge} p-1.5 rounded-full flex items-center justify-center shadow-sm`}
                        >
                          {status.icon}
                        </div>
                      </div>

                      <div className="flex flex-col space-y-3">
                        {/* Title */}
                        <div className="pr-12">
                          <h4 className="text-sm font-bold text-slate-800 leading-tight">
                            {entry?.label || formatKeyName(key)}
                          </h4>
                        </div>

                        {/* Value */}
                        <div>
                          <p className="text-3xl font-extrabold text-slate-900 leading-tight">
                            {typeof value === "number"
                              ? value.toFixed(2)
                              : value}
                          </p>
                          {selectedDashboardFileId &&
                            typeof entry?.count === "number" && (
                              <p className="text-xs mt-1 text-slate-500 font-medium">
                                Samples: {entry.count}
                              </p>
                            )}
                        </div>

                        {/* Threshold Input or Info */}
                        <div>
                          {showThresholds && hasThreshold ? (
                            <>
                              <input
                                type="number"
                                step="any"
                                placeholder="0.00"
                                className="w-full px-2.5 py-2 text-sm border-2 border-slate-300 rounded-xl outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500 transition-all bg-white font-bold shadow-sm"
                                value={thresholdValue ?? ""}
                                onChange={(e) =>
                                  handleThresholdChange(key, e.target.value)
                                }
                              />
                              <select
                                className="w-full mt-2 px-2.5 py-2 text-sm border-2 border-slate-300 rounded-xl outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500 transition-all bg-white font-semibold shadow-sm"
                                value={getOperatorForKey(key)}
                                onChange={(e) =>
                                  handleThresholdOperatorChange(
                                    key,
                                    e.target.value,
                                  )
                                }
                              >
                                {THRESHOLD_OPERATOR_OPTIONS.map(
                                  (operatorOption) => (
                                    <option
                                      key={operatorOption.value}
                                      value={operatorOption.value}
                                    >
                                      {operatorOption.label}
                                    </option>
                                  ),
                                )}
                              </select>
                            </>
                          ) : (
                            hasThreshold && (
                              <div className="pt-3 border-t-2 border-white/50">
                                {thresholdValue !== undefined &&
                                thresholdValue !== null &&
                                thresholdValue !== 0 ? (
                                  <div className="flex items-center gap-2">
                                    <Settings className="w-3.5 h-3.5 text-slate-500" />
                                    <p className="text-xs font-bold text-slate-700">
                                      Target: {getOperatorForKey(key)}{" "}
                                      {thresholdValue}
                                    </p>
                                  </div>
                                ) : (
                                  <div className="flex items-center gap-2">
                                    <AlertCircle className="w-3.5 h-3.5 text-slate-400" />
                                    <p className="text-xs font-medium text-slate-500 italic">
                                      No threshold
                                    </p>
                                  </div>
                                )}
                              </div>
                            )
                          )}
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            ) : (
              <div className="flex flex-col items-center justify-center py-24 gap-5">
                <Loader2 className="w-16 h-16 text-blue-500 animate-spin" />
                <p className="text-lg text-slate-500 font-semibold">
                  Loading performance metrics...
                </p>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

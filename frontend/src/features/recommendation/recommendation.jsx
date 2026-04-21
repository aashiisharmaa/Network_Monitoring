import React, { useContext, useEffect, useMemo, useRef, useState } from "react";
import { fetchDynamicKpiColumns, fetchKpiUploadHistory } from "../kpi/kpiService";
import {
  downloadRecommendationExport,
  fetchRecommendationPresets,
  loadRecommendationPreset,
  runRecommendation,
  saveRecommendationPreset,
} from "./recommendationService";
import RecommendationConfigPanel from "./RecommendationConfigPanel";
import RecommendationResultsPanel from "./RecommendationResultsPanel";
import {
  ANOMALY_DEFAULTS,
  BASE_DEFAULT_MAPPING,
  RCA_DEFAULTS,
  SEVERITY_DEFAULTS,
  asNumberMap,
  buildDefaultKpiState,
  groupRcaThresholdKeys,
  suggestKpiMapping,
} from "./recommendationUtils";
import UserContext from "../../context/fileContext";
const RECOMMENDATION_PAGE_STATE_KEY = "recommendationPageState:v1";

export default function RecommendationPage() {
  const hasHydratedRef = useRef(false);
  const { selectedFileId, setSelectedFileId } = useContext(UserContext);
  const [files, setFiles] = useState([]);
  const [columns, setColumns] = useState([]);
  const [vendor, setVendor] = useState("nokia");
  const [baseMapping, setBaseMapping] = useState(BASE_DEFAULT_MAPPING);
  const [kpiState, setKpiState] = useState(buildDefaultKpiState);
  const [dateMode, setDateMode] = useState("single");
  const [startDate, setStartDate] = useState("");
  const [endDate, setEndDate] = useState("");
  const [preStartDate, setPreStartDate] = useState("");
  const [preEndDate, setPreEndDate] = useState("");
  const [postStartDate, setPostStartDate] = useState("");
  const [postEndDate, setPostEndDate] = useState("");
  const [rcaThresholds, setRcaThresholds] = useState(RCA_DEFAULTS);
  const [severityThresholds, setSeverityThresholds] = useState(SEVERITY_DEFAULTS);
  const [anomalyThresholds, setAnomalyThresholds] = useState(ANOMALY_DEFAULTS);
  const [presets, setPresets] = useState([]);
  const [presetName, setPresetName] = useState("");
  const [selectedPreset, setSelectedPreset] = useState("");
  const [loading, setLoading] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState(null);
  const [resultView, setResultView] = useState("table");
  const [resultScope, setResultScope] = useState("top10");
  const sharedSelectedFileId = String(selectedFileId || "");

  useEffect(() => {
    if (hasHydratedRef.current) return;
    hasHydratedRef.current = true;
    if (typeof window === "undefined") return;
    try {
      const raw = window.localStorage.getItem(RECOMMENDATION_PAGE_STATE_KEY);
      if (!raw) return;
      const saved = JSON.parse(raw);
      if (!saved || typeof saved !== "object") return;

      setVendor(saved.vendor || "nokia");
      setBaseMapping(saved.baseMapping || BASE_DEFAULT_MAPPING);
      setKpiState(saved.kpiState || buildDefaultKpiState());
      setDateMode(saved.dateMode === "pre_post" ? "pre_post" : "single");
      setStartDate(saved.startDate || "");
      setEndDate(saved.endDate || "");
      setPreStartDate(saved.preStartDate || "");
      setPreEndDate(saved.preEndDate || "");
      setPostStartDate(saved.postStartDate || "");
      setPostEndDate(saved.postEndDate || "");
      setRcaThresholds({ ...RCA_DEFAULTS, ...(saved.rcaThresholds || {}) });
      setSeverityThresholds({ ...SEVERITY_DEFAULTS, ...(saved.severityThresholds || {}) });
      setAnomalyThresholds({ ...ANOMALY_DEFAULTS, ...(saved.anomalyThresholds || {}) });
      setResult(saved.result || null);
      setResultView(saved.resultView || "table");
      setResultScope(saved.resultScope || "top10");
      setSelectedPreset(saved.selectedPreset || "");
      setPresetName(saved.presetName || "");
    } catch {
      // ignore invalid persisted state
    }
  }, []);

  useEffect(() => {
    if (typeof window === "undefined") return;
    try {
      window.localStorage.setItem(
        RECOMMENDATION_PAGE_STATE_KEY,
        JSON.stringify({
          vendor,
          baseMapping,
          kpiState,
          dateMode,
          startDate,
          endDate,
          preStartDate,
          preEndDate,
          postStartDate,
          postEndDate,
          rcaThresholds,
          severityThresholds,
          anomalyThresholds,
          result,
          resultView,
          resultScope,
          selectedPreset,
          presetName,
        }),
      );
    } catch {
      // ignore storage full / serialization failures
    }
  }, [
    vendor,
    baseMapping,
    kpiState,
    dateMode,
    startDate,
    endDate,
    preStartDate,
    preEndDate,
    postStartDate,
    postEndDate,
    rcaThresholds,
    severityThresholds,
    anomalyThresholds,
    result,
    resultView,
    resultScope,
    selectedPreset,
    presetName,
  ]);

  const rcaGroupedThresholds = useMemo(
    () => groupRcaThresholdKeys(Object.keys(RCA_DEFAULTS)),
    [],
  );

  useEffect(() => {
    const loadFiles = async () => {
      const response = await fetchKpiUploadHistory();
      const fileRows = response?.success ? response.data || [] : [];
      setFiles(fileRows);
      if (fileRows.length) {
        setSelectedFileId((prev) => prev || String(fileRows[0].id));
      }
    };
    loadFiles();
  }, []);

  useEffect(() => {
    const loadColumns = async () => {
      if (!sharedSelectedFileId) {
        setColumns([]);
        return;
      }
      const data = await fetchDynamicKpiColumns(sharedSelectedFileId);
      const nextColumns = Array.isArray(data?.columns) ? data.columns : [];
      setColumns(nextColumns);
      setKpiState((prev) => suggestKpiMapping(vendor, nextColumns, prev, true));
    };
    loadColumns();
  }, [sharedSelectedFileId, vendor]);

  useEffect(() => {
    const loadPresets = async () => {
      const data = await fetchRecommendationPresets();
      setPresets(Array.isArray(data?.presets) ? data.presets : []);
    };
    loadPresets();
  }, []);

  const columnOptions = useMemo(
    () => columns.map((col) => ({ value: col.key, label: `${col.label} (${col.key})` })),
    [columns],
  );

  const selectedFileLabel = useMemo(() => {
    const hit = files.find((f) => String(f.id) === String(sharedSelectedFileId));
    return hit?.fileName || "";
  }, [files, sharedSelectedFileId]);

  const payload = useMemo(() => {
    const selectedKpis = Object.entries(kpiState)
      .filter(([, meta]) => meta.selected)
      .map(([name]) => name);

    const kpiMapping = {};
    const kpis = {};
    selectedKpis.forEach((name) => {
      const meta = kpiState[name];
      if (meta.mappedColumn) kpiMapping[name] = meta.mappedColumn;
      kpis[name] = {
        direction: meta.direction,
        threshold: Number(meta.threshold),
        weight: Number(meta.weight),
        min_scale: Number(meta.min_scale),
      };
    });

    return {
      vendor,
      columnMapping: baseMapping,
      kpiMapping,
      selectedKpis,
      kpis,
      dateMode,
      startDate: startDate || null,
      endDate: endDate || null,
      preStartDate: preStartDate || null,
      preEndDate: preEndDate || null,
      postStartDate: postStartDate || null,
      postEndDate: postEndDate || null,
      rcaThresholds: asNumberMap(rcaThresholds, RCA_DEFAULTS),
      severityThresholds: asNumberMap(severityThresholds, SEVERITY_DEFAULTS),
      anomalyThresholds: asNumberMap(anomalyThresholds, ANOMALY_DEFAULTS),
    };
  }, [
    vendor,
    baseMapping,
    kpiState,
    dateMode,
    startDate,
    endDate,
    preStartDate,
    preEndDate,
    postStartDate,
    postEndDate,
    rcaThresholds,
    severityThresholds,
    anomalyThresholds,
  ]);

  const validateDateInputs = () => {
    if (dateMode === "pre_post") {
      if (!preStartDate || !preEndDate || !postStartDate || !postEndDate) {
        return "Please select pre and post start/end dates.";
      }
      if (preStartDate > preEndDate) return "Pre start date cannot be greater than pre end date.";
      if (postStartDate > postEndDate) return "Post start date cannot be greater than post end date.";
      if (preEndDate >= postStartDate) return "Pre end date must be earlier than post start date.";
      return "";
    }
    if (startDate && endDate && startDate > endDate) {
      return "Start date cannot be greater than end date.";
    }
    return "";
  };

  const handleRun = async () => {
    if (!sharedSelectedFileId) return;
    const dateError = validateDateInputs();
    if (dateError) {
      setError(dateError);
      return;
    }
    setError("");
    setLoading(true);
    try {
      setResult(await runRecommendation(sharedSelectedFileId, payload));
    } catch (err) {
      setError(err?.message || "Failed to run recommendation.");
    } finally {
      setLoading(false);
    }
  };

  const handleExport = async () => {
    if (!sharedSelectedFileId) return;
    const dateError = validateDateInputs();
    if (dateError) {
      setError(dateError);
      return;
    }
    setError("");
    setExporting(true);
    try {
      const { blob, fileName } = await downloadRecommendationExport(sharedSelectedFileId, payload);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = fileName;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      setError(err?.message || "Failed to export recommendation.");
    } finally {
      setExporting(false);
    }
  };

  const handleSavePreset = async () => {
    if (!presetName.trim()) return;
    setError("");
    try {
      await saveRecommendationPreset(presetName.trim(), payload);
      const data = await fetchRecommendationPresets();
      setPresets(Array.isArray(data?.presets) ? data.presets : []);
      setPresetName("");
    } catch (err) {
      setError(err?.message || "Failed to save preset.");
    }
  };

  const handleLoadPreset = async () => {
    if (!selectedPreset) return;
    setError("");
    try {
      const response = await loadRecommendationPreset(selectedPreset);
      const cfg = response?.config || {};
      setVendor(cfg.vendor || "nokia");
      setBaseMapping(cfg.column_mapping || BASE_DEFAULT_MAPPING);

      const nextKpiState = buildDefaultKpiState();
      const selected = new Set(Array.isArray(cfg.selected_kpis) ? cfg.selected_kpis : []);
      Object.keys(nextKpiState).forEach((kpiName) => {
        const mapped = cfg?.kpi_mapping?.[kpiName] || "";
        const override = cfg?.kpis?.[kpiName] || {};
        nextKpiState[kpiName] = {
          ...nextKpiState[kpiName],
          selected: selected.size ? selected.has(kpiName) : true,
          mappedColumn: mapped,
          direction: override.direction || nextKpiState[kpiName].direction,
          threshold: override.threshold ?? nextKpiState[kpiName].threshold,
          weight: override.weight ?? nextKpiState[kpiName].weight,
          min_scale: override.min_scale ?? nextKpiState[kpiName].min_scale,
        };
      });

      setKpiState(nextKpiState);
      const nextDateMode = cfg?.date_mode === "pre_post" ? "pre_post" : "single";
      setDateMode(nextDateMode);
      setStartDate(cfg?.date_filter?.start_date || "");
      setEndDate(cfg?.date_filter?.end_date || "");
      setPreStartDate(cfg?.pre_date_filter?.start_date || "");
      setPreEndDate(cfg?.pre_date_filter?.end_date || "");
      setPostStartDate(cfg?.post_date_filter?.start_date || "");
      setPostEndDate(cfg?.post_date_filter?.end_date || "");
      setRcaThresholds({ ...RCA_DEFAULTS, ...(cfg.rca_thresholds || {}) });
      setSeverityThresholds({ ...SEVERITY_DEFAULTS, ...(cfg.severity_thresholds || {}) });
      setAnomalyThresholds({ ...ANOMALY_DEFAULTS, ...(cfg.anomaly_thresholds || {}) });
    } catch (err) {
      setError(err?.message || "Failed to load preset.");
    }
  };

  return (
    <div className="min-h-screen bg-gradient-to-b from-stone-100 via-zinc-50 to-white p-4 md:p-6">
      <div className="mx-auto w-full max-w-[98vw] space-y-4">
        <RecommendationConfigPanel
          files={files}
          selectedFileId={sharedSelectedFileId}
          setSelectedFileId={setSelectedFileId}
          vendor={vendor}
          setVendor={setVendor}
          presets={presets}
          selectedPreset={selectedPreset}
          setSelectedPreset={setSelectedPreset}
          handleLoadPreset={handleLoadPreset}
          baseMapping={baseMapping}
          setBaseMapping={setBaseMapping}
          columnOptions={columnOptions}
          dateMode={dateMode}
          setDateMode={setDateMode}
          startDate={startDate}
          setStartDate={setStartDate}
          endDate={endDate}
          setEndDate={setEndDate}
          preStartDate={preStartDate}
          setPreStartDate={setPreStartDate}
          preEndDate={preEndDate}
          setPreEndDate={setPreEndDate}
          postStartDate={postStartDate}
          setPostStartDate={setPostStartDate}
          postEndDate={postEndDate}
          setPostEndDate={setPostEndDate}
          kpiState={kpiState}
          setKpiState={setKpiState}
          onAutoSuggest={() => setKpiState((prev) => suggestKpiMapping(vendor, columns, prev, false))}
          rcaGroupedThresholds={rcaGroupedThresholds}
          rcaThresholds={rcaThresholds}
          setRcaThresholds={setRcaThresholds}
          severityThresholds={severityThresholds}
          setSeverityThresholds={setSeverityThresholds}
          anomalyThresholds={anomalyThresholds}
          setAnomalyThresholds={setAnomalyThresholds}
          handleRun={handleRun}
          loading={loading}
          handleExport={handleExport}
          exporting={exporting}
          presetName={presetName}
          setPresetName={setPresetName}
          handleSavePreset={handleSavePreset}
          error={error}
        />

        <RecommendationResultsPanel
          result={result}
          resultView={resultView}
          setResultView={setResultView}
          resultScope={resultScope}
          setResultScope={setResultScope}
          selectedFileId={sharedSelectedFileId}
          selectedFileLabel={selectedFileLabel}
          currentConfig={payload}
          availableColumns={columns}
        />
      </div>
    </div>
  );
}

import React from "react";
import { ChevronDown, Download, Loader2, Play, Save, WandSparkles } from "lucide-react";
import {
  ANOMALY_DEFAULTS,
  BASE_FIELDS,
  RCA_LABELS,
  SEVERITY_DEFAULTS,
  formatConfigLabel,
} from "./recommendationUtils";

export default function RecommendationConfigPanel({
  files,
  selectedFileId,
  setSelectedFileId,
  vendor,
  setVendor,
  presets,
  selectedPreset,
  setSelectedPreset,
  handleLoadPreset,
  baseMapping,
  setBaseMapping,
  columnOptions,
  dateMode,
  setDateMode,
  startDate,
  setStartDate,
  endDate,
  setEndDate,
  preStartDate,
  setPreStartDate,
  preEndDate,
  setPreEndDate,
  postStartDate,
  setPostStartDate,
  postEndDate,
  setPostEndDate,
  kpiState,
  setKpiState,
  onAutoSuggest,
  rcaGroupedThresholds,
  rcaThresholds,
  setRcaThresholds,
  severityThresholds,
  setSeverityThresholds,
  anomalyThresholds,
  setAnomalyThresholds,
  handleRun,
  loading,
  handleExport,
  exporting,
  presetName,
  setPresetName,
  handleSavePreset,
  error,
}) {
  return (
    <>
      <section className="rounded-2xl border border-zinc-200 bg-white shadow-sm overflow-hidden">
        <div className="border-b border-zinc-100 bg-white p-6">
          <h2 className="text-xl font-bold tracking-tight text-zinc-900">RCA Recommendation</h2>
          <p className="mt-1 text-xs text-zinc-500 uppercase tracking-wider font-medium">
            Step 1: Configure Mapping & Scope
          </p>
        </div>

        <div className="p-6 space-y-8">
          <div className="grid grid-cols-1 gap-6 md:grid-cols-3">
            <div className="space-y-1.5">
              <label className="text-[13px] font-semibold text-zinc-700">Source File</label>
              <select
                value={selectedFileId}
                onChange={(e) => setSelectedFileId(e.target.value)}
                className="w-full rounded-xl border border-zinc-200 bg-zinc-50/50 px-3 py-2.5 text-sm focus:border-indigo-500 focus:bg-white focus:outline-none focus:ring-4 focus:ring-indigo-500/5 transition-all"
              >
                <option value="">Select file...</option>
                {files.map((f) => (
                  <option key={f.id} value={f.id}>
                    {f.fileName || `Upload ${f.id}`}
                  </option>
                ))}
              </select>
            </div>

            <div className="space-y-1.5">
              <label className="text-[13px] font-semibold text-zinc-700">Vendor Hardware</label>
              <select
                value={vendor}
                onChange={(e) => setVendor(e.target.value)}
                className="w-full rounded-xl border border-zinc-200 bg-zinc-50/50 px-3 py-2.5 text-sm focus:border-indigo-500 focus:bg-white focus:outline-none transition-all"
              >
                <option value="nokia">Nokia</option>
                <option value="ericsson">Ericsson</option>
              </select>
            </div>

            <div className="space-y-1.5">
              <label className="text-[13px] font-semibold text-zinc-700">Configuration Preset</label>
              <div className="flex gap-2">
                <select
                  value={selectedPreset}
                  onChange={(e) => setSelectedPreset(e.target.value)}
                  className="flex-1 rounded-xl border border-zinc-200 bg-zinc-50/50 px-3 py-2.5 text-sm focus:border-indigo-500 focus:bg-white focus:outline-none transition-all"
                >
                  <option value="">Default settings</option>
                  {presets.map((name) => (
                    <option key={name} value={name}>
                      {name}
                    </option>
                  ))}
                </select>
                <button
                  type="button"
                  onClick={handleLoadPreset}
                  className="rounded-xl border border-zinc-200 bg-white px-4 py-2.5 text-sm font-bold text-zinc-700 hover:bg-zinc-50 active:scale-95 transition-all"
                >
                  Load
                </button>
              </div>
            </div>
          </div>

          <div className="rounded-2xl bg-zinc-50/50 border border-zinc-100 p-5">
            <div className="mb-4 flex items-center gap-2">
              <div className="h-1 w-1 rounded-full bg-zinc-400" />
              <span className="text-[11px] font-bold uppercase tracking-widest text-zinc-500">Core Column Mapping</span>
            </div>
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-5">
              {BASE_FIELDS.map((fieldName) => (
                <div key={fieldName} className="space-y-1.5">
                  <label className="text-[12px] font-medium text-zinc-600 pl-1">{fieldName}</label>
                  <select
                    value={baseMapping[fieldName] || ""}
                    onChange={(e) =>
                      setBaseMapping((prev) => ({
                        ...prev,
                        [fieldName]: e.target.value,
                      }))
                    }
                    className="w-full rounded-lg border border-zinc-200 bg-white px-3 py-2 text-sm focus:border-indigo-500 focus:outline-none shadow-sm"
                  >
                    <option value="">Select column</option>
                    {columnOptions.map((opt) => (
                      <option key={`${fieldName}-${opt.value}`} value={opt.value}>
                        {opt.label}
                      </option>
                    ))}
                  </select>
                </div>
              ))}
            </div>
          </div>

          <div className="border-t border-zinc-100 pt-6 space-y-4">
            <div className="inline-flex rounded-lg border border-zinc-300 p-1">
              <button
                type="button"
                onClick={() => setDateMode("single")}
                className={`rounded-md px-3 py-1.5 text-sm ${dateMode === "single" ? "bg-zinc-900 text-white" : "text-zinc-700 hover:bg-zinc-100"}`}
              >
                Single Range
              </button>
              <button
                type="button"
                onClick={() => setDateMode("pre_post")}
                className={`rounded-md px-3 py-1.5 text-sm ${dateMode === "pre_post" ? "bg-zinc-900 text-white" : "text-zinc-700 hover:bg-zinc-100"}`}
              >
                Pre vs Post
              </button>
            </div>

            {dateMode === "pre_post" ? (
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                <div className="rounded-xl border border-zinc-200 bg-zinc-50/50 p-4">
                  <p className="text-[12px] font-bold uppercase tracking-wider text-zinc-600 mb-2">Pre Range</p>
                  <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                    <div className="space-y-1.5">
                      <label className="text-[12px] font-medium text-zinc-700">Start Date</label>
                      <input
                        type="date"
                        value={preStartDate}
                        onChange={(e) => setPreStartDate(e.target.value)}
                        className="w-full rounded-xl border border-zinc-200 bg-white px-3 py-2.5 text-sm focus:border-indigo-500 focus:outline-none"
                      />
                    </div>
                    <div className="space-y-1.5">
                      <label className="text-[12px] font-medium text-zinc-700">End Date</label>
                      <input
                        type="date"
                        value={preEndDate}
                        onChange={(e) => setPreEndDate(e.target.value)}
                        className="w-full rounded-xl border border-zinc-200 bg-white px-3 py-2.5 text-sm focus:border-indigo-500 focus:outline-none"
                      />
                    </div>
                  </div>
                </div>

                <div className="rounded-xl border border-zinc-200 bg-zinc-50/50 p-4">
                  <p className="text-[12px] font-bold uppercase tracking-wider text-zinc-600 mb-2">Post Range</p>
                  <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                    <div className="space-y-1.5">
                      <label className="text-[12px] font-medium text-zinc-700">Start Date</label>
                      <input
                        type="date"
                        value={postStartDate}
                        onChange={(e) => setPostStartDate(e.target.value)}
                        className="w-full rounded-xl border border-zinc-200 bg-white px-3 py-2.5 text-sm focus:border-indigo-500 focus:outline-none"
                      />
                    </div>
                    <div className="space-y-1.5">
                      <label className="text-[12px] font-medium text-zinc-700">End Date</label>
                      <input
                        type="date"
                        value={postEndDate}
                        onChange={(e) => setPostEndDate(e.target.value)}
                        className="w-full rounded-xl border border-zinc-200 bg-white px-3 py-2.5 text-sm focus:border-indigo-500 focus:outline-none"
                      />
                    </div>
                  </div>
                </div>
              </div>
            ) : (
              <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
                <div className="space-y-1.5">
                  <label className="text-[13px] font-semibold text-zinc-700">
                    Analysis Start Date <span className="text-zinc-400 font-normal">(Optional)</span>
                  </label>
                  <input
                    type="date"
                    value={startDate}
                    onChange={(e) => setStartDate(e.target.value)}
                    className="w-full rounded-xl border border-zinc-200 bg-zinc-50/50 px-4 py-2.5 text-sm focus:border-indigo-500 focus:bg-white focus:outline-none transition-all"
                  />
                </div>
                <div className="space-y-1.5">
                  <label className="text-[13px] font-semibold text-zinc-700">
                    Analysis End Date <span className="text-zinc-400 font-normal">(Optional)</span>
                  </label>
                  <input
                    type="date"
                    value={endDate}
                    onChange={(e) => setEndDate(e.target.value)}
                    className="w-full rounded-xl border border-zinc-200 bg-zinc-50/50 px-4 py-2.5 text-sm focus:border-indigo-500 focus:bg-white focus:outline-none transition-all"
                  />
                </div>
              </div>
            )}
          </div>
        </div>
      </section>

      <section className="rounded-2xl border border-zinc-200 bg-white shadow-sm overflow-hidden">
        <details className="group">
          <summary className="flex cursor-pointer items-center justify-between border-b border-zinc-100 bg-zinc-50/50 px-6 py-4 transition-colors hover:bg-zinc-50">
            <div className="flex items-center gap-2">
              <ChevronDown className="h-4 w-4 text-zinc-400 transition-transform group-open:rotate-180" />
              <h3 className="text-sm font-bold text-zinc-900 tracking-tight">KPI Mapping and Thresholds</h3>
            </div>
            <button
              type="button"
              onClick={onAutoSuggest}
              className="inline-flex items-center gap-2 rounded-lg border border-zinc-200 bg-white px-3 py-1.5 text-[12px] font-bold text-zinc-600 shadow-sm transition-all hover:bg-zinc-50 hover:text-indigo-600 active:scale-95"
            >
              <WandSparkles className="h-3.5 w-3.5" />
              Auto Suggest Mapping
            </button>
          </summary>

          <div className="overflow-x-auto">
            <table className="w-full border-collapse text-left text-sm">
              <thead>
                <tr className="border-b border-zinc-100 bg-white">
                  <th className="px-6 py-3 text-[11px] font-bold uppercase tracking-wider text-zinc-400">Use</th>
                  <th className="px-3 py-3 text-[11px] font-bold uppercase tracking-wider text-zinc-400">KPI Name</th>
                  <th className="px-3 py-3 text-[11px] font-bold uppercase tracking-wider text-zinc-400">Mapped Column</th>
                  <th className="px-3 py-3 text-[11px] font-bold uppercase tracking-wider text-zinc-400">Direction</th>
                  <th className="px-3 py-3 text-[11px] font-bold uppercase tracking-wider text-zinc-400">Threshold</th>
                  <th className="px-3 py-3 text-[11px] font-bold uppercase tracking-wider text-zinc-400">Weight</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-zinc-50">
                {Object.keys(kpiState).map((kpiName) => {
                  const isSelected = kpiState[kpiName].selected;
                  return (
                    <tr key={kpiName} className={`transition-colors hover:bg-zinc-50/80 ${isSelected ? "bg-indigo-50/30" : "bg-white"}`}>
                      <td className="px-6 py-3">
                        <input
                          type="checkbox"
                          checked={isSelected}
                          onChange={(e) =>
                            setKpiState((prev) => ({
                              ...prev,
                              [kpiName]: { ...prev[kpiName], selected: e.target.checked },
                            }))
                          }
                          className="h-4 w-4 rounded border-zinc-300 text-indigo-600 focus:ring-indigo-500"
                        />
                      </td>
                      <td className="whitespace-nowrap px-3 py-3 font-semibold text-zinc-700">{kpiName}</td>
                      <td className="px-3 py-3">
                        <select
                          value={kpiState[kpiName].mappedColumn}
                          onChange={(e) =>
                            setKpiState((prev) => ({
                              ...prev,
                              [kpiName]: { ...prev[kpiName], mappedColumn: e.target.value },
                            }))
                          }
                          className="w-full min-w-[200px] rounded-md border-transparent bg-transparent px-2 py-1 text-sm text-zinc-600 transition-all hover:border-zinc-200 focus:border-indigo-500 focus:bg-white focus:outline-none"
                        >
                          <option value="">Not mapped (skip)</option>
                          {columnOptions.map((opt) => (
                            <option key={opt.value} value={opt.value}>
                              {opt.label}
                            </option>
                          ))}
                        </select>
                      </td>
                      <td className="px-3 py-3">
                        <select
                          value={kpiState[kpiName].direction}
                          onChange={(e) =>
                            setKpiState((prev) => ({
                              ...prev,
                              [kpiName]: { ...prev[kpiName], direction: e.target.value },
                            }))
                          }
                          className="w-full min-w-[120px] rounded-md border-transparent bg-transparent px-2 py-1 text-sm font-medium text-zinc-600 transition-all hover:border-zinc-200 focus:border-indigo-500 focus:bg-white focus:outline-none"
                        >
                          <option value="high_good">High is Good</option>
                          <option value="low_good">Low is Good</option>
                        </select>
                      </td>
                      <td className="px-3 py-3">
                        <input
                          type="number"
                          value={kpiState[kpiName].threshold}
                          onChange={(e) =>
                            setKpiState((prev) => ({
                              ...prev,
                              [kpiName]: { ...prev[kpiName], threshold: e.target.value },
                            }))
                          }
                          className="w-20 rounded-md border-transparent bg-transparent px-2 py-1 text-sm font-mono font-medium text-zinc-800 transition-all hover:border-zinc-200 focus:border-indigo-500 focus:bg-white focus:outline-none"
                        />
                      </td>
                      <td className="px-3 py-3">
                        <input
                          type="number"
                          step="0.1"
                          value={kpiState[kpiName].weight}
                          onChange={(e) =>
                            setKpiState((prev) => ({
                              ...prev,
                              [kpiName]: { ...prev[kpiName], weight: e.target.value },
                            }))
                          }
                          className="w-16 rounded-md border-transparent bg-transparent px-2 py-1 text-sm font-mono font-medium text-zinc-800 transition-all hover:border-zinc-200 focus:border-indigo-500 focus:bg-white focus:outline-none"
                        />
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </details>
      </section>

      <section className="rounded-2xl border border-zinc-200 bg-white shadow-md overflow-hidden font-sans">
        <div className="border-b border-zinc-100 bg-white p-6">
          <h3 className="text-base font-bold text-zinc-900 tracking-tight">Advanced RCA Rule Thresholds</h3>
          <p className="text-xs text-zinc-500 mt-1">Fine-tune rule cutoffs used by RCA scoring and severity bucketing.</p>
        </div>

        <details className="group">
          <summary className="flex cursor-pointer items-center justify-between border-b border-zinc-100 bg-zinc-50/50 px-6 py-3 transition-colors hover:bg-zinc-100/50">
            <span className="text-[11px] font-bold uppercase tracking-widest text-zinc-500">Configuration Settings</span>
            <ChevronDown className="h-4 w-4 text-zinc-400 transition-transform group-open:rotate-180" />
          </summary>

          <div className="p-6 space-y-10">
            {rcaGroupedThresholds.map((group) => (
              <div key={group.key} className="space-y-4">
                <div className="flex items-center gap-3">
                  <div className="h-4 w-1 rounded-full bg-indigo-500" />
                  <span className="text-[11px] font-bold uppercase tracking-[0.15em] text-indigo-600">{group.label}</span>
                </div>

                <div className="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-3">
                  {group.items.map((key) => (
                    <div key={key} className="flex items-center justify-between gap-4 rounded-xl border border-zinc-100 bg-zinc-50/30 p-2 pl-4 transition-all hover:border-zinc-200 hover:bg-zinc-50">
                      <span className="truncate text-[12px] font-medium text-zinc-600">{RCA_LABELS[key] || formatConfigLabel(key)}</span>
                      <input
                        type="number"
                        step="0.1"
                        value={rcaThresholds[key]}
                        onChange={(e) =>
                          setRcaThresholds((prev) => ({
                            ...prev,
                            [key]: e.target.value,
                          }))
                        }
                        className="h-8 w-20 rounded-lg border border-zinc-200 bg-white px-2 text-right text-[13px] font-semibold text-zinc-800 shadow-sm transition-focus focus:border-indigo-500 focus:outline-none focus:ring-4 focus:ring-indigo-500/5"
                      />
                    </div>
                  ))}
                </div>
              </div>
            ))}

            <div className="grid grid-cols-1 gap-8 border-t border-zinc-100 pt-10 lg:grid-cols-2">
              <div className="space-y-4">
                <div className="flex items-center gap-3 text-amber-600">
                  <div className="h-4 w-1 rounded-full bg-amber-500" />
                  <span className="text-[11px] font-bold uppercase tracking-widest">Severity Thresholds</span>
                </div>
                <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-1">
                  {Object.keys(SEVERITY_DEFAULTS).map((key) => (
                    <div key={key} className="flex items-center justify-between rounded-lg bg-zinc-50/50 p-2 px-4 border border-transparent hover:border-zinc-100">
                      <span className="text-[12px] font-medium text-zinc-600">{formatConfigLabel(key)}</span>
                      <input
                        type="number"
                        value={severityThresholds[key]}
                        onChange={(e) =>
                          setSeverityThresholds((prev) => ({
                            ...prev,
                            [key]: e.target.value,
                          }))
                        }
                        className="h-8 w-20 rounded-md border border-zinc-200 bg-white text-right text-xs font-bold focus:border-amber-500 focus:outline-none"
                      />
                    </div>
                  ))}
                </div>
              </div>

              <div className="space-y-4">
                <div className="flex items-center gap-3 text-amber-600">
                  <div className="h-4 w-1 rounded-full bg-amber-500" />
                  <span className="text-[11px] font-bold uppercase tracking-widest">Anomaly Detection</span>
                </div>
                <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-1">
                  {Object.keys(ANOMALY_DEFAULTS).map((key) => (
                    <div key={key} className="flex items-center justify-between rounded-lg bg-zinc-50/50 p-2 px-4 border border-transparent hover:border-zinc-100">
                      <span className="text-[12px] font-medium text-zinc-600">{formatConfigLabel(key)}</span>
                      <input
                        type="number"
                        value={anomalyThresholds[key]}
                        onChange={(e) =>
                          setAnomalyThresholds((prev) => ({
                            ...prev,
                            [key]: e.target.value,
                          }))
                        }
                        className="h-8 w-20 rounded-md border border-zinc-200 bg-white text-right text-xs font-bold focus:border-emerald-500 focus:outline-none"
                      />
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </div>
        </details>

        <div className="flex flex-col items-center justify-between gap-4 border-t border-zinc-100 bg-white p-6 sm:flex-row">
          <div className="flex items-center gap-3">
            <button
              onClick={handleRun}
              disabled={loading || !selectedFileId}
              className="flex h-11 items-center gap-2 rounded-xl bg-zinc-900 px-6 text-sm font-bold text-white shadow-lg shadow-zinc-200 transition-all hover:bg-zinc-800 active:scale-95 disabled:opacity-30"
            >
              {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4 fill-current" />}
              Run Recommendation
            </button>
            <button
              onClick={handleExport}
              disabled={exporting || !selectedFileId}
              className="flex h-11 items-center gap-2 rounded-xl border border-zinc-200 bg-white px-5 text-sm font-bold text-zinc-700 hover:bg-zinc-50 active:scale-95 disabled:opacity-40"
            >
              {exporting ? <Loader2 className="h-4 w-4 animate-spin" /> : <Download className="h-4 w-4" />}
              Export
            </button>
          </div>

          <div className="flex items-center gap-2">
            <div className="relative">
              <input
                value={presetName}
                onChange={(e) => setPresetName(e.target.value)}
                placeholder="Preset name..."
                className="h-11 w-48 rounded-xl border border-zinc-200 bg-zinc-50/50 pl-4 pr-10 text-sm focus:border-indigo-500 focus:bg-white focus:outline-none"
              />
              <button
                onClick={handleSavePreset}
                className="absolute right-2 top-1.5 flex h-8 w-8 items-center justify-center rounded-lg text-zinc-400 hover:bg-zinc-100 hover:text-zinc-600"
              >
                <Save className="h-4 w-4" />
              </button>
            </div>
          </div>
        </div>
        {error ? <div className="mx-6 mb-6 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600">{error}</div> : null}
      </section>
    </>
  );
}

export const BASE_FIELDS = ["Short name", "Date", "Cell I'd", "Site I'd", "Tech"];

export const BASE_DEFAULT_MAPPING = {
  "Short name": "cellname",
  Date: "date",
  "Cell I'd": "cellname",
  "Site I'd": "site",
  Tech: "tech",
};

export const KPI_DEFAULTS = {
  "MV_PUSCH SINR": { direction: "high_good", threshold: 6, weight: 1.2, min_scale: 0.8 },
  "MV_E-UTRAN Average CQI": { direction: "high_good", threshold: 7, weight: 1.0, min_scale: 0.3 },
  "DL PRB Utilisation": { direction: "low_good", threshold: 70, weight: 1.0, min_scale: 5.0 },
  "Average UE Distance_KM": { direction: "low_good", threshold: 1.2, weight: 0.7, min_scale: 0.05 },
  "MV_Packet Loss DL": { direction: "low_good", threshold: 0.05, weight: 0.8, min_scale: 0.02 },
  "MV_Packet Loss UL": { direction: "low_good", threshold: 2.0, weight: 0.8, min_scale: 0.2 },
  "MV_Radio NW Availability": { direction: "high_good", threshold: 97.0, weight: 1.4, min_scale: 0.5 },
  "MV_VoLTE DCR": { direction: "low_good", threshold: 1.0, weight: 1.0, min_scale: 0.2 },
  "VoLTE ERAB Setup Success Rate": { direction: "high_good", threshold: 98.5, weight: 1.0, min_scale: 0.5 },
  "VoLTE InterF HOSR": { direction: "high_good", threshold: 95, weight: 0.9, min_scale: 1.0 },
  "VoLTE IntraF HOSR [CBBH]": { direction: "high_good", threshold: 95, weight: 0.9, min_scale: 1.0 },
  "UL User Throughput_Kbps": { direction: "high_good", threshold: 250, weight: 0.9, min_scale: 80 },
  "PS IntraF HOSR [CDBH]": { direction: "high_good", threshold: 95, weight: 0.9, min_scale: 1.0 },
  "PS InterF HOSR": { direction: "high_good", threshold: 95, weight: 0.9, min_scale: 1.0 },
  "RRC Setup Success Rate": { direction: "high_good", threshold: 98, weight: 1.2, min_scale: 0.5 },
  "MV_ERAB Setup Success Rate": { direction: "high_good", threshold: 98.5, weight: 1.1, min_scale: 0.4 },
  "MV_DL User Throughput_Mbps": { direction: "high_good", threshold: 5, weight: 1.3, min_scale: 1.5 },
  "MV_% RSRP Sample <-110 dBm": { direction: "low_good", threshold: 35, weight: 1.1, min_scale: 3 },
  "MV_PS Drop Call Rate %": { direction: "low_good", threshold: 1.0, weight: 1.2, min_scale: 0.2 },
  "MV_VoLTE CSSR%": { direction: "high_good", threshold: 98.5, weight: 1.0, min_scale: 0.5 },
  "Avg RSSI PUSCH": { direction: "low_good", threshold: -103, weight: 0.8, min_scale: 1.5 },
  "Avg RSSI PUCCH": { direction: "low_good", threshold: -103, weight: 0.7, min_scale: 1.5 },
  "UL NI [RSSI-SINR]": { direction: "low_good", threshold: -113, weight: 0.8, min_scale: 1.0 },
  "MV_VoLTE Packet Loss DL": { direction: "low_good", threshold: 0.3, weight: 0.8, min_scale: 0.1 },
  "MV_VoLTE Packet Loss UL": { direction: "low_good", threshold: 0.3, weight: 0.8, min_scale: 0.1 },
};

export const RCA_DEFAULTS = {
  coverage_rsrp_poor: 35,
  coverage_ue_distance: 1,
  coverage_pusch_sinr_low: 5,
  coverage_dl_tp_low: 5,
  ul_interference_pusch_sinr_low: 5,
  ul_interference_rssi_pusch_high: -103,
  ul_interference_ul_ni_high: -113,
  ul_interference_packet_loss_ul_high: 3,
  congestion_dl_prb_high: 70,
  congestion_connected_users_high: 40,
  mobility_drop_rate_high: 1,
  mobility_volte_dcr_high: 1,
  mobility_hosr_low: 95,
  accessibility_rrc_setup_low: 98,
  accessibility_erab_setup_low: 98.5,
  accessibility_volte_erab_setup_low: 98.5,
  accessibility_volte_cssr_low: 98.5,
  availability_radio_low: 97,
  transport_packet_loss_dl_high: 0.1,
  transport_packet_loss_ul_high: 3,
  transport_volte_packet_loss_dl_high: 0.5,
  transport_volte_packet_loss_ul_high: 0.5,
};

export const SEVERITY_DEFAULTS = {
  critical_score: 35,
  major_score: 22,
  moderate_score: 15,
  critical_drop_rate: 3,
  critical_volte_dcr: 5,
};

export const ANOMALY_DEFAULTS = {
  is_anomaly_score_threshold: 15,
  fallback_general_score_threshold: 18,
};

export const RCA_LABELS = {
  coverage_rsrp_poor: "Coverage poor RSRP >",
  coverage_ue_distance: "Coverage UE distance >",
  coverage_pusch_sinr_low: "Coverage SINR <",
  coverage_dl_tp_low: "Coverage DL throughput <",
  ul_interference_pusch_sinr_low: "UL Interference SINR <",
  ul_interference_rssi_pusch_high: "UL Interference RSSI >",
  ul_interference_ul_ni_high: "UL Interference UL NI >",
  ul_interference_packet_loss_ul_high: "UL Interference UL packet loss >",
  congestion_dl_prb_high: "Congestion DL PRB >",
  congestion_connected_users_high: "Congestion connected users >",
  mobility_drop_rate_high: "Mobility drop rate >",
  mobility_volte_dcr_high: "Mobility VoLTE DCR >",
  mobility_hosr_low: "Mobility HOSR <",
  accessibility_rrc_setup_low: "Accessibility RRC SR <",
  accessibility_erab_setup_low: "Accessibility ERAB SR <",
  accessibility_volte_erab_setup_low: "Accessibility VoLTE ERAB SR <",
  accessibility_volte_cssr_low: "Accessibility VoLTE CSSR <",
  availability_radio_low: "Availability radio <",
  transport_packet_loss_dl_high: "Transport packet loss DL >",
  transport_packet_loss_ul_high: "Transport packet loss UL >",
  transport_volte_packet_loss_dl_high: "Transport VoLTE packet loss DL >",
  transport_volte_packet_loss_ul_high: "Transport VoLTE packet loss UL >",
};

const KPI_ALIAS_MAP = {
  "MV_PUSCH SINR": { common: ["pusch sinr", "ul sinr"], ericsson: ["puschsinr"] },
  "MV_E-UTRAN Average CQI": { common: ["average cqi", "cqi"] },
  "DL PRB Utilisation": { common: ["dl prb utilisation", "dl prb utilization", "prb dl"] },
  "Average UE Distance_KM": { common: ["average ue distance", "ue distance"] },
  "MV_Packet Loss DL": { common: ["packet loss dl"] },
  "MV_Packet Loss UL": { common: ["packet loss ul"] },
  "MV_Radio NW Availability": { common: ["radio availability", "cell availability"] },
  "MV_VoLTE DCR": { common: ["volte dcr"] },
  "VoLTE ERAB Setup Success Rate": { common: ["volte erab setup success"] },
  "VoLTE InterF HOSR": { common: ["volte interf hosr", "volte inter freq hosr"] },
  "VoLTE IntraF HOSR [CBBH]": { common: ["volte intraf hosr", "volte intra freq hosr"] },
  "UL User Throughput_Kbps": { common: ["ul user throughput", "throughput ul"] },
  "PS IntraF HOSR [CDBH]": { common: ["ps intraf hosr", "intra freq hosr"] },
  "PS InterF HOSR": { common: ["ps interf hosr", "inter freq hosr"] },
  "RRC Setup Success Rate": { common: ["rrc setup success", "rrc establishment success"] },
  "MV_ERAB Setup Success Rate": { common: ["erab setup success"] },
  "MV_DL User Throughput_Mbps": { common: ["dl user throughput", "throughput dl"] },
  "MV_% RSRP Sample <-110 dBm": { common: ["rsrp sample", "rsrp weak", "rsrp -110"] },
  "MV_PS Drop Call Rate %": { common: ["ps drop call rate", "drop rate"] },
  "MV_VoLTE CSSR%": { common: ["volte cssr"] },
  "Avg RSSI PUSCH": { common: ["rssi pusch"] },
  "Avg RSSI PUCCH": { common: ["rssi pucch"] },
  "UL NI [RSSI-SINR]": { common: ["ul ni", "noise indicator"] },
  "MV_VoLTE Packet Loss DL": { common: ["volte packet loss dl"] },
  "MV_VoLTE Packet Loss UL": { common: ["volte packet loss ul"] },
};

export const buildDefaultKpiState = () =>
  Object.fromEntries(
    Object.entries(KPI_DEFAULTS).map(([name, meta]) => [
      name,
      {
        selected: true,
        mappedColumn: "",
        direction: meta.direction,
        threshold: meta.threshold,
        weight: meta.weight,
        min_scale: meta.min_scale,
      },
    ]),
  );

const normalizeKey = (value) =>
  String(value || "")
    .toLowerCase()
    .replace(/[^a-z0-9]/g, " ")
    .replace(/\s+/g, " ")
    .trim();

export const normalizeShortName = (value) => String(value || "").trim().toLowerCase();

export const asNumberMap = (current, defaults) =>
  Object.fromEntries(
    Object.keys(defaults).map((key) => {
      const parsed = Number(current?.[key]);
      return [key, Number.isFinite(parsed) ? parsed : defaults[key]];
    }),
  );

const scoreCandidate = (candidate, patterns) => {
  const text = normalizeKey(candidate);
  let score = 0;
  patterns.forEach((phrase) => {
    const normPhrase = normalizeKey(phrase);
    if (!normPhrase) return;
    if (text.includes(normPhrase)) score += 10;
    normPhrase.split(" ").forEach((token) => {
      if (token.length >= 3 && text.includes(token)) score += 1;
    });
  });
  return score;
};

export const suggestKpiMapping = (vendor, columns, currentState, onlyEmpty = true) => {
  const next = { ...currentState };
  Object.keys(next).forEach((kpiName) => {
    if (onlyEmpty && next[kpiName].mappedColumn) return;
    const aliases = KPI_ALIAS_MAP[kpiName] || {};
    const patterns = [
      ...(aliases.common || []),
      ...((vendor === "ericsson" ? aliases.ericsson : aliases.nokia) || []),
      kpiName,
    ];
    let best = { key: "", score: 0 };
    columns.forEach((col) => {
      const candidate = `${col.key} ${col.label} ${col.rawLabel || ""}`;
      const s = scoreCandidate(candidate, patterns);
      if (s > best.score) best = { key: col.key, score: s };
    });
    if (best.score >= 6) {
      next[kpiName] = { ...next[kpiName], mappedColumn: best.key };
    }
  });
  return next;
};

export const toDateKey = (value) => {
  if (!value) return "";
  const text = String(value);
  if (/^\d{4}-\d{2}-\d{2}$/.test(text)) return text;
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return "";
  const year = parsed.getFullYear();
  const month = String(parsed.getMonth() + 1).padStart(2, "0");
  const day = String(parsed.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
};

export const formatDateHeader = (dateKey) => {
  if (!dateKey) return "";
  const parsed = new Date(`${dateKey}T00:00:00`);
  if (Number.isNaN(parsed.getTime())) return dateKey;
  return parsed.toLocaleDateString("en-GB", { day: "2-digit", month: "short" });
};

export const formatConfigLabel = (key) =>
  String(key || "")
    .replace(/_/g, " ")
    .replace(/\b\w/g, (m) => m.toUpperCase());

const RCA_GROUP_ORDER = [
  "coverage",
  "ul_interference",
  "congestion",
  "mobility",
  "accessibility",
  "availability",
  "transport",
  "other",
];

const RCA_GROUP_LABELS = {
  coverage: "Coverage",
  ul_interference: "UL Interference",
  congestion: "Congestion",
  mobility: "Mobility",
  accessibility: "Accessibility",
  availability: "Availability",
  transport: "Transport",
  other: "Other",
};

export const groupRcaThresholdKeys = (keys) => {
  const grouped = keys.reduce((acc, key) => {
    const normalized = String(key || "");
    const knownGroup = RCA_GROUP_ORDER.find(
      (group) => group !== "other" && normalized.startsWith(`${group}_`),
    );
    const bucket = knownGroup || "other";
    if (!acc[bucket]) acc[bucket] = [];
    acc[bucket].push(key);
    return acc;
  }, {});

  return RCA_GROUP_ORDER
    .filter((bucket) => Array.isArray(grouped[bucket]) && grouped[bucket].length)
    .map((bucket) => ({
      key: bucket,
      label: RCA_GROUP_LABELS[bucket] || formatConfigLabel(bucket),
      items: grouped[bucket].sort((a, b) => {
        const la = RCA_LABELS[a] || formatConfigLabel(a);
        const lb = RCA_LABELS[b] || formatConfigLabel(b);
        return la.localeCompare(lb);
      }),
    }));
};

export const getHeatColor = (score, minScore, maxScore) => {
  if (score === null || score === undefined || Number.isNaN(Number(score))) return "#f4f4f5";
  if (maxScore <= minScore) return "#e31a1c";
  const palette = ["#ffffcc", "#ffeda0", "#fed976", "#feb24c", "#fd8d3c", "#fc4e2a", "#e31a1c", "#8f001f"];
  const t = Math.max(0, Math.min(1, (Number(score) - minScore) / (maxScore - minScore)));
  const scaled = t * (palette.length - 1);
  const lowIdx = Math.floor(scaled);
  const highIdx = Math.min(palette.length - 1, lowIdx + 1);
  const alpha = scaled - lowIdx;
  const hexToRgb = (hex) => ({
    r: parseInt(hex.slice(1, 3), 16),
    g: parseInt(hex.slice(3, 5), 16),
    b: parseInt(hex.slice(5, 7), 16),
  });
  const a = hexToRgb(palette[lowIdx]);
  const b = hexToRgb(palette[highIdx]);
  const toHex = (v) => Math.round(v).toString(16).padStart(2, "0");
  return `#${toHex(a.r + (b.r - a.r) * alpha)}${toHex(a.g + (b.g - a.g) * alpha)}${toHex(a.b + (b.b - a.b) * alpha)}`;
};

export const LINE_COLORS = [
  "#2563eb",
  "#dc2626",
  "#16a34a",
  "#9333ea",
  "#d97706",
  "#0891b2",
  "#be123c",
  "#4f46e5",
  "#0f766e",
  "#7c2d12",
];

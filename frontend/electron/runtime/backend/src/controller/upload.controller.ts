import type { Request, Response } from "express";
import path from "path";
import fs from "fs";
import { parseFile } from "../utils/parserFile.ts";
import { prisma } from "../config/prisma.ts";
import { deleteKpiCache, writeKpiCache } from "../utils/kpiDynamicCache.ts";

type RawRow = Record<string, any>;
type NormalizedRow = Record<string, any>;
type DatasetType = "kpi" | "site" | "alarm";

const ALLOWED_EXTENSIONS = [".xlsx", ".xls", ".csv"];
const INSERT_CHUNK_SIZE = 2000;
const isKpiDebugEnabled = process.env.KPI_DEBUG_LOGS === "1";
const kpiDebugLog = (...args: any[]) => {
  if (isKpiDebugEnabled) {
    console.info(...args);
  }
};

const canonicalizeHeader = (key: string): string =>
  String(key).toLowerCase().replace(/[^a-z0-9]/g, "");

const normalizeRow = (row: RawRow): NormalizedRow => {
  const normalized: NormalizedRow = {};
  for (const [key, value] of Object.entries(row)) {
    normalized[canonicalizeHeader(key)] = value;
  }
  return normalized;
};

const pickValue = (row: NormalizedRow, aliases: string[]): any => {
  for (const alias of aliases) {
    const value = row[canonicalizeHeader(alias)];
    if (value !== undefined) return value;
  }
  return undefined;
};

const safeString = (value: any): string | null => {
  if (value === null || value === undefined) return null;
  if (typeof value === "object") return null;
  const parsed = String(value).trim();
  return parsed === "" ? null : parsed;
};

const parseFloatSafe = (value: any): number | null => {
  if (value === null || value === undefined || value === "") return null;
  if (typeof value === "object") return null;
  const cleaned = String(value).replace(/[%,\s]/g, "").trim();
  const parsed = parseFloat(cleaned);
  return Number.isNaN(parsed) ? null : parsed;
};

const parseIntSafe = (value: any): number | null => {
  if (value === null || value === undefined || value === "") return null;
  if (typeof value === "object") return null;
  const parsed = parseInt(String(value), 10);
  return Number.isNaN(parsed) ? null : parsed;
};

const parseDateSafe = (value: any): Date | null => {
  if (value === null || value === undefined || value === "") return null;
  if (value instanceof Date && !Number.isNaN(value.getTime())) return value;

  if (typeof value === "number") {
    // Handles Excel serial dates when numeric values appear in input.
    const excelDate = new Date(Math.round((value - 25569) * 86400 * 1000));
    if (!Number.isNaN(excelDate.getTime())) return excelDate;
  }

  const parsed = new Date(String(value));
  return Number.isNaN(parsed.getTime()) ? null : parsed;
};

const deleteTempFile = (filePath?: string) => {
  if (filePath && fs.existsSync(filePath)) {
    fs.unlinkSync(filePath);
  }
};

const insertInChunks = async (
  runInsert: (chunk: any[]) => Promise<{ count: number }>,
  rows: any[],
) => {
  let insertedCount = 0;
  for (let i = 0; i < rows.length; i += INSERT_CHUNK_SIZE) {
    const chunk = rows.slice(i, i + INSERT_CHUNK_SIZE);
    const result = await runInsert(chunk);
    insertedCount += result.count;
  }
  return insertedCount;
};

const mapKpiRow = (row: NormalizedRow, fileId: number) => {
  return {
    fileId,
    date: parseDateSafe(pickValue(row, ["date", "datetime"])),
    Cell_Name: safeString(pickValue(row, ["cell_name", "cellname"])),
    Site: safeString(pickValue(row, ["site"])),
    Band: safeString(pickValue(row, ["band"])),
    Tech: safeString(pickValue(row, ["tech"])),
    Sector_ID: safeString(pickValue(row, ["sector_id", "sectorid"])),
    Sector_Name: safeString(pickValue(row, ["sector_name", "sectorname"])),
    Groups: safeString(pickValue(row, ["groups", "group"])),
    UL_PRB_Utilization_Rate: parseFloatSafe(
      pickValue(row, [
        "ul_prb_utilization_rate",
        "ulprbutilizationrate",
        "ul prb utilization",
        "ul prb utilization [cdbh]",
      ]),
    ),
    DL_PRB_Utilization_Rate: parseFloatSafe(
      pickValue(row, [
        "dl_prb_utilization_rate",
        "dlprbutilizationrate",
        "dl prb utilization",
        "dl prb utilization [cdbh]",
      ]),
    ),
    UME_4G_Data_Volume_STD_MAPS_MB_903593_1: parseFloatSafe(
      pickValue(row, [
        "ume_4g_data_volume_std_maps_mb_903593_1",
        "ume4gdatavolumestdmapsmb9035931",
        "4g data volume [mb] [cdbh]",
        "mv_4g_data_volume_gb",
        "mv_4g_data_volume_gb [cdbh]",
      ]),
    ),
    UME_E_UTRAN_IP_Throughput_UE_UL_STD_Kbps: parseFloatSafe(
      pickValue(row, [
        "ume_e_utran_ip_throughput_ue_ul_std_kbps",
        "umeeutranipthroughputueulstdkbps",
        "mv_ul_user_throughput_mbps [cdbh]",
      ]),
    ),
    UME_E_UTRAN_IP_Throughput_UE_DL_STD_Kbps: parseFloatSafe(
      pickValue(row, [
        "ume_e_utran_ip_throughput_ue_dl_std_kbps",
        "umeeutranipthroughputuedlstdkbps",
        "mv_dl_user_throughput_mbps [cdbh]",
      ]),
    ),
    E_RAB_Drop_Rate: parseFloatSafe(
      pickValue(row, [
        "e_rab_drop_rate",
        "erabdroprate",
        "mv_dcr_data [cdbh]",
      ]),
    ),
    Initial_ERAB_Establishment_Success_Rate: parseFloatSafe(
      pickValue(row, [
        "initial_erab_establishment_success_rate",
        "initialerabestablishmentsuccessrate",
        "mv_erab_setup_success_rate [cdbh]",
      ]),
    ),
    RRC_Establishment_Success_Rate: parseFloatSafe(
      pickValue(row, [
        "rrc_establishment_success_rate",
        "rrcestablishmentsuccessrate",
        "mv_rrc_setup_success_rate [cdbh]",
      ]),
    ),
    Mean_RRC_Connected_User_Number: parseIntSafe(
      pickValue(row, [
        "mean_rrc_connected_user_number",
        "meanrrcconnectedusernumber",
      ]),
    ),
    Maximum_RRC_Connected_User_Number: parseIntSafe(
      pickValue(row, [
        "maximum_rrc_connected_user_number",
        "maximumrrcconnectedusernumber",
      ]),
    ),
    E_RAB_Setup_Success_Rate: parseFloatSafe(
      pickValue(row, [
        "e_rab_setup_success_rate",
        "erabsetupsuccessrate",
        "mv_erab_setup_success_rate [cdbh]",
      ]),
    ),
    RRC_Drop_Rate: parseFloatSafe(
      pickValue(row, ["rrc_drop_rate", "rrcdroprate"]),
    ),
    VOLTE_CSSR_Eric: parseFloatSafe(
      pickValue(row, [
        "volte_cssr_eric",
        "voltecssreric",
        "mv_volte_erab_setup_success_rate",
        "mv_volte_erab_setup_success_rate [cbbh]",
      ]),
    ),
    VOLTE_DCR_Eric: parseFloatSafe(
      pickValue(row, [
        "volte_dcr_eric",
        "voltedcreric",
        "mv_volte_dcr [cdbh]",
      ]),
    ),
    Inter_Freq_HOSR: parseFloatSafe(
      pickValue(row, [
        "inter_freq_hosr",
        "interfreqhosr",
        "mv_ps_interf_hosr [cdbh]",
        "volte interf hosr [cdbh]",
      ]),
    ),
    Intra_Freq_HOSR: parseFloatSafe(
      pickValue(row, [
        "intra_freq_hosr",
        "intrafreqhosr",
        "mv_ps_intraf_hosr [cdbh]",
        "volte intraf hosr [cbbh]",
      ]),
    ),
    CSFB_Success_Rate: parseFloatSafe(
      pickValue(row, [
        "csfb_success_rate",
        "csfbsuccessrate",
        "overall csfb success rate [cdbh]",
      ]),
    ),
  };
};

const KPI_DIMENSION_KEYS = new Set([
  "date",
  "datetime",
  "cellname",
  "cell_name",
  "site",
  "band",
  "tech",
  "sectorid",
  "sector_id",
  "sectorname",
  "sector_name",
  "groups",
  "group",
]);

const KPI_HINT_KEYWORDS = [
  "rate",
  "throughput",
  "volume",
  "utilization",
  "success",
  "drop",
  "hosr",
  "sinr",
  "cqi",
  "packetloss",
  "srvcc",
  "volte",
  "dcr",
  "rrc",
  "erab",
  "prb",
  "csfb",
];

const isLikelyDimensionColumn = (canonicalKey: string) => {
  if (KPI_DIMENSION_KEYS.has(canonicalKey)) return true;
  if (canonicalKey.endsWith("id")) return true;
  if (canonicalKey.includes("name")) return true;
  if (canonicalKey.includes("region")) return true;
  if (canonicalKey.includes("cluster")) return true;
  return false;
};

const hasKpiHeaderHint = (header: string) => {
  const clean = canonicalizeHeader(header);
  return KPI_HINT_KEYWORDS.some((keyword) => clean.includes(keyword));
};

const buildKpiDynamicCachePayload = (rows: RawRow[]) => {
  const preferredFilterKeys = [
    "tech",
    "band",
    "groups",
    "site",
    "sectorname",
    "sectorid",
    "cellname",
    "date",
  ];
  const metricLabels: Record<string, string> = {};
  const columnStats: Record<
    string,
    {
      label: string;
      nonEmptyCount: number;
      numericCount: number;
      numericRatio: number;
      isDimension: boolean;
      hasKpiHint: boolean;
    }
  > = {};

  for (const rawRow of rows) {
    for (const [header, value] of Object.entries(rawRow)) {
      const key = canonicalizeHeader(header);
      if (!columnStats[key]) {
        columnStats[key] = {
          label: String(header),
          nonEmptyCount: 0,
          numericCount: 0,
          numericRatio: 0,
          isDimension: isLikelyDimensionColumn(key),
          hasKpiHint: hasKpiHeaderHint(String(header)),
        };
      }

      if (value !== null && value !== undefined && String(value).trim() !== "") {
        columnStats[key].nonEmptyCount += 1;
      }
      if (parseFloatSafe(value) !== null) {
        columnStats[key].numericCount += 1;
      }
    }
  }

  Object.values(columnStats).forEach((stat) => {
    stat.numericRatio =
      stat.nonEmptyCount > 0 ? stat.numericCount / stat.nonEmptyCount : 0;
  });

  const detectedMetricKeys = Object.entries(columnStats)
    .filter(([, stat]) => {
      if (stat.isDimension) return false;
      if (stat.numericCount === 0) return false;
      if (stat.numericRatio >= 0.6) return true;
      if (stat.hasKpiHint && stat.numericRatio >= 0.2) return true;
      return false;
    })
    .map(([key]) => key);

  const detectedFilterKeys = Object.entries(columnStats)
    .filter(([key, stat]) => {
      if (detectedMetricKeys.includes(key)) return false;
      return (stat?.nonEmptyCount || 0) > 0;
    })
    .map(([key]) => key);

  const selectedFilterKeys = [
    ...preferredFilterKeys.filter((key) => detectedFilterKeys.includes(key)),
    ...detectedFilterKeys.filter((key) => !preferredFilterKeys.includes(key)),
  ];

  const records = rows.map((rawRow) => {
    const normalized = normalizeRow(rawRow);
    const metrics: Record<string, number> = {};
    const dimensions: Record<string, string> = {};

    detectedFilterKeys.forEach((dimensionKey) => {
      const rawValue = pickValue(normalized, [dimensionKey]);
      const value = safeString(rawValue);
      if (value !== null) {
        dimensions[dimensionKey] = value;
      }
    });

    for (const [header, value] of Object.entries(rawRow)) {
      const metricKey = canonicalizeHeader(header);
      if (!detectedMetricKeys.includes(metricKey)) continue;

      const parsed = parseFloatSafe(value);
      if (parsed === null) continue;

      metrics[metricKey] = parsed;
      if (!metricLabels[metricKey]) {
        metricLabels[metricKey] = String(header);
      }
    }

    return {
      date: parseDateSafe(
        pickValue(normalized, ["date", "datetime", "timestamp", "time"]),
      ),
      cellName: safeString(
        pickValue(normalized, [
          "cell_name",
          "cellname",
          "cell_id",
          "cellid",
          "cell",
          "short_name",
          "shortname",
        ]),
      ),
      site: safeString(
        pickValue(normalized, ["site", "site_id", "siteid", "site_name", "sitename"]),
      ),
      band: safeString(
        pickValue(normalized, ["band", "frequency_band", "layer", "carrier"]),
      ),
      tech: safeString(pickValue(normalized, ["tech", "technology"])),
      sectorid: safeString(
        pickValue(normalized, ["sector_id", "sectorid", "sec_id", "secid", "sec"]),
      ),
      sectorname: safeString(
        pickValue(normalized, ["sector_name", "sectorname", "sec", "sector"]),
      ),
      groups: safeString(
        pickValue(normalized, ["groups", "group", "region", "cluster"]),
      ),
      dimensions,
      metrics,
    };
  });

  return {
    generatedAt: new Date().toISOString(),
    metricLabels,
    columnStats,
    detectedMetricKeys,
    selectedMetricKeys: detectedMetricKeys,
    detectedFilterKeys,
    selectedFilterKeys,
    records,
  };
};

const mapSiteRow = (row: NormalizedRow) => {
  const id = parseIntSafe(pickValue(row, ["id"]));
  if (id === null) return null;

  return {
    id,
    Cell_Name: safeString(pickValue(row, ["cell_name", "cellname"])),
    SI_CI: safeString(pickValue(row, ["si_ci", "sici"])),
    EGCI: safeString(pickValue(row, ["egci"])),
    SuNetwork_ID: safeString(
      pickValue(row, ["sunetwork_id", "sunetworkid", "sunetwork_id"]),
    ),
    SITEID: safeString(pickValue(row, ["siteid"])),
    Site_Name: safeString(pickValue(row, ["site_name", "sitename"])),
    Cell_ID: safeString(pickValue(row, ["cell_id", "cellid"])),
    SEC_ID: safeString(pickValue(row, ["sec_id", "secid"])),
    lon: parseFloatSafe(pickValue(row, ["lon", "longitude"])),
    lat: parseFloatSafe(pickValue(row, ["lat", "latitude"])),
    TAC: parseIntSafe(pickValue(row, ["tac"])),
    PCI: parseIntSafe(pickValue(row, ["pci"])),
    AZIMUTH: parseIntSafe(pickValue(row, ["azimuth"])),
    Antenna_Height: parseFloatSafe(
      pickValue(row, ["antenna_height", "antennaheight"]),
    ),
    M_tilt: parseFloatSafe(pickValue(row, ["m_tilt", "mtilt"])),
    E_tilt: parseFloatSafe(pickValue(row, ["e_tilt", "etilt"])),
    TX_RX: safeString(pickValue(row, ["tx_rx", "txrx"])),
    Real_Transmit_Power_of_Resource: parseFloatSafe(
      pickValue(row, [
        "real_transmit_power_of_resource",
        "realtransmitpowerofresource",
      ]),
    ),
    Referenced_Signal_Power_of_Resource: parseFloatSafe(
      pickValue(row, [
        "referenced_signal_power_of_resource",
        "referencedsignalpowerofresource",
      ]),
    ),
    cellSize: safeString(pickValue(row, ["cellsize", "cell_size"])),
    cellRadius: parseFloatSafe(pickValue(row, ["cellradius", "cell_radius"])),
    RachRootSequence: parseIntSafe(
      pickValue(row, ["rachrootsequence", "rach_root_sequence"]),
    ),
    Bandwidth: parseIntSafe(pickValue(row, ["bandwidth"])),
    Frequency: parseIntSafe(pickValue(row, ["frequency"])),
    Downlink_Center_Frequency: parseIntSafe(
      pickValue(row, [
        "downlink_center_frequency",
        "downlinkcenterfrequency",
        "downlink_frequency",
      ]),
    ),
    Region: safeString(pickValue(row, ["region"])),
    Cluster: safeString(pickValue(row, ["cluster"])),
    OMM: safeString(pickValue(row, ["omm"])),
    Antenna: safeString(pickValue(row, ["antenna"])),
    RET: safeString(pickValue(row, ["ret"])),
  };
};

const mapAlarmRow = (row: NormalizedRow, fileId: number) => {
  return {
    fileId,
    FILENAME: safeString(pickValue(row, ["filename", "file_name"])),
    DATETIME: parseDateSafe(pickValue(row, ["datetime", "date_time", "date"])),
    configData_dnPrefix: safeString(
      pickValue(row, ["configdata_dnprefix", "configdatadnprefix"]),
    ),
    SubNetwork_id: safeString(
      pickValue(row, ["subnetwork_id", "subnetworkid"]),
    ),
    SubNetwork_2_id: safeString(
      pickValue(row, ["subnetwork_2_id", "subnetwork2id"]),
    ),
    MeContext_id: safeString(pickValue(row, ["mecontext_id", "mecontextid"])),
    ManagedElement_id: safeString(
      pickValue(row, ["managedelement_id", "managedelementid"]),
    ),
    vsEquipment_id: safeString(
      pickValue(row, ["vsequipment_id", "vsequipmentid"]),
    ),
    vsFieldReplaceableUnit_id: safeString(
      pickValue(row, [
        "vsfieldreplaceableunit_id",
        "vsfieldreplaceableunitid",
      ]),
    ),
    vsAlarmPort_id: safeString(
      pickValue(row, ["vsalarmport_id", "vsalarmportid"]),
    ),
    userLabel: safeString(pickValue(row, ["userlabel", "user_label"])),
    administrativeState: safeString(
      pickValue(row, ["administrativestate", "administrative_state"]),
    ),
    perceivedSeverity: safeString(
      pickValue(row, ["perceivedseverity", "perceived_severity"]),
    ),
    alarmPortId: safeString(pickValue(row, ["alarmportid", "alarm_port_id"])),
    alarmSlogan: safeString(pickValue(row, ["alarmslogan", "alarm_slogan"])),
    filterDelay: safeString(pickValue(row, ["filterdelay", "filter_delay"])),
    operationalState: safeString(
      pickValue(row, ["operationalstate", "operational_state"]),
    ),
    filterTime: safeString(pickValue(row, ["filtertime", "filter_time"])),
    availabilityStatus: safeString(
      pickValue(row, ["availabilitystatus", "availability_status"]),
    ),
    filterAlgorithm: safeString(
      pickValue(row, ["filteralgorithm", "filter_algorithm"]),
    ),
    normallyOpen: safeString(pickValue(row, ["normallyopen", "normally_open"])),
    alarmInExternalMe: safeString(
      pickValue(row, ["alarminexternalme", "alarm_in_external_me"]),
    ),
  };
};

const runUpload = async (
  req: Request,
  res: Response,
  datasetType: DatasetType,
) => {
  const file = req.file;
  const { remarks, uploadedBy } = req.body;

  if (!file) {
    return res.status(400).json({
      success: false,
      message: "No file uploaded.",
    });
  }

  const ext = path.extname(file.originalname).toLowerCase();
  if (!ALLOWED_EXTENSIONS.includes(ext)) {
    deleteTempFile(file.path);
    return res.status(400).json({
      success: false,
      message: "Invalid file type. Only .xlsx, .xls, and .csv files are supported.",
    });
  }

  let uploadRecordId: number | null = null;
  try {
    const uploadRecord = await prisma.uploadHistory.create({
      data: {
        fileName: file.originalname,
        fileType: ext.replace(".", ""),
        uploadedBy: uploadedBy || "anonymous",
        remarks: remarks
          ? `[${datasetType}] ${remarks}`
          : `[${datasetType}] file upload`,
      },
    });
    uploadRecordId = uploadRecord.id;

    const parsedRows = await parseFile(file.path);
    if (!parsedRows?.length) {
      return res.status(200).json({
        success: true,
        message: `${datasetType.toUpperCase()} file uploaded, but no rows were found.`,
        data: {
          uploadId: uploadRecord.id,
          datasetType,
          totalRows: 0,
          insertedRows: 0,
          skippedRows: 0,
        },
      });
    }

    const normalizedRows = parsedRows.map((row) => normalizeRow(row));
    let preparedRows: any[] = [];
    let skippedRows = 0;

    if (datasetType === "kpi") {
      preparedRows = normalizedRows.map((row) => mapKpiRow(row, uploadRecord.id));
      const insertedRows = await insertInChunks(
        (chunk) => prisma.uploadData.createMany({ data: chunk, skipDuplicates: true }),
        preparedRows,
      );

      try {
        const kpiCachePayload = buildKpiDynamicCachePayload(parsedRows);
        kpiDebugLog("[KPI DEBUG] Built dynamic cache payload", {
          uploadId: uploadRecord.id,
          rows: parsedRows.length,
          metricCount: kpiCachePayload.detectedMetricKeys?.length || 0,
          filterCount: kpiCachePayload.detectedFilterKeys?.length || 0,
          selectedMetrics: kpiCachePayload.selectedMetricKeys?.length || 0,
          selectedFilters: kpiCachePayload.selectedFilterKeys?.length || 0,
        });
        writeKpiCache(uploadRecord.id, kpiCachePayload);
      } catch (cacheError: any) {
        console.warn("Failed to persist KPI dynamic cache:", cacheError?.message);
      }

      return res.status(200).json({
        success: true,
        message: `KPI upload complete. Inserted ${insertedRows} row(s).`,
        data: {
          uploadId: uploadRecord.id,
          datasetType,
          totalRows: parsedRows.length,
          insertedRows,
          skippedRows: parsedRows.length - insertedRows,
          detectedHeaders: Object.keys(parsedRows[0] ?? {}),
        },
      });
    }

    if (datasetType === "site") {
      const mapped = normalizedRows.map((row) => mapSiteRow(row));
      preparedRows = mapped.filter((row) => row !== null);
      skippedRows = mapped.length - preparedRows.length;

      if (!preparedRows.length) {
        return res.status(400).json({
          success: false,
          message: "No valid site rows found. Ensure the file contains a valid 'id' column.",
          data: {
            uploadId: uploadRecord.id,
            datasetType,
            totalRows: parsedRows.length,
            insertedRows: 0,
            skippedRows: parsedRows.length,
            detectedHeaders: Object.keys(parsedRows[0] ?? {}),
          },
        });
      }

      const insertedRows = await insertInChunks(
        (chunk) => prisma.siteData.createMany({ data: chunk, skipDuplicates: true }),
        preparedRows,
      );

      return res.status(200).json({
        success: true,
        message: `Site upload complete. Inserted ${insertedRows} row(s).`,
        data: {
          uploadId: uploadRecord.id,
          datasetType,
          totalRows: parsedRows.length,
          insertedRows,
          skippedRows: skippedRows + (preparedRows.length - insertedRows),
          detectedHeaders: Object.keys(parsedRows[0] ?? {}),
        },
      });
    }

    preparedRows = normalizedRows.map((row) => mapAlarmRow(row, uploadRecord.id));
    const insertedRows = await insertInChunks(
      (chunk) => prisma.alarmData.createMany({ data: chunk, skipDuplicates: true }),
      preparedRows,
    );

    return res.status(200).json({
      success: true,
      message: `Alarm upload complete. Inserted ${insertedRows} row(s).`,
      data: {
        uploadId: uploadRecord.id,
        datasetType,
        totalRows: parsedRows.length,
        insertedRows,
        skippedRows: parsedRows.length - insertedRows,
        detectedHeaders: Object.keys(parsedRows[0] ?? {}),
      },
    });
  } catch (error: any) {
    console.error(`${datasetType.toUpperCase()} upload error:`, error);

    return res.status(500).json({
      success: false,
      message: "Server error: " + (error.message || "Unknown error"),
      data: uploadRecordId ? { uploadId: uploadRecordId, datasetType } : undefined,
    });
  } finally {
    deleteTempFile(file.path);
  }
};

export const uploadKpiData = async (req: Request, res: Response) =>
  runUpload(req, res, "kpi");

export const uploadSiteData = async (req: Request, res: Response) =>
  runUpload(req, res, "site");

export const uploadAlarmData = async (req: Request, res: Response) =>
  runUpload(req, res, "alarm");

// Backward compatibility for old route naming.
export const uploadAndParseFile = uploadKpiData;

export const getUploadHistory = async (req: Request, res: Response) => {
  try {
    const uploads = await prisma.uploadHistory.findMany({
      orderBy: { createdAt: "desc" },
      include: {
        _count: {
          select: { uploadData: true, alarmData: true },
        },
      },
    });

    return res.status(200).json({
      success: true,
      data: uploads,
    });
  } catch (error: any) {
    console.error("Error fetching upload history:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

export const deleteUpload = async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const parsedId = parseInt(id, 10);

    await prisma.uploadHistory.delete({
      where: { id: parsedId },
    });
    deleteKpiCache(parsedId);

    return res.status(200).json({
      success: true,
      message: "Upload and related KPI/alarm data deleted successfully",
    });
  } catch (error: any) {
    console.error("Error deleting upload:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

export const getNetworkData = async (req: Request, res: Response) => {
  try {
    const page = parseInt(req.query.page as string, 10) || 1;
    const limit = parseInt(req.query.limit as string, 10) || 50;
    const skip = (page - 1) * limit;

    const [data, total] = await Promise.all([
      prisma.uploadData.findMany({
        skip,
        take: limit,
        include: {
          uploadHistory: {
            select: {
              fileName: true,
              uploadedBy: true,
              createdAt: true,
            },
          },
        },
        orderBy: { id: "desc" },
      }),
      prisma.uploadData.count(),
    ]);

    return res.status(200).json({
      success: true,
      data,
      pagination: {
        page,
        limit,
        total,
        totalPages: Math.ceil(total / limit),
      },
    });
  } catch (error: any) {
    console.error("Error fetching network data:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

export const getuploadHistory = async (req: Request, res: Response) => {
  try {
    const uploads = await prisma.uploadHistory.findMany({
      orderBy: { createdAt: "desc" },
    });
    return res.status(200).json({ success: true, data: uploads });
  } catch (err: any) {
    console.error("Get upload history error:", err);
    return res.status(500).json({ success: false, message: err.message });
  }
};

const normalizeSeverity = (value: string | null): string => {
  const raw = (value || "").toLowerCase();
  if (raw.includes("critical")) return "Critical";
  if (raw.includes("major")) return "Major";
  if (raw.includes("minor")) return "Minor";
  if (raw.includes("warning")) return "Warning";
  return "Unknown";
};

const getStandardAging = (dateValue: Date | null): string => {
  if (!dateValue) return "N/A";
  const diffMs = Date.now() - new Date(dateValue).getTime();
  if (diffMs < 0) return "N/A";

  const totalMinutes = Math.floor(diffMs / 60000);
  const days = Math.floor(totalMinutes / 1440);
  const hours = Math.floor((totalMinutes % 1440) / 60);
  const minutes = totalMinutes % 60;

  if (days > 0) return `${days}d ${hours}h`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  return `${minutes}m`;
};

export const getSiteData = async (req: Request, res: Response) => {
  try {
    const page = parseInt(req.query.page as string, 10) || 1;
    const limit = parseInt(req.query.limit as string, 10) || 10000;
    const skip = (page - 1) * limit;

    const [data, totalCount] = await Promise.all([
      prisma.siteData.findMany({
        skip,
        take: limit,
        orderBy: { id: "asc" },
      }),
      prisma.siteData.count(),
    ]);

    return res.status(200).json({
      success: true,
      data,
      pagination: {
        page,
        limit,
        totalCount,
        totalPages: Math.ceil(totalCount / limit),
        hasNextPage: page * limit < totalCount,
        hasPrevPage: page > 1,
      },
    });
  } catch (error: any) {
    console.error("Error fetching site data:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

export const getAlarmData = async (req: Request, res: Response) => {
  try {
    const page = parseInt(req.query.page as string, 10) || 1;
    const limit = parseInt(req.query.limit as string, 10) || 10;
    const skip = (page - 1) * limit;
    const circle = safeString(req.query.circle);
    const severity = safeString(req.query.severity);
    const search = safeString(req.query.search);
    const startDate = safeString(req.query.startDate);
    const endDate = safeString(req.query.endDate);

    const whereClauses: any[] = [];

    if (circle) {
      whereClauses.push({
        OR: [
          { SubNetwork_id: { contains: circle } },
          { SubNetwork_2_id: { contains: circle } },
        ],
      });
    }

    if (severity) {
      whereClauses.push({
        perceivedSeverity: { contains: severity },
      });
    }

    if (search) {
      whereClauses.push({
        OR: [
          { userLabel: { contains: search } },
          { ManagedElement_id: { contains: search } },
          { MeContext_id: { contains: search } },
          { alarmSlogan: { contains: search } },
          { alarmPortId: { contains: search } },
        ],
      });
    }

    if (startDate || endDate) {
      const dateFilter: { gte?: Date; lte?: Date } = {};
      if (startDate) {
        const parsed = new Date(startDate);
        if (!Number.isNaN(parsed.getTime())) dateFilter.gte = parsed;
      }
      if (endDate) {
        const parsed = new Date(endDate);
        if (!Number.isNaN(parsed.getTime())) dateFilter.lte = parsed;
      }
      if (dateFilter.gte || dateFilter.lte) {
        whereClauses.push({ DATETIME: dateFilter });
      }
    }

    const where = whereClauses.length ? { AND: whereClauses } : {};

    const [rows, totalCount] = await Promise.all([
      prisma.alarmData.findMany({
        where,
        orderBy: { id: "desc" },
        skip,
        take: limit,
      }),
      prisma.alarmData.count({ where }),
    ]);

    const data = rows.map((row: any) => ({
      id: row.id,
      Circle: row.SubNetwork_id || row.SubNetwork_2_id || "N/A",
      Name: row.userLabel || row.ManagedElement_id || row.MeContext_id || "Unknown",
      EnodeBID: row.ManagedElement_id || row.MeContext_id || "N/A",
      AlarmNumber: row.alarmPortId || row.vsAlarmPort_id || String(row.id),
      AlarmText: row.alarmSlogan || row.configData_dnPrefix || "N/A",
      SupplementryInfo:
        row.availabilityStatus || row.operationalState || row.filterAlgorithm || "N/A",
      Severity: normalizeSeverity(row.perceivedSeverity),
      AlarmTime: row.DATETIME,
      StandardAging: getStandardAging(row.DATETIME),
    }));

    return res.status(200).json({
      success: true,
      data,
      pagination: {
        page,
        limit,
        totalCount,
        totalPages: Math.ceil(totalCount / limit),
        hasNextPage: page * limit < totalCount,
        hasPrevPage: page > 1,
      },
    });
  } catch (error: any) {
    console.error("Error fetching alarm data:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

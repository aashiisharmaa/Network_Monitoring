import type { Request, Response } from "express";
import { prisma } from "../config/prisma.ts";
import { readKpiCache, updateKpiCache } from "../utils/kpiDynamicCache.ts";
import * as XLSX from "xlsx";

const isKpiDebugEnabled = process.env.KPI_DEBUG_LOGS === "1";
const kpiDebugLog = (...args: any[]) => {
  if (isKpiDebugEnabled) {
    console.info(...args);
  }
};

const parseFileId = (req: Request): number | null => {
  const fileIdRaw = req.query.fileId;
  if (fileIdRaw === undefined || fileIdRaw === null || fileIdRaw === "") {
    return null;
  }
  const parsed = Number(fileIdRaw);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
};

const buildMetricWhere = (req: Request, metricKey: string) => {
  const fileId = parseFileId(req);
  return {
    [metricKey]: { not: null },
    ...(fileId ? { fileId } : {}),
  } as Record<string, any>;
};

const buildOptionalFileWhere = (req: Request) => {
  const fileId = parseFileId(req);
  return fileId ? { fileId } : undefined;
};

const formatMetricLabel = (rawKey: string) =>
  String(rawKey || "")
    .replace(/\[[^\]]*\]/g, "")
    .replace(/_/g, " ")
    .replace(/\s+/g, " ")
    .trim();

const getSelectedMetricKeys = (cache: any): string[] => {
  if (Array.isArray(cache?.selectedMetricKeys)) return cache.selectedMetricKeys;
  if (Array.isArray(cache?.detectedMetricKeys)) return cache.detectedMetricKeys;
  return [];
};

const getSelectedFilterKeys = (cache: any): string[] => {
  if (Array.isArray(cache?.selectedFilterKeys)) return cache.selectedFilterKeys;
  if (Array.isArray(cache?.detectedFilterKeys)) return cache.detectedFilterKeys;
  return [];
};

const canonicalizeKey = (value: any) =>
  String(value || "")
    .toLowerCase()
    .replace(/[^a-z0-9]/g, "");

const toFilterString = (value: any) => {
  if (value === undefined || value === null) return "";
  return String(value).trim();
};

const sanitizeSheetName = (value: string, fallback: string) => {
  const base = String(value || fallback || "Sheet")
    .replace(/[:\\/?*[\]]/g, "_")
    .trim();
  const safe = base || fallback || "Sheet";
  return safe.slice(0, 31);
};

const formatDateColumnKey = (value: any) => {
  const parsed = parseComparableDate(value);
  if (!parsed) return "Unknown_Date";
  const year = parsed.getFullYear();
  const month = String(parsed.getMonth() + 1).padStart(2, "0");
  const day = String(parsed.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
};

const findRowDimensionValue = (row: any, key: string): string => {
  if (!row || !key) return "";
  const direct = row?.[key];
  if (direct !== undefined && direct !== null && String(direct).trim() !== "") {
    return toFilterString(direct);
  }

  const target = canonicalizeKey(key);
  const dimensions = row?.dimensions || {};
  const dimensionEntry = Object.entries(dimensions).find(
    ([dimensionKey]) => canonicalizeKey(dimensionKey) === target,
  );
  if (dimensionEntry) return toFilterString(dimensionEntry[1]);

  const rowEntry = Object.entries(row).find(
    ([rowKey]) => canonicalizeKey(rowKey) === target,
  );
  if (rowEntry) return toFilterString(rowEntry[1]);

  return "";
};

const buildStatistics = (values: number[]) => {
  if (!values.length) {
    return {
      count: 0,
      average: 0,
      minimum: 0,
      maximum: 0,
      median: 0,
    };
  }

  const sorted = [...values].sort((a, b) => a - b);
  const sum = values.reduce((acc, current) => acc + current, 0);
  const mid = Math.floor(sorted.length / 2);
  const median =
    sorted.length % 2 === 0
      ? (sorted[mid - 1] + sorted[mid]) / 2
      : sorted[mid];

  return {
    count: values.length,
    average: Number((sum / values.length).toFixed(4)),
    minimum: sorted[0],
    maximum: sorted[sorted.length - 1],
    median: Number(median.toFixed(4)),
  };
};

const parseComparableDate = (value: any): Date | null => {
  if (!value) return null;
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
};

const isWithinDateRange = (dateValue: any, start?: string, end?: string) => {
  if (!start && !end) return true;
  const date = parseComparableDate(dateValue);
  if (!date) return false;
  if (start) {
    const startDate = parseComparableDate(start);
    if (startDate && date < startDate) return false;
  }
  if (end) {
    const endDate = parseComparableDate(end);
    if (endDate) {
      endDate.setHours(23, 59, 59, 999);
      if (date > endDate) return false;
    }
  }
  return true;
};

//  1. UL PRB Utilization Rate
export const getUlPrbUtilization = async (req: Request, res: Response) => {
  try {
    const data = await prisma.uploadData.findMany({
      where: buildMetricWhere(req, "UL_PRB_Utilization_Rate"),
      select: {
        date: true,
        Groups: true,
        Sector_ID: true,
        Sector_Name: true,
        id: true,
        Cell_Name: true,
        Site: true,
        Band: true,
        Tech: true,
        UL_PRB_Utilization_Rate: true,
      },
      orderBy: { id: "desc" },
      take: 10000,
    });

    const values = data.map((d: any) => d.UL_PRB_Utilization_Rate!);
    const avg =
      values.length > 0
        ? values.reduce((a: any, b: any) => a + b, 0) / values.length
        : 0;
    const max = values.length > 0 ? Math.max(...values) : 0;
    const min = values.length > 0 ? Math.min(...values) : 0;

    // Median
    const sorted = [...values].sort((a, b) => a - b);
    let median = 0;
    if (sorted.length > 0) {
      const mid = Math.floor(sorted.length / 2);
      median =
        sorted.length % 2 !== 0
          ? sorted[mid]
          : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    return res.status(200).json({
      success: true,
      kpi: "UL PRB Utilization Rate(%)",

      data: data.map((d: any) => ({
        date: d.date,
        cellName: d.Cell_Name,
        site: d.Site,
        band: d.Band,
        tech: d.Tech,
        sectorid: d.Sector_ID,
        sectorname: d.Sector_Name,
        groups: d.Groups,
        value: d.UL_PRB_Utilization_Rate,
      })),
      statistics: {
        count: values.length,
        average: parseFloat(avg.toFixed(2)),
        maximum: max,
        minimum: min,
        median: parseFloat(median.toFixed(2)),
      },
    });
  } catch (error: any) {
    console.error(" Error fetching UL PRB Utilization:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

//  2. DL PRB Utilization Rate
export const getDlPrbUtilization = async (req: Request, res: Response) => {
  try {
    const data = await prisma.uploadData.findMany({
      where: buildMetricWhere(req, "DL_PRB_Utilization_Rate"),
      select: {
        date: true,
        Groups: true,
        Sector_ID: true,
        Sector_Name: true,
        id: true,
        Cell_Name: true,
        Site: true,
        Band: true,
        Tech: true,
        DL_PRB_Utilization_Rate: true,
      },
      orderBy: { id: "desc" },
      take: 1000,
    });

    const values = data.map((d: any) => d.DL_PRB_Utilization_Rate!);
    const avg =
      values.length > 0
        ? values.reduce((a: any, b: any) => a + b, 0) / values.length
        : 0;
    const max = values.length > 0 ? Math.max(...values) : 0;
    const min = values.length > 0 ? Math.min(...values) : 0;

    // Median
    const sorted = [...values].sort((a, b) => a - b);
    let median = 0;
    if (sorted.length > 0) {
      const mid = Math.floor(sorted.length / 2);
      median =
        sorted.length % 2 !== 0
          ? sorted[mid]
          : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    return res.status(200).json({
      success: true,
      kpi: "DL PRB Utilization Rate(%)",
      data: data.map((d: any) => ({
        sectorid: d.Sector_ID,
        sectorname: d.Sector_Name,
        groups: d.Groups,
        date: d.date,
        cellName: d.Cell_Name,
        site: d.Site,
        band: d.Band,
        tech: d.Tech,
        value: d.DL_PRB_Utilization_Rate,
      })),
      statistics: {
        count: values.length,
        average: parseFloat(avg.toFixed(2)),
        maximum: max,
        minimum: min,
        median: parseFloat(median.toFixed(2)),
      },
    });
  } catch (error: any) {
    console.error(" Error fetching DL PRB Utilization:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

//  3. 4G Data Volume
export const getDataVolume = async (req: Request, res: Response) => {
  try {
    const data = await prisma.uploadData.findMany({
      where: buildMetricWhere(req, "UME_4G_Data_Volume_STD_MAPS_MB_903593_1"),
      select: {
        date: true,
        Groups: true,
        Sector_ID: true,
        Sector_Name: true,
        id: true,
        Cell_Name: true,
        Site: true,
        Band: true,
        Tech: true,
        UME_4G_Data_Volume_STD_MAPS_MB_903593_1: true,
      },
      orderBy: { id: "desc" },
      take: 2000,
    });

    const values = data.map(
      (d: any) => d.UME_4G_Data_Volume_STD_MAPS_MB_903593_1!,
    );
    const avg =
      values.length > 0
        ? values.reduce((a: any, b: any) => a + b, 0) / values.length
        : 0;
    const max = values.length > 0 ? Math.max(...values) : 0;
    const min = values.length > 0 ? Math.min(...values) : 0;
    const total = values.reduce((a: any, b: any) => a + b, 0);

    // Median
    const sorted = [...values].sort((a: any, b: any) => a - b);
    let median = 0;
    if (sorted.length > 0) {
      const mid = Math.floor(sorted.length / 2);
      median =
        sorted.length % 2 !== 0
          ? sorted[mid]
          : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    return res.status(200).json({
      success: true,
      kpi: "UME_4G_Data_Volume_STD_MAPS_MB_903593_1",
      data: data.map((d: any) => ({
        sectorid: d.Sector_ID,
        sectorname: d.Sector_Name,
        groups: d.Groups,
        date: d.date,
        cellName: d.Cell_Name,
        site: d.Site,
        band: d.Band,
        tech: d.Tech,
        value: d.UME_4G_Data_Volume_STD_MAPS_MB_903593_1,
      })),
      statistics: {
        count: values.length,
        average: parseFloat(avg.toFixed(2)),
        maximum: max,
        minimum: min,
        total: parseFloat(total.toFixed(2)),
        median: parseFloat(median.toFixed(2)),
      },
    });
  } catch (error: any) {
    console.error(" Error fetching Data Volume:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

// 4. UL Throughput
export const getUlThroughput = async (req: Request, res: Response) => {
  try {
    const data = await prisma.uploadData.findMany({
      where: buildMetricWhere(req, "UME_E_UTRAN_IP_Throughput_UE_UL_STD_Kbps"),
      select: {
        date: true,
        Groups: true,
        Sector_ID: true,
        Sector_Name: true,
        id: true,
        Cell_Name: true,
        Site: true,
        Band: true,
        Tech: true,
        UME_E_UTRAN_IP_Throughput_UE_UL_STD_Kbps: true,
      },
      orderBy: { id: "desc" },
      take: 2000,
    });

    const values = data.map(
      (d: any) => d.UME_E_UTRAN_IP_Throughput_UE_UL_STD_Kbps!,
    );
    const avg =
      values.length > 0
        ? values.reduce((a: any, b: any) => a + b, 0) / values.length
        : 0;
    const max = values.length > 0 ? Math.max(...values) : 0;
    const min = values.length > 0 ? Math.min(...values) : 0;

    // Median
    const sorted = [...values].sort((a: any, b: any) => a - b);
    let median = 0;
    if (sorted.length > 0) {
      const mid = Math.floor(sorted.length / 2);
      median =
        sorted.length % 2 !== 0
          ? sorted[mid]
          : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    return res.status(200).json({
      success: true,
      kpi: "UME_E-UTRAN IP Throughput UE UL_STD(Kbps)",
      data: data.map((d: any) => ({
        sectorid: d.Sector_ID,
        sectorname: d.Sector_Name,
        groups: d.Groups,
        date: d.date,
        cellName: d.Cell_Name,
        site: d.Site,
        band: d.Band,
        tech: d.Tech,
        value: d.UME_E_UTRAN_IP_Throughput_UE_UL_STD_Kbps,
      })),
      statistics: {
        count: values.length,
        average: parseFloat(avg.toFixed(2)),
        maximum: max,
        minimum: min,
        median: parseFloat(median.toFixed(2)),
      },
    });
  } catch (error: any) {
    console.error(" Error fetching UL Throughput:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

// 5. DL Throughput
export const getDlThroughput = async (req: Request, res: Response) => {
  try {
    const data = await prisma.uploadData.findMany({
      where: buildMetricWhere(req, "UME_E_UTRAN_IP_Throughput_UE_DL_STD_Kbps"),
      select: {
        date: true,
        Groups: true,
        Sector_ID: true,
        Sector_Name: true,
        id: true,
        Cell_Name: true,
        Site: true,
        Band: true,
        Tech: true,
        UME_E_UTRAN_IP_Throughput_UE_DL_STD_Kbps: true,
      },
      orderBy: { id: "desc" },
      take: 2000,
    });

    const values = data.map(
      (d: any) => d.UME_E_UTRAN_IP_Throughput_UE_DL_STD_Kbps!,
    );
    const avg =
      values.length > 0
        ? values.reduce((a: any, b: any) => a + b, 0) / values.length
        : 0;
    const max = values.length > 0 ? Math.max(...values) : 0;
    const min = values.length > 0 ? Math.min(...values) : 0;

    // Median
    const sorted = [...values].sort((a: any, b: any) => a - b);
    let median = 0;
    if (sorted.length > 0) {
      const mid = Math.floor(sorted.length / 2);
      median =
        sorted.length % 2 !== 0
          ? sorted[mid]
          : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    return res.status(200).json({
      success: true,
      kpi: "UME_E-UTRAN IP Throughput UE DL_STD(Kbps)",
      data: data.map((d: any) => ({
        sectorid: d.Sector_ID,
        sectorname: d.Sector_Name,
        groups: d.Groups,
        date: d.date,
        cellName: d.Cell_Name,
        site: d.Site,
        band: d.Band,
        tech: d.Tech,
        value: d.UME_E_UTRAN_IP_Throughput_UE_DL_STD_Kbps,
      })),
      statistics: {
        count: values.length,
        average: parseFloat(avg.toFixed(2)),
        maximum: max,
        minimum: min,
        median: parseFloat(median.toFixed(2)),
      },
    });
  } catch (error: any) {
    console.error(" Error fetching DL Throughput:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

// 6. E-RAB Drop Rate
export const getErabDropRate = async (req: Request, res: Response) => {
  try {
    const data = await prisma.uploadData.findMany({
      where: buildMetricWhere(req, "E_RAB_Drop_Rate"),
      select: {
        date: true,
        Groups: true,
        Sector_ID: true,
        Sector_Name: true,
        id: true,
        Cell_Name: true,
        Site: true,
        Band: true,
        Tech: true,
        E_RAB_Drop_Rate: true,
      },
      orderBy: { id: "desc" },
      take: 2000,
    });

    const values = data.map((d: any) => d.E_RAB_Drop_Rate!);
    const avg =
      values.length > 0
        ? values.reduce((a: any, b: any) => a + b, 0) / values.length
        : 0;
    const max = values.length > 0 ? Math.max(...values) : 0;
    const min = values.length > 0 ? Math.min(...values) : 0;

    // Median
    const sorted = [...values].sort((a: any, b: any) => a - b);
    let median = 0;
    if (sorted.length > 0) {
      const mid = Math.floor(sorted.length / 2);
      median =
        sorted.length % 2 !== 0
          ? sorted[mid]
          : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    return res.status(200).json({
      success: true,
      kpi: "E-RAB Drop Rate(%)",
      data: data.map((d: any) => ({
        sectorid: d.Sector_ID,
        sectorname: d.Sector_Name,
        groups: d.Groups,
        date: d.date,
        cellName: d.Cell_Name,
        site: d.Site,
        band: d.Band,
        tech: d.Tech,
        value: d.E_RAB_Drop_Rate,
      })),
      statistics: {
        count: values.length,
        average: parseFloat(avg.toFixed(2)),
        maximum: max,
        minimum: min,
        median: parseFloat(median.toFixed(2)),
      },
    });
  } catch (error: any) {
    console.error(" Error fetching E-RAB Drop Rate:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

// 7. Initial ERAB Establishment Success Rate
export const getErabSuccessRate = async (req: Request, res: Response) => {
  try {
    const data = await prisma.uploadData.findMany({
      where: buildMetricWhere(req, "Initial_ERAB_Establishment_Success_Rate"),
      select: {
        date: true,
        Groups: true,
        Sector_ID: true,
        Sector_Name: true,
        id: true,
        Cell_Name: true,
        Site: true,
        Band: true,
        Tech: true,
        Initial_ERAB_Establishment_Success_Rate: true,
      },
      orderBy: { id: "desc" },
      take: 2000,
    });

    const values = data.map(
      (d: any) => d.Initial_ERAB_Establishment_Success_Rate!,
    );
    const avg =
      values.length > 0
        ? values.reduce((a: any, b: any) => a + b, 0) / values.length
        : 0;
    const max = values.length > 0 ? Math.max(...values) : 0;
    const min = values.length > 0 ? Math.min(...values) : 0;

    // Median
    const sorted = [...values].sort((a: any, b: any) => a - b);
    let median = 0;
    if (sorted.length > 0) {
      const mid = Math.floor(sorted.length / 2);
      median =
        sorted.length % 2 !== 0
          ? sorted[mid]
          : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    return res.status(200).json({
      success: true,
      kpi: "initial ERAB Establishment Success Rate",
      data: data.map((d: any) => ({
        sectorid: d.Sector_ID,
        sectorname: d.Sector_Name,
        groups: d.Groups,
        date: d.date,
        cellName: d.Cell_Name,
        site: d.Site,
        band: d.Band,
        tech: d.Tech,
        value: d.Initial_ERAB_Establishment_Success_Rate,
      })),
      statistics: {
        count: values.length,
        average: parseFloat(avg.toFixed(2)),
        maximum: max,
        minimum: min,
        median: parseFloat(median.toFixed(2)),
      },
    });
  } catch (error: any) {
    console.error(" Error fetching ERAB Success Rate:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

// 8. RRC Establishment Success Rate
export const getRrcSuccessRate = async (req: Request, res: Response) => {
  try {
    const data = await prisma.uploadData.findMany({
      where: buildMetricWhere(req, "RRC_Establishment_Success_Rate"),
      select: {
        date: true,
        Groups: true,
        Sector_ID: true,
        Sector_Name: true,
        id: true,
        Cell_Name: true,
        Site: true,
        Band: true,
        Tech: true,
        RRC_Establishment_Success_Rate: true,
      },
      orderBy: { id: "desc" },
      take: 2000,
    });

    const values = data.map((d: any) => d.RRC_Establishment_Success_Rate!);
    const avg =
      values.length > 0
        ? values.reduce((a: any, b: any) => a + b, 0) / values.length
        : 0;
    const max = values.length > 0 ? Math.max(...values) : 0;
    const min = values.length > 0 ? Math.min(...values) : 0;

    // Median
    const sorted = [...values].sort((a: any, b: any) => a - b);
    let median = 0;
    if (sorted.length > 0) {
      const mid = Math.floor(sorted.length / 2);
      median =
        sorted.length % 2 !== 0
          ? sorted[mid]
          : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    return res.status(200).json({
      success: true,
      kpi: "RRC Establishment Success Rate(%)",
      data: data.map((d: any) => ({
        sectorid: d.Sector_ID,
        sectorname: d.Sector_Name,
        groups: d.Groups,
        date: d.date,
        cellName: d.Cell_Name,
        site: d.Site,
        band: d.Band,
        tech: d.Tech,
        value: d.RRC_Establishment_Success_Rate,
      })),
      statistics: {
        count: values.length,
        average: parseFloat(avg.toFixed(2)),
        maximum: max,
        minimum: min,
        median: parseFloat(median.toFixed(2)),
      },
    });
  } catch (error: any) {
    console.error(" Error fetching RRC Success Rate:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

//  9. Mean RRC Connected Users
export const getMeanRrcUsers = async (req: Request, res: Response) => {
  try {
    const data = await prisma.uploadData.findMany({
      where: buildMetricWhere(req, "Mean_RRC_Connected_User_Number"),
      select: {
        date: true,
        Groups: true,
        Sector_ID: true,
        Sector_Name: true,
        id: true,
        Cell_Name: true,
        Site: true,
        Band: true,
        Tech: true,
        Mean_RRC_Connected_User_Number: true,
      },
      orderBy: { id: "desc" },
      take: 2000,
    });

    const values = data.map((d: any) => d.Mean_RRC_Connected_User_Number!);
    const avg =
      values.length > 0
        ? values.reduce((a: any, b: any) => a + b, 0) / values.length
        : 0;
    const max = values.length > 0 ? Math.max(...values) : 0;
    const min = values.length > 0 ? Math.min(...values) : 0;

    // Median
    const sorted = [...values].sort((a: any, b: any) => a - b);
    let median = 0;
    if (sorted.length > 0) {
      const mid = Math.floor(sorted.length / 2);
      median =
        sorted.length % 2 !== 0
          ? sorted[mid]
          : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    return res.status(200).json({
      success: true,
      kpi: "Mean RRC-Connected User Number",
      data: data.map((d: any) => ({
        sectorid: d.Sector_ID,
        sectorname: d.Sector_Name,
        groups: d.Groups,
        date: d.date,
        cellName: d.Cell_Name,
        site: d.Site,
        band: d.Band,
        tech: d.Tech,
        value: d.Mean_RRC_Connected_User_Number,
      })),
      statistics: {
        count: values.length,
        average: parseFloat(avg.toFixed(2)),
        maximum: max,
        minimum: min,
        median: parseFloat(median.toFixed(2)),
      },
    });
  } catch (error: any) {
    console.error("Error fetching Mean RRC Users:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

// 10. Maximum RRC Connected Users
export const getMaxRrcUsers = async (req: Request, res: Response) => {
  try {
    const data = await prisma.uploadData.findMany({
      where: buildMetricWhere(req, "Maximum_RRC_Connected_User_Number"),
      select: {
        date: true,
        Groups: true,
        Sector_ID: true,
        Sector_Name: true,
        id: true,
        Cell_Name: true,
        Site: true,
        Band: true,
        Tech: true,
        Maximum_RRC_Connected_User_Number: true,
      },
      orderBy: { id: "desc" },
      take: 2000,
    });

    const values = data.map((d: any) => d.Maximum_RRC_Connected_User_Number!);
    const avg =
      values.length > 0
        ? values.reduce((a: any, b: any) => a + b, 0) / values.length
        : 0;
    const max = values.length > 0 ? Math.max(...values) : 0;
    const min = values.length > 0 ? Math.min(...values) : 0;

    // Median
    const sorted = [...values].sort((a: any, b: any) => a - b);
    let median = 0;
    if (sorted.length > 0) {
      const mid = Math.floor(sorted.length / 2);
      median =
        sorted.length % 2 !== 0
          ? sorted[mid]
          : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    return res.status(200).json({
      success: true,
      kpi: "Maximum RRC-Connected User Number",
      data: data.map((d: any) => ({
        sectorid: d.Sector_ID,
        sectorname: d.Sector_Name,
        groups: d.Groups,
        date: d.date,
        cellName: d.Cell_Name,
        site: d.Site,
        band: d.Band,
        tech: d.Tech,
        value: d.Maximum_RRC_Connected_User_Number,
      })),
      statistics: {
        count: values.length,
        average: parseFloat(avg.toFixed(2)),
        maximum: max,
        minimum: min,
        median: parseFloat(median.toFixed(2)),
      },
    });
  } catch (error: any) {
    console.error(" Error fetching Max RRC Users:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

// 11. E-RAB Setup Success Rate
export const getErabSetupRate = async (req: Request, res: Response) => {
  try {
    const data = await prisma.uploadData.findMany({
      where: buildMetricWhere(req, "E_RAB_Setup_Success_Rate"),
      select: {
        date: true,
        Groups: true,
        Sector_ID: true,
        Sector_Name: true,
        id: true,
        Cell_Name: true,
        Site: true,
        Band: true,
        Tech: true,
        E_RAB_Setup_Success_Rate: true,
      },
      orderBy: { id: "desc" },
      take: 2000,
    });

    const values = data.map((d: any) => d.E_RAB_Setup_Success_Rate!);
    const avg =
      values.length > 0
        ? values.reduce((a: any, b: any) => a + b, 0) / values.length
        : 0;
    const max = values.length > 0 ? Math.max(...values) : 0;
    const min = values.length > 0 ? Math.min(...values) : 0;

    // Median
    const sorted = [...values].sort((a: any, b: any) => a - b);
    let median = 0;
    if (sorted.length > 0) {
      const mid = Math.floor(sorted.length / 2);
      median =
        sorted.length % 2 !== 0
          ? sorted[mid]
          : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    return res.status(200).json({
      success: true,
      kpi: "E-RAB Setup Success Rate(%)",
      data: data.map((d: any) => ({
        sectorid: d.Sector_ID,
        sectorname: d.Sector_Name,
        groups: d.Groups,
        date: d.date,
        cellName: d.Cell_Name,
        site: d.Site,
        band: d.Band,
        tech: d.Tech,
        value: d.E_RAB_Setup_Success_Rate,
      })),
      statistics: {
        count: values.length,
        average: parseFloat(avg.toFixed(2)),
        maximum: max,
        minimum: min,
        median: parseFloat(median.toFixed(2)),
      },
    });
  } catch (error: any) {
    console.error(" Error fetching E-RAB Setup Rate:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

// 12. RRC Drop Rate
export const getRrcDropRate = async (req: Request, res: Response) => {
  try {
    const data = await prisma.uploadData.findMany({
      where: buildMetricWhere(req, "RRC_Drop_Rate"),
      select: {
        date: true,
        Groups: true,
        Sector_ID: true,
        Sector_Name: true,
        id: true,
        Cell_Name: true,
        Site: true,
        Band: true,
        Tech: true,
        RRC_Drop_Rate: true,
      },
      orderBy: { id: "desc" },
      take: 2000,
    });

    const values = data.map((d: any) => d.RRC_Drop_Rate!);
    const avg =
      values.length > 0
        ? values.reduce((a: any, b: any) => a + b, 0) / values.length
        : 0;
    const max = values.length > 0 ? Math.max(...values) : 0;
    const min = values.length > 0 ? Math.min(...values) : 0;

    // Median
    const sorted = [...values].sort((a: any, b: any) => a - b);
    let median = 0;
    if (sorted.length > 0) {
      const mid = Math.floor(sorted.length / 2);
      median =
        sorted.length % 2 !== 0
          ? sorted[mid]
          : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    return res.status(200).json({
      success: true,
      kpi: "RRC Drop Rate(%)",
      data: data.map((d: any) => ({
        sectorid: d.Sector_ID,
        sectorname: d.Sector_Name,
        groups: d.Groups,
        date: d.date,
        cellName: d.Cell_Name,
        site: d.Site,
        band: d.Band,
        tech: d.Tech,
        value: d.RRC_Drop_Rate,
      })),
      statistics: {
        count: values.length,
        average: parseFloat(avg.toFixed(2)),
        maximum: max,
        minimum: min,
        median: parseFloat(median.toFixed(2)),
      },
    });
  } catch (error: any) {
    console.error(" Error fetching RRC Drop Rate:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

// 13. VOLTE CSSR
export const getVolteCssr = async (req: Request, res: Response) => {
  try {
    const data = await prisma.uploadData.findMany({
      where: buildMetricWhere(req, "VOLTE_CSSR_Eric"),
      select: {
        id: true,
        date: true,
        Groups: true,
        Sector_ID: true,
        Sector_Name: true,
        Cell_Name: true,
        Site: true,
        Band: true,
        Tech: true,
        VOLTE_CSSR_Eric: true,
      },
      orderBy: { id: "desc" },
      take: 2000,
    });

    const values = data.map((d: any) => d.VOLTE_CSSR_Eric!);
    const avg =
      values.length > 0
        ? values.reduce((a: any, b: any) => a + b, 0) / values.length
        : 0;
    const max = values.length > 0 ? Math.max(...values) : 0;
    const min = values.length > 0 ? Math.min(...values) : 0;

    // Median
    const sorted = [...values].sort((a: any, b: any) => a - b);
    let median = 0;
    if (sorted.length > 0) {
      const mid = Math.floor(sorted.length / 2);
      median =
        sorted.length % 2 !== 0
          ? sorted[mid]
          : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    return res.status(200).json({
      success: true,
      kpi: "VOLTE CSSR_Eric",
      data: data.map((d: any) => ({
        sectorid: d.Sector_ID,
        sectorname: d.Sector_Name,
        groups: d.Groups,
        date: d.date,
        cellName: d.Cell_Name,
        site: d.Site,
        band: d.Band,
        tech: d.Tech,
        value: d.VOLTE_CSSR_Eric,
      })),
      statistics: {
        count: values.length,
        average: parseFloat(avg.toFixed(2)),
        maximum: max,
        minimum: min,
        median: parseFloat(median.toFixed(2)),
      },
    });
  } catch (error: any) {
    console.error(" Error fetching VOLTE CSSR:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

// 14. VOLTE DCR
export const getVolteDcr = async (req: Request, res: Response) => {
  try {
    const data = await prisma.uploadData.findMany({
      where: buildMetricWhere(req, "VOLTE_DCR_Eric"),
      select: {
        date: true,
        Groups: true,
        Sector_ID: true,
        Sector_Name: true,
        id: true,
        Cell_Name: true,
        Site: true,
        Band: true,
        Tech: true,
        VOLTE_DCR_Eric: true,
      },
      orderBy: { id: "desc" },
      take: 2000,
    });

    const values = data.map((d: any) => d.VOLTE_DCR_Eric!);
    const avg =
      values.length > 0
        ? values.reduce((a: any, b: any) => a + b, 0) / values.length
        : 0;
    const max = values.length > 0 ? Math.max(...values) : 0;
    const min = values.length > 0 ? Math.min(...values) : 0;

    // Median
    const sorted = [...values].sort((a: any, b: any) => a - b);
    let median = 0;
    if (sorted.length > 0) {
      const mid = Math.floor(sorted.length / 2);
      median =
        sorted.length % 2 !== 0
          ? sorted[mid]
          : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    return res.status(200).json({
      success: true,
      kpi: "VOLTE DCR_Eric",
      data: data.map((d: any) => ({
        sectorid: d.Sector_ID,
        sectorname: d.Sector_Name,
        groups: d.Groups,
        date: d.date,
        cellName: d.Cell_Name,
        site: d.Site,
        band: d.Band,
        tech: d.Tech,
        value: d.VOLTE_DCR_Eric,
      })),
      statistics: {
        count: values.length,
        average: parseFloat(avg.toFixed(2)),
        maximum: max,
        minimum: min,
        median: parseFloat(median.toFixed(2)),
      },
    });
  } catch (error: any) {
    console.error("Error fetching VOLTE DCR:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

// 15. Inter Freq HOSR
export const getInterFreqHosr = async (req: Request, res: Response) => {
  try {
    const data = await prisma.uploadData.findMany({
      where: buildMetricWhere(req, "Inter_Freq_HOSR"),
      select: {
        date: true,
        Groups: true,
        Sector_ID: true,
        Sector_Name: true,
        id: true,
        Cell_Name: true,
        Site: true,
        Band: true,
        Tech: true,
        Inter_Freq_HOSR: true,
      },
      orderBy: { id: "desc" },
      take: 2000,
    });

    const values = data.map((d: any) => d.Inter_Freq_HOSR!);
    const avg =
      values.length > 0
        ? values.reduce((a: any, b: any) => a + b, 0) / values.length
        : 0;
    const max = values.length > 0 ? Math.max(...values) : 0;
    const min = values.length > 0 ? Math.min(...values) : 0;

    // Median
    const sorted = [...values].sort((a: any, b: any) => a - b);
    let median = 0;
    if (sorted.length > 0) {
      const mid = Math.floor(sorted.length / 2);
      median =
        sorted.length % 2 !== 0
          ? sorted[mid]
          : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    return res.status(200).json({
      success: true,
      kpi: "Inter Freq HOSR",
      data: data.map((d: any) => ({
        sectorid: d.Sector_ID,
        sectorname: d.Sector_Name,
        groups: d.Groups,
        date: d.date,
        cellName: d.Cell_Name,
        site: d.Site,
        band: d.Band,
        tech: d.Tech,
        value: d.Inter_Freq_HOSR,
      })),
      statistics: {
        count: values.length,
        average: parseFloat(avg.toFixed(2)),
        maximum: max,
        minimum: min,
        median: parseFloat(median.toFixed(2)),
      },
    });
  } catch (error: any) {
    console.error(" Error fetching Inter Freq HOSR:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

// 16. Intra Freq HOSR
export const getIntraFreqHosr = async (req: Request, res: Response) => {
  try {
    const data = await prisma.uploadData.findMany({
      where: buildMetricWhere(req, "Intra_Freq_HOSR"),
      select: {
        date: true,
        Groups: true,
        Sector_ID: true,
        Sector_Name: true,
        id: true,
        Cell_Name: true,
        Site: true,
        Band: true,
        Tech: true,
        Intra_Freq_HOSR: true,
      },
      orderBy: { id: "desc" },
      take: 2000,
    });

    const values = data.map((d: any) => d.Intra_Freq_HOSR!);
    const avg =
      values.length > 0
        ? values.reduce((a: any, b: any) => a + b, 0) / values.length
        : 0;
    const max = values.length > 0 ? Math.max(...values) : 0;
    const min = values.length > 0 ? Math.min(...values) : 0;

    // Median
    const sorted = [...values].sort((a: any, b: any) => a - b);
    let median = 0;
    if (sorted.length > 0) {
      const mid = Math.floor(sorted.length / 2);
      median =
        sorted.length % 2 !== 0
          ? sorted[mid]
          : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    return res.status(200).json({
      success: true,
      kpi: "Intra Freq HOSR",
      data: data.map((d: any) => ({
        sectorid: d.Sector_ID,
        sectorname: d.Sector_Name,
        groups: d.Groups,
        date: d.date,
        cellName: d.Cell_Name,
        site: d.Site,
        band: d.Band,
        tech: d.Tech,
        value: d.Intra_Freq_HOSR,
      })),
      statistics: {
        count: values.length,
        average: parseFloat(avg.toFixed(2)),
        maximum: max,
        minimum: min,
        median: parseFloat(median.toFixed(2)),
      },
    });
  } catch (error: any) {
    console.error(" Error fetching Intra Freq HOSR:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

// 17. CSFB Success Rate
export const getCsfbSuccessRate = async (req: Request, res: Response) => {
  try {
    const data = await prisma.uploadData.findMany({
      where: buildMetricWhere(req, "CSFB_Success_Rate"),
      select: {
        date: true,
        Groups: true,
        Sector_ID: true,
        Sector_Name: true,
        id: true,
        Cell_Name: true,
        Site: true,
        Band: true,
        Tech: true,
        CSFB_Success_Rate: true,
      },
      orderBy: { id: "desc" },
      take: 2000,
    });

    const values = data.map((d: any) => d.CSFB_Success_Rate!);
    const avg =
      values.length > 0
        ? values.reduce((a: any, b: any) => a + b, 0) / values.length
        : 0;
    const max = values.length > 0 ? Math.max(...values) : 0;
    const min = values.length > 0 ? Math.min(...values) : 0;

    // Median
    const sorted = [...values].sort((a: any, b: any) => a - b);
    let median = 0;
    if (sorted.length > 0) {
      const mid = Math.floor(sorted.length / 2);
      median =
        sorted.length % 2 !== 0
          ? sorted[mid]
          : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    return res.status(200).json({
      success: true,
      kpi: "CSFB Success rate",
      data: data.map((d: any) => ({
        sectorid: d.Sector_ID,
        sectorname: d.Sector_Name,
        groups: d.Groups,
        date: d.date,
        cellName: d.Cell_Name,
        site: d.Site,
        band: d.Band,
        tech: d.Tech,
        value: d.CSFB_Success_Rate,
      })),
      statistics: {
        count: values.length,
        average: parseFloat(avg.toFixed(2)),
        maximum: max,
        minimum: min,
        median: parseFloat(median.toFixed(2)),
      },
    });
  } catch (error: any) {
    console.error(" Error fetching CSFB Success Rate:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

export const getAvailableKpis = async (req: Request, res: Response) => {
  try {
    const where = buildOptionalFileWhere(req);
    const metricsCount = await prisma.uploadData.aggregate({
      where,
      _count: {
        UL_PRB_Utilization_Rate: true,
        DL_PRB_Utilization_Rate: true,
        UME_4G_Data_Volume_STD_MAPS_MB_903593_1: true,
        UME_E_UTRAN_IP_Throughput_UE_UL_STD_Kbps: true,
        UME_E_UTRAN_IP_Throughput_UE_DL_STD_Kbps: true,
        E_RAB_Drop_Rate: true,
        Initial_ERAB_Establishment_Success_Rate: true,
        RRC_Establishment_Success_Rate: true,
        Mean_RRC_Connected_User_Number: true,
        Maximum_RRC_Connected_User_Number: true,
        E_RAB_Setup_Success_Rate: true,
        RRC_Drop_Rate: true,
        VOLTE_CSSR_Eric: true,
        VOLTE_DCR_Eric: true,
        Inter_Freq_HOSR: true,
        Intra_Freq_HOSR: true,
        CSFB_Success_Rate: true,
      },
    });

    const availableCounts: Record<string, number> = {};
    Object.entries(metricsCount._count || {}).forEach(([key, value]) => {
      if (typeof value === "number" && value > 0) {
        availableCounts[key] = value;
      }
    });

    return res.status(200).json({
      success: true,
      fileId: parseFileId(req),
      availableMetrics: Object.keys(availableCounts),
      metricCounts: availableCounts,
    });
  } catch (error: any) {
    console.error(" Error fetching available KPI metrics:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

export const getDynamicKpiMetrics = async (req: Request, res: Response) => {
  try {
    const fileId = parseFileId(req);
    if (!fileId) {
      return res.status(400).json({
        success: false,
        message: "fileId query parameter is required.",
      });
    }

    const cache = readKpiCache(fileId);
    if (!cache) {
      kpiDebugLog("[KPI DEBUG] getDynamicKpiMetrics cache missing", { fileId });
      return res.status(200).json({
        success: true,
        fileId,
        metrics: [],
      });
    }

    const metricLabels = cache.metricLabels || {};
    const records = Array.isArray(cache.records) ? cache.records : [];
    const selectedMetricKeys = getSelectedMetricKeys(cache);
    kpiDebugLog("[KPI DEBUG] getDynamicKpiMetrics", {
      fileId,
      hasCache: Boolean(cache),
      records: records.length,
      selectedMetricKeysCount: selectedMetricKeys.length,
    });
    const metricCounts: Record<string, number> = {};

    for (const row of records) {
      const metrics = row?.metrics || {};
      Object.keys(metrics)
        .filter((key) => selectedMetricKeys.includes(key))
        .forEach((key) => {
        metricCounts[key] = (metricCounts[key] || 0) + 1;
        });
    }

    const metrics = Object.keys(metricCounts)
      .sort((a, b) =>
        formatMetricLabel(metricLabels[a] || a).localeCompare(
          formatMetricLabel(metricLabels[b] || b),
        ),
      )
      .map((key) => ({
        key,
        label: formatMetricLabel(metricLabels[key] || key),
        count: metricCounts[key],
      }));

    return res.status(200).json({
      success: true,
      fileId,
      metrics,
    });
  } catch (error: any) {
    console.error(" Error fetching dynamic KPI metric list:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

export const getDynamicKpiColumns = async (req: Request, res: Response) => {
  try {
    const fileId = parseFileId(req);
    if (!fileId) {
      return res.status(400).json({
        success: false,
        message: "fileId query parameter is required.",
      });
    }

    const cache = readKpiCache(fileId);
    if (!cache) {
      kpiDebugLog("[KPI DEBUG] getDynamicKpiColumns cache missing", { fileId });
      return res.status(200).json({
        success: true,
        fileId,
        columns: [],
        selectedMetricKeys: [],
      });
    }

    const metricLabels = cache.metricLabels || {};
    const columnStats = cache.columnStats || {};
    const selectedMetricKeys = getSelectedMetricKeys(cache);
    const selectedFilterKeys = getSelectedFilterKeys(cache);

    const columns = Object.entries(columnStats).map(([key, stat]: [string, any]) => ({
      key,
      label: formatMetricLabel(metricLabels[key] || stat?.label || key),
      rawLabel: String(stat?.label || metricLabels[key] || key),
      numericRatio: Number(((stat?.numericRatio || 0) * 100).toFixed(1)),
      numericCount: stat?.numericCount || 0,
      nonEmptyCount: stat?.nonEmptyCount || 0,
      isDimension: Boolean(stat?.isDimension),
      hasKpiHint: Boolean(stat?.hasKpiHint),
      selected: selectedMetricKeys.includes(key),
      selectedForFilter: selectedFilterKeys.includes(key),
    }));

    kpiDebugLog("[KPI DEBUG] getDynamicKpiColumns", {
      fileId,
      columnsCount: columns.length,
      selectedMetricKeysCount: selectedMetricKeys.length,
      selectedFilterKeysCount: selectedFilterKeys.length,
    });

    return res.status(200).json({
      success: true,
      fileId,
      columns,
      selectedMetricKeys,
      selectedFilterKeys,
    });
  } catch (error: any) {
    console.error(" Error fetching dynamic KPI columns:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

export const updateDynamicKpiSelection = async (req: Request, res: Response) => {
  try {
    const fileId = parseFileId(req);
    const hasMetricKeysProp = Object.prototype.hasOwnProperty.call(
      req.body || {},
      "metricKeys",
    );
    const hasFilterKeysProp = Object.prototype.hasOwnProperty.call(
      req.body || {},
      "filterKeys",
    );
    const metricKeysInput = hasMetricKeysProp && Array.isArray(req.body?.metricKeys)
      ? req.body.metricKeys
      : undefined;
    const filterKeysInput = hasFilterKeysProp && Array.isArray(req.body?.filterKeys)
      ? req.body.filterKeys
      : undefined;

    if (!fileId) {
      return res.status(400).json({
        success: false,
        message: "fileId query parameter is required.",
      });
    }

    const metricKeys =
      metricKeysInput?.map((key: any) => String(key || "").trim()).filter(Boolean) ||
      [];
    const filterKeys =
      filterKeysInput?.map((key: any) => String(key || "").trim()).filter(Boolean) ||
      [];

    const updatedCache = updateKpiCache(fileId, (cache) => {
      const allowedKeys = Array.isArray(cache?.detectedMetricKeys)
        ? cache.detectedMetricKeys
        : [];
      const currentSelectedMetrics = getSelectedMetricKeys(cache);
      const selectedMetrics = hasMetricKeysProp
        ? metricKeys.filter((key: string) => allowedKeys.includes(key))
        : currentSelectedMetrics;
      const allowedFilterKeys = Array.isArray(cache?.detectedFilterKeys)
        ? cache.detectedFilterKeys
        : [];
      const currentSelectedFilters = getSelectedFilterKeys(cache);
      const selectedFilters = hasFilterKeysProp
        ? filterKeys.filter((key: string) => allowedFilterKeys.includes(key))
        : currentSelectedFilters;
      return {
        ...cache,
        selectedMetricKeys: selectedMetrics,
        selectedFilterKeys: selectedFilters,
      };
    });

    if (!updatedCache) {
      return res.status(404).json({
        success: false,
        message: "KPI cache not found for this file.",
      });
    }

    kpiDebugLog("[KPI DEBUG] updateDynamicKpiSelection", {
      fileId,
      hasMetricKeysProp,
      hasFilterKeysProp,
      requestedMetricKeys: metricKeys.length,
      requestedFilterKeys: filterKeys.length,
      selectedMetricKeys: getSelectedMetricKeys(updatedCache).length,
      selectedFilterKeys: getSelectedFilterKeys(updatedCache).length,
    });

    return res.status(200).json({
      success: true,
      fileId,
      selectedMetricKeys: getSelectedMetricKeys(updatedCache),
      selectedFilterKeys: getSelectedFilterKeys(updatedCache),
    });
  } catch (error: any) {
    console.error(" Error updating dynamic KPI selection:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

export const getDynamicKpiData = async (req: Request, res: Response) => {
  try {
    const fileId = parseFileId(req);
    const metricKey = String(req.query.metricKey || "").trim();

    if (!fileId || !metricKey) {
      return res.status(400).json({
        success: false,
        message: "fileId and metricKey query parameters are required.",
      });
    }

    const cache = readKpiCache(fileId);
    if (!cache) {
      kpiDebugLog("[KPI DEBUG] getDynamicKpiData cache missing", { fileId, metricKey });
      return res.status(200).json({
        success: true,
        fileId,
        metricKey,
        kpi: formatMetricLabel(metricKey),
        data: [],
        statistics: {
          count: 0,
          average: 0,
          maximum: 0,
          minimum: 0,
          median: 0,
        },
      });
    }

    const metricLabels = cache.metricLabels || {};
    const selectedMetricKeys = getSelectedMetricKeys(cache);
    if (!selectedMetricKeys.includes(metricKey)) {
      kpiDebugLog("[KPI DEBUG] metric not selected for file", {
        fileId,
        metricKey,
        selectedMetricKeysCount: selectedMetricKeys.length,
      });
      return res.status(200).json({
        success: true,
        fileId,
        metricKey,
        kpi: formatMetricLabel(metricLabels[metricKey] || metricKey),
        data: [],
        statistics: {
          count: 0,
          average: 0,
          maximum: 0,
          minimum: 0,
          median: 0,
        },
      });
    }
    const records = Array.isArray(cache.records) ? cache.records : [];
    kpiDebugLog("[KPI DEBUG] getDynamicKpiData:start", {
      fileId,
      metricKey,
      records: records.length,
      selectedMetricKeysCount: selectedMetricKeys.length,
      metricSelected: selectedMetricKeys.includes(metricKey),
    });

    const data = records
      .map((row: any) => {
        const value = row?.metrics?.[metricKey];
        if (value === undefined || value === null) return null;
        return {
          date: row.date,
          cellName: row.cellName,
          site: row.site,
          band: row.band,
          tech: row.tech,
          sectorid: row.sectorid,
          sectorname: row.sectorname,
          groups: row.groups,
          ...(row?.dimensions || {}),
          value: Number(value),
        };
      })
      .filter(Boolean);
    const values = data
      .map((item: any) => item.value)
      .filter((v: number) => !Number.isNaN(v));
    const stats = buildStatistics(values);

    kpiDebugLog("[KPI DEBUG] getDynamicKpiData:result", {
      fileId,
      metricKey,
      dataPoints: data.length,
      numericValues: values.length,
      sampleKeys: data.length > 0 ? Object.keys(data[0]).slice(0, 12) : [],
    });

    return res.status(200).json({
      success: true,
      fileId,
      metricKey,
      kpi: formatMetricLabel(metricLabels[metricKey] || metricKey),
      data,
      statistics: {
        count: stats.count,
        average: Number(stats.average.toFixed(2)),
        maximum: stats.maximum,
        minimum: stats.minimum,
        median: Number(stats.median.toFixed(2)),
      },
    });
  } catch (error: any) {
    console.error(" Error fetching dynamic KPI data:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

export const exportDynamicKpiReport = async (req: Request, res: Response) => {
  try {
    const fileId = parseFileId(req);
    if (!fileId) {
      return res.status(400).json({
        success: false,
        message: "fileId query parameter is required.",
      });
    }

    const cache = readKpiCache(fileId);
    if (!cache) {
      return res.status(404).json({
        success: false,
        message: "KPI cache not found for this file.",
      });
    }

    const records = Array.isArray(cache.records) ? cache.records : [];
    const metricLabels = cache.metricLabels || {};
    const selectedMetricKeys = getSelectedMetricKeys(cache);
    const selectedFilterKeys = getSelectedFilterKeys(cache);

    const requestedMetricKeys = Array.isArray(req.body?.metricKeys)
      ? req.body.metricKeys.map((key: any) => String(key || "").trim()).filter(Boolean)
      : [];
    const requestedGroupByKeys = Array.isArray(req.body?.groupByKeys)
      ? req.body.groupByKeys.map((key: any) => String(key || "").trim()).filter(Boolean)
      : [];
    const activeFiltersInput =
      req.body && typeof req.body.activeFilters === "object"
        ? req.body.activeFilters
        : {};
    const compareRangesInput =
      req.body && typeof req.body.compareRanges === "object"
        ? req.body.compareRanges
        : {};
    const exportFormat =
      String(req.body?.exportFormat || "standard").toLowerCase() === "date_matrix"
        ? "date_matrix"
        : "standard";
    const compareRanges = {
      preStart: toFilterString((compareRangesInput as any)?.preStart || ""),
      preEnd: toFilterString((compareRangesInput as any)?.preEnd || ""),
      postStart: toFilterString((compareRangesInput as any)?.postStart || ""),
      postEnd: toFilterString((compareRangesInput as any)?.postEnd || ""),
    };
    const hasPreRange = Boolean(compareRanges.preStart || compareRanges.preEnd);
    const hasPostRange = Boolean(compareRanges.postStart || compareRanges.postEnd);
    const isComparisonMode = hasPreRange || hasPostRange;

    const activeFilters: Record<string, string[]> = {};
    Object.entries(activeFiltersInput).forEach(([key, values]) => {
      if (!Array.isArray(values)) return;
      const cleaned = values
        .map((value: any) => toFilterString(value))
        .filter(Boolean);
      if (cleaned.length) activeFilters[key] = cleaned;
    });

    const metricKeys = (
      requestedMetricKeys.length ? requestedMetricKeys : selectedMetricKeys
    ).filter((key: string) => selectedMetricKeys.includes(key));
    const groupByKeys = (
      requestedGroupByKeys.length ? requestedGroupByKeys : selectedFilterKeys
    ).filter((key: string) => selectedFilterKeys.includes(key));

    const activeFilterEntries = Object.entries(activeFilters);
    const filteredRecords = records.filter((row: any) => {
      return activeFilterEntries.every(([filterKey, selectedValues]) => {
        const rowValue = findRowDimensionValue(row, filterKey);
        return selectedValues.includes(rowValue);
      });
    });

    const periodRecords: Array<{ period: string; row: any }> = isComparisonMode
      ? [
          ...(hasPreRange
            ? filteredRecords
                .filter((row: any) =>
                  isWithinDateRange(row?.date, compareRanges.preStart, compareRanges.preEnd),
                )
                .map((row: any) => ({ period: "Pre", row }))
            : []),
          ...(hasPostRange
            ? filteredRecords
                .filter((row: any) =>
                  isWithinDateRange(row?.date, compareRanges.postStart, compareRanges.postEnd),
                )
                .map((row: any) => ({ period: "Post", row }))
            : []),
        ]
      : filteredRecords.map((row: any) => ({ period: "All", row }));
    const preRecordCount = periodRecords.filter((item) => item.period === "Pre").length;
    const postRecordCount = periodRecords.filter((item) => item.period === "Post").length;

    const longRows: Array<Record<string, any>> = [];
    periodRecords.forEach(({ period, row }) => {
      const baseDimensions: Record<string, string> = {};
      selectedFilterKeys.forEach((filterKey) => {
        const value = findRowDimensionValue(row, filterKey);
        if (value) baseDimensions[filterKey] = value;
      });

      metricKeys.forEach((metricKey: string) => {
        const rawValue = row?.metrics?.[metricKey];
        if (rawValue === undefined || rawValue === null || Number.isNaN(Number(rawValue))) {
          return;
        }
        longRows.push({
          period,
          date: row?.date || "",
          metricKey,
          metricName: formatMetricLabel(metricLabels[metricKey] || metricKey),
          value: Number(rawValue),
          ...baseDimensions,
        });
      });
    });

    const workbook = XLSX.utils.book_new();

    const summaryRows = [
      { Field: "File ID", Value: fileId },
      { Field: "Exported At", Value: new Date().toISOString() },
      { Field: "Total Records (cache)", Value: records.length },
      { Field: "Records After Filters", Value: filteredRecords.length },
      { Field: "Selected KPI Count", Value: metricKeys.length },
      { Field: "Selected KPI Keys", Value: metricKeys.join(", ") },
      { Field: "Selected Filter Keys", Value: selectedFilterKeys.join(", ") },
      { Field: "GroupBy Keys", Value: groupByKeys.join(", ") },
      { Field: "Export Format", Value: exportFormat },
      { Field: "Comparison Mode", Value: isComparisonMode ? "Yes" : "No" },
      { Field: "Pre Start", Value: compareRanges.preStart || "" },
      { Field: "Pre End", Value: compareRanges.preEnd || "" },
      { Field: "Post Start", Value: compareRanges.postStart || "" },
      { Field: "Post End", Value: compareRanges.postEnd || "" },
      { Field: "Pre Records", Value: preRecordCount },
      { Field: "Post Records", Value: postRecordCount },
      {
        Field: "Applied Filters",
        Value: activeFilterEntries
          .map(([key, values]) => `${key}=${values.join("|")}`)
          .join("; "),
      },
    ];
    XLSX.utils.book_append_sheet(
      workbook,
      XLSX.utils.json_to_sheet(summaryRows),
      "Summary",
    );

    const kpiOverviewRows = metricKeys.map((metricKey: string) => {
      const allValues = periodRecords
        .map(({ row }) => Number(row?.metrics?.[metricKey]))
        .filter((value: number) => !Number.isNaN(value));
      const allStats = buildStatistics(allValues);

      if (!isComparisonMode) {
        return {
          metricKey,
          metricName: formatMetricLabel(metricLabels[metricKey] || metricKey),
          count: allStats.count,
          average: allStats.average,
          minimum: allStats.minimum,
          maximum: allStats.maximum,
          median: allStats.median,
        };
      }

      const preValues = periodRecords
        .filter((item) => item.period === "Pre")
        .map(({ row }) => Number(row?.metrics?.[metricKey]))
        .filter((value: number) => !Number.isNaN(value));
      const postValues = periodRecords
        .filter((item) => item.period === "Post")
        .map(({ row }) => Number(row?.metrics?.[metricKey]))
        .filter((value: number) => !Number.isNaN(value));
      const preStats = buildStatistics(preValues);
      const postStats = buildStatistics(postValues);

      return {
        metricKey,
        metricName: formatMetricLabel(metricLabels[metricKey] || metricKey),
        pre_count: preStats.count,
        pre_average: preStats.average,
        pre_minimum: preStats.minimum,
        pre_maximum: preStats.maximum,
        pre_median: preStats.median,
        post_count: postStats.count,
        post_average: postStats.average,
        post_minimum: postStats.minimum,
        post_maximum: postStats.maximum,
        post_median: postStats.median,
      };
    });
    XLSX.utils.book_append_sheet(
      workbook,
      XLSX.utils.json_to_sheet(kpiOverviewRows),
      "KPI_Overview",
    );

    XLSX.utils.book_append_sheet(
      workbook,
      XLSX.utils.json_to_sheet(longRows),
      "KPI_Data_Long",
    );

    const usedSheetNames = new Set<string>(["Summary", "KPI_Overview", "KPI_Data_Long"]);
    groupByKeys.forEach((groupKey: string, index: number) => {
      let sheetRows: Array<Record<string, any>> = [];

      if (exportFormat === "date_matrix") {
        const periods = isComparisonMode
          ? ["Pre", "Post"]
          : ["All"];
        const dateHeadersByPeriod: Record<string, string[]> = {};
        periods.forEach((period) => {
          const dates = Array.from(
            new Set(
              periodRecords
                .filter((item) => item.period === period)
                .map((item) => formatDateColumnKey(item.row?.date)),
            ),
          ).sort((a, b) => a.localeCompare(b));
          dateHeadersByPeriod[period] = dates;
        });

        const orderedDateHeaders = periods.flatMap((period) =>
          (dateHeadersByPeriod[period] || []).map((dateKey) =>
            isComparisonMode ? `${period}_${dateKey}` : dateKey,
          ),
        );

        const matrixBucket: Record<
          string,
          Record<string, Record<string, number[]>>
        > = {};

        periodRecords.forEach(({ period, row }) => {
          const groupValue = findRowDimensionValue(row, groupKey) || "N/A";
          if (!matrixBucket[groupValue]) matrixBucket[groupValue] = {};

          const dateKey = formatDateColumnKey(row?.date);
          const dateHeader = isComparisonMode ? `${period}_${dateKey}` : dateKey;

          metricKeys.forEach((metricKey: string) => {
            const rawValue = Number(row?.metrics?.[metricKey]);
            if (Number.isNaN(rawValue)) return;
            if (!matrixBucket[groupValue][metricKey]) matrixBucket[groupValue][metricKey] = {};
            if (!matrixBucket[groupValue][metricKey][dateHeader]) {
              matrixBucket[groupValue][metricKey][dateHeader] = [];
            }
            matrixBucket[groupValue][metricKey][dateHeader].push(rawValue);
          });
        });

        sheetRows = Object.entries(matrixBucket)
          .sort(([a], [b]) => a.localeCompare(b))
          .flatMap(([groupValue, metricMap]) =>
            metricKeys.map((metricKey: string) => {
              const rowPayload: Record<string, any> = {
                [groupKey]: groupValue,
                KPI: formatMetricLabel(metricLabels[metricKey] || metricKey),
              };
              orderedDateHeaders.forEach((dateHeader) => {
                const values = metricMap?.[metricKey]?.[dateHeader] || [];
                const avg =
                  values.length > 0
                    ? values.reduce((acc, value) => acc + value, 0) / values.length
                    : null;
                rowPayload[dateHeader] = avg === null ? "" : Number(avg.toFixed(4));
              });
              return rowPayload;
            }),
          );
      } else {
        const bucket: Record<string, Record<string, Record<string, number[]>>> = {};

        periodRecords.forEach(({ period, row }) => {
          const groupValue = findRowDimensionValue(row, groupKey) || "N/A";
          if (!bucket[period]) bucket[period] = {};
          if (!bucket[period][groupValue]) bucket[period][groupValue] = {};
          metricKeys.forEach((metricKey: string) => {
            const rawValue = Number(row?.metrics?.[metricKey]);
            if (Number.isNaN(rawValue)) return;
            if (!bucket[period][groupValue][metricKey]) bucket[period][groupValue][metricKey] = [];
            bucket[period][groupValue][metricKey].push(rawValue);
          });
        });

        sheetRows = Object.entries(bucket).flatMap(([period, periodBucket]) =>
          Object.entries(periodBucket).map(([groupValue, metricMap]) => {
            const rowPayload: Record<string, any> = {
              [groupKey]: groupValue,
              period,
              recordCount: Object.values(metricMap).reduce(
                (acc, values) => Math.max(acc, values.length),
                0,
              ),
            };
            metricKeys.forEach((metricKey: string) => {
              const values = metricMap[metricKey] || [];
              const average =
                values.length > 0
                  ? values.reduce((acc, value) => acc + value, 0) / values.length
                  : null;
              rowPayload[formatMetricLabel(metricLabels[metricKey] || metricKey)] =
                average === null ? "" : Number(average.toFixed(4));
            });
            return rowPayload;
          }),
        );
      }

      let sheetName = sanitizeSheetName(`By_${groupKey}`, `By_Filter_${index + 1}`);
      while (usedSheetNames.has(sheetName)) {
        sheetName = sanitizeSheetName(`${sheetName}_${index + 1}`, `By_Filter_${index + 1}`);
      }
      usedSheetNames.add(sheetName);

      XLSX.utils.book_append_sheet(
        workbook,
        XLSX.utils.json_to_sheet(sheetRows),
        sheetName,
      );
    });

    const fileName = sanitizeSheetName(`kpi_report_${fileId}_${Date.now()}`, "kpi_report");
    const buffer = XLSX.write(workbook, { type: "buffer", bookType: "xlsx" });

    kpiDebugLog("[KPI DEBUG] exportDynamicKpiReport", {
      fileId,
      records: records.length,
      filteredRecords: filteredRecords.length,
      periodRecords: periodRecords.length,
      isComparisonMode,
      compareRanges,
      metricKeysCount: metricKeys.length,
      groupByKeysCount: groupByKeys.length,
      exportFormat,
      longRows: longRows.length,
      activeFilters: Object.keys(activeFilters),
    });

    res.setHeader(
      "Content-Type",
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    );
    res.setHeader("Content-Disposition", `attachment; filename=\"${fileName}.xlsx\"`);
    return res.status(200).send(buffer);
  } catch (error: any) {
    console.error(" Error exporting dynamic KPI report:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

export const getAllKpis = async (req: Request, res: Response) => {
  try {
    const data = await prisma.uploadData.findMany({
      where: buildOptionalFileWhere(req),
      select: {
        date: true,
        Groups: true,
        Sector_ID: true,
        Sector_Name: true,
        Cell_Name: true,
        Site: true,
        Band: true,
        Tech: true,

        UL_PRB_Utilization_Rate: true,
        DL_PRB_Utilization_Rate: true,
        UME_4G_Data_Volume_STD_MAPS_MB_903593_1: true,
        UME_E_UTRAN_IP_Throughput_UE_UL_STD_Kbps: true,
        UME_E_UTRAN_IP_Throughput_UE_DL_STD_Kbps: true,
        E_RAB_Drop_Rate: true,
        Initial_ERAB_Establishment_Success_Rate: true,
        RRC_Establishment_Success_Rate: true,
        Mean_RRC_Connected_User_Number: true,
        Maximum_RRC_Connected_User_Number: true,
        E_RAB_Setup_Success_Rate: true,
        RRC_Drop_Rate: true,
        VOLTE_CSSR_Eric: true,
        VOLTE_DCR_Eric: true,
        Inter_Freq_HOSR: true,
        Intra_Freq_HOSR: true,
        CSFB_Success_Rate: true,
      },
      orderBy: { id: "desc" },
    });

    return res.status(200).json({
      success: true,
      count: data.length,
      data: data,
    });
  } catch (error: any) {
    console.error(" Error fetching all KPIs:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};


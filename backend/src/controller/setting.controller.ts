import type { Request, Response } from "express";
import { sendResponse } from "../utils/response.ts";
import { authMessages, statusCode } from "../constants/constants.ts";
import { prisma } from "../config/prisma.ts";
import * as XLSX from "xlsx";

const OPERATOR_VALUES = [">", ">=", "<", "<="] as const;
const OPERATOR_SET = new Set<string>(OPERATOR_VALUES);
const RESERVED_THRESHOLD_KEYS = new Set([
  "id",
  "fileId",
  "success",
  "message",
  "thresholdOperators",
  "operators",
  "dynamicThresholds",
]);
const KNOWN_THRESHOLD_FIELDS = [
  "UL_PRB_Utilization_Rate",
  "DL_PRB_Utilization_Rate",
  "E_RAB_Drop_Rate",
  "RRC_Drop_Rate",
  "Initial_ERAB_Establishment_Success_Rate",
  "RRC_Establishment_Success_Rate",
  "E_RAB_Setup_Success_Rate",
  "VOLTE_CSSR_Eric",
  "VOLTE_DCR_Eric",
  "Inter_Freq_HOSR",
  "Intra_Freq_HOSR",
  "CSFB_Success_Rate",
  "Max_RRC_Users",
] as const;
const KNOWN_THRESHOLD_FIELD_SET = new Set<string>(KNOWN_THRESHOLD_FIELDS);

const normalizeColumnName = (value: unknown): string =>
  String(value || "")
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "");

const getDefaultOperatorForMetric = (metricKey: string): string => {
  const lowerIsBetter = new Set([
    "erabdroprate",
    "rrcdroprate",
    "voltedcr",
    "minerabdroprate",
    "minrrcdroprate",
    "e_rab_drop_rate",
    "rrc_drop_rate",
    "volte_dcr_eric",
  ]);
  const normalizedMetricKey = normalizeColumnName(metricKey);
  return lowerIsBetter.has(normalizedMetricKey) ? "<=" : ">=";
};

const sanitizeThresholdMap = (payload: unknown): Record<string, number> => {
  if (!payload || typeof payload !== "object") return {};

  return Object.entries(payload as Record<string, unknown>).reduce(
    (acc, [rawKey, rawValue]) => {
      const key = String(rawKey || "").trim();
      if (!key) return acc;
      if (RESERVED_THRESHOLD_KEYS.has(key)) return acc;

      const numericValue = Number(rawValue);
      if (!Number.isFinite(numericValue)) return acc;

      acc[key] = numericValue;
      return acc;
    },
    {} as Record<string, number>
  );
};

const sanitizeOperatorMap = (
  payload: unknown,
  thresholdMap: Record<string, number>
): Record<string, string> => {
  const defaults = Object.keys(thresholdMap).reduce((acc, key) => {
    acc[key] = getDefaultOperatorForMetric(key);
    return acc;
  }, {} as Record<string, string>);

  if (!payload || typeof payload !== "object") return defaults;

  Object.entries(payload as Record<string, unknown>).forEach(([rawKey, rawValue]) => {
    const key = String(rawKey || "").trim();
    if (!key) return;
    if (!Object.prototype.hasOwnProperty.call(thresholdMap, key)) return;

    const operatorValue = String(rawValue || "").trim();
    if (OPERATOR_SET.has(operatorValue)) {
      defaults[key] = operatorValue;
    }
  });

  return defaults;
};

const sanitizeOperatorPayload = (payload: unknown): Record<string, string> => {
  if (!payload || typeof payload !== "object") return {};

  return Object.entries(payload as Record<string, unknown>).reduce(
    (acc, [rawKey, rawValue]) => {
      const key = String(rawKey || "").trim();
      if (!key) return acc;

      const operatorValue = String(rawValue || "").trim();
      if (!OPERATOR_SET.has(operatorValue)) return acc;

      acc[key] = operatorValue;
      return acc;
    },
    {} as Record<string, string>
  );
};

const toNumericRecordFromJson = (payload: unknown): Record<string, number> => {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) return {};

  return Object.entries(payload as Record<string, unknown>).reduce(
    (acc, [rawKey, rawValue]) => {
      const key = String(rawKey || "").trim();
      if (!key) return acc;
      const numericValue = Number(rawValue);
      if (!Number.isFinite(numericValue)) return acc;
      acc[key] = numericValue;
      return acc;
    },
    {} as Record<string, number>
  );
};

const toOperatorRecordFromJson = (payload: unknown): Record<string, string> => {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) return {};

  return Object.entries(payload as Record<string, unknown>).reduce(
    (acc, [rawKey, rawValue]) => {
      const key = String(rawKey || "").trim();
      if (!key) return acc;

      const operatorValue = String(rawValue || "").trim();
      if (!OPERATOR_SET.has(operatorValue)) return acc;
      acc[key] = operatorValue;
      return acc;
    },
    {} as Record<string, string>
  );
};

const resolveScopedFileId = (req: Request): number | null | "invalid" => {
  const rawFileId =
    req.query?.fileId ?? (req.body as { fileId?: unknown } | undefined)?.fileId;

  if (rawFileId === undefined || rawFileId === null || String(rawFileId).trim() === "") {
    return null;
  }

  const parsed = Number(rawFileId);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    return "invalid";
  }

  return parsed;
};

const splitKnownAndDynamicThresholds = (thresholdMap: Record<string, number>) => {
  const known: Record<string, number> = {};
  const dynamic: Record<string, number> = {};

  Object.entries(thresholdMap).forEach(([key, value]) => {
    if (KNOWN_THRESHOLD_FIELD_SET.has(key)) {
      known[key] = value;
    } else {
      dynamic[key] = value;
    }
  });

  return { known, dynamic };
};

const extractThresholdPayloadFromRow = (row: Record<string, unknown> | null) => {
  if (!row) return {};

  const knownThresholds = KNOWN_THRESHOLD_FIELDS.reduce((acc, field) => {
    const rawValue = row[field];
    const numericValue = Number(rawValue);
    if (Number.isFinite(numericValue)) {
      acc[field] = numericValue;
    }
    return acc;
  }, {} as Record<string, number>);

  return {
    ...knownThresholds,
    ...toNumericRecordFromJson(row.dynamicThresholds),
  };
};

const extractColumnValue = (
  row: Record<string, unknown>,
  candidateColumns: ReadonlySet<string>
): unknown => {
  for (const [columnName, columnValue] of Object.entries(row)) {
    if (candidateColumns.has(normalizeColumnName(columnName))) {
      return columnValue;
    }
  }
  return undefined;
};

export const updateSettings = async (req: Request, res: Response) => {
  try {
    const scopedFileId = resolveScopedFileId(req);
    if (scopedFileId === "invalid") {
      return sendResponse(
        res,
        statusCode.BAD_REQUEST,
        "Invalid fileId. It must be a positive integer.",
        false
      );
    }

    const incomingThresholds = sanitizeThresholdMap(req.body);
    const incomingOperators = sanitizeOperatorPayload(
      req.body?.thresholdOperators ?? req.body?.operators
    );

    if (!Object.keys(incomingThresholds).length && !Object.keys(incomingOperators).length) {
      return sendResponse(
        res,
        statusCode.BAD_REQUEST,
        "No valid threshold values or operators provided.",
        false
      );
    }

    if (typeof scopedFileId === "number") {
      const fileExists = await prisma.uploadHistory.findUnique({
        where: { id: scopedFileId },
        select: { id: true },
      });
      if (!fileExists) {
        return sendResponse(
          res,
          statusCode.NOT_FOUND,
          `No upload found for fileId ${scopedFileId}.`,
          false
        );
      }
    }

    const existingRow =
      typeof scopedFileId === "number"
        ? await prisma.threshold.findUnique({ where: { fileId: scopedFileId } })
        : await prisma.threshold.findFirst({
            where: { fileId: null },
            orderBy: { id: "asc" },
          });

    const { known: knownThresholdUpdates, dynamic: dynamicThresholdUpdates } =
      splitKnownAndDynamicThresholds(incomingThresholds);
    const existingDynamicThresholds = toNumericRecordFromJson(
      existingRow?.dynamicThresholds
    );
    const existingOperators = toOperatorRecordFromJson(existingRow?.thresholdOperators);

    const mergedDynamicThresholds = {
      ...existingDynamicThresholds,
      ...dynamicThresholdUpdates,
    };
    const mergedOperators = {
      ...existingOperators,
      ...incomingOperators,
    };

    const updateData: Record<string, unknown> = { ...knownThresholdUpdates };
    if (Object.keys(dynamicThresholdUpdates).length) {
      updateData.dynamicThresholds = mergedDynamicThresholds;
    }
    if (Object.keys(incomingOperators).length) {
      updateData.thresholdOperators = mergedOperators;
    }

    let setting;
    if (existingRow) {
      if (!Object.keys(updateData).length) {
        return sendResponse(
          res,
          statusCode.BAD_REQUEST,
          "No valid threshold fields provided for update.",
          false
        );
      }
      setting = await prisma.threshold.update({
        where: { id: existingRow.id },
        data: updateData,
      });
    } else {
      setting = await prisma.threshold.create({
        data: {
          fileId: scopedFileId,
          ...knownThresholdUpdates,
          dynamicThresholds: Object.keys(dynamicThresholdUpdates).length
            ? dynamicThresholdUpdates
            : undefined,
          thresholdOperators: Object.keys(incomingOperators).length
            ? incomingOperators
            : undefined,
        },
      });
    }

    const responseThresholds = extractThresholdPayloadFromRow(setting as unknown as Record<string, unknown>);
    const responseOperators = toOperatorRecordFromJson(setting.thresholdOperators);

    return sendResponse(
      res,
      statusCode.OK,
      "Threshold updated successfully",
      true,
      {
        id: setting.id,
        fileId: setting.fileId,
        ...responseThresholds,
        thresholdOperators: responseOperators,
      }
    );
  } catch (error) {
    console.error("Update threshold Error:", error);

    if (error instanceof Error) {
      console.error("Error details:", error.message);
      console.error("Stack:", error.stack);
    }

    return sendResponse(
      res,
      statusCode.INTERNAL_SERVER_ERROR,
      authMessages.SERVER_ERROR,
      false
    );
  }
};

export const getSettings = async (req: Request, res: Response) => {
  try {
    const scopedFileId = resolveScopedFileId(req);
    if (scopedFileId === "invalid") {
      return sendResponse(
        res,
        statusCode.BAD_REQUEST,
        "Invalid fileId. It must be a positive integer.",
        false
      );
    }

    let globalRow = await prisma.threshold.findFirst({
      where: { fileId: null },
      orderBy: { id: "asc" },
    });

    if (!globalRow) {
      globalRow = await prisma.threshold.create({
        data: { fileId: null },
      });
    }

    let scopedRow = null;
    if (typeof scopedFileId === "number") {
      scopedRow = await prisma.threshold.findUnique({
        where: { fileId: scopedFileId },
      });
    }

    const globalThresholds = extractThresholdPayloadFromRow(
      globalRow as unknown as Record<string, unknown>
    );
    const scopedThresholds = extractThresholdPayloadFromRow(
      scopedRow as unknown as Record<string, unknown>
    );
    const mergedThresholds =
      typeof scopedFileId === "number"
        ? { ...globalThresholds, ...scopedThresholds }
        : globalThresholds;

    const globalOperators = toOperatorRecordFromJson(globalRow.thresholdOperators);
    const scopedOperators = toOperatorRecordFromJson(scopedRow?.thresholdOperators);
    const mergedOperators =
      typeof scopedFileId === "number"
        ? { ...globalOperators, ...scopedOperators }
        : globalOperators;

    const rowId =
      typeof scopedFileId === "number" && scopedRow ? scopedRow.id : globalRow.id;
    const responseFileId =
      typeof scopedFileId === "number" ? scopedFileId : globalRow.fileId;

    return sendResponse(
      res,
      statusCode.OK,
      "Threshold retrieved successfully",
      true,
      {
        id: rowId,
        fileId: responseFileId,
        ...mergedThresholds,
        thresholdOperators: mergedOperators,
      }
    );
  } catch (error) {
    console.error(" Get threshold Error:", error);
    return sendResponse(
      res,
      statusCode.INTERNAL_SERVER_ERROR,
      authMessages.SERVER_ERROR,
      false
    );
  }
};

export const exportThresholdWorkbook = async (req: Request, res: Response) => {
  try {
    const thresholds = sanitizeThresholdMap(req.body?.thresholds);

    if (!Object.keys(thresholds).length) {
      return sendResponse(
        res,
        statusCode.BAD_REQUEST,
        "No valid thresholds provided for export.",
        false
      );
    }

    const operators = sanitizeOperatorMap(req.body?.operators, thresholds);
    const rows = Object.keys(thresholds)
      .sort((a, b) => a.localeCompare(b))
      .map((metricKey) => ({
        Metric_Key: metricKey,
        Threshold_Value: thresholds[metricKey],
        Operator: operators[metricKey] || getDefaultOperatorForMetric(metricKey),
      }));

    const workbook = XLSX.utils.book_new();
    const worksheet = XLSX.utils.json_to_sheet(rows);
    XLSX.utils.book_append_sheet(workbook, worksheet, "Thresholds");

    const outputBuffer = XLSX.write(workbook, {
      type: "buffer",
      bookType: "xlsx",
    }) as Buffer;

    const scopeFileId = String(req.body?.scopeFileId || "").trim();
    const scopeSuffix = scopeFileId ? `file_${scopeFileId}` : "global";
    const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
    const fileName = `dashboard_thresholds_${scopeSuffix}_${timestamp}.xlsx`;

    res.setHeader(
      "Content-Type",
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );
    res.setHeader("Content-Disposition", `attachment; filename="${fileName}"`);
    return res.status(statusCode.OK).send(outputBuffer);
  } catch (error) {
    console.error("Export threshold workbook error:", error);
    return sendResponse(
      res,
      statusCode.INTERNAL_SERVER_ERROR,
      authMessages.SERVER_ERROR,
      false
    );
  }
};

export const importThresholdWorkbook = async (req: Request, res: Response) => {
  try {
    const uploadedFile = req.file;
    if (!uploadedFile?.buffer) {
      return sendResponse(
        res,
        statusCode.BAD_REQUEST,
        "Threshold file is required.",
        false
      );
    }

    const workbook = XLSX.read(uploadedFile.buffer, { type: "buffer" });
    const firstSheetName = workbook.SheetNames[0];

    if (!firstSheetName) {
      return sendResponse(
        res,
        statusCode.BAD_REQUEST,
        "Workbook does not contain any sheets.",
        false
      );
    }

    const firstSheet = workbook.Sheets[firstSheetName];
    const rows = XLSX.utils.sheet_to_json<Record<string, unknown>>(firstSheet, {
      defval: "",
    });

    if (!rows.length) {
      return sendResponse(
        res,
        statusCode.BAD_REQUEST,
        "Workbook is empty.",
        false
      );
    }

    const thresholdKeyColumns = new Set([
      "metric_key",
      "threshold_key",
      "metric",
      "kpi",
      "kpi_key",
      "key",
      "name",
    ]);
    const thresholdValueColumns = new Set([
      "threshold_value",
      "threshold",
      "value",
      "target",
      "target_value",
    ]);
    const operatorColumns = new Set([
      "operator",
      "threshold_operator",
      "comparison_operator",
      "condition",
    ]);

    const importedThresholds: Record<string, number> = {};
    const importedOperators: Record<string, string> = {};
    let importedCount = 0;

    rows.forEach((row) => {
      const thresholdKeyRaw = extractColumnValue(row, thresholdKeyColumns);
      const thresholdValueRaw = extractColumnValue(row, thresholdValueColumns);

      const thresholdKey = String(thresholdKeyRaw || "").trim();
      if (!thresholdKey) return;

      const thresholdValue = Number(thresholdValueRaw);
      if (!Number.isFinite(thresholdValue)) return;

      importedThresholds[thresholdKey] = thresholdValue;
      importedCount += 1;

      const operatorRaw = String(
        extractColumnValue(row, operatorColumns) || ""
      ).trim();
      importedOperators[thresholdKey] = OPERATOR_SET.has(operatorRaw)
        ? operatorRaw
        : getDefaultOperatorForMetric(thresholdKey);
    });

    if (!Object.keys(importedThresholds).length) {
      return sendResponse(
        res,
        statusCode.BAD_REQUEST,
        "No valid threshold rows found. Keep columns like Metric_Key, Threshold_Value, Operator.",
        false
      );
    }

    return sendResponse(
      res,
      statusCode.OK,
      "Threshold file parsed successfully.",
      true,
      {
        thresholds: importedThresholds,
        operators: importedOperators,
        totalRows: rows.length,
        importedCount,
      }
    );
  } catch (error) {
    console.error("Import threshold workbook error:", error);
    return sendResponse(
      res,
      statusCode.INTERNAL_SERVER_ERROR,
      authMessages.SERVER_ERROR,
      false
    );
  }
};

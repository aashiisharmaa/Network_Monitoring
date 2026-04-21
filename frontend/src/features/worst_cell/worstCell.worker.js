const normalizeKey = (value) =>
  String(value || "")
    .toLowerCase()
    .replace(/[^a-z0-9]/g, "");

const toText = (value) => {
  if (value === undefined || value === null) return "";
  return String(value).trim();
};

const getDatePart = (value) => {
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

const readValueByKey = (row, key) => {
  if (!row || !key) return undefined;
  if (Object.prototype.hasOwnProperty.call(row, key)) return row[key];

  const target = normalizeKey(key);
  const match = Object.entries(row).find(([k]) => normalizeKey(k) === target);
  return match ? match[1] : undefined;
};

const readFirstValueByKeys = (row, keys) => {
  for (const key of keys || []) {
    const value = toText(readValueByKey(row, key));
    if (value) return value;
  }
  return "";
};

const extractDimensionMeta = (row, dimensionKey, dimensionValue) => ({
  shortName:
    readFirstValueByKeys(row, [
      "shortName",
      "shortname",
      "cellName",
      "cellname",
      "cellId",
      "cellid",
      dimensionKey,
    ]) || toText(dimensionValue),
  cellId:
    readFirstValueByKeys(row, ["cellId", "cellid", "cellName", "cellname"]) ||
    toText(dimensionValue),
  siteId: readFirstValueByKeys(row, ["siteId", "siteid", "site"]),
  tech: readFirstValueByKeys(row, ["tech", "technology"]),
  sector: readFirstValueByKeys(row, ["sector", "sectorname", "sectorid"]),
});

const mergeDimensionMeta = (preMeta, postMeta, dimensionValue) => ({
  shortName: postMeta?.shortName || preMeta?.shortName || toText(dimensionValue),
  cellId: postMeta?.cellId || preMeta?.cellId || toText(dimensionValue),
  siteId: postMeta?.siteId || preMeta?.siteId || "",
  tech: postMeta?.tech || preMeta?.tech || "",
  sector: postMeta?.sector || preMeta?.sector || "",
});

const mergeMeta = (baseMeta, nextMeta, dimensionValue) => ({
  shortName: baseMeta?.shortName || nextMeta?.shortName || toText(dimensionValue),
  cellId: baseMeta?.cellId || nextMeta?.cellId || toText(dimensionValue),
  siteId: baseMeta?.siteId || nextMeta?.siteId || "",
  tech: baseMeta?.tech || nextMeta?.tech || "",
  sector: baseMeta?.sector || nextMeta?.sector || "",
});

const isWithinRange = (dateValue, start, end) => {
  if (!start && !end) return true;
  const datePart = getDatePart(dateValue);
  if (!datePart) return false;
  if (start && datePart < start) return false;
  if (end && datePart > end) return false;
  return true;
};

const aggregateByDimension = (rows, dimensionKey, start, end) => {
  const bucket = new Map();

  (rows || []).forEach((row) => {
    const date = readValueByKey(row, "date");
    if (!isWithinRange(date, start, end)) return;

    const dimensionValue = String(readValueByKey(row, dimensionKey) || "").trim();
    if (!dimensionValue) return;

    const numericValue = Number(row.value);
    if (Number.isNaN(numericValue)) return;

    const currentMeta = extractDimensionMeta(row, dimensionKey, dimensionValue);

    if (!bucket.has(dimensionValue)) {
      bucket.set(dimensionValue, { sum: 0, count: 0, meta: currentMeta });
    }

    const item = bucket.get(dimensionValue);
    item.meta = {
      shortName: item.meta?.shortName || currentMeta.shortName,
      cellId: item.meta?.cellId || currentMeta.cellId,
      siteId: item.meta?.siteId || currentMeta.siteId,
      tech: item.meta?.tech || currentMeta.tech,
      sector: item.meta?.sector || currentMeta.sector,
    };
    item.sum += numericValue;
    item.count += 1;
  });

  const result = new Map();
  bucket.forEach((item, key) => {
    if (!item.count) return;
    result.set(key, {
      average: item.sum / item.count,
      count: item.count,
      meta: item.meta || null,
    });
  });
  return result;
};

const aggregateDailyByDimension = (rows, dimensionKey, start, end) => {
  const cellBucket = new Map();

  (rows || []).forEach((row) => {
    const datePart = getDatePart(readValueByKey(row, "date"));
    if (!datePart || !isWithinRange(datePart, start, end)) return;

    const dimensionValue = String(readValueByKey(row, dimensionKey) || "").trim();
    if (!dimensionValue) return;

    const numericValue = Number(row.value);
    if (Number.isNaN(numericValue)) return;

    const rowMeta = extractDimensionMeta(row, dimensionKey, dimensionValue);
    if (!cellBucket.has(dimensionValue)) {
      cellBucket.set(dimensionValue, new Map());
    }

    const dateBucket = cellBucket.get(dimensionValue);
    if (!dateBucket.has(datePart)) {
      dateBucket.set(datePart, {
        sum: 0,
        count: 0,
        meta: rowMeta,
      });
    }

    const item = dateBucket.get(datePart);
    item.meta = mergeMeta(item.meta, rowMeta, dimensionValue);
    item.sum += numericValue;
    item.count += 1;
  });

  const result = new Map();
  cellBucket.forEach((dateBucket, cellKey) => {
    const dayMap = new Map();
    dateBucket.forEach((item, datePart) => {
      if (!item.count) return;
      dayMap.set(datePart, {
        average: item.sum / item.count,
        count: item.count,
        meta: item.meta || null,
      });
    });
    if (dayMap.size) {
      result.set(cellKey, dayMap);
    }
  });

  return result;
};

const evaluateComparison = (postValue, preValue, operator) => {
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

const evaluateThreshold = (value, threshold, operator) => {
  const numericValue = Number(value);
  const numericThreshold = Number(threshold);
  if (Number.isNaN(numericValue) || Number.isNaN(numericThreshold)) return false;

  switch (operator) {
    case ">":
      return numericValue > numericThreshold;
    case ">=":
      return numericValue >= numericThreshold;
    case "<":
      return numericValue < numericThreshold;
    case "<=":
      return numericValue <= numericThreshold;
    default:
      return numericValue >= numericThreshold;
  }
};

const analyzeThresholdMetric = (payload) => {
  const {
    metricKey,
    metricLabel,
    rows,
    dimensionKey,
    activeRange,
    thresholdContext,
  } = payload;

  const start = activeRange?.start || "";
  const end = activeRange?.end || "";
  const rangeMap = aggregateByDimension(rows, dimensionKey, start, end);
  const failedDimensions = [];
  const hasThreshold =
    typeof thresholdContext?.thresholdValue === "number" &&
    !Number.isNaN(thresholdContext?.thresholdValue);

  rangeMap.forEach((item, cell) => {
    if (!hasThreshold) return;
    const pass = evaluateThreshold(
      item.average,
      thresholdContext.thresholdValue,
      thresholdContext.thresholdOperator,
    );
    if (!pass) {
      failedDimensions.push({
        value: cell,
        avgValue: item.average,
        thresholdValue: thresholdContext.thresholdValue,
        thresholdOperator: thresholdContext.thresholdOperator,
        preValue: item.average,
        postValue: thresholdContext.thresholdValue,
        delta: item.average - thresholdContext.thresholdValue,
        preCount: item.count,
        postCount: item.count,
        meta: item.meta || null,
      });
    }
  });

  failedDimensions.sort((a, b) => Math.abs(b.delta) - Math.abs(a.delta));
  const averageDelta = failedDimensions.length
    ? failedDimensions.reduce((sum, item) => sum + item.delta, 0) /
      failedDimensions.length
    : 0;

  return {
    metricKey,
    metricLabel,
    mode: "threshold",
    thresholdKey: thresholdContext?.thresholdKey || "",
    thresholdValue: thresholdContext?.thresholdValue ?? null,
    thresholdOperator: thresholdContext?.thresholdOperator || ">=",
    comparedDimensions: rangeMap.size,
    failedDimensions,
    failCount: failedDimensions.length,
    passCount: Math.max(rangeMap.size - failedDimensions.length, 0),
    averageDelta,
    activeRange: { start, end },
  };
};

const analyzePrePostMetric = (payload) => {
  const {
    metricKey,
    metricLabel,
    rows,
    dimensionKey,
    preRange,
    postRange,
    operator,
  } = payload;

  const preMap = aggregateByDimension(
    rows,
    dimensionKey,
    preRange?.start || "",
    preRange?.end || "",
  );
  const postMap = aggregateByDimension(
    rows,
    dimensionKey,
    postRange?.start || "",
    postRange?.end || "",
  );

  const commonKeys = [...preMap.keys()].filter((cell) => postMap.has(cell));
  const failedDimensions = [];
  commonKeys.forEach((cell) => {
    const pre = preMap.get(cell);
    const post = postMap.get(cell);
    const pass = evaluateComparison(post.average, pre.average, operator);
    if (pass === false) {
      failedDimensions.push({
        value: cell,
        preValue: pre.average,
        postValue: post.average,
        delta: post.average - pre.average,
        preCount: pre.count,
        postCount: post.count,
        meta: mergeDimensionMeta(pre.meta, post.meta, cell),
      });
    }
  });

  failedDimensions.sort((a, b) => Math.abs(b.delta) - Math.abs(a.delta));
  const averageDelta = failedDimensions.length
    ? failedDimensions.reduce((sum, item) => sum + item.delta, 0) /
      failedDimensions.length
    : 0;

  return {
    metricKey,
    metricLabel,
    mode: "pre_post",
    operator,
    comparedDimensions: commonKeys.length,
    failedDimensions,
    failCount: failedDimensions.length,
    passCount: Math.max(commonKeys.length - failedDimensions.length, 0),
    averageDelta,
  };
};

const analyzeBadDaysByCell = (payload) => {
  const { metrics, dimensionKey, activeRange, minimumBadDays } = payload || {};
  const start = activeRange?.start || "";
  const end = activeRange?.end || "";
  const minDays = Number.isFinite(Number(minimumBadDays))
    ? Math.max(1, Number(minimumBadDays))
    : 1;

  const cellState = new Map();
  const comparedCellSet = new Set();

  (metrics || []).forEach((metricPayload) => {
    const thresholdValue = Number(metricPayload?.thresholdContext?.thresholdValue);
    const hasThreshold = Number.isFinite(thresholdValue);
    if (!hasThreshold) return;

    const thresholdOperator =
      metricPayload?.thresholdContext?.thresholdOperator || ">=";
    const metricLabel = metricPayload?.metricLabel || metricPayload?.metricKey || "KPI";
    const metricKey = metricPayload?.metricKey || metricLabel;
    const thresholdKey = metricPayload?.thresholdContext?.thresholdKey || metricKey;

    const dayAveragesByCell = aggregateDailyByDimension(
      metricPayload?.rows || [],
      dimensionKey,
      start,
      end,
    );

    dayAveragesByCell.forEach((dayMap, cellValue) => {
      comparedCellSet.add(cellValue);
      if (!cellState.has(cellValue)) {
        cellState.set(cellValue, {
          value: cellValue,
          meta: null,
          badDayMap: new Map(),
        });
      }

      const cellRecord = cellState.get(cellValue);
      dayMap.forEach((dayItem, datePart) => {
        cellRecord.meta = mergeMeta(cellRecord.meta, dayItem?.meta, cellValue);
        const pass = evaluateThreshold(
          dayItem.average,
          thresholdValue,
          thresholdOperator,
        );
        if (pass) return;

        if (!cellRecord.badDayMap.has(datePart)) {
          cellRecord.badDayMap.set(datePart, []);
        }
        cellRecord.badDayMap.get(datePart).push({
          metricKey,
          metricLabel,
          thresholdKey,
          thresholdOperator,
          thresholdValue,
          averageValue: dayItem.average,
          sampleCount: dayItem.count,
        });
      });
    });
  });

  const rows = [];
  cellState.forEach((cellRecord) => {
    const badDays = [...cellRecord.badDayMap.entries()]
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([datePart, degradedKpis]) => ({
        date: datePart,
        degradedKpis,
        degradedKpiCount: degradedKpis.length,
      }));

    const badDayCount = badDays.length;
    if (badDayCount < minDays) return;

    const uniqueKeyMap = new Map();
    let degradedKpiEventCount = 0;
    badDays.forEach((day) => {
      degradedKpiEventCount += day.degradedKpiCount;
      day.degradedKpis.forEach((kpiItem) => {
        if (!uniqueKeyMap.has(kpiItem.metricKey)) {
          uniqueKeyMap.set(kpiItem.metricKey, kpiItem.metricLabel);
        }
      });
    });

    const degradedKpiNames = [...uniqueKeyMap.values()].sort((a, b) =>
      a.localeCompare(b),
    );

    rows.push({
      value: cellRecord.value,
      meta: cellRecord.meta || null,
      badDayCount,
      degradedKpiUniqueCount: uniqueKeyMap.size,
      degradedKpiEventCount,
      degradedKpiNames,
      badDays,
      activeRange: { start, end },
    });
  });

  rows.sort((a, b) => {
    if (b.badDayCount !== a.badDayCount) return b.badDayCount - a.badDayCount;
    if (b.degradedKpiEventCount !== a.degradedKpiEventCount) {
      return b.degradedKpiEventCount - a.degradedKpiEventCount;
    }
    if (b.degradedKpiUniqueCount !== a.degradedKpiUniqueCount) {
      return b.degradedKpiUniqueCount - a.degradedKpiUniqueCount;
    }
    return String(a.value || "").localeCompare(String(b.value || ""));
  });

  return {
    mode: "bad_days",
    minimumBadDays: minDays,
    activeRange: { start, end },
    rows,
    totalComparedCells: comparedCellSet.size,
  };
};

const analyzeBadDaysByCellPrePost = (payload) => {
  const { metrics, dimensionKey, preRange, postRange, minimumBadDays } = payload || {};
  const preStart = preRange?.start || "";
  const preEnd = preRange?.end || "";
  const postStart = postRange?.start || "";
  const postEnd = postRange?.end || "";
  const minDays = Number.isFinite(Number(minimumBadDays))
    ? Math.max(1, Number(minimumBadDays))
    : 1;

  const cellState = new Map();
  const comparedCellSet = new Set();

  (metrics || []).forEach((metricPayload) => {
    const metricLabel = metricPayload?.metricLabel || metricPayload?.metricKey || "KPI";
    const metricKey = metricPayload?.metricKey || metricLabel;
    const thresholdOperator =
      metricPayload?.thresholdContext?.thresholdOperator || ">=";
    const thresholdKey = metricPayload?.thresholdContext?.thresholdKey || metricKey;
    const configuredThresholdValue = Number(
      metricPayload?.thresholdContext?.thresholdValue,
    );

    const preCellMap = aggregateByDimension(
      metricPayload?.rows || [],
      dimensionKey,
      preStart,
      preEnd,
    );
    const postDailyByCell = aggregateDailyByDimension(
      metricPayload?.rows || [],
      dimensionKey,
      postStart,
      postEnd,
    );
    const preDailyByCell = aggregateDailyByDimension(
      metricPayload?.rows || [],
      dimensionKey,
      preStart,
      preEnd,
    );

    postDailyByCell.forEach((dayMap, cellValue) => {
      const preItem = preCellMap.get(cellValue);
      if (!preItem) return;
      comparedCellSet.add(cellValue);

      if (!cellState.has(cellValue)) {
        cellState.set(cellValue, {
          value: cellValue,
          meta: null,
          badDayMap: new Map(),
          heatmapMap: new Map(),
        });
      }

      const cellRecord = cellState.get(cellValue);
      const preDayMap = preDailyByCell.get(cellValue);

      const upsertHeatmapItem = (datePart, dayItem, period, isFail = false) => {
        if (!datePart || !dayItem) return;
        const mapKey = `${metricKey}__${datePart}`;
        const existing = cellRecord.heatmapMap.get(mapKey);
        const payloadItem = {
          metricKey,
          metricLabel,
          thresholdKey,
          thresholdOperator,
          thresholdValue: Number.isFinite(configuredThresholdValue)
            ? configuredThresholdValue
            : null,
          averageValue: dayItem.average,
          preAverageValue: preItem.average,
          sampleCount: dayItem.count,
          preSampleCount: preItem.count,
          comparisonMode: "pre_post_daily",
          period,
          isFail,
          date: datePart,
        };
        cellRecord.heatmapMap.set(
          mapKey,
          existing ? { ...existing, ...payloadItem } : payloadItem,
        );
      };

      if (preDayMap) {
        preDayMap.forEach((dayItem, datePart) => {
          upsertHeatmapItem(datePart, dayItem, "pre", false);
        });
      }

      dayMap.forEach((dayItem, datePart) => {
        cellRecord.meta = mergeMeta(cellRecord.meta, dayItem?.meta, cellValue);
        const pass = evaluateComparison(
          dayItem.average,
          preItem.average,
          thresholdOperator,
        );
        const isFail = pass === false;
        upsertHeatmapItem(datePart, dayItem, "post", isFail);
        if (pass !== false) return;

        if (!cellRecord.badDayMap.has(datePart)) {
          cellRecord.badDayMap.set(datePart, []);
        }
        cellRecord.badDayMap.get(datePart).push({
          metricKey,
          metricLabel,
          thresholdKey,
          thresholdOperator,
          thresholdValue: preItem.average,
          averageValue: dayItem.average,
          preAverageValue: preItem.average,
          sampleCount: dayItem.count,
          preSampleCount: preItem.count,
          comparisonMode: "pre_post_daily",
        });
      });
    });
  });

  const rows = [];
  cellState.forEach((cellRecord) => {
    const badDays = [...cellRecord.badDayMap.entries()]
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([datePart, degradedKpis]) => ({
        date: datePart,
        degradedKpis,
        degradedKpiCount: degradedKpis.length,
      }));

    const badDayCount = badDays.length;
    if (badDayCount < minDays) return;

    const uniqueKeyMap = new Map();
    let degradedKpiEventCount = 0;
    badDays.forEach((day) => {
      degradedKpiEventCount += day.degradedKpiCount;
      day.degradedKpis.forEach((kpiItem) => {
        if (!uniqueKeyMap.has(kpiItem.metricKey)) {
          uniqueKeyMap.set(kpiItem.metricKey, kpiItem.metricLabel);
        }
      });
    });

    const degradedKpiNames = [...uniqueKeyMap.values()].sort((a, b) =>
      a.localeCompare(b),
    );

    rows.push({
      value: cellRecord.value,
      meta: cellRecord.meta || null,
      badDayCount,
      degradedKpiUniqueCount: uniqueKeyMap.size,
      degradedKpiEventCount,
      degradedKpiNames,
      badDays,
      heatmapEntries: [...cellRecord.heatmapMap.values()].sort((a, b) => {
        if (a.date !== b.date) return String(a.date).localeCompare(String(b.date));
        return String(a.metricLabel).localeCompare(String(b.metricLabel));
      }),
      preRange: { start: preStart, end: preEnd },
      postRange: { start: postStart, end: postEnd },
    });
  });

  rows.sort((a, b) => {
    if (b.badDayCount !== a.badDayCount) return b.badDayCount - a.badDayCount;
    if (b.degradedKpiEventCount !== a.degradedKpiEventCount) {
      return b.degradedKpiEventCount - a.degradedKpiEventCount;
    }
    if (b.degradedKpiUniqueCount !== a.degradedKpiUniqueCount) {
      return b.degradedKpiUniqueCount - a.degradedKpiUniqueCount;
    }
    return String(a.value || "").localeCompare(String(b.value || ""));
  });

  return {
    mode: "bad_days_pre_post",
    minimumBadDays: minDays,
    preRange: { start: preStart, end: preEnd },
    postRange: { start: postStart, end: postEnd },
    rows,
    totalComparedCells: comparedCellSet.size,
  };
};

self.onmessage = (event) => {
  const { type, id, payload } = event.data || {};
  if (type !== "analyzeMetric") return;

  try {
    let result = null;
    if (payload?.mode === "bad_days_pre_post") {
      result = analyzeBadDaysByCellPrePost(payload);
    } else if (payload?.mode === "bad_days") {
      result = analyzeBadDaysByCell(payload);
    } else if (payload?.mode === "threshold") {
      result = analyzeThresholdMetric(payload);
    } else {
      result = analyzePrePostMetric(payload);
    }

    self.postMessage({ id, result });
  } catch (error) {
    self.postMessage({
      id,
      error: error?.message || "Metric analysis failed in worker.",
    });
  }
};

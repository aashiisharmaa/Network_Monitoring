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
  const matched = text.match(/^(\d{4}-\d{2}-\d{2})/);
  if (matched?.[1]) return matched[1];

  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return "";
  return parsed.toISOString().slice(0, 10);
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

self.onmessage = (event) => {
  const { type, id, payload } = event.data || {};
  if (type !== "analyzeMetric") return;

  try {
    const result =
      payload?.mode === "threshold"
        ? analyzeThresholdMetric(payload)
        : analyzePrePostMetric(payload);

    self.postMessage({ id, result });
  } catch (error) {
    self.postMessage({
      id,
      error: error?.message || "Metric analysis failed in worker.",
    });
  }
};

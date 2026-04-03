import fs from "fs";
import path from "path";

const KPI_CACHE_DIR = path.join(process.cwd(), "uploads", "kpi-cache");

const ensureKpiCacheDir = () => {
  if (!fs.existsSync(KPI_CACHE_DIR)) {
    fs.mkdirSync(KPI_CACHE_DIR, { recursive: true });
  }
};

export const getKpiCachePath = (fileId: number) =>
  path.join(KPI_CACHE_DIR, `${fileId}.json`);

export const writeKpiCache = (fileId: number, payload: unknown) => {
  ensureKpiCacheDir();
  fs.writeFileSync(getKpiCachePath(fileId), JSON.stringify(payload));
};

export const readKpiCache = (fileId: number): any | null => {
  const cachePath = getKpiCachePath(fileId);
  if (!fs.existsSync(cachePath)) return null;
  try {
    const raw = fs.readFileSync(cachePath, "utf-8");
    return JSON.parse(raw);
  } catch {
    return null;
  }
};

export const deleteKpiCache = (fileId: number) => {
  const cachePath = getKpiCachePath(fileId);
  if (fs.existsSync(cachePath)) {
    fs.unlinkSync(cachePath);
  }
};

export const updateKpiCache = (
  fileId: number,
  updater: (current: any) => any,
): any | null => {
  const current = readKpiCache(fileId);
  if (!current) return null;
  const updated = updater(current);
  writeKpiCache(fileId, updated);
  return updated;
};

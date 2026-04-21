import { apiFetch, API_BASE_URL } from "../../../services/apiClient";

async function getRequest(endpoint, fileId) {
  try {
    return await apiFetch(endpoint, {
      method: "GET",
      query: fileId ? { fileId } : undefined,
    });
  } catch (error) {
    console.error("[dashboardService] GET request failed", {
      endpoint,
      fileId,
      message: error?.message,
    });
    return null;
  }
}

export async function getDashboardData(fileId) {
  return getRequest("dashboard/stats", fileId);
}

export async function getPerformanceData(fileId) {
  return getRequest("dashboard/stats/performance", fileId);
}

export async function getCellsData(fileId) {
  return getRequest("dashboard/stats/cells", fileId);
}

export async function getSitesData(fileId) {
  return getRequest("dashboard/stats/sites", fileId);
}

export async function getBandsData(fileId) {
  return getRequest("dashboard/stats/bands", fileId);
}

export async function getThresholdSetting(fileId) {
  return getRequest("setting/getsettings", fileId);
}

export async function postThresholdSetting(body, fileId) {
  try {
    return await apiFetch("setting/updateSetting", {
      method: "POST",
      query: fileId ? { fileId } : undefined,
      body,
    });
  } catch (error) {
    console.error("[dashboardService] postThresholdSetting failed", {
      fileId,
      message: error?.message,
    });
    return null;
  }
}

export async function exportThresholdWorkbook(payload = {}) {
  const baseUrl = API_BASE_URL.replace(/\/+$/, "");
  const token =
    typeof window !== "undefined" ? window.localStorage.getItem("authToken") : null;

  const response = await fetch(`${baseUrl}/setting/thresholds/export`, {
    method: "POST",
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      "Content-Type": "application/json",
    },
    body: JSON.stringify(payload || {}),
  });

  if (!response.ok) {
    let message = `API Error: ${response.status}`;
    try {
      const json = await response.json();
      if (json?.message) message = json.message;
    } catch {
      // Ignore parse errors for non-JSON responses.
    }
    throw new Error(message);
  }

  const blob = await response.blob();
  const contentDisposition = response.headers.get("content-disposition") || "";
  const fileNameMatch = contentDisposition.match(/filename="?([^";]+)"?/i);
  const fileName = fileNameMatch?.[1] || "dashboard_thresholds.xlsx";

  return { blob, fileName };
}

export async function importThresholdWorkbook(file) {
  try {
    const formData = new FormData();
    formData.append("file", file);

    return await apiFetch("setting/thresholds/import", {
      method: "POST",
      body: formData,
    });
  } catch (error) {
    console.error("[dashboardService] importThresholdWorkbook failed", {
      fileName: file?.name,
      message: error?.message,
    });
    return null;
  }
}

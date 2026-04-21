import { apiFetch, API_BASE_URL } from "../../services/apiClient";

export async function runRecommendation(fileId, payload = {}) {
  try {
    return await apiFetch("kpi/recommendation/run", {
      method: "POST",
      query: { fileId },
      body: payload,
    });
  } catch (error) {
    console.error("[recommendationService] runRecommendation failed", {
      fileId,
      payload,
      message: error?.message,
    });
    throw error;
  }
}

export async function fetchRecommendationPresets() {
  try {
    return await apiFetch("kpi/recommendation/presets", {
      method: "GET",
    });
  } catch (error) {
    console.error("[recommendationService] fetchRecommendationPresets failed", {
      message: error?.message,
    });
    throw error;
  }
}

export async function saveRecommendationPreset(name, config) {
  try {
    return await apiFetch("kpi/recommendation/presets", {
      method: "POST",
      body: { name, config },
    });
  } catch (error) {
    console.error("[recommendationService] saveRecommendationPreset failed", {
      name,
      message: error?.message,
    });
    throw error;
  }
}

export async function loadRecommendationPreset(name) {
  try {
    return await apiFetch(`kpi/recommendation/presets/${encodeURIComponent(name)}`, {
      method: "GET",
    });
  } catch (error) {
    console.error("[recommendationService] loadRecommendationPreset failed", {
      name,
      message: error?.message,
    });
    throw error;
  }
}

export async function downloadRecommendationExport(fileId, payload = {}) {
  const baseUrl = API_BASE_URL.replace(/\/+$/, "");
  const token =
    typeof window !== "undefined" ? window.localStorage.getItem("authToken") : null;
  const url = new URL(`${baseUrl}/kpi/recommendation/export`);
  url.searchParams.set("fileId", String(fileId));

  const response = await fetch(url.toString(), {
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
      // ignore
    }
    throw new Error(message);
  }

  const blob = await response.blob();
  const contentDisposition = response.headers.get("content-disposition") || "";
  const fileNameMatch = contentDisposition.match(/filename="?([^";]+)"?/i);
  const fileName = fileNameMatch?.[1] || `rca_recommendation_${fileId}.xlsx`;
  return { blob, fileName };
}

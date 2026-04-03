import { apiFetch, API_BASE_URL } from "../../services/apiClient";

async function getKpiData(endpoint, fileId) {
  try {
    return await apiFetch(endpoint, {
      method: "GET",
      query: fileId ? { fileId } : undefined,
    });
  } catch {
    return null;
  }
}

export async function getkpi1Data(fileId) {
  return getKpiData("kpi/ul-prb-utilization", fileId);
}

export async function getkpi2Data(fileId) {
  return getKpiData("kpi/dl-prb-utilization", fileId);
}

export async function getkpi3Data(fileId) {
  return getKpiData("kpi/data-volume", fileId);
}

export async function getkpi4Data(fileId) {
  return getKpiData("kpi/ul-throughput", fileId);
}

export async function getkpi5Data(fileId) {
  return getKpiData("kpi/dl-throughput", fileId);
}

export async function getkpi6Data(fileId) {
  return getKpiData("kpi/erab-drop-rate", fileId);
}

export async function getkpi7Data(fileId) {
  return getKpiData("kpi/erab-success-rate", fileId);
}

export async function getkpi8Data(fileId) {
  return getKpiData("kpi/rrc-success-rate", fileId);
}

export async function getkpi9Data(fileId) {
  return getKpiData("kpi/mean-rrc-users", fileId);
}

export async function getkpi10Data(fileId) {
  return getKpiData("kpi/max-rrc-users", fileId);
}

export async function getkpi11Data(fileId) {
  return getKpiData("kpi/erab-setup-rate", fileId);
}

export async function getkpi12Data(fileId) {
  return getKpiData("kpi/rrc-drop-rate", fileId);
}

export async function getkpi13Data(fileId) {
  return getKpiData("kpi/volte-cssr", fileId);
}

export async function getkpi14Data(fileId) {
  return getKpiData("kpi/volte-dcr", fileId);
}

export async function getkpi15Data(fileId) {
  return getKpiData("kpi/inter-freq-hosr", fileId);
}

export async function getkpi16Data(fileId) {
  return getKpiData("kpi/intra-freq-hosr", fileId);
}

export async function getkpi17Data(fileId) {
  return getKpiData("kpi/csfb-success-rate", fileId);
}

export async function fetchKpiUploadHistory() {
  try {
    const response = await apiFetch("upload/uploads/history", { method: "GET" });
    const uploads = Array.isArray(response?.data) ? response.data : [];

    const kpiUploads = uploads.filter((upload) =>
      String(upload?.remarks || "").toLowerCase().includes("[kpi]"),
    );

    return {
      success: true,
      data: kpiUploads,
    };
  } catch {
    return {
      success: false,
      data: [],
    };
  }
}

export async function fetchAvailableKpiMetrics(fileId) {
  try {
    const response = await apiFetch("kpi/available-metrics", {
      method: "GET",
      query: fileId ? { fileId } : undefined,
    });

    return {
      success: true,
      data: Array.isArray(response?.availableMetrics)
        ? response.availableMetrics
        : [],
    };
  } catch {
    return {
      success: false,
      data: [],
    };
  }
}

export async function fetchDynamicKpiMetrics(fileId) {
  try {
    const response = await apiFetch("kpi/dynamic/metrics", {
      method: "GET",
      query: { fileId },
    });
    return {
      success: true,
      data: Array.isArray(response?.metrics) ? response.metrics : [],
    };
  } catch {
    return {
      success: false,
      data: [],
    };
  }
}

export async function fetchDynamicKpiData(fileId, metricKey) {
  try {
    return await apiFetch("kpi/dynamic/data", {
      method: "GET",
      query: { fileId, metricKey },
    });
  } catch {
    return null;
  }
}

export async function fetchDynamicKpiColumns(fileId) {
  try {
    return await apiFetch("kpi/dynamic/columns", {
      method: "GET",
      query: { fileId },
    });
  } catch {
    return null;
  }
}

export async function updateDynamicKpiSelection(fileId, payload = {}) {
  try {
    return await apiFetch("kpi/dynamic/selection", {
      method: "POST",
      query: { fileId },
      body: payload,
    });
  } catch {
    return null;
  }
}

export async function downloadDynamicKpiReport(fileId, payload = {}) {
  const baseUrl = API_BASE_URL.replace(/\/+$/, "");
  const token =
    typeof window !== "undefined" ? window.localStorage.getItem("authToken") : null;
  const url = new URL(`${baseUrl}/kpi/dynamic/export`);
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
      // ignore parse errors
    }
    throw new Error(message);
  }

  const blob = await response.blob();
  const contentDisposition = response.headers.get("content-disposition") || "";
  const fileNameMatch = contentDisposition.match(/filename="?([^";]+)"?/i);
  const fileName = fileNameMatch?.[1] || `kpi_report_${fileId}.xlsx`;
  return { blob, fileName };
}

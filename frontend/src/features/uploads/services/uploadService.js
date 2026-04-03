import { apiFetch } from "../../../services/apiClient";

const ENDPOINTS = {
  KPI_UPLOAD: import.meta.env.VITE_UPLOAD_KPI_ENDPOINT || "upload/kpi",
  SITE_UPLOAD: import.meta.env.VITE_UPLOAD_SITE_ENDPOINT || "upload/site",
  HISTORY: import.meta.env.VITE_UPLOAD_HISTORY_ENDPOINT || "upload/uploads/history",
};

function createUploadFormData(file, uploadedBy, remarks) {
  const formData = new FormData();
  formData.append("file", file);

  if (uploadedBy) {
    formData.append("uploadedBy", uploadedBy);
  }

  if (remarks) {
    formData.append("remarks", remarks);
  }

  return formData;
}

export async function uploadKpisFile(file, uploadedBy, remarks) {
  try {
    return await apiFetch(ENDPOINTS.KPI_UPLOAD, {
      method: "POST",
      body: createUploadFormData(file, uploadedBy, remarks),
    });
  } catch (error) {
    return {
      success: false,
      message: error.message || "KPI upload failed",
      data: null,
    };
  }
}

export async function uploadSitesFile(file, uploadedBy, remarks) {
  try {
    return await apiFetch(ENDPOINTS.SITE_UPLOAD, {
      method: "POST",
      body: createUploadFormData(file, uploadedBy, remarks),
    });
  } catch (error) {
    return {
      success: false,
      message: error.message || "Site upload failed",
      data: null,
    };
  }
}

export async function fetchUploads() {
  try {
    return await apiFetch(ENDPOINTS.HISTORY, { method: "GET" });
  } catch (error) {
    return {
      success: false,
      message: error.message || "Failed to fetch uploads",
      data: [],
    };
  }
}

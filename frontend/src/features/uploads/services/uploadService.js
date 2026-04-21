import { apiFetch, API_BASE_URL } from "../../../services/apiClient";

const ENDPOINTS = {
  KPI_UPLOAD: import.meta.env.VITE_UPLOAD_KPI_ENDPOINT || "upload/kpi",
  SITE_UPLOAD: import.meta.env.VITE_UPLOAD_SITE_ENDPOINT || "upload/site",
  HISTORY: import.meta.env.VITE_UPLOAD_HISTORY_ENDPOINT || "upload/uploads/history",
  DELETE_UPLOAD: import.meta.env.VITE_UPLOAD_DELETE_ENDPOINT || "upload/uploads",
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
  return uploadKpisFileWithProgress(file, uploadedBy, remarks);
}

export async function uploadKpisFileWithProgress(file, uploadedBy, remarks, onProgress) {
  const formData = createUploadFormData(file, uploadedBy, remarks);
  const baseUrl = API_BASE_URL.replace(/\/+$/, "");
  const endpoint = ENDPOINTS.KPI_UPLOAD.replace(/^\/+/, "");
  const url = `${baseUrl}/${endpoint}`;
  const token =
    typeof window !== "undefined" ? window.localStorage.getItem("authToken") : null;

  try {
    const result = await new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open("POST", url, true);

      if (token) {
        xhr.setRequestHeader("Authorization", `Bearer ${token}`);
      }

      xhr.upload.onprogress = (event) => {
        if (!event.lengthComputable || typeof onProgress !== "function") return;
        const percent = Math.max(0, Math.min(100, Math.round((event.loaded / event.total) * 100)));
        onProgress(percent);
      };

      xhr.onload = () => {
        let payload = null;
        try {
          payload = JSON.parse(xhr.responseText || "{}");
        } catch {
          payload = null;
        }

        if (xhr.status >= 200 && xhr.status < 300) {
          resolve(payload || { success: true, message: "Upload processed successfully." });
          return;
        }

        if (xhr.status === 401 && typeof window !== "undefined") {
          localStorage.removeItem("authToken");
          localStorage.removeItem("user");
          window.dispatchEvent(new Event("auth:unauthorized"));
        }

        reject(new Error(payload?.message || `API Error: ${xhr.status}`));
      };

      xhr.onerror = () => reject(new Error("Network error during upload."));
      xhr.send(formData);
    });

    if (typeof onProgress === "function") onProgress(100);
    return result;
  } catch (error) {
    console.error("[uploadService] uploadKpisFileWithProgress failed", {
      fileName: file?.name,
      uploadedBy,
      remarks,
      message: error?.message,
    });
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
    console.error("[uploadService] uploadSitesFile failed", {
      fileName: file?.name,
      uploadedBy,
      remarks,
      message: error?.message,
    });
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
    console.error("[uploadService] fetchUploads failed", {
      message: error?.message,
    });
    return {
      success: false,
      message: error.message || "Failed to fetch uploads",
      data: [],
    };
  }
}

export async function deleteUploadById(uploadId) {
  try {
    return await apiFetch(`${ENDPOINTS.DELETE_UPLOAD}/${uploadId}`, {
      method: "DELETE",
    });
  } catch (error) {
    console.error("[uploadService] deleteUploadById failed", {
      uploadId,
      message: error?.message,
    });
    return {
      success: false,
      message: error.message || "Failed to delete upload",
    };
  }
}

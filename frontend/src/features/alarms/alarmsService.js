import { apiFetch } from "../../services/apiClient";

const ENDPOINTS = {
  ALARM_UPLOAD: import.meta.env.VITE_UPLOAD_ALARM_ENDPOINT || "upload/alarm",
  ALARM_DATA: import.meta.env.VITE_ALARM_DATA_ENDPOINT || "upload/alarm-data",
};

export async function uploadAlarmFile(file) {
  try {
    const formData = new FormData();
    formData.append("file", file);

    return await apiFetch(ENDPOINTS.ALARM_UPLOAD, {
      method: "POST",
      body: formData,
    });
  } catch (error) {
    return {
      success: false,
      message: error.message || "An error occurred during upload",
    };
  }
}

const defaultAlarmResponse = {
  success: false,
  message: "Failed to fetch alarm data",
  data: [],
  pagination: {
    page: 1,
    limit: 10,
    totalCount: 0,
    totalPages: 0,
    hasNextPage: false,
    hasPrevPage: false,
  },
};

export async function getAlarmData(params = {}) {
  try {
    return await apiFetch(ENDPOINTS.ALARM_DATA, {
      method: "GET",
      query: {
        page: params.page,
        limit: params.limit,
        circle: params.circle,
        severity: params.severity,
        name: params.name,
        search: params.search,
        startDate: params.startDate,
        endDate: params.endDate,
      },
    });
  } catch (error) {
    return {
      ...defaultAlarmResponse,
      message: error.message || defaultAlarmResponse.message,
    };
  }
}

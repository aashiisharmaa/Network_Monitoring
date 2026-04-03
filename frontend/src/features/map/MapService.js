import { apiFetch } from "../../services/apiClient";

export async function getMapDetails() {
  try {
    return await apiFetch("upload/site-data", {
      method: "GET",
      query: { page: 1, limit: 10000 },
    });
  } catch (error) {
    return {
      success: false,
      message: error.message || "Failed to fetch map data",
      data: [],
    };
  }
}

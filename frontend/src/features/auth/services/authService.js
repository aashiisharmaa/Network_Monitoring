import { apiFetch } from "../../../services/apiClient";

export async function loginAPI(email, password) {
  try {
    return await apiFetch("auth/login", {
      method: "POST",
      body: { email, password },
    });
  } catch (error) {
    return {
      success: false,
      message: error.message || "Network error. Please check your connection.",
      data: null,
    };
  }
}

export async function registerAPI(email, password) {
  try {
    return await apiFetch("auth/register", {
      method: "POST",
      body: { email, password },
    });
  } catch (error) {
    return {
      success: false,
      message: error.message || "Registration failed",
      data: null,
    };
  }
}

export async function logoutAPI(token) {
  try {
    return await apiFetch("auth/logout", {
      method: "POST",
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
  } catch (error) {
    return {
      success: false,
      message: error.message || "Logout failed",
      data: null,
    };
  }
}

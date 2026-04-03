const DEFAULT_BASE_URL = "http://localhost:3000";

export const API_BASE_URL =
  import.meta.env.VITE_API_URL?.trim() || DEFAULT_BASE_URL;

function buildUrl(endpoint, query) {
  const cleanBaseUrl = API_BASE_URL.replace(/\/+$/, "");
  const cleanEndpoint = String(endpoint || "").replace(/^\/+/, "");
  const url = new URL(`${cleanBaseUrl}/${cleanEndpoint}`);

  if (query) {
    Object.entries(query).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== "") {
        url.searchParams.append(key, String(value));
      }
    });
  }

  return url.toString();
}

export async function apiFetch(
  endpoint,
  { query, body, headers = {}, ...options } = {},
) {
  const isFormData = typeof FormData !== "undefined" && body instanceof FormData;
  const finalHeaders = { ...headers };
  const token =
    typeof window !== "undefined" ? localStorage.getItem("authToken") : null;

  if (token && !finalHeaders.Authorization) {
    finalHeaders.Authorization = `Bearer ${token}`;
  }

  if (!isFormData && body !== undefined && !finalHeaders["Content-Type"]) {
    finalHeaders["Content-Type"] = "application/json";
  }

  const response = await fetch(buildUrl(endpoint, query), {
    ...options,
    headers: finalHeaders,
    body:
      body === undefined
        ? undefined
        : isFormData || typeof body === "string"
          ? body
          : JSON.stringify(body),
  });

  let payload = null;
  try {
    payload = await response.json();
  } catch {
    payload = null;
  }

  if (!response.ok) {
    const message = payload?.message || `API Error: ${response.status}`;
    const error = new Error(message);
    error.status = response.status;

    if (response.status === 401 && typeof window !== "undefined") {
      localStorage.removeItem("authToken");
      localStorage.removeItem("user");
      window.dispatchEvent(new Event("auth:unauthorized"));
    }

    throw error;
  }

  return payload;
}

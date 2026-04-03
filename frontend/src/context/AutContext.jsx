/* eslint-disable react-refresh/only-export-components */
import { createContext, useContext, useEffect, useState } from "react";
import {
  loginAPI,
  logoutAPI,
  registerAPI,
} from "../features/auth/services/authService";

const AuthContext = createContext();

export const AuthProvider = ({ children }) => {
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [user, setUser] = useState(null);
  const [token, setToken] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    try {
      const storedToken = localStorage.getItem("authToken");
      const storedUser = localStorage.getItem("user");

      if (
        storedToken &&
        storedUser &&
        storedUser !== "undefined" &&
        storedUser !== "null"
      ) {
        const parsedUser = JSON.parse(storedUser);
        setToken(storedToken);
        setUser(parsedUser);
        setIsAuthenticated(true);
      } else {
        localStorage.removeItem("authToken");
        localStorage.removeItem("user");
      }
    } catch (storageError) {
      console.error("Error loading auth data from localStorage:", storageError);
      localStorage.removeItem("authToken");
      localStorage.removeItem("user");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const handleUnauthorized = () => {
      setIsAuthenticated(false);
      setUser(null);
      setToken(null);
      setError("Session expired. Please log in again.");
      setLoading(false);
    };

    window.addEventListener("auth:unauthorized", handleUnauthorized);
    return () => {
      window.removeEventListener("auth:unauthorized", handleUnauthorized);
    };
  }, []);

  const login = async (email, password) => {
    setLoading(true);
    setError(null);

    try {
      const result = await loginAPI(email, password);

      if (result?.success && result?.data) {
        const { token: authToken, user: authUser } = result.data;

        if (!authToken || !authUser) {
          setError("Invalid response from server");
          return { success: false, message: "Invalid response from server" };
        }

        setIsAuthenticated(true);
        setUser(authUser);
        setToken(authToken);

        localStorage.setItem("authToken", authToken);
        localStorage.setItem("user", JSON.stringify(authUser));

        return { success: true };
      }

      const message = result?.message || "Login failed";
      setError(message);
      return { success: false, message };
    } catch (loginError) {
      console.error("Login error:", loginError);
      setError("An unexpected error occurred");
      return { success: false, message: "An unexpected error occurred" };
    } finally {
      setLoading(false);
    }
  };

  const logout = async () => {
    setLoading(true);

    try {
      if (token) {
        const result = await logoutAPI(token);
        if (!result?.success) {
          console.warn("Logout API failed, clearing local session anyway");
        }
      }
    } catch (logoutError) {
      console.error("Logout error:", logoutError);
    } finally {
      setIsAuthenticated(false);
      setUser(null);
      setToken(null);
      setError(null);

      localStorage.removeItem("authToken");
      localStorage.removeItem("user");

      setLoading(false);
    }
  };

  const register = async (email, password) => {
    setLoading(true);
    setError(null);

    try {
      const result = await registerAPI(email, password);

      if (result?.success && result?.data) {
        const { token: authToken, user: authUser } = result.data;
        if (!authToken || !authUser) {
          setError("Invalid response from server");
          return { success: false, message: "Invalid response from server" };
        }

        setIsAuthenticated(true);
        setUser(authUser);
        setToken(authToken);

        localStorage.setItem("authToken", authToken);
        localStorage.setItem("user", JSON.stringify(authUser));

        return { success: true };
      }

      const message = result?.message || "Registration failed";
      setError(message);
      return { success: false, message };
    } catch (registerError) {
      console.error("Registration error:", registerError);
      setError("An unexpected error occurred");
      return { success: false, message: "An unexpected error occurred" };
    } finally {
      setLoading(false);
    }
  };

  return (
    <AuthContext.Provider
      value={{
        isAuthenticated,
        user,
        token,
        loading,
        error,
        login,
        register,
        logout,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return context;
};

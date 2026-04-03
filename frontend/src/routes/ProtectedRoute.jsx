import { Navigate, Outlet } from "react-router-dom";
import { useAuth } from "../context/AutContext";
import { Loader2 } from "lucide-react";
import MainLayout from "../components/layout/MainLayout";

export default function ProtectedRoute() {
  const { isAuthenticated, loading } = useAuth();

  // Show loading spinner while checking authentication
  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-slate-50 via-blue-50 to-indigo-50">
        <div className="flex flex-col items-center gap-3">
          <Loader2 className="w-10 h-10 text-blue-500 animate-spin" />
          <p className="text-sm text-gray-600">Loading...</p>
        </div>
      </div>
    );
  }

  // Redirect to login if not authenticated
  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  // Render children with MainLayout
  return (
    <MainLayout>
      <Outlet />
    </MainLayout>
  );
}
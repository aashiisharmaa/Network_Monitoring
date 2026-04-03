import { Routes, Route, Navigate } from "react-router-dom";
import DashboardPage from "../features/dashboard/DashboardPage";
import MapPage from "../features/map/MapPage";
import UploadsPage from "../features/uploads/UploadsPage";
import WorstCell from "../features/worst_cell/WorstCell";
import AlarmsPage from "../features/alarms/AlarmsPage";
import SitesPage from "../features/sites/SitesPage";
import KpiView from "../features/kpi/KpiView";
import RcaPage from "../features/recommendation/recommendation";
import LoginPage from "../features/auth/LoginPage";
import ProtectedRoute from "./ProtectedRoute";

export default function AppRoutes() {
  return (
    <Routes>
      {/* Public Route */}
      <Route path="/login" element={<LoginPage />} />

      {/* Protected Routes - All wrapped in MainLayout via ProtectedRoute */}
      <Route element={<ProtectedRoute />}>
        <Route path="/" element={<DashboardPage />} />
        <Route path="/kpi" element={<KpiView />} />
        <Route path="/map" element={<MapPage />} />
        <Route path="/worstcell" element={<WorstCell />} />
        <Route path="/recommendation" element={<RcaPage />} />
        <Route path="/uploads" element={<UploadsPage />} />
        <Route path="/alarms" element={<AlarmsPage />} />
        <Route path="/sites" element={<SitesPage />} />
      </Route>

      {/* Catch-all redirect to home */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
import { NavLink } from "react-router-dom";
import {
  LayoutDashboard,
  BarChart3,
  Map,
  AlertTriangle,
  Lightbulb,
  Upload,
  Bell,
  Radio,
  Menu,
  X,
  LogOut,
  User,
  Loader2,
  ChevronLeft,
  ChevronRight,
} from "lucide-react";
import { useState } from "react";
import toolLogo from "../../assets/images/toolLogo.svg";

export default function Sidebar({
  user,
  onLogout,
  loading,
  isCollapsed,
  setIsCollapsed,
}) {
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);

  const navItems = [
    { to: "/", label: "Dashboard", icon: LayoutDashboard, end: true },
    { to: "/kpi", label: "KPI View", icon: BarChart3 },
    { to: "/map", label: "Map View", icon: Map },
    { to: "/worstcell", label: "Anomaly Detection", icon: AlertTriangle },
    { to: "/recommendation", label: "RCA Recommendation", icon: Lightbulb },
    { to: "/uploads", label: "Uploads", icon: Upload },
    { to: "/alarms", label: "Alarms", icon: Bell },
    { to: "/sites", label: "Sites", icon: Radio },
  ];

  return (
    <>
      {/* Mobile Menu Button */}
      <button
        onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
        className="lg:hidden fixed top-4 left-4 z-100 p-2 bg-white text-gray-700 hover:bg-gray-100 rounded-lg transition shadow-md"
      >
        {mobileMenuOpen ? (
          <X className="w-6 h-6" />
        ) : (
          <Menu className="w-6 h-6" />
        )}
      </button>

      {/* Overlay for mobile */}
      {mobileMenuOpen && (
        <div
          className="lg:hidden fixed inset-0 bg-black/50 z-40"
          onClick={() => setMobileMenuOpen(false)}
        />
      )}

      {/* Sidebar */}
      <aside
        className={`fixed top-0 left-0 h-screen bg-white border-r-1 border-gray-200 z-40 transition-all duration-300 flex flex-col ${
          isCollapsed ? "w-20" : "w-64"
        } ${
          mobileMenuOpen
            ? "translate-x-0"
            : "-translate-x-full lg:translate-x-0"
        }`}
      >
        {/* Header/Logo */}
        <div
          className={`flex items-center gap-3 p-6 border-b border-gray-200 ${
            isCollapsed ? "justify-center" : ""
          }`}
        >
          <img
            src={toolLogo}
            alt="Network Monitor Logo"
            className={`flex-shrink-0 transition-all duration-300 ${
              isCollapsed ? "w-10 h-10" : "w-10 h-10"
            }`}
          />
          {!isCollapsed && (
            <div className="overflow-hidden">
              <h2 className="text-md font-extrabold text-transparent bg-clip-text bg-gradient-to-r from-slate-900 via-blue-900 to-indigo-900 truncate">
                Network Monitor
              </h2>
              <p className="text-xs text-gray-500 font-medium">
                Management Portal
              </p>
            </div>
          )}
        </div>

        {/* Navigation Items */}
        <nav className="flex-1 overflow-y-auto overflow-x-hidden py-4 px-3">
          <div className="space-y-1">
            {navItems.map((item) => {
              const Icon = item.icon;
              return (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.end}
                  onClick={() => setMobileMenuOpen(false)}
                  className={({ isActive }) =>
                    `group flex items-center gap-3 px-3 py-3 rounded-xl transition-all duration-300 ${
                      isActive
                        ? "bg-gradient-to-r from-blue-500 to-indigo-600 text-white shadow-lg shadow-blue-200/50"
                        : "text-gray-700 hover:bg-gray-100 hover:text-blue-600"
                    } ${isCollapsed ? "justify-center" : ""}`
                  }
                  title={isCollapsed ? item.label : ""}
                >
                  {({ isActive }) => (
                    <>
                      <Icon
                        className={`w-5 h-5 flex-shrink-0 transition-transform duration-300 ${
                          isActive ? "scale-110" : "group-hover:scale-110"
                        }`}
                      />
                      {!isCollapsed && (
                        <span className="text-sm font-semibold whitespace-nowrap">
                          {item.label}
                        </span>
                      )}
                    </>
                  )}
                </NavLink>
              );
            })}
          </div>
        </nav>

        {/* User Info & Logout */}
        <div className="border-t border-gray-200 p-3 space-y-3">
          {/* User Info */}
          {user && (
            <div
              className={`flex items-center gap-2 px-3 py-2 bg-blue-50 rounded-xl border border-blue-200 ${
                isCollapsed ? "justify-center" : ""
              }`}
              title={isCollapsed ? user.email : ""}
            >
              <div className="p-1.5 bg-blue-100 rounded-lg flex-shrink-0">
                <User className="w-4 h-4 text-blue-700" />
              </div>
              {!isCollapsed && (
                <span className="text-xs font-semibold text-gray-800 truncate">
                  {user.email}
                </span>
              )}
            </div>
          )}

          {/* Logout Button */}
          <button
            onClick={onLogout}
            disabled={loading}
            className={`w-full flex items-center gap-2 px-3 py-2.5 rounded-xl transition-all text-sm font-semibold shadow-md hover:shadow-lg ${
              loading
                ? "bg-gray-200 text-gray-400 cursor-not-allowed"
                : "bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 text-white"
            } ${isCollapsed ? "justify-center" : ""}`}
            title={isCollapsed ? "Logout" : ""}
          >
            {loading ? (
              <>
                <Loader2 className="w-4 h-4 animate-spin" />
                {!isCollapsed && <span>Logging out...</span>}
              </>
            ) : (
              <>
                <LogOut className="w-4 h-4" />
                {!isCollapsed && <span>Logout</span>}
              </>
            )}
          </button>

          {/* Collapse Toggle (Desktop only) */}
          <button
            onClick={() => setIsCollapsed(!isCollapsed)}
            className="hidden lg:flex w-full items-center justify-center gap-2 px-3 py-2 text-gray-600 hover:bg-gray-100 rounded-xl transition-all"
            title={isCollapsed ? "Expand sidebar" : "Collapse sidebar"}
          >
            {isCollapsed ? (
              <ChevronRight className="w-4 h-4" />
            ) : (
              <>
                <ChevronLeft className="w-4 h-4" />
                <span className="text-xs font-semibold">Collapse</span>
              </>
            )}
          </button>
        </div>
      </aside>
    </>
  );
}
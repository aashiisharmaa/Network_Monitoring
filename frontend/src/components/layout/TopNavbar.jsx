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
  Activity,
  Menu,
  X,
  LogOut,
  User,
  Loader2,
} from "lucide-react";
import { useState } from "react";

export default function TopNavbar({ user, onLogout, loading }) {
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);

  const navItems = [
    { to: "/", label: "Dashboard", icon: LayoutDashboard, end: true },
    { to: "/kpi", label: "KPI View", icon: BarChart3 },
    { to: "/map", label: "Map View", icon: Map },
    { to: "/worstcell", label: "Anomaly Detection", icon: AlertTriangle },
    { to: "/recommendation", label: "RCA Recommendation", icon: Lightbulb },
    { to: "/uploads", label: "Uploads", icon: Upload },
    { to: "/alarms", label: "Alarms", icon: Bell },
    { to: "/Sites", label: "Sites", icon: Radio },
  ];

  return (
    <nav className="sticky top-0 z-50 bg-white border-b border-gray-200 shadow-sm">
      <div className="max-w-[1920px] mx-auto px-6">
        <div className="flex items-center justify-between h-20 gap-4">
          {/* Logo/Brand */}
          <div className="flex items-center gap-4 flex-shrink-0">
            <div className="relative">
              <div className="absolute inset-0 bg-gradient-to-r from-blue-500 to-indigo-600 rounded-xl blur opacity-30"></div>
              <div className="relative p-2.5 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-xl shadow-lg">
                <Activity className="w-6 h-6 text-white" />
              </div>
            </div>
            <div>
              <h2 className="text-xl font-extrabold bg-gradient-to-r from-gray-800 to-gray-600 bg-clip-text text-transparent">
                Network Monitor
              </h2>
              <p className="text-xs text-gray-500 font-medium">
                Management Portal
              </p>
            </div>
          </div>

          {/* Desktop Navigation - Scrollable */}
          <div className="hidden lg:flex items-center flex-1 mx-4 overflow-hidden">
            <div className="flex items-center gap-2 overflow-x-auto scrollbar-hide scroll-smooth pb-1">
              <style jsx>{`
                .scrollbar-hide::-webkit-scrollbar {
                  display: none;
                }
                .scrollbar-hide {
                  -ms-overflow-style: none;
                  scrollbar-width: none;
                }
              `}</style>
              {navItems.map((item) => {
                const Icon = item.icon;
                return (
                  <NavLink
                    key={item.to}
                    to={item.to}
                    end={item.end}
                    className={({ isActive }) =>
                      `group flex items-center gap-2 px-4 py-2.5 rounded-xl transition-all duration-300 flex-shrink-0 ${
                        isActive
                          ? "bg-gradient-to-r from-blue-500 to-indigo-600 text-white shadow-lg shadow-blue-200/50"
                          : "text-gray-700 hover:bg-gray-100 hover:text-blue-600"
                      }`
                    }
                  >
                    {({ isActive }) => (
                      <>
                        <Icon
                          className={`w-4 h-4 transition-transform duration-300 ${
                            isActive ? "scale-110" : "group-hover:scale-110"
                          }`}
                        />
                        <span className="text-sm font-semibold whitespace-nowrap">
                          {item.label}
                        </span>
                      </>
                    )}
                  </NavLink>
                );
              })}
            </div>
          </div>

          {/* User Info & Logout (Desktop) */}
          <div className="hidden lg:flex items-center gap-4 flex-shrink-0">
            {/* System Status */}

            {/* User Info */}
            {user && (
              <div className="flex items-center gap-2 px-4 py-2 bg-blue-50 rounded-xl border border-blue-200">
                <div className="p-1.5 bg-blue-100 rounded-lg">
                  <User className="w-4 h-4 text-blue-700" />
                </div>
                <span className="text-sm font-semibold text-gray-800">
                  {user.email}
                </span>
              </div>
            )}

            {/* Logout Button */}
            <button
              onClick={onLogout}
              disabled={loading}
              className={`flex items-center gap-2 px-5 py-2.5 rounded-xl transition-all text-sm font-semibold shadow-md hover:shadow-lg ${
                loading
                  ? "bg-gray-200 text-gray-400 cursor-not-allowed"
                  : "bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 text-white"
              }`}
            >
              {loading ? (
                <>
                  <Loader2 className="w-4 h-4 animate-spin" />
                  Logging out...
                </>
              ) : (
                <>
                  <LogOut className="w-4 h-4" />
                  Logout
                </>
              )}
            </button>
          </div>

          {/* Mobile Menu Button */}
          <button
            onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
            className="lg:hidden p-2 text-gray-700 hover:bg-gray-100 rounded-lg transition flex-shrink-0"
          >
            {mobileMenuOpen ? (
              <X className="w-6 h-6" />
            ) : (
              <Menu className="w-6 h-6" />
            )}
          </button>
        </div>

        {/* Mobile Navigation */}
        {mobileMenuOpen && (
          <div className="lg:hidden py-4 border-t border-gray-200">
            {/* Mobile Nav Items */}
            <div className="space-y-1 mb-4">
              {navItems.map((item) => {
                const Icon = item.icon;
                return (
                  <NavLink
                    key={item.to}
                    to={item.to}
                    end={item.end}
                    onClick={() => setMobileMenuOpen(false)}
                    className={({ isActive }) =>
                      `group flex items-center gap-3 px-4 py-3 rounded-xl transition-all duration-300 ${
                        isActive
                          ? "bg-gradient-to-r from-blue-500 to-indigo-600 text-white shadow-lg shadow-blue-200/50"
                          : "text-gray-700 hover:bg-gray-100 hover:text-blue-600"
                      }`
                    }
                  >
                    {({ isActive }) => (
                      <>
                        <Icon
                          className={`w-5 h-5 transition-transform duration-300 ${
                            isActive ? "scale-110" : "group-hover:scale-110"
                          }`}
                        />
                        <span className="text-sm font-semibold">
                          {item.label}
                        </span>
                      </>
                    )}
                  </NavLink>
                );
              })}
            </div>

            {/* Mobile User Info */}
            {user && (
              <div className="mb-3 px-4 py-3 bg-blue-50 rounded-xl border border-blue-200">
                <div className="flex items-center gap-2">
                  <div className="p-1.5 bg-blue-100 rounded-lg">
                    <User className="w-4 h-4 text-blue-700" />
                  </div>
                  <span className="text-sm font-semibold text-gray-800">
                    {user.email}
                  </span>
                </div>
              </div>
            )}

            {/* Mobile Status */}
            <div className="mb-3 px-4 py-3 bg-gradient-to-r from-green-50 to-emerald-50 rounded-xl border border-green-200">
              <div className="flex items-center gap-2">
                <div className="w-2 h-2 bg-green-500 rounded-full animate-pulse"></div>
                <p className="text-xs font-semibold text-gray-700">
                  All Systems Online
                </p>
              </div>
            </div>

            {/* Mobile Logout */}
            <button
              onClick={() => {
                setMobileMenuOpen(false);
                onLogout();
              }}
              disabled={loading}
              className={`w-full flex items-center justify-center gap-2 px-4 py-3 rounded-xl transition-all text-sm font-semibold ${
                loading
                  ? "bg-gray-200 text-gray-400 cursor-not-allowed"
                  : "bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 text-white"
              }`}
            >
              {loading ? (
                <>
                  <Loader2 className="w-4 h-4 animate-spin" />
                  Logging out...
                </>
              ) : (
                <>
                  <LogOut className="w-4 h-4" />
                  Logout
                </>
              )}
            </button>
          </div>
        )}
      </div>
    </nav>
  );
}
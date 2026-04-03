import React, { useEffect, useState, useMemo } from 'react';
import { getAlarmData } from './alarmsService';
import {
  AlertTriangle,
  CheckCircle,
  XCircle,
  Activity,
  Search,
  RefreshCw,
  X,
  Loader2,
  AlertCircle,
  ChevronLeft,
  ChevronRight,
  Info,
  Calendar,
  Clock,
  MapPin,
  Zap,
  TrendingUp,
  Eye,
} from 'lucide-react';

export default function AlarmsPage() {
  const [alarmData, setAlarmData] = useState([]);
  const [pagination, setPagination] = useState({
    page: 1,
    limit: 10,
    totalCount: 0,
    totalPages: 0,
    hasNextPage: false,
    hasPrevPage: false,
  });
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [filterCircle, setFilterCircle] = useState('');
  const [filterSeverity, setFilterSeverity] = useState('');
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [selectedAlarm, setSelectedAlarm] = useState(null);

  // Fetch alarm data on component mount and when filters change
  useEffect(() => {
    fetchAlarmData();
  }, [currentPage, pageSize, filterCircle, filterSeverity, searchTerm]);

  const fetchAlarmData = async () => {
    try {
      setLoading(true);
      const params = {
        page: currentPage,
        limit: pageSize,
        circle: filterCircle || null,
        severity: filterSeverity || null,
        search: searchTerm || null,
      };

      const response = await getAlarmData(params);
      if (response.success && response.data) {
        setAlarmData(response.data);
        setPagination(response.pagination);
      }
    } catch (error) {
      console.error('Error fetching alarm data:', error);
    } finally {
      setLoading(false);
    }
  };

  // Statistics calculation from current data
  const statistics = useMemo(() => {
    const total = pagination.totalCount;
    const critical = alarmData.filter((a) => a.Severity === 'Critical').length;
    const major = alarmData.filter((a) => a.Severity === 'Major').length;
    const minor = alarmData.filter((a) => a.Severity === 'Minor').length;
    const warning = alarmData.filter((a) => a.Severity === 'Warning').length;

    return { total, critical, major, minor, warning };
  }, [alarmData, pagination]);

  // Get unique circles for filter
  const uniqueCircles = useMemo(() => {
    const circles = [...new Set(alarmData.map((a) => a.Circle))].filter(Boolean);
    return circles.sort();
  }, [alarmData]);

  const handlePageChange = (newPage) => {
    if (newPage >= 1 && newPage <= pagination.totalPages) {
      setCurrentPage(newPage);
    }
  };

  const clearFilters = () => {
    setSearchTerm('');
    setFilterCircle('');
    setFilterSeverity('');
    setCurrentPage(1);
  };

  const getSeverityColor = (severity) => {
    switch (severity) {
      case 'Critical':
        return 'bg-red-100 text-red-700 border-red-200';
      case 'Major':
        return 'bg-orange-100 text-orange-700 border-orange-200';
      case 'Minor':
        return 'bg-yellow-100 text-yellow-700 border-yellow-200';
      case 'Warning':
        return 'bg-blue-100 text-blue-700 border-blue-200';
      default:
        return 'bg-gray-100 text-gray-700 border-gray-200';
    }
  };

  const getSeverityIcon = (severity) => {
    switch (severity) {
      case 'Critical':
        return <XCircle className="w-4 h-4" />;
      case 'Major':
        return <AlertTriangle className="w-4 h-4" />;
      case 'Minor':
        return <Info className="w-4 h-4" />;
      case 'Warning':
        return <Zap className="w-4 h-4" />;
      default:
        return <Info className="w-4 h-4" />;
    }
  };

  const formatDate = (dateString) => {
    if (!dateString) return 'N/A';
    try {
      const date = new Date(dateString);
      return date.toLocaleString('en-US', {
        year: 'numeric',
        month: 'short',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
      });
    } catch {
      return 'Invalid Date';
    }
  };

  if (loading && alarmData.length === 0) {
    return (
      <div className="h-screen w-full flex items-center justify-center bg-gradient-to-br from-slate-50 via-blue-50 to-indigo-50">
        <div className="text-center">
          <div className="relative">
            <div className="w-20 h-20 border-4 border-blue-200 border-t-blue-600 rounded-full animate-spin mx-auto"></div>
            <Activity className="w-8 h-8 text-blue-600 absolute top-1/2 left-1/2 transform -translate-x-1/2 -translate-y-1/2" />
          </div>
          <p className="text-gray-800 text-lg font-semibold mt-6">Loading Alarm Data...</p>
          <p className="text-gray-500 text-sm mt-2">Please wait while we fetch the data</p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 via-blue-50 to-indigo-50 p-4 md:p-6 lg:p-8">
      <div className="max-w-[1600px] mx-auto space-y-6">
        {/* Enhanced Header */}
        <div className="bg-white/80 backdrop-blur-xl rounded-3xl shadow-xl p-8 border border-white/20">
          <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
            <div>
              <div className="flex items-center gap-4 mb-2">
                <div className="p-3 bg-gradient-to-br from-orange-500 to-red-500 rounded-2xl shadow-lg">
                  <AlertTriangle className="w-8 h-8 text-white" />
                </div>
                <div>
                  <h1 className="text-3xl md:text-4xl font-bold bg-gradient-to-r from-gray-900 to-gray-700 bg-clip-text text-transparent">
                    Network Alarms Dashboard
                  </h1>
                  <p className="text-gray-600 mt-1 flex items-center gap-2">
                    <Activity className="w-4 h-4" />
                    Real-time monitoring and management
                  </p>
                </div>
              </div>
            </div>
            <button
              onClick={fetchAlarmData}
              disabled={loading}
              className="flex items-center gap-2 px-6 py-3 bg-gradient-to-r from-blue-600 to-blue-700 text-white rounded-xl hover:from-blue-700 hover:to-blue-800 transition-all duration-200 disabled:opacity-50 disabled:cursor-not-allowed shadow-lg hover:shadow-xl transform hover:-translate-y-0.5"
            >
              <RefreshCw className={`w-5 h-5 ${loading ? 'animate-spin' : ''}`} />
              <span className="font-semibold">Refresh Data</span>
            </button>
          </div>
        </div>

        {/* Enhanced Statistics Cards */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-6">
          <div className="group relative overflow-hidden bg-gradient-to-br from-blue-500 via-blue-600 to-indigo-600 rounded-2xl shadow-xl p-6 text-white hover:shadow-2xl transition-all duration-300 transform hover:-translate-y-1">
            <div className="absolute top-0 right-0 w-32 h-32 bg-white/10 rounded-full -mr-16 -mt-16 group-hover:scale-150 transition-transform duration-500"></div>
            <div className="relative">
              <div className="flex items-center justify-between mb-4">
                <div className="p-3 bg-white/20 rounded-xl backdrop-blur-sm">
                  <Activity className="w-7 h-7" />
                </div>
                <TrendingUp className="w-6 h-6 text-blue-200" />
              </div>
              <p className="text-blue-100 text-sm font-medium mb-1">Total Alarms</p>
              <p className="text-4xl font-black mb-2">{statistics.total.toLocaleString()}</p>
              <p className="text-xs text-blue-200">All severities combined</p>
            </div>
          </div>

          <div className="group relative overflow-hidden bg-gradient-to-br from-red-500 via-red-600 to-rose-600 rounded-2xl shadow-xl p-6 text-white hover:shadow-2xl transition-all duration-300 transform hover:-translate-y-1">
            <div className="absolute top-0 right-0 w-32 h-32 bg-white/10 rounded-full -mr-16 -mt-16 group-hover:scale-150 transition-transform duration-500"></div>
            <div className="relative">
              <div className="flex items-center justify-between mb-4">
                <div className="p-3 bg-white/20 rounded-xl backdrop-blur-sm">
                  <XCircle className="w-7 h-7" />
                </div>
                <Zap className="w-6 h-6 text-red-200" />
              </div>
              <p className="text-red-100 text-sm font-medium mb-1">Critical</p>
              <p className="text-4xl font-black mb-2">{statistics.critical.toLocaleString()}</p>
              <p className="text-xs text-red-200">Requires immediate attention</p>
            </div>
          </div>

          <div className="group relative overflow-hidden bg-gradient-to-br from-orange-500 via-orange-600 to-amber-600 rounded-2xl shadow-xl p-6 text-white hover:shadow-2xl transition-all duration-300 transform hover:-translate-y-1">
            <div className="absolute top-0 right-0 w-32 h-32 bg-white/10 rounded-full -mr-16 -mt-16 group-hover:scale-150 transition-transform duration-500"></div>
            <div className="relative">
              <div className="flex items-center justify-between mb-4">
                <div className="p-3 bg-white/20 rounded-xl backdrop-blur-sm">
                  <AlertTriangle className="w-7 h-7" />
                </div>
                <TrendingUp className="w-6 h-6 text-orange-200" />
              </div>
              <p className="text-orange-100 text-sm font-medium mb-1">Major</p>
              <p className="text-4xl font-black mb-2">{statistics.major.toLocaleString()}</p>
              <p className="text-xs text-orange-200">High priority issues</p>
            </div>
          </div>

          <div className="group relative overflow-hidden bg-gradient-to-br from-yellow-500 via-yellow-600 to-amber-600 rounded-2xl shadow-xl p-6 text-white hover:shadow-2xl transition-all duration-300 transform hover:-translate-y-1">
            <div className="absolute top-0 right-0 w-32 h-32 bg-white/10 rounded-full -mr-16 -mt-16 group-hover:scale-150 transition-transform duration-500"></div>
            <div className="relative">
              <div className="flex items-center justify-between mb-4">
                <div className="p-3 bg-white/20 rounded-xl backdrop-blur-sm">
                  <Info className="w-7 h-7" />
                </div>
                <Activity className="w-6 h-6 text-yellow-200" />
              </div>
              <p className="text-yellow-100 text-sm font-medium mb-1">Minor</p>
              <p className="text-4xl font-black mb-2">{statistics.minor.toLocaleString()}</p>
              <p className="text-xs text-yellow-200">Low priority items</p>
            </div>
          </div>
        </div>

        {/* Enhanced Filters */}
        <div className="bg-white/80 backdrop-blur-xl rounded-3xl shadow-xl p-6 border border-white/20">
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-4">
            {/* Search */}
            <div className="lg:col-span-5">
              <div className="relative group">
                <Search className="absolute left-4 top-1/2 transform -translate-y-1/2 w-5 h-5 text-gray-400 group-focus-within:text-blue-600 transition-colors" />
                <input
                  type="text"
                  placeholder="Search alarms, sites, or IDs..."
                  value={searchTerm}
                  onChange={(e) => {
                    setSearchTerm(e.target.value);
                    setCurrentPage(1);
                  }}
                  className="w-full pl-12 pr-4 py-3.5 border-2 border-gray-200 rounded-xl focus:ring-4 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all bg-white"
                />
              </div>
            </div>

            {/* Circle Filter */}
            <div className="lg:col-span-3">
              <select
                value={filterCircle}
                onChange={(e) => {
                  setFilterCircle(e.target.value);
                  setCurrentPage(1);
                }}
                className="w-full px-4 py-3.5 border-2 border-gray-200 rounded-xl focus:ring-4 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all bg-white font-medium"
              >
                <option value="">All Circles</option>
                {uniqueCircles.map((circle) => (
                  <option key={circle} value={circle}>
                    {circle}
                  </option>
                ))}
              </select>
            </div>

            {/* Severity Filter */}
            <div className="lg:col-span-3">
              <select
                value={filterSeverity}
                onChange={(e) => {
                  setFilterSeverity(e.target.value);
                  setCurrentPage(1);
                }}
                className="w-full px-4 py-3.5 border-2 border-gray-200 rounded-xl focus:ring-4 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all bg-white font-medium"
              >
                <option value="">All Severities</option>
                <option value="Critical">🔴 Critical</option>
                <option value="Major">🟠 Major</option>
                <option value="Minor">🟡 Minor</option>
                <option value="Warning">🔵 Warning</option>
              </select>
            </div>

            {/* Clear Button */}
            <div className="lg:col-span-1">
              <button
                onClick={clearFilters}
                className="w-full px-4 py-3.5 bg-gray-500 text-white rounded-xl hover:bg-gray-600 transition-all font-semibold flex items-center justify-center gap-2 shadow-lg hover:shadow-xl"
              >
                <X className="w-5 h-5" />
                <span className="hidden xl:inline">Clear</span>
              </button>
            </div>
          </div>

          <div className="mt-6 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4 text-sm">
            <div className="flex items-center gap-2 text-gray-700">
              <div className="p-2 bg-blue-100 rounded-lg">
                <Activity className="w-4 h-4 text-blue-600" />
              </div>
              <span>
                Showing <span className="font-bold text-blue-600">{alarmData.length}</span> of{' '}
                <span className="font-bold text-gray-900">{pagination.totalCount.toLocaleString()}</span> alarms
              </span>
            </div>
            <div className="flex items-center gap-3">
              <label className="text-gray-700 font-medium">Rows per page:</label>
              <select
                value={pageSize}
                onChange={(e) => {
                  setPageSize(Number(e.target.value));
                  setCurrentPage(1);
                }}
                className="px-4 py-2 border-2 border-gray-200 rounded-lg focus:ring-4 focus:ring-blue-100 focus:border-blue-500 outline-none bg-white font-semibold"
              >
                <option value="10">10</option>
                <option value="25">25</option>
                <option value="50">50</option>
                <option value="100">100</option>
              </select>
            </div>
          </div>
        </div>

        {/* Enhanced Table */}
        <div className="bg-white/80 backdrop-blur-xl rounded-3xl shadow-xl border border-white/20 overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="bg-gradient-to-r from-gray-50 to-gray-100 border-b-2 border-gray-200">
                  <th className="px-6 py-4 text-left text-xs font-bold text-gray-700 uppercase tracking-wider">
                    Circle
                  </th>
                  <th className="px-6 py-4 text-left text-xs font-bold text-gray-700 uppercase tracking-wider">
                    Site Information
                  </th>
                  <th className="px-6 py-4 text-left text-xs font-bold text-gray-700 uppercase tracking-wider">
                    Alarm Details
                  </th>
                  <th className="px-6 py-4 text-left text-xs font-bold text-gray-700 uppercase tracking-wider">
                    Severity
                  </th>
                  <th className="px-6 py-4 text-left text-xs font-bold text-gray-700 uppercase tracking-wider">
                    Timestamp
                  </th>
                  <th className="px-6 py-4 text-left text-xs font-bold text-gray-700 uppercase tracking-wider">
                    Aging
                  </th>
                  <th className="px-6 py-4 text-left text-xs font-bold text-gray-700 uppercase tracking-wider">
                    Actions
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {loading ? (
                  <tr>
                    <td colSpan="7" className="px-6 py-16 text-center">
                      <div className="flex flex-col items-center gap-4">
                        <div className="relative">
                          <div className="w-16 h-16 border-4 border-blue-200 border-t-blue-600 rounded-full animate-spin"></div>
                          <Activity className="w-6 h-6 text-blue-600 absolute top-1/2 left-1/2 transform -translate-x-1/2 -translate-y-1/2" />
                        </div>
                        <p className="text-gray-600 font-medium">Loading alarm data...</p>
                      </div>
                    </td>
                  </tr>
                ) : alarmData.length === 0 ? (
                  <tr>
                    <td colSpan="7" className="px-6 py-16 text-center">
                      <div className="flex flex-col items-center gap-4">
                        <div className="p-6 bg-gray-100 rounded-full">
                          <AlertCircle className="w-12 h-12 text-gray-400" />
                        </div>
                        <div>
                          <p className="text-gray-800 font-semibold text-lg">No alarms found</p>
                          <p className="text-gray-600 text-sm mt-2">
                            Try adjusting your filters
                          </p>
                        </div>
                      </div>
                    </td>
                  </tr>
                ) : (
                  alarmData.map((alarm) => (
                    <tr
                      key={alarm.id}
                      className="hover:bg-blue-50/50 transition-all duration-150 group"
                    >
                      <td className="px-6 py-4">
                        <div className="flex items-center gap-2">
                          <MapPin className="w-4 h-4 text-blue-600" />
                          <span className="font-semibold text-gray-900">{alarm.Circle}</span>
                        </div>
                      </td>
                      <td className="px-6 py-4">
                        <div className="font-semibold text-gray-900 mb-1">{alarm.Name}</div>
                        <div className="text-xs text-gray-500 truncate max-w-xs font-mono bg-gray-100 px-2 py-1 rounded" title={alarm.EnodeBID}>
                          {alarm.EnodeBID}
                        </div>
                      </td>
                      <td className="px-6 py-4">
                        <div className="font-semibold text-gray-900 mb-1">
                          #{alarm.AlarmNumber} - {alarm.AlarmText}
                        </div>
                        <div className="text-xs text-gray-600 truncate max-w-xs" title={alarm.SupplementryInfo}>
                          {alarm.SupplementryInfo}
                        </div>
                      </td>
                      <td className="px-6 py-4">
                        <span
                          className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold border-2 ${getSeverityColor(
                            alarm.Severity
                          )}`}
                        >
                          {getSeverityIcon(alarm.Severity)}
                          {alarm.Severity}
                        </span>
                      </td>
                      <td className="px-6 py-4">
                        <div className="flex items-center gap-2 text-sm text-gray-700">
                          <Clock className="w-4 h-4 text-gray-400" />
                          {formatDate(alarm.AlarmTime)}
                        </div>
                      </td>
                      <td className="px-6 py-4">
                        <span className="inline-flex items-center gap-1 text-sm text-gray-700 bg-gray-100 px-3 py-1 rounded-lg font-medium">
                          <Calendar className="w-3.5 h-3.5" />
                          {alarm.StandardAging}
                        </span>
                      </td>
                      <td className="px-6 py-4">
                        <button
                          onClick={() => setSelectedAlarm(alarm)}
                          className="inline-flex items-center gap-2 text-blue-600 hover:text-blue-800 font-semibold text-sm hover:bg-blue-50 px-3 py-2 rounded-lg transition-all"
                        >
                          <Eye className="w-4 h-4" />
                          View
                        </button>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>

          {/* Enhanced Pagination */}
          {pagination.totalPages > 1 && (
            <div className="bg-gradient-to-r from-gray-50 to-gray-100 px-6 py-5 border-t-2 border-gray-200">
              <div className="flex flex-col sm:flex-row items-center justify-between gap-4">
                <div className="text-sm text-gray-700 font-medium">
                  Page <span className="font-bold text-blue-600">{pagination.page}</span> of{' '}
                  <span className="font-bold text-gray-900">{pagination.totalPages}</span>
                </div>
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => handlePageChange(currentPage - 1)}
                    disabled={!pagination.hasPrevPage}
                    className="p-2.5 border-2 border-gray-300 rounded-xl hover:bg-white hover:border-blue-500 hover:text-blue-600 disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-transparent disabled:hover:border-gray-300 transition-all"
                  >
                    <ChevronLeft className="w-5 h-5" />
                  </button>

                  <div className="flex items-center gap-1">
                    {[...Array(pagination.totalPages)].map((_, idx) => {
                      const pageNum = idx + 1;
                      if (
                        pageNum === 1 ||
                        pageNum === pagination.totalPages ||
                        (pageNum >= currentPage - 1 && pageNum <= currentPage + 1)
                      ) {
                        return (
                          <button
                            key={pageNum}
                            onClick={() => handlePageChange(pageNum)}
                            className={`min-w-[40px] px-4 py-2.5 rounded-xl font-semibold transition-all ${
                              currentPage === pageNum
                                ? 'bg-gradient-to-r from-blue-600 to-indigo-600 text-white shadow-lg scale-110'
                                : 'border-2 border-gray-300 hover:bg-white hover:border-blue-500 hover:text-blue-600'
                            }`}
                          >
                            {pageNum}
                          </button>
                        );
                      } else if (pageNum === currentPage - 2 || pageNum === currentPage + 2) {
                        return (
                          <span key={pageNum} className="px-2 text-gray-400">
                            ...
                          </span>
                        );
                      }
                      return null;
                    })}
                  </div>

                  <button
                    onClick={() => handlePageChange(currentPage + 1)}
                    disabled={!pagination.hasNextPage}
                    className="p-2.5 border-2 border-gray-300 rounded-xl hover:bg-white hover:border-blue-500 hover:text-blue-600 disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-transparent disabled:hover:border-gray-300 transition-all"
                  >
                    <ChevronRight className="w-5 h-5" />
                  </button>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Enhanced Modal */}
      {selectedAlarm && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-3xl shadow-2xl max-w-3xl w-full max-h-[90vh] overflow-y-auto">
            <div className="sticky top-0 bg-gradient-to-r from-blue-600 to-indigo-600 p-6 rounded-t-3xl flex items-center justify-between z-10">
              <div className="flex items-center gap-4">
                <div className="p-3 bg-white/20 rounded-2xl backdrop-blur-sm">
                  <AlertTriangle className="w-7 h-7 text-white" />
                </div>
                <div>
                  <h3 className="text-2xl font-bold text-white">Alarm Details</h3>
                  <p className="text-blue-100 text-sm mt-1">Complete information</p>
                </div>
              </div>
              <button
                onClick={() => setSelectedAlarm(null)}
                className="p-2.5 hover:bg-white/20 rounded-xl transition-colors"
              >
                <X className="w-6 h-6 text-white" />
              </button>
            </div>

            <div className="p-8 space-y-6">
              <div className="grid grid-cols-2 gap-6">
                <div className="p-4 bg-blue-50 rounded-2xl border border-blue-200">
                  <label className="text-xs font-bold text-blue-600 uppercase tracking-wide flex items-center gap-2 mb-2">
                    <MapPin className="w-4 h-4" />
                    Circle
                  </label>
                  <p className="text-gray-900 font-semibold text-lg">{selectedAlarm.Circle}</p>
                </div>
                <div className="p-4 bg-gradient-to-br from-gray-50 to-gray-100 rounded-2xl border border-gray-200">
                  <label className="text-xs font-bold text-gray-600 uppercase tracking-wide mb-2 block">
                    Severity
                  </label>
                  <span
                    className={`inline-flex items-center gap-2 px-4 py-2 rounded-xl text-sm font-bold border-2 ${getSeverityColor(
                      selectedAlarm.Severity
                    )}`}
                  >
                    {getSeverityIcon(selectedAlarm.Severity)}
                    {selectedAlarm.Severity}
                  </span>
                </div>
              </div>

              <div className="p-5 bg-gradient-to-br from-indigo-50 to-blue-50 rounded-2xl border border-indigo-200">
                <label className="text-xs font-bold text-indigo-600 uppercase tracking-wide mb-2 block">
                  Site Name
                </label>
                <p className="text-gray-900 font-semibold text-lg">{selectedAlarm.Name}</p>
              </div>

              <div className="p-5 bg-gray-50 rounded-2xl border border-gray-200">
                <label className="text-xs font-bold text-gray-600 uppercase tracking-wide mb-2 block">
                  eNodeB ID
                </label>
                <p className="text-gray-900 text-sm font-mono bg-white px-3 py-2 rounded-lg break-all border border-gray-200">
                  {selectedAlarm.EnodeBID}
                </p>
              </div>

              <div className="grid grid-cols-2 gap-6">
                <div className="p-5 bg-purple-50 rounded-2xl border border-purple-200">
                  <label className="text-xs font-bold text-purple-600 uppercase tracking-wide mb-2 block">
                    Alarm Number
                  </label>
                  <p className="text-gray-900 font-bold text-xl">#{selectedAlarm.AlarmNumber}</p>
                </div>
                <div className="p-5 bg-orange-50 rounded-2xl border border-orange-200">
                  <label className="text-xs font-bold text-orange-600 uppercase tracking-wide flex items-center gap-2 mb-2">
                    <Calendar className="w-4 h-4" />
                    Standard Aging
                  </label>
                  <p className="text-gray-900 font-semibold">{selectedAlarm.StandardAging}</p>
                </div>
              </div>

              <div className="p-5 bg-yellow-50 rounded-2xl border border-yellow-200">
                <label className="text-xs font-bold text-yellow-700 uppercase tracking-wide mb-2 block">
                  Alarm Text
                </label>
                <p className="text-gray-900 font-semibold">{selectedAlarm.AlarmText}</p>
              </div>

              <div className="p-5 bg-green-50 rounded-2xl border border-green-200">
                <label className="text-xs font-bold text-green-600 uppercase tracking-wide mb-2 block">
                  Supplementary Information
                </label>
                <p className="text-gray-900">{selectedAlarm.SupplementryInfo}</p>
              </div>

              <div className="p-5 bg-blue-50 rounded-2xl border border-blue-200">
                <label className="text-xs font-bold text-blue-600 uppercase tracking-wide flex items-center gap-2 mb-2">
                  <Clock className="w-4 h-4" />
                  Alarm Time
                </label>
                <p className="text-gray-900 font-semibold text-lg">{formatDate(selectedAlarm.AlarmTime)}</p>
              </div>

              {selectedAlarm.AgeingFormula && (
                <div className="p-5 bg-gray-50 rounded-2xl border border-gray-200">
                  <label className="text-xs font-bold text-gray-600 uppercase tracking-wide mb-2 block">
                    Aging Formula
                  </label>
                  <p className="text-gray-900 font-mono text-sm">{selectedAlarm.AgeingFormula}</p>
                </div>
              )}
            </div>

            <div className="sticky bottom-0 bg-gray-50 p-6 rounded-b-3xl border-t border-gray-200">
              <button
                onClick={() => setSelectedAlarm(null)}
                className="w-full px-6 py-3.5 bg-gradient-to-r from-gray-600 to-gray-700 text-white rounded-xl hover:from-gray-700 hover:to-gray-800 transition-all font-semibold shadow-lg hover:shadow-xl transform hover:-translate-y-0.5"
              >
                Close Details
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
import { useEffect, useState } from "react";
import { 
  Upload, 
  File, 
  X, 
  Trash2,
  CheckCircle, 
  AlertCircle, 
  FileSpreadsheet,
  Database,
  Clock,
  ArrowUpCircle,
  TrendingUp,
  Radio,
  Hash,
  HardDrive,
  Users,
  Lock,
  User,
  Calendar,
  FileText,
  Loader2 as LoaderIcon,
  AlertTriangle,
  Bell
} from "lucide-react";
import { uploadSitesFile, uploadKpisFileWithProgress, fetchUploads, deleteUploadById } from "./services/uploadService";
import { uploadAlarmFile } from "../alarms/alarmsService";
import { useAuth } from "../../context/AutContext";

const UPLOAD_TYPES = {
  KPI: {
    id: 'kpi',
    label: 'KPI Data',
    icon: TrendingUp,
    color: 'emerald',
    description: 'Upload Key Performance Indicators',
    enabled: true,
    uploadFn: uploadKpisFileWithProgress
  },
  SITE: {
    id: 'site',
    label: 'Site Data',
    icon: Radio,
    color: 'blue',
    description: 'Upload site configuration files',
    enabled: true,
    uploadFn: uploadSitesFile
  },
  ALARM: {
    id: 'alarm',
    label: 'Alarm Data',
    icon: Bell,
    color: 'orange',
    description: 'Upload network alarm files',
    enabled: true,
    uploadFn: uploadAlarmFile
  },
  COUNTER: {
    id: 'counter',
    label: 'Counter Data',
    icon: Hash,
    color: 'purple',
    description: 'Upload counter metrics',
    enabled: false,
    uploadFn: null
  },
  DUMP: {
    id: 'dump',
    label: 'Dump Data',
    icon: HardDrive,
    color: 'amber',
    description: 'Upload system dump files',
    enabled: false,
    uploadFn: null
  },
  GROUP: {
    id: 'group',
    label: 'Group Data',
    icon: Users,
    color: 'rose',
    description: 'Upload group configuration',
    enabled: false,
    uploadFn: null
  }
};

export default function UploadsPage() {
  const { user } = useAuth();
  const [selectedType, setSelectedType] = useState(UPLOAD_TYPES.KPI);
  const [dragActive, setDragActive] = useState(false);
  const [selectedFile, setSelectedFile] = useState(null);
  const [uploading, setUploading] = useState(false);
  const [uploadStatus, setUploadStatus] = useState(null);
  const [uploadMessage, setUploadMessage] = useState("");
  const [uploadHistory, setUploadHistory] = useState([]);
  const [loadingHistory, setLoadingHistory] = useState(true);
  const [deletingUploadId, setDeletingUploadId] = useState(null);
  const [uploadProgress, setUploadProgress] = useState(0);
  
  // Fetch upload history on component mount
  useEffect(() => {
    loadUploadHistory();
  }, []);

  const loadUploadHistory = async () => {
    try {
      setLoadingHistory(true);
      const response = await fetchUploads();
      
      if (response.success && Array.isArray(response.data)) {
        setUploadHistory(response.data);
      } else {
        setUploadHistory([]);
      }
    } catch (error) {
      console.error("Error fetching upload history:", error);
    } finally {
      setLoadingHistory(false);
    }
  };

  const acceptedFormats = [".xlsx", ".xls", ".csv"];

  const getColorClasses = (color) => {
    const colors = {
      emerald: {
        gradient: 'from-emerald-500 to-teal-600',
        bg: 'bg-emerald-50',
        border: 'border-emerald-200',
        borderDrag: 'border-emerald-400',
        text: 'text-emerald-700',
        icon: 'text-emerald-600',
        hover: 'hover:bg-emerald-100',
        shadow: 'shadow-emerald-200/50',
        active: 'bg-emerald-500 text-white border-emerald-500'
      },
      blue: {
        gradient: 'from-blue-500 to-indigo-600',
        bg: 'bg-blue-50',
        border: 'border-blue-200',
        borderDrag: 'border-blue-400',
        text: 'text-blue-700',
        icon: 'text-blue-600',
        hover: 'hover:bg-blue-100',
        shadow: 'shadow-blue-200/50',
        active: 'bg-blue-500 text-white border-blue-500'
      },
      orange: {
        gradient: 'from-orange-500 to-red-600',
        bg: 'bg-orange-50',
        border: 'border-orange-200',
        borderDrag: 'border-orange-400',
        text: 'text-orange-700',
        icon: 'text-orange-600',
        hover: 'hover:bg-orange-100',
        shadow: 'shadow-orange-200/50',
        active: 'bg-orange-500 text-white border-orange-500'
      },
      purple: {
        gradient: 'from-purple-500 to-violet-600',
        bg: 'bg-purple-50',
        border: 'border-purple-200',
        borderDrag: 'border-purple-400',
        text: 'text-purple-700',
        icon: 'text-purple-600',
        hover: 'hover:bg-purple-100',
        shadow: 'shadow-purple-200/50',
        active: 'bg-purple-500 text-white border-purple-500'
      },
      amber: {
        gradient: 'from-amber-500 to-orange-600',
        bg: 'bg-amber-50',
        border: 'border-amber-200',
        borderDrag: 'border-amber-400',
        text: 'text-amber-700',
        icon: 'text-amber-600',
        hover: 'hover:bg-amber-100',
        shadow: 'shadow-amber-200/50',
        active: 'bg-amber-500 text-white border-amber-500'
      },
      rose: {
        gradient: 'from-rose-500 to-pink-600',
        bg: 'bg-rose-50',
        border: 'border-rose-200',
        borderDrag: 'border-rose-400',
        text: 'text-rose-700',
        icon: 'text-rose-600',
        hover: 'hover:bg-rose-100',
        shadow: 'shadow-rose-200/50',
        active: 'bg-rose-500 text-white border-rose-500'
      }
    };
    return colors[color];
  };

  // Determine upload type color based on remarks or filename
  const getUploadTypeColor = (remarks, fileName) => {
    const lowerRemarks = (remarks || '').toLowerCase();
    const lowerFileName = (fileName || '').toLowerCase();
    
    if (lowerRemarks.includes('kpi') || lowerFileName.includes('kpi')) {
      return 'emerald';
    } else if (lowerRemarks.includes('site') || lowerFileName.includes('site')) {
      return 'blue';
    } else if (lowerRemarks.includes('alarm') || lowerFileName.includes('alarm')) {
      return 'orange';
    } else if (lowerRemarks.includes('counter')) {
      return 'purple';
    } else if (lowerRemarks.includes('dump')) {
      return 'amber';
    } else if (lowerRemarks.includes('group')) {
      return 'rose';
    }
    return 'blue'; // default
  };

  const getUploadTypeLabel = (remarks, fileName) => {
    const lowerRemarks = (remarks || '').toLowerCase();
    const lowerFileName = (fileName || '').toLowerCase();
    
    if (lowerRemarks.includes('kpi') || lowerFileName.includes('kpi')) {
      return 'KPI Data';
    } else if (lowerRemarks.includes('site') || lowerFileName.includes('site')) {
      return 'Site Data';
    } else if (lowerRemarks.includes('alarm') || lowerFileName.includes('alarm')) {
      return 'Alarm Data';
    }
    return 'Data File';
  };

  const getStatusColor = (status) => {
    switch (status?.toLowerCase()) {
      case 'success':
      case 'completed':
        return {
          bg: 'bg-emerald-50',
          text: 'text-emerald-700',
          border: 'border-emerald-200',
          icon: 'text-emerald-600'
        };
      case 'pending':
      case 'processing':
        return {
          bg: 'bg-amber-50',
          text: 'text-amber-700',
          border: 'border-amber-200',
          icon: 'text-amber-600'
        };
      case 'error':
      case 'failed':
        return {
          bg: 'bg-red-50',
          text: 'text-red-700',
          border: 'border-red-200',
          icon: 'text-red-600'
        };
      default:
        return {
          bg: 'bg-slate-50',
          text: 'text-slate-700',
          border: 'border-slate-200',
          icon: 'text-slate-600'
        };
    }
  };

  const getStatusIcon = (status) => {
    switch (status?.toLowerCase()) {
      case 'success':
      case 'completed':
        return CheckCircle;
      case 'pending':
      case 'processing':
        return Clock;
      case 'error':
      case 'failed':
        return AlertCircle;
      default:
        return File;
    }
  };

  const handleDrag = (e) => {
    e.preventDefault();
    e.stopPropagation();
    if (e.type === "dragenter" || e.type === "dragover") {
      setDragActive(true);
    } else if (e.type === "dragleave") {
      setDragActive(false);
    }
  };

  const handleDrop = (e) => {
    e.preventDefault();
    e.stopPropagation();
    setDragActive(false);

    if (e.dataTransfer.files && e.dataTransfer.files[0]) {
      handleFileChange(e.dataTransfer.files[0]);
    }
  };

  const handleFileChange = (file) => {
    if (!file) return;

    const fileExtension = "." + file.name.split(".").pop().toLowerCase();
    
    if (!acceptedFormats.includes(fileExtension)) {
      setUploadStatus("error");
      setUploadMessage("Invalid file type. Only .xlsx, .xls, and .csv are supported.");
      setTimeout(() => setUploadStatus(null), 3000);
      return;
    }

    setSelectedFile(file);
    setUploadStatus(null);
    setUploadMessage("");
  };

  const handleFileInputChange = (e) => {
    if (e.target.files && e.target.files[0]) {
      handleFileChange(e.target.files[0]);
    }
  };

  const handleUpload = async () => {
    if (!selectedFile || !selectedType.enabled) return;

    setUploading(true);
    setUploadProgress(0);
    setUploadStatus(null);

    try {
      let result;
      
      // Handle alarm upload differently as it only takes the file
      if (selectedType.id === 'alarm') {
        result = await selectedType.uploadFn(selectedFile);
      } else {
        const uploadedBy = user?.email || "system";
        const remarks = `${selectedType.label} upload`;
        if (selectedType.id === "kpi") {
          result = await selectedType.uploadFn(selectedFile, uploadedBy, remarks, (percent) =>
            setUploadProgress(percent),
          );
        } else {
          result = await selectedType.uploadFn(selectedFile, uploadedBy, remarks);
        }
      }

      if (result && result.success) {
        setUploadProgress(100);
        setUploadStatus("success");
        setUploadMessage(result.message || "Upload processed successfully.");
        
        // Reload upload history after successful upload
        await loadUploadHistory();

        // Clear after success
        setTimeout(() => {
          setSelectedFile(null);
          setUploadStatus(null);
          setUploadMessage("");
        }, 3000);
      } else {
        setUploadStatus("error");
        setUploadMessage(result?.message || "Upload failed.");
      }

    } catch (error) {
      console.error("Upload error:", error);
      setUploadStatus("error");
      setUploadMessage(error.message || "Upload failed.");
    } finally {
      setUploading(false);
      setTimeout(() => setUploadProgress(0), 500);
    }
  };

  const removeFile = () => {
    setSelectedFile(null);
    setUploadStatus(null);
    setUploadMessage("");
  };

  const handleDeleteUpload = async (uploadId, fileName) => {
    const confirmed = window.confirm(`Delete upload "${fileName}"? This action cannot be undone.`);
    if (!confirmed) return;

    setDeletingUploadId(uploadId);
    try {
      const response = await deleteUploadById(uploadId);
      if (response?.success) {
        setUploadStatus("success");
        setUploadMessage(response?.message || "Upload deleted successfully.");
        await loadUploadHistory();
        setTimeout(() => {
          setUploadStatus(null);
          setUploadMessage("");
        }, 2500);
      } else {
        setUploadStatus("error");
        setUploadMessage(response?.message || "Failed to delete upload.");
      }
    } catch (error) {
      setUploadStatus("error");
      setUploadMessage(error?.message || "Failed to delete upload.");
    } finally {
      setDeletingUploadId(null);
    }
  };

  const formatFileSize = (bytes) => {
    if (bytes === 0) return "0 Bytes";
    const k = 1024;
    const sizes = ["Bytes", "KB", "MB", "GB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return Math.round((bytes / Math.pow(k, i)) * 100) / 100 + " " + sizes[i];
  };

  const formatDate = (dateString) => {
    if (!dateString) return "Unknown date";
    const date = new Date(dateString);
    if (Number.isNaN(date.getTime())) return "Unknown date";
    const now = new Date();
    const diffInHours = (now - date) / (1000 * 60 * 60);
    
    if (diffInHours < 1) {
      const diffInMinutes = Math.floor(diffInHours * 60);
      return `${diffInMinutes}m ago`;
    } else if (diffInHours < 24) {
      return `${Math.floor(diffInHours)}h ago`;
    } else {
      return date.toLocaleString('en-US', {
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit'
      });
    }
  };

  const getFileIcon = (fileName) => {
    const ext = fileName.split('.').pop().toLowerCase();
    if (ext === 'csv') return Database;
    return FileSpreadsheet;
  };

  const colorClasses = getColorClasses(selectedType.color);

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 via-blue-50/30 to-indigo-50/40">
      <div className="max-w-6xl mx-auto px-4 py-12">
        
        {/* Header */}
        <div className="text-center mb-8">
          <div className={`inline-flex items-center justify-center w-16 h-16 bg-gradient-to-br ${colorClasses.gradient} rounded-2xl mb-4 shadow-lg ${colorClasses.shadow} transition-all duration-300`}>
            <ArrowUpCircle className="w-8 h-8 text-white" strokeWidth={2} />
          </div>
          <h1 className="text-4xl font-semibold text-slate-800 mb-3 tracking-tight">
            Upload Data Files
          </h1>
          <p className="text-slate-500 text-lg max-w-2xl mx-auto">
            {selectedType.description}
          </p>
        </div>

        {/* Upload Type Toggle */}
        <div className="mb-8">
          <div className="bg-white/80 backdrop-blur-sm rounded-2xl shadow-sm border border-slate-200/50 p-2">
            <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-2">
              {Object.values(UPLOAD_TYPES).map((type) => {
                const TypeIcon = type.icon;
                const typeColors = getColorClasses(type.color);
                const isActive = selectedType.id === type.id;
                
                return (
                  <button
                    key={type.id}
                    onClick={() => type.enabled && setSelectedType(type)}
                    disabled={!type.enabled}
                    className={`relative flex flex-col items-center gap-2 p-4 rounded-xl transition-all duration-300 ${
                      isActive
                        ? `${typeColors.active} shadow-lg ${typeColors.shadow}`
                        : type.enabled
                        ? `bg-slate-50 ${typeColors.text} hover:bg-slate-100 border border-slate-200`
                        : 'bg-slate-50 text-slate-300 border border-slate-100 cursor-not-allowed opacity-60'
                    }`}
                  >
                    {!type.enabled && (
                      <div className="absolute top-2 right-2">
                        <Lock className="w-3 h-3 text-slate-400" />
                      </div>
                    )}
                    <TypeIcon className={`w-5 h-5 ${isActive ? 'text-white' : ''} transition-transform duration-300 ${isActive ? 'scale-110' : ''}`} strokeWidth={2} />
                    <span className={`text-xs font-semibold ${isActive ? 'text-white' : ''}`}>
                      {type.label}
                    </span>
                  </button>
                );
              })}
            </div>
          </div>
        </div>

        <div className="grid lg:grid-cols-3 gap-6">
          
          {/* Main Upload Area - Takes 2 columns */}
          <div className="lg:col-span-2">
            <div className="bg-white/80 backdrop-blur-sm rounded-3xl shadow-sm border border-slate-200/50 p-8">
              
              {/* Upload Zone */}
              <div
                className={`relative border-2 border-dashed rounded-2xl p-16 transition-all duration-300 ease-out ${
                  dragActive
                    ? `${colorClasses.borderDrag} ${colorClasses.bg} scale-[1.01]`
                    : `border-slate-200 bg-slate-50/50 hover:border-slate-300 hover:bg-slate-50`
                }`}
                onDragEnter={handleDrag}
                onDragLeave={handleDrag}
                onDragOver={handleDrag}
                onDrop={handleDrop}
              >
                <input
                  type="file"
                  id="fileInput"
                  className="hidden"
                  accept={acceptedFormats.join(",")}
                  onChange={handleFileInputChange}
                  disabled={!selectedType.enabled}
                />

                <div className="flex flex-col items-center justify-center space-y-6">
                  {/* Upload Icon */}
                  <div className={`p-6 rounded-2xl transition-all duration-300 ${
                    dragActive ? `${colorClasses.bg} scale-110` : 'bg-slate-100'
                  }`}>
                    <Upload className={`w-16 h-16 transition-all duration-300 ${
                      dragActive ? colorClasses.icon : 'text-slate-400'
                    }`} strokeWidth={1.5} />
                  </div>

                  {/* Instructions */}
                  <div className="text-center space-y-4">
                    <div>
                      <p className="text-xl font-semibold text-slate-700 mb-2">
                        Drop your {selectedType.label.toLowerCase()} file here
                      </p>
                      <p className="text-sm text-slate-400">or</p>
                    </div>
                    
                    <label
                      htmlFor="fileInput"
                      className={`inline-flex items-center gap-2 px-8 py-3 text-white text-sm font-medium rounded-xl transition-all duration-200 shadow-lg hover:shadow-xl ${
                        selectedType.enabled
                          ? `bg-gradient-to-r ${colorClasses.gradient} ${colorClasses.shadow} cursor-pointer active:scale-95`
                          : 'bg-slate-300 cursor-not-allowed'
                      }`}
                    >
                      <Upload className="w-4 h-4" />
                      Browse Files
                    </label>
                  </div>

                  {/* Accepted formats */}
                  <div className="flex items-center gap-3 pt-4">
                    <div className="flex items-center gap-2 px-3 py-1.5 bg-white rounded-lg border border-slate-200">
                      <FileSpreadsheet className="w-4 h-4 text-emerald-600" />
                      <span className="text-xs font-medium text-slate-600">Excel</span>
                    </div>
                    <div className="flex items-center gap-2 px-3 py-1.5 bg-white rounded-lg border border-slate-200">
                      <Database className="w-4 h-4 text-blue-600" />
                      <span className="text-xs font-medium text-slate-600">CSV</span>
                    </div>
                  </div>
                </div>
              </div>

              {/* Selected File */}
              {selectedFile && (
                <div className={`mt-6 ${colorClasses.bg} rounded-xl p-4 border ${colorClasses.border} animate-in fade-in slide-in-from-top-2 duration-300`}>
                  <div className="flex items-center justify-between">
                    <div className="flex items-center space-x-4 flex-1 min-w-0">
                      <div className={`p-3 bg-white rounded-xl shadow-sm border ${colorClasses.border}`}>
                        <File className={`w-5 h-5 ${colorClasses.icon}`} strokeWidth={2} />
                      </div>
                      <div className="flex-1 min-w-0">
                        <p className={`font-medium ${colorClasses.text} truncate text-sm`}>
                          {selectedFile.name}
                        </p>
                        <div className="flex items-center gap-2 mt-0.5">
                          <p className="text-xs text-slate-500">
                            {formatFileSize(selectedFile.size)}
                          </p>
                          <span className="text-xs text-slate-300">•</span>
                          <span className={`text-xs ${colorClasses.text} font-medium`}>
                            {selectedType.label}
                          </span>
                        </div>
                      </div>
                    </div>
                    <button
                      onClick={removeFile}
                      className="ml-2 p-2 hover:bg-red-50 rounded-lg transition-all duration-200 group"
                      disabled={uploading}
                    >
                      <X className="w-4 h-4 text-slate-400 group-hover:text-red-500 transition-colors" strokeWidth={2} />
                    </button>
                  </div>
                </div>
              )}

              {/* Upload Button */}
              {selectedFile && !uploadStatus && (
                <button
                  onClick={handleUpload}
                  disabled={uploading || !selectedType.enabled}
                  className={`relative overflow-hidden w-full mt-6 py-4 rounded-xl font-semibold text-sm transition-all duration-200 flex items-center justify-center gap-2 ${
                    uploading || !selectedType.enabled
                      ? "bg-slate-200 text-slate-400 cursor-not-allowed"
                      : `bg-gradient-to-r ${colorClasses.gradient} text-white shadow-lg ${colorClasses.shadow} hover:shadow-xl active:scale-[0.99]`
                  }`}
                >
                  {uploading && selectedType.id === "kpi" ? (
                    <>
                      <div className="absolute inset-0 bg-slate-200/50">
                        <div
                          className={`h-full bg-gradient-to-r ${colorClasses.gradient} transition-all duration-200`}
                          style={{ width: `${uploadProgress}%` }}
                        />
                      </div>
                      <span className="relative z-10 text-slate-800 font-semibold">
                        Uploading KPI Data... {uploadProgress}%
                      </span>
                    </>
                  ) : uploading ? (
                    <>
                      <div className="w-4 h-4 border-2 border-slate-300 border-t-slate-500 rounded-full animate-spin"></div>
                      <span>Uploading {selectedType.label}...</span>
                    </>
                  ) : (
                    <>
                      <ArrowUpCircle className="w-5 h-5" />
                      <span>Upload {selectedType.label}</span>
                    </>
                  )}
                </button>
              )}

              {/* Status Messages */}
              {uploadStatus === "success" && (
                <div className="mt-6 bg-emerald-50 border border-emerald-100 rounded-xl p-4 animate-in fade-in slide-in-from-top-2 duration-300">
                  <div className="flex items-start space-x-3">
                    <div className="mt-0.5">
                      <CheckCircle className="w-5 h-5 text-emerald-600" strokeWidth={2} />
                    </div>
                    <div className="flex-1">
                      <p className="font-semibold text-emerald-900 text-sm">Upload Complete</p>
                      <p className="text-xs text-emerald-700 mt-1">
                        {uploadMessage || `Your ${selectedType.label.toLowerCase()} has been processed successfully.`}
                      </p>
                    </div>
                  </div>
                </div>
              )}

              {uploadStatus === "error" && (
                <div className="mt-6 bg-red-50 border border-red-100 rounded-xl p-4 animate-in fade-in slide-in-from-top-2 duration-300">
                  <div className="flex items-start space-x-3">
                    <div className="mt-0.5">
                      <AlertCircle className="w-5 h-5 text-red-600" strokeWidth={2} />
                    </div>
                    <div className="flex-1">
                      <p className="font-semibold text-red-900 text-sm">Upload Failed</p>
                      <p className="text-xs text-red-700 mt-1">
                        {uploadMessage || "Please check your file format and try again."}
                      </p>
                    </div>
                  </div>
                </div>
              )}
            </div>
          </div>

          {/* Sidebar - Upload History */}
          <div className="lg:col-span-1 space-y-6">
            <div className="bg-white/80 backdrop-blur-sm rounded-3xl shadow-sm border border-slate-200/50 p-6">
              <div className="flex items-center justify-between mb-4">
                <h3 className="text-lg font-semibold text-slate-800 flex items-center gap-2">
                  <Clock className="w-5 h-5 text-slate-600" />
                  Recent Uploads
                </h3>
                {!loadingHistory && uploadHistory.length > 0 && (
                  <button
                    onClick={loadUploadHistory}
                    className="text-xs text-slate-500 hover:text-slate-700 transition-colors"
                  >
                    Refresh
                  </button>
                )}
              </div>
              
              {loadingHistory ? (
                <div className="text-center py-12">
                  <LoaderIcon className="w-8 h-8 text-slate-400 animate-spin mx-auto mb-3" />
                  <p className="text-sm text-slate-400">Loading uploads...</p>
                </div>
              ) : uploadHistory.length === 0 ? (
                <div className="text-center py-12">
                  <div className="w-16 h-16 bg-slate-100 rounded-2xl flex items-center justify-center mx-auto mb-3">
                    <Upload className="w-8 h-8 text-slate-300" />
                  </div>
                  <p className="text-sm text-slate-400">No uploads yet</p>
                </div>
              ) : (
                <div className="space-y-3 max-h-[600px] overflow-y-auto pr-2">
                  {uploadHistory.slice(0, 10).map((item) => {
                    const fileName = item.fileName || "Unknown file";
                    const status = item.status || "completed";
                    const rowCount =
                      item.totalRows ??
                      ((item._count?.uploadData || 0) + (item._count?.alarmData || 0));
                    const FileIcon = getFileIcon(fileName);
                    const itemColor = getUploadTypeColor(item.remarks, fileName);
                    const itemColors = getColorClasses(itemColor);
                    const statusColors = getStatusColor(status);
                    const StatusIcon = getStatusIcon(status);
                    
                    return (
                      <div
                        key={item.id}
                        className="group p-4 bg-slate-50 rounded-xl border border-slate-100 hover:border-slate-200 hover:shadow-sm transition-all duration-200"
                      >
                        <div className="flex items-start justify-between gap-2 mb-2">
                          <div className="text-[11px] text-slate-400">ID #{item.id}</div>
                          <button
                            type="button"
                            onClick={() => handleDeleteUpload(item.id, fileName)}
                            disabled={deletingUploadId === item.id}
                            className="inline-flex items-center gap-1 px-2 py-1 text-xs rounded-md border border-red-200 text-red-600 hover:bg-red-50 disabled:opacity-50"
                            title="Delete upload"
                          >
                            {deletingUploadId === item.id ? (
                              <LoaderIcon className="w-3 h-3 animate-spin" />
                            ) : (
                              <Trash2 className="w-3 h-3" />
                            )}
                            Delete
                          </button>
                        </div>

                        <div className="flex items-start gap-3">
                          <div className={`p-2 rounded-lg ${itemColors.bg} group-hover:scale-110 transition-transform`}>
                            <FileIcon className={`w-4 h-4 ${itemColors.icon}`} />
                          </div>
                          <div className="flex-1 min-w-0">
                            <p className="text-xs font-medium text-slate-800 truncate">
                              {fileName}
                            </p>
                            
                            {/* Type and Status */}
                            <div className="flex items-center gap-2 mt-1.5">
                              <span className={`text-xs px-2 py-0.5 rounded-md ${itemColors.bg} ${itemColors.text} font-medium`}>
                                {getUploadTypeLabel(item.remarks, fileName)}
                              </span>
                              <span className={`text-xs px-2 py-0.5 rounded-md ${statusColors.bg} ${statusColors.text} font-medium flex items-center gap-1`}>
                                <StatusIcon className="w-3 h-3" />
                                {status}
                              </span>
                            </div>
                            
                            {/* Metadata */}
                            <div className="mt-2 space-y-1">
                              {item.uploadedBy && (
                                <div className="flex items-center gap-1.5 text-xs text-slate-500">
                                  <User className="w-3 h-3" />
                                  <span className="truncate">{item.uploadedBy}</span>
                                </div>
                              )}
                              <div className="flex items-center gap-1.5 text-xs text-slate-500">
                                <Calendar className="w-3 h-3" />
                                <span>{formatDate(item.createdAt)}</span>
                              </div>
                              {item.remarks && (
                                <div className="flex items-start gap-1.5 text-xs text-slate-500">
                                  <FileText className="w-3 h-3 mt-0.5 flex-shrink-0" />
                                  <span className="line-clamp-1">{item.remarks}</span>
                                </div>
                              )}
                              {rowCount > 0 && (
                                <div className="flex items-center gap-1.5 text-xs text-slate-500">
                                  <Database className="w-3 h-3" />
                                  <span>{rowCount.toLocaleString()} rows</span>
                                </div>
                              )}
                            </div>
                          </div>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

            {/* Info Card */}
            <div className={`${colorClasses.bg} backdrop-blur-sm rounded-2xl border ${colorClasses.border} p-6 transition-all duration-300`}>
              <h4 className={`text-sm font-semibold ${colorClasses.text} mb-3`}>
                💡 {selectedType.label} Upload Tips
              </h4>
              <ul className={`space-y-2 text-xs ${colorClasses.text}`}>
                <li className="flex items-start gap-2">
                  <span className="text-slate-400 mt-0.5">•</span>
                  <span>Maximum file size: 50MB</span>
                </li>
                <li className="flex items-start gap-2">
                  <span className="text-slate-400 mt-0.5">•</span>
                  <span>Supported formats: Excel and CSV</span>
                </li>
                <li className="flex items-start gap-2">
                  <span className="text-slate-400 mt-0.5">•</span>
                  <span>Ensure {selectedType.label.toLowerCase()} is properly formatted</span>
                </li>
                {!selectedType.enabled && (
                  <li className="flex items-start gap-2 pt-2 border-t border-slate-200">
                    <Lock className="w-3 h-3 mt-0.5" />
                    <span className="text-slate-500 italic">This upload type is coming soon</span>
                  </li>
                )}
              </ul>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

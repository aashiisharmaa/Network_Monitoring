import { Upload, File, X, CheckCircle, AlertCircle } from "lucide-react";
import { useState, useRef } from "react";

export default function FileUploadCard({
  title,
  description,
  acceptedFormats = [".xlsx", ".xls", ".csv"],
  onUpload
}) {
  const [dragActive, setDragActive] = useState(false);
  const [selectedFile, setSelectedFile] = useState(null);
  const [uploading, setUploading] = useState(false);
  const [uploadStatus, setUploadStatus] = useState(null);
  const fileInputRef = useRef(null);

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

    console.log("📥 [FileUploadCard] File dropped");
    
    if (e.dataTransfer.files && e.dataTransfer.files[0]) {
      console.log("📂 [FileUploadCard] Dropped file:", e.dataTransfer.files[0].name);
      handleFileChange(e.dataTransfer.files[0]);
    }
  };

  const handleFileChange = (file) => {
    console.log("🔄 [FileUploadCard] handleFileChange called");
    
    if (!file) {
      console.warn("⚠️ [FileUploadCard] No file provided");
      return;
    }

    console.log("📄 [FileUploadCard] File details:", {
      name: file.name,
      size: file.size,
      type: file.type
    });

    // Validate file type
    const fileExtension = "." + file.name.split(".").pop().toLowerCase();
    console.log("🔍 [FileUploadCard] File extension:", fileExtension);
    console.log("✓ [FileUploadCard] Accepted formats:", acceptedFormats);
    
    if (!acceptedFormats.includes(fileExtension)) {
      console.error("❌ [FileUploadCard] Invalid file format");
      setUploadStatus("error");
      alert(`Please upload a valid file format: ${acceptedFormats.join(", ")}`);
      return;
    }

    console.log("✅ [FileUploadCard] File validation passed");
    setSelectedFile(file);
    setUploadStatus(null);
  };

  const handleFileInputChange = (e) => {
    console.log("📤 [FileUploadCard] File input changed");
    
    if (e.target.files && e.target.files[0]) {
      console.log("📁 [FileUploadCard] Selected file:", e.target.files[0].name);
      handleFileChange(e.target.files[0]);
    }
  };

  const handleUploadClick = async () => {
    console.log("🎯 [FileUploadCard] Upload button clicked");
    
    if (!selectedFile) {
      console.warn("⚠️ [FileUploadCard] No file selected");
      return;
    }

    console.log("📦 [FileUploadCard] Preparing to upload:", selectedFile.name);
    
    setUploading(true);
    setUploadStatus(null);

    try {
      console.log("🚀 [FileUploadCard] Starting upload...");
      console.log("🔗 [FileUploadCard] onUpload function exists:", !!onUpload);
      
      if (onUpload) {
        console.log("⏳ [FileUploadCard] Calling onUpload with file:", {
          name: selectedFile.name,
          size: selectedFile.size,
          type: selectedFile.type
        });
        
        const result = await onUpload(selectedFile);
        
        console.log("✅ [FileUploadCard] onUpload completed successfully");
        console.log("📊 [FileUploadCard] Upload result:", result);
        
        setUploadStatus("success");
        
        // Clear file after successful upload
        setTimeout(() => {
          console.log("🧹 [FileUploadCard] Clearing file after success");
          setSelectedFile(null);
          setUploadStatus(null);
        }, 3000);
      } else {
        console.error("❌ [FileUploadCard] No onUpload function provided");
      }
    } catch (error) {
      console.error("💥 [FileUploadCard] Upload failed");
      console.error("Error message:", error.message);
      console.error("Error stack:", error.stack);
      console.error("Full error object:", error);
      
      setUploadStatus("error");
    } finally {
      console.log("🏁 [FileUploadCard] Upload process finished");
      setUploading(false);
    }
  };

  const removeFile = () => {
    console.log("🗑️ [FileUploadCard] Removing file");
    setSelectedFile(null);
    setUploadStatus(null);
    if (fileInputRef.current) {
      fileInputRef.current.value = "";
    }
  };

  const formatFileSize = (bytes) => {
    if (bytes === 0) return "0 Bytes";
    const k = 1024;
    const sizes = ["Bytes", "KB", "MB", "GB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return Math.round(bytes / Math.pow(k, i) * 100) / 100 + " " + sizes[i];
  };

  return (
    <div className="bg-white rounded-2xl shadow-lg p-8 max-w-2xl mx-auto">
      {/* Header */}
      <div className="text-center mb-8">
        <h2 className="text-3xl font-bold text-gray-800 mb-2">{title}</h2>
        <p className="text-gray-600">{description}</p>
      </div>

      {/* Upload Area */}
      <div
        className={`relative border-3 border-dashed rounded-2xl p-12 transition-all duration-300 ${
          dragActive
            ? "border-blue-500 bg-blue-50"
            : "border-gray-300 bg-gray-50 hover:border-gray-400"
        }`}
        onDragEnter={handleDrag}
        onDragLeave={handleDrag}
        onDragOver={handleDrag}
        onDrop={handleDrop}
      >
        <input
          ref={fileInputRef}
          type="file"
          className="hidden"
          accept={acceptedFormats.join(",")}
          onChange={handleFileInputChange}
        />

        <div className="flex flex-col items-center justify-center space-y-4">
          {/* Upload Icon */}
          <div className={`p-6 rounded-full transition-all duration-300 ${
            dragActive ? "bg-blue-100" : "bg-blue-50"
          }`}>
            <Upload className={`w-16 h-16 transition-all duration-300 ${
              dragActive ? "text-blue-600 scale-110" : "text-blue-500"
            }`} />
          </div>

          {/* Instructions */}
          <div className="text-center">
            <p className="text-lg font-semibold text-gray-700 mb-2">
              Drag & drop your file here
            </p>
            <p className="text-sm text-gray-500 mb-4">or</p>
            <button
              onClick={() => fileInputRef.current?.click()}
              className="px-6 py-3 bg-gradient-to-r from-blue-500 to-indigo-600 text-white font-semibold rounded-xl hover:from-blue-600 hover:to-indigo-700 transition-all duration-300 shadow-lg hover:shadow-xl transform hover:scale-105"
            >
              Browse Files
            </button>
          </div>

          {/* Accepted formats */}
          <p className="text-xs text-gray-500 mt-4">
            Accepted formats: {acceptedFormats.join(", ").toUpperCase()}
          </p>
        </div>
      </div>

      {/* Selected File Display */}
      {selectedFile && (
        <div className="mt-6 bg-gradient-to-r from-blue-50 to-indigo-50 rounded-xl p-4 border border-blue-200">
          <div className="flex items-center justify-between">
            <div className="flex items-center space-x-4">
              <div className="p-3 bg-white rounded-lg shadow-sm">
                <File className="w-6 h-6 text-blue-600" />
              </div>
              <div>
                <p className="font-semibold text-gray-800">{selectedFile.name}</p>
                <p className="text-sm text-gray-600">
                  {formatFileSize(selectedFile.size)}
                </p>
              </div>
            </div>
            <button
              onClick={removeFile}
              className="p-2 hover:bg-red-100 rounded-lg transition-colors"
              disabled={uploading}
            >
              <X className="w-5 h-5 text-red-500" />
            </button>
          </div>
        </div>
      )}

      {/* Upload Button */}
      {selectedFile && !uploadStatus && (
        <button
          onClick={handleUploadClick}
          disabled={uploading}
          className={`w-full mt-6 py-4 rounded-xl font-bold text-lg transition-all duration-300 shadow-lg hover:shadow-xl transform hover:scale-105 ${
            uploading
              ? "bg-gray-400 cursor-not-allowed"
              : "bg-gradient-to-r from-green-500 to-emerald-600 hover:from-green-600 hover:to-emerald-700 text-white"
          }`}
        >
          {uploading ? (
            <span className="flex items-center justify-center space-x-2">
              <div className="w-5 h-5 border-3 border-white border-t-transparent rounded-full animate-spin"></div>
              <span>Uploading...</span>
            </span>
          ) : (
            "Upload File"
          )}
        </button>
      )}

      {/* Success/Error Messages */}
      {uploadStatus === "success" && (
        <div className="mt-6 bg-green-50 border border-green-200 rounded-xl p-4 flex items-center space-x-3">
          <CheckCircle className="w-6 h-6 text-green-600 flex-shrink-0" />
          <div>
            <p className="font-semibold text-green-800">Upload Successful!</p>
            <p className="text-sm text-green-700">Your file has been uploaded successfully.</p>
          </div>
        </div>
      )}

      {uploadStatus === "error" && (
        <div className="mt-6 bg-red-50 border border-red-200 rounded-xl p-4 flex items-center space-x-3">
          <AlertCircle className="w-6 h-6 text-red-600 flex-shrink-0" />
          <div>
            <p className="font-semibold text-red-800">Upload Failed</p>
            <p className="text-sm text-red-700">There was an error uploading your file. Please try again.</p>
          </div>
        </div>
      )}
    </div>
  );
}

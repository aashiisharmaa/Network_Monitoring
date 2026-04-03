import FileUploadCard from "./components/FileUploadCard";
import { uploadSitesFile } from "./services/uploadService";

export default function UploadSitesPage() {
  const handleUpload = async (file) => {
    console.log("🚀 [UploadSitesPage] handleUpload called");
    console.log("📁 [UploadSitesPage] File received:", {
      name: file?.name,
      size: file?.size,
      type: file?.type,
      lastModified: file?.lastModified
    });

    try {
      const uploadedBy = "admin@example.com"; // Dummy uploaded by
      const remarks = "Sites data upload"; // Dummy remarks

      console.log("📝 [UploadSitesPage] Upload metadata:", {
        uploadedBy,
        remarks
      });

      console.log("⏳ [UploadSitesPage] Calling uploadSitesFile service...");
      
      const result = await uploadSitesFile(file, uploadedBy, remarks);
      
      console.log("✅ [UploadSitesPage] Service call completed");
      console.log("📊 [UploadSitesPage] Result:", result);
      
      if (result) {
        console.log("🎉 [UploadSitesPage] Upload successful:", result);
        return result;
      } else {
        console.error("❌ [UploadSitesPage] Upload failed - No result returned");
        throw new Error("Upload failed");
      }
    } catch (error) {
      console.error("💥 [UploadSitesPage] Error uploading file:");
      console.error("Error message:", error.message);
      console.error("Error stack:", error.stack);
      console.error("Full error:", error);
      throw error;
    }
  };

  return (
    <div className="p-6">
      <FileUploadCard
        title="Upload Sites Data"
        description="Upload your sites configuration file in Excel or CSV format"
        acceptedFormats={[".xlsx", ".xls", ".csv"]}
        onUpload={handleUpload}
      />
    </div>
  );
}

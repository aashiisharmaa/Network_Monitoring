import FileUploadCard from "./components/FileUploadCard";

export default function UploadDumpPage() {
  const handleUpload = async (file) => {
    // TODO: Replace with actual API call
    console.log("Uploading dump file:", file);
    
    return new Promise((resolve) => {
      setTimeout(() => {
        resolve();
      }, 2000);
    });
  };

  return (
    <div className="p-6">
      <FileUploadCard
        title="Upload Dump Data"
        description="Upload your network dump file in Excel or CSV format"
        acceptedFormats={[".xlsx", ".xls", ".csv"]}
        onUpload={handleUpload}
      />
    </div>
  );
}

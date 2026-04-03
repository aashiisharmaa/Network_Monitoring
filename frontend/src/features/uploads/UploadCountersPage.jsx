import FileUploadCard from "./components/FileUploadCard";

export default function UploadCountersPage() {
  const handleUpload = async (file) => {
    // TODO: Replace with actual API call
    console.log("Uploading counters file:", file);
    
    return new Promise((resolve) => {
      setTimeout(() => {
        resolve();
      }, 2000);
    });
  };

  return (
    <div className="p-6">
      <FileUploadCard
        title="Upload Counters Data"
        description="Upload your network counters file in Excel or CSV format"
        acceptedFormats={[".xlsx", ".xls", ".csv"]}
        onUpload={handleUpload}
      />
    </div>
  );
}

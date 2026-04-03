import FileUploadCard from "./components/FileUploadCard";

export default function UploadGroupPage() {
  const handleUpload = async (file) => {
    // TODO: Replace with actual API call
    console.log("Uploading group file:", file);
    
    return new Promise((resolve) => {
      setTimeout(() => {
        resolve();
      }, 2000);
    });
  };

  return (
    <div className="p-6">
      <FileUploadCard
        title="Upload Group Data"
        description="Upload your group configuration file in Excel or CSV format"
        acceptedFormats={[".xlsx", ".xls", ".csv"]}
        onUpload={handleUpload}
      />
    </div>
  );
}

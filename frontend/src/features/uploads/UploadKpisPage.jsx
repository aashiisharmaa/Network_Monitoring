import FileUploadCard from "./components/FileUploadCard";

export default function UploadKpisPage() {
  const handleUpload = async (file) => {
    // TODO: Replace with actual API call
    console.log("Uploading KPIs file:", file);

    return new Promise((resolve) => {
      setTimeout(() => {
        resolve();
      }, 2000);
    });
  };

  return (
    <div className="p-6">
      <FileUploadCard
        title="Upload KPIs Data"
        description="Upload your KPI metrics file in Excel or CSV format"
        acceptedFormats={[".xlsx", ".xls", ".csv"]}
        onUpload={handleUpload}
      />
    </div>
  );
}

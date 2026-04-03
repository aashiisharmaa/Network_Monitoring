import fs from "fs";
import * as XLSX from "xlsx";
import Papa from "papaparse";
import path from "path";

interface ParsedRow {
  [key: string]: any;
}

const normalizeHeader = (key: string): string => {
  return key
    .trim()
    .replace(/\s+/g, "_")
    .replace(/[-()]/g, "_")
    .replace(/[^\w_]/g, "")
    .replace(/^_+|_+$/g, "");
};

export const parseFile = async (filePath: string): Promise<ParsedRow[]> => {
  if (!fs.existsSync(filePath)) {
    throw new Error(`File not found: ${filePath}`);
  }

  const ext = path.extname(filePath).toLowerCase();

  // ✅ Parse Excel (.xlsx / .xls)
  if (ext === ".xlsx" || ext === ".xls") {
    try {
      const fileBuffer = fs.readFileSync(filePath);
      const workbook = XLSX.read(fileBuffer, { type: "buffer" });

      if (!workbook.SheetNames.length) {
        throw new Error("No sheets found in Excel file");
      }

      const sheet = workbook.Sheets[workbook.SheetNames[0]];
      const rawData = XLSX.utils.sheet_to_json(sheet, {
        defval: null,
        raw: false,
      });

      // Normalize headers + trim values
      const cleanedData = rawData.map((row: any) => {
        const cleanRow: ParsedRow = {};
        for (const key in row) {
          const normalizedKey = normalizeHeader(key);
          const value = row[key];
          cleanRow[normalizedKey] =
            typeof value === "string"
              ? value.trim().replace(/\u00A0/g, "") // remove non-breaking space
              : value;
        }
        return cleanRow;
      });

      console.log(`✅ Parsed ${cleanedData.length} rows from Excel`);
      return cleanedData;
    } catch (error: any) {
      throw new Error(`Failed to parse Excel: ${error.message}`);
    }
  }

  // ✅ Parse CSV (.csv)
  if (ext === ".csv") {
    const fileContent = fs.readFileSync(filePath, "utf-8");

    return new Promise((resolve, reject) => {
      Papa.parse<ParsedRow>(fileContent, {
        header: true,
        skipEmptyLines: true,
        complete: (results) => {
          const cleanedData = results.data.map((row) => {
            const cleanRow: ParsedRow = {};
            for (const key in row) {
              const normalizedKey = normalizeHeader(key);
              const value = row[key];
              cleanRow[normalizedKey] =
                typeof value === "string"
                  ? value.trim().replace(/\u00A0/g, "")
                  : value;
            }
            return cleanRow;
          });

          console.log(`✅ Parsed ${cleanedData.length} rows from CSV`);
          resolve(cleanedData);
        },
        error: (error: any) => {
          reject(new Error(`Failed to parse CSV: ${error.message}`));
        },
      });
    });
  }

  throw new Error(`Unsupported file type: ${ext}`);
};

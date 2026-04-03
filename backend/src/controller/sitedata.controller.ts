// import type { Request, Response } from "express";
// import { PrismaClient } from "@prisma/client";
// import path from "path";
// import fs from "fs";
// import { parseFile } from "../utils/parserFile.ts";

// const prisma = new PrismaClient();

// /* ---------------- SAFE PARSERS ---------------- */

// const parseFloatSafe = (value: any): number | null => {
//   if (value === null || value === undefined || value === "") return null;

//   const cleaned = String(value)
//     .replace(/[%,\s]/g, "")
//     .trim();
//   const parsed = parseFloat(cleaned);

//   return isNaN(parsed) ? null : parsed;
// };

// const parseIntSafe = (value: any): number | null => {
//   if (value === null || value === undefined || value === "") return null;

//   const parsed = parseInt(value, 10);
//   return isNaN(parsed) ? null : parsed;
// };

// const safeString = (val: any): string | null => {
//   if (!val || typeof val === "object") return null;
//   return String(val).trim() || null;
// };

// /* ---------------- MAIN API ---------------- */

// export const uploadSiteData = async (req: Request, res: Response) => {
//   try {
//     const file = req.file;

//     if (!file) {
//       return res.status(400).json({
//         success: false,
//         message: "No file uploaded",
//       });
//     }

//     const ext = path.extname(file.originalname).toLowerCase();

//     if (![".xlsx", ".xls", ".csv"].includes(ext)) {
//       fs.unlinkSync(file.path);
//       return res.status(400).json({
//         success: false,
//         message: "Only Excel/CSV files allowed",
//       });
//     }

//     console.log("📂 Uploading:", file.originalname);

//     const parsedRows = await parseFile(file.path);

//     if (!parsedRows.length) {
//       return res.status(200).json({
//         success: true,
//         message: "No data found in file",
//       });
//     }

//     console.log(`✅ Parsed ${parsedRows.length} rows`);

//     /* ---------------- MAPPING ---------------- */

//     const formattedRows = parsedRows.map((row: any, index: number) => {
//       const normalized = Object.fromEntries(
//         Object.entries(row).map(([k, v]) => [k.toLowerCase().trim(), v]),
//       );

//       if (index === 0) {
//         console.log("HEADERS:", Object.keys(normalized));
//       }

//       return {
//         id: index + 1, // ⚠️ IMPORTANT since no autoincrement

//         Cell_Name: safeString(normalized["cell name"]),
//         SI_CI: safeString(normalized["si_ci"]),
//         EGCI: safeString(normalized["egci"]),
//         SuNetwork_ID: safeString(normalized["sunetwork id"]),
//         SITEID: safeString(normalized["siteid"]),
//         Site_Name: safeString(normalized["site name"]),
//         Cell_ID: safeString(normalized["cell id"]),
//         SEC_ID: safeString(normalized["sec id"]),

//         lon: parseFloatSafe(normalized["lon"]),
//         lat: parseFloatSafe(normalized["lat"]),

//         TAC: parseIntSafe(normalized["tac"]),
//         PCI: parseIntSafe(normalized["pci"]),
//         AZIMUTH: parseIntSafe(normalized["azimuth"]),

//         Antenna_Height: parseFloatSafe(normalized["antenna height"]),
//         M_tilt: parseFloatSafe(normalized["m-tilt"]),
//         E_tilt: parseFloatSafe(normalized["e-tilt"]),

//         TX_RX: safeString(normalized["tx/rx"]),

//         Real_Transmit_Power_of_Resource: parseFloatSafe(
//           normalized["real transmit power of resource"],
//         ),

//         Referenced_Signal_Power_of_Resource: parseFloatSafe(
//           normalized["referenced signal power of resource"],
//         ),

//         cellSize: safeString(normalized["cellsize"]),
//         cellRadius: parseFloatSafe(normalized["cellradius"]),

//         RachRootSequence: parseIntSafe(normalized["rachrootsequence"]),
//         Bandwidth: parseIntSafe(normalized["bandwidth"]),
//         Frequency: parseIntSafe(normalized["frequency"]),
//         Downlink_Center_Frequency: parseIntSafe(
//           normalized["downlink center frequency"],
//         ),

//         Region: safeString(normalized["region"]),
//         Cluster: safeString(normalized["cluster"]),
//         OMM: safeString(normalized["omm"]),
//         Antenna: safeString(normalized["antenna"]),
//         RET: safeString(normalized["ret"]),
//       };
//     });

//     /* ---------------- INSERT ---------------- */

//     const result = await prisma.siteData.createMany({
//       data: formattedRows,
//       skipDuplicates: true,
//     });

//     console.log(`🔥 Inserted ${result.count} rows`);

//     fs.unlinkSync(file.path);

//     return res.status(200).json({
//       success: true,
//       message: `Inserted ${result.count} rows successfully`,
//     });
//   } catch (error: any) {
//     console.error("❌ Upload Error:", error);

//     if (req.file?.path && fs.existsSync(req.file.path)) {
//       fs.unlinkSync(req.file.path);
//     }

//     return res.status(500).json({
//       success: false,
//       message: error.message,
//     });
//   }
// };
// export const getSiteData = async (req: Request, res: Response) => {
//   try {
//     const page = parseInt(req.query.page as string) || 1;
//     const limit = parseInt(req.query.limit as string) || 50;

//     const skip = (page - 1) * limit;

//     console.log(`📊 Fetching site data: page ${page}, limit ${limit}`);

//     const [data, total] = await Promise.all([
//       prisma.siteData.findMany({
//         skip,
//         take: limit,
//         orderBy: { id: "desc" },
//       }),
//       prisma.siteData.count(),
//     ]);

//     return res.status(200).json({
//       success: true,
//       data,
//       pagination: {
//         page,
//         limit,
//         total,
//         totalPages: Math.ceil(total / limit),
//       },
//     });
//   } catch (error: any) {
//     console.error("❌ Error fetching site data:", error);

//     return res.status(500).json({
//       success: false,
//       message: error.message,
//     });
//   }
// };

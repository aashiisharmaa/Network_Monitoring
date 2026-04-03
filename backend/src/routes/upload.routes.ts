import express from "express";

import {
  uploadAndParseFile,
  uploadKpiData,
  uploadSiteData,
  uploadAlarmData,
  getUploadHistory,
  deleteUpload,
  getNetworkData,
  getuploadHistory,
  getSiteData,
  getAlarmData,
} from "../controller/upload.controller.ts";
import { upload } from "../middleware/multer.middleware.ts";

const router = express.Router();

router.post("/upload", upload.single("file"), uploadAndParseFile);
router.post("/kpi", upload.single("file"), uploadKpiData);
router.post("/site", upload.single("file"), uploadSiteData);
router.post("/alarm", upload.single("file"), uploadAlarmData);
router.get("/uploads", getUploadHistory);
// router.get("/uploads/:id", getUploadById);
router.delete("/uploads/:id", deleteUpload);
router.get("/network-data", getNetworkData);
router.get("/uploads/history", getuploadHistory);
router.get("/history", getuploadHistory);
router.get("/site-data", getSiteData);
router.get("/alarm-data", getAlarmData);

export default router;

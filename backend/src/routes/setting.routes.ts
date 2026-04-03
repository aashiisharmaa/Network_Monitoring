// ✅ FIXED: Use curly braces for named import
import { Router } from "express";
import multer from "multer";
import {
  exportThresholdWorkbook,
  getSettings,
  importThresholdWorkbook,
  updateSettings,
} from "../controller/setting.controller.ts";

const settingRouter = Router();
const upload = multer({ storage: multer.memoryStorage() });

settingRouter.post("/updateSetting", updateSettings);
settingRouter.get("/getsettings", getSettings);
settingRouter.post("/thresholds/export", exportThresholdWorkbook);
settingRouter.post("/thresholds/import", upload.single("file"), importThresholdWorkbook);

export { settingRouter };

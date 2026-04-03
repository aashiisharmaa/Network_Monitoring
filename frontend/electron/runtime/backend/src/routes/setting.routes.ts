// ✅ FIXED: Use curly braces for named import
import { Router } from "express";
import {
  getSettings,
  updateSettings,
} from "../controller/setting.controller.ts";

const settingRouter = Router();

settingRouter.post("/updateSetting", updateSettings);
settingRouter.get("/getsettings", getSettings);

export { settingRouter };

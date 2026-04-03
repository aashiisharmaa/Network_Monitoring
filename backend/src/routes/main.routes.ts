import { Router } from "express";
import { authRouter } from "./auth.routes.ts";
import router from "./upload.routes.ts";
import dashboardRouter from "./dashboard.routes.ts";
import { kpiRouter } from "./kpi.routes.ts";
import { settingRouter } from "./setting.routes.ts";
import { authMiddleware } from "../middleware/auth.middleware.ts";
// import { siteRouter } from "./sitedata.routes.ts";

const mainRouter = Router();
mainRouter.use("/auth", authRouter);
mainRouter.use("/setting", authMiddleware, settingRouter);
mainRouter.use("/upload", authMiddleware, router);
mainRouter.use("/dashboard", authMiddleware, dashboardRouter);
mainRouter.use("/kpi", authMiddleware, kpiRouter);
// mainRouter.use("/site", siteRouter);

export { mainRouter };

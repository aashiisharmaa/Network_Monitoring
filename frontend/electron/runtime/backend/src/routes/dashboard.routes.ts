import Router from "express";
import {
  getDashboardStats,
  getTotalCells,
  getTotalSites,
  getTotalBands,
  getTotalTech,
  getTotalSectors,
  getTotalGroups,
  getDetailedStats,
  getPerformanceMetrics,
} from "../controller/dashboard.controller.ts";

const dashboardRouter = Router();

dashboardRouter.get("/stats", getDashboardStats);

dashboardRouter.get("/stats/cells", getTotalCells);
dashboardRouter.get("/stats/sites", getTotalSites);
dashboardRouter.get("/stats/bands", getTotalBands);
dashboardRouter.get("/stats/tech", getTotalTech);
dashboardRouter.get("/stats/sectors", getTotalSectors);
dashboardRouter.get("/stats/groups", getTotalGroups);

dashboardRouter.get("/stats/detailed", getDetailedStats);
dashboardRouter.get("/stats/performance", getPerformanceMetrics);

export default dashboardRouter;

import Router from "express";
import {
  getUlPrbUtilization,
  getDlPrbUtilization,
  getDataVolume,
  getUlThroughput,
  getDlThroughput,
  getErabDropRate,
  getErabSuccessRate,
  getRrcSuccessRate,
  getMeanRrcUsers,
  getMaxRrcUsers,
  getErabSetupRate,
  getRrcDropRate,
  getVolteCssr,
  getVolteDcr,
  getInterFreqHosr,
  getIntraFreqHosr,
  getCsfbSuccessRate,
  getAllKpis,
  getAvailableKpis,
  getDynamicKpiMetrics,
  getDynamicKpiData,
  getDynamicKpiColumns,
  updateDynamicKpiSelection,
  exportDynamicKpiReport,
} from "../controller/kpi.controllers.ts";

const kpiRouter = Router();

// Individual KPI endpoints
kpiRouter.get("/ul-prb-utilization", getUlPrbUtilization);
kpiRouter.get("/dl-prb-utilization", getDlPrbUtilization);
kpiRouter.get("/data-volume", getDataVolume);
kpiRouter.get("/ul-throughput", getUlThroughput);
kpiRouter.get("/dl-throughput", getDlThroughput);
kpiRouter.get("/erab-drop-rate", getErabDropRate);
kpiRouter.get("/erab-success-rate", getErabSuccessRate);
kpiRouter.get("/rrc-success-rate", getRrcSuccessRate);
kpiRouter.get("/mean-rrc-users", getMeanRrcUsers);
kpiRouter.get("/max-rrc-users", getMaxRrcUsers);
kpiRouter.get("/erab-setup-rate", getErabSetupRate);
kpiRouter.get("/rrc-drop-rate", getRrcDropRate);
kpiRouter.get("/volte-cssr", getVolteCssr);
kpiRouter.get("/volte-dcr", getVolteDcr);
kpiRouter.get("/inter-freq-hosr", getInterFreqHosr);
kpiRouter.get("/intra-freq-hosr", getIntraFreqHosr);
kpiRouter.get("/csfb-success-rate", getCsfbSuccessRate);
kpiRouter.get("/available-metrics", getAvailableKpis);
kpiRouter.get("/dynamic/metrics", getDynamicKpiMetrics);
kpiRouter.get("/dynamic/data", getDynamicKpiData);
kpiRouter.get("/dynamic/columns", getDynamicKpiColumns);
kpiRouter.post("/dynamic/selection", updateDynamicKpiSelection);
kpiRouter.post("/dynamic/export", exportDynamicKpiReport);
kpiRouter.get("/all", getAllKpis);

export { kpiRouter };

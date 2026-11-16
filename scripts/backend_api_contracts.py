"""Reviewed examples/notes for the source-derived backend handbook. No real credentials."""
from copy import deepcopy

MODULES = {
    "AuthController": ("Authentication", "Register, log in and invalidate a session token. The first registered account becomes SUPER_ADMIN; later self-registered accounts are USER."),
    "HealthController": ("Service health", "Liveness of the Java process. UP does not establish database, scheduler, mail or source-file health."),
    "CompanyController": ("Companies", "Customer/tenant master records, contact details and blocking. Company is not a geographical circle."),
    "UserController": ("User administration", "List accounts and create company-assigned users. Current create-user service accepts USER or SUPER_ADMIN, not ADMIN."),
    "LicenseController": ("License administration", "Issue, renew, block and download company/device-bound license records."),
    "LocalLicenseController": ("Installed license", "Upload the signed license file, inspect validity and obtain this installation's device ID."),
    "UploadController": ("Uploads and parsing", "KPI upload jobs, source preview, site/alarm imports, upload history and paged uploaded data. Legacy alarm upload is separate from the new durable OSS workflow."),
    "DashboardController": ("Dashboard", "Network inventory counts, distinct dimensions and aggregated KPI performance."),
    "KpiController": ("KPI data and exports", "Uploaded fixed-schema KPI samples, dynamic metric discovery/selection, statistics, bad-day analysis and Excel export."),
    "KpiThresholdRuleController": ("KPI severity thresholds", "Configure per-metric severity bands and evaluate individual values or a complete uploaded KPI file."),
    "SettingController": ("Dashboard threshold settings", "Global/file-scoped threshold values/operators and threshold workbook import/export. These differ from severity-band rules."),
    "KpiAlarmController": ("KPI-generated alarms", "Alarm rules, generation, summary, paging, acknowledgement and resolution based on KPI threshold evaluation. These are not raw OSS alarm observations."),
    "KpiValidationReportController": ("KPI validation", "Uploaded-file validation report and data-quality results."),
    "SiteAnalyticsController": ("Site analytics and map intelligence", "Site summary, worst sites, detail and PCI/handover/overshoot/neighbour intelligence for map and site views."),
    "PciAnalysisController": ("PCI analysis", "Inspect a selected PCI and optionally a source site in an uploaded site dataset."),
    "SitePredictionController": ("Predictions", "Site recommendations/summary and the LB worst-cell prediction workflow, optionally with timing-advance data."),
    "WorstPerformanceController": ("Worst-performance analysis", "Rank cells/sites by an explicit KPI metric and inspect cell-level bad-day evidence."),
    "RecommendationController": ("Root cause analysis", "Run the Python RCA engine, submit/poll jobs, manage presets and use the older blocking Excel export."),
    "RecommendationExportController": ("Durable RCA export", "Queue and poll captured-result Excel export, then download its artifact. Prefer this route for interactive exports."),
    "ReportDatasetController": ("Report dataset discovery", "Bounded, company-scoped dataset picker for the reporting workspace."),
    "KpiIngestionController": ("Durable KPI ingestion", "Append/replay-safe KPI import into an existing dataset with explicit identity fields and run history."),
    "DailyAnalysisController": ("Daily KPI comparison", "Configure aggregation, create daily snapshots and compare with observed prior-day/seven-day baselines."),
    "CommonReportController": ("Common KPI reports", "Generate persisted common reports from daily snapshots, inspect status and download PDF/XLSX/JSON artifacts."),
    "ReportMailController": ("Common-report email outbox", "Configure reviewed recipients, queue mail, inspect/cancel/retry/reconcile deliveries and download captured MIME."),
    "NightlyWorkflowController": ("Nightly KPI automation", "Configure daily folder discovery, import, analysis, report and delivery stages with IST cutoffs and durable run tracking."),
    "AlarmConfigurationController": ("Native OSS master data", "Tenant-owned zones, circles, exact aliases and source profiles; version checks protect updates."),
    "AlarmImportPreviewController": ("Native OSS preview", "Read-only bounded CSV/XLSX validation. Does not import alarms or clear missing records."),
    "AlarmWorkflowController": ("Native OSS snapshot workflow", "Frozen-source ingestion, corrections, catalogue/site mapping, source comparisons/history, scoped reports, folder scheduling and delivery/escalation settings."),
}

RCA = {
    "vendor": "ericsson", "dateMode": "single", "startDate": "2026-11-01", "endDate": "2026-11-15",
    "columnMapping": {"Short name": "cellName", "Date": "date", "Cell I'd": "cellName", "Site I'd": "site", "Tech": "tech"},
    "kpiMapping": {"MV_PUSCH SINR": "sinr"}, "selectedKpis": ["MV_PUSCH SINR"],
    "kpis": {}, "rcaThresholds": {}, "severityThresholds": {}, "anomalyThresholds": {},
    "inputRowLimit": 10000, "responseRowLimit": 1000,
}
OPTIONS = {"modules": ["thresholds", "alarmCandidates", "worstCells", "prediction"], "rcaConfig": {}}
PROFILE = {"groupBy": ["cellName"], "metrics": [{"key": "rrcsetup", "aggregation": "MEAN", "direction": "HIGHER_IS_BETTER", "unit": "PERCENT", "numeratorKey": None, "denominatorKey": None, "scale": None, "relativeChangeEnabled": False}], "minimumBaselineDays": 7, "minimumSamplesPerDay": 1, "requireEqualSampleCounts": True}
COMPANY = {"companyName": "Synthetic Customer", "contactName": "Demo Operator", "email": "operator@example.invalid", "phone": "", "address": "Synthetic address", "status": "ACTIVE"}
FEED = {"sourceKey": "SYNTHETIC-4G", "name": "Synthetic Nokia 4G", "oem": "NOKIA", "technology": "4G", "fileFormat": "CSV", "sheet": None, "sourceTimezone": "Asia/Kolkata", "dumpType": "SNAPSHOT", "circleId": 10}
RULE = {"metricName": "RRC_Establishment_Success_Rate", "technology": "LTE", "severity": "CRITICAL", "messageTemplate": "Review the matched KPI threshold evidence", "recommendation": "Inspect the source counters and transport", "enabled": True}
THRESHOLD = {"metricName": "RRC_Establishment_Success_Rate", "technology": "LTE", "direction": "LOWER_IS_BAD", "criticalValue": 90.0, "majorValue": 95.0, "minorValue": 98.0, "warningValue": 99.0, "enabled": True}


def request_example(entry):
    c, h, verb = entry["controller"], entry["handler"], entry["method"]
    if not any(p["location"] == "body" for p in entry["parameters"]): return None
    if c == "AuthController": return {} if h == "logout" else {"email": "operator@example.invalid", "password": "REPLACE_WITH_A_TEST_PASSWORD"}
    if c == "CompanyController": return deepcopy(COMPANY)
    if c == "UserController": return {"email": "reader@example.invalid", "password": "REPLACE_WITH_A_TEST_PASSWORD", "companyId": 100, "role": "USER", "status": "ACTIVE"}
    if c == "LicenseController": return {"expiresAt": "2027-11-15"} if h == "renewLicense" else {"companyId": 100, "deviceId": "DEVICE_ID_FROM_LOCAL_LICENSE_API", "plan": "BASIC", "maxUsers": 5, "validFrom": "2026-11-16", "expiresAt": "2027-11-15", "status": "ACTIVE"}
    if c == "AlarmConfigurationController":
        bodies = {"createZone": {"code": "WEST", "name": "Synthetic West"}, "updateZone": {"name": "Synthetic West", "active": True, "version": 1},
            "createCircle": {"zoneId": 1, "code": "MU", "name": "Synthetic Circle"}, "updateCircle": {"zoneId": 1, "name": "Synthetic Circle", "active": True, "version": 1},
            "createAlias": {"circleId": 10, "alias": "SYNTHETIC-ALIAS"}, "createFeed": FEED,
            "updateFeed": {**{k: v for k, v in FEED.items() if k != "sourceKey"}, "active": True, "version": 1}}
        return deepcopy(bodies[h])
    if c == "AlarmWorkflowController":
        bodies = {"catalog": {"oem": "NOKIA", "technology": "4G", "matchField": "CODE", "matchValue": "SYNTHETIC-100", "classification": "CRITICAL", "included": True, "serviceAffecting": True, "siteDown": False},
            "site": {"circleId": 10, "objectKey": "PLMN/MRBTS-SYNTHETIC", "siteCode": "SYNTHETIC-SITE"},
            "event": {"action": "NOTE", "note": "Replace with the actual operator review evidence"},
            "settings": {"version": 0, "enabled": False, "freshnessMinutes": 60, "reports": [{"key": "national-preview", "circleId": None, "recipients": ["reviewer@example.invalid"], "mode": "CAPTURE"}], "escalations": [], "watches": []},
            "reconcile": {"outcome": "CONFIRMED_NOT_SENT", "note": "Replace with actual provider reconciliation evidence"}}
        return deepcopy(bodies[h])
    if c == "KpiThresholdRuleController": return deepcopy(THRESHOLD)
    if c == "KpiAlarmController": return {"user": "Demo reviewer", "remarks": "Replace with actual operator evidence"} if h in {"acknowledge", "resolve"} else deepcopy(RULE)
    if c == "KpiController":
        return {"updateDynamicSelection": {"metricKeys": ["rrcsetup"], "filterKeys": ["site", "tech"]},
            "getDynamicBadDays": {"dimensionKey": "site", "thresholdContexts": [{"metricKey": "rrcsetup", "thresholdValue": 98.0, "thresholdOperator": "<", "metricLabel": "RRC Setup SR"}]},
            "exportDynamicReport": {"metricKeys": ["rrcsetup"], "groupByKeys": ["site"], "activeFilters": {}, "compareRanges": {"preStart": "2026-11-01", "preEnd": "2026-11-07", "postStart": "2026-11-08", "postEnd": "2026-11-15"}, "exportFormat": "standard", "maxLongRows": 10000}}[h]
    if c in {"RecommendationController", "RecommendationExportController"}: return {"name": "synthetic-review", "config": deepcopy(RCA)} if h == "savePreset" else deepcopy(RCA)
    if c == "DailyAnalysisController": return deepcopy(PROFILE)
    if c == "CommonReportController": return deepcopy(OPTIONS)
    if c == "ReportMailController": return {"outcome": "NOT_ACCEPTED", "evidence": "Replace with actual provider verification"} if h == "reconcile" else {"mode": "CAPTURE", "recipients": ["reviewer@example.invalid"], "attachments": ["pdf", "xlsx"], "allowPartial": False, "enabled": False}
    if c == "NightlyWorkflowController": return {"enabled": False, "folder": "", "filePattern": "kpi-{date}.csv", "identityFields": ["date", "cellName"], "columnMapping": {}, "deliveryMode": "REPORT_ONLY", "cutoffTime": "01:30", "reportOptions": deepcopy(OPTIONS)}
    if c == "SettingController": return {"thresholds": {"RRC_Establishment_Success_Rate": 98.0}, "operators": {"RRC_Establishment_Success_Rate": ">="}, "scopeFileId": "global"} if h == "exportThresholdWorkbook" else {"RRC_Establishment_Success_Rate": 98.0, "thresholdOperators": {"RRC_Establishment_Success_Rate": ">="}, "dynamicThresholds": {"rrcsetup": 98.0}}
    raise ValueError("Unreviewed body example: " + c + "." + h + ":" + verb)


NOTES = {
    "AlarmConfigurationController": "Codes/source keys are immutable; PUT sends the full editable representation and current version. New feeds are inactive. Exact aliases do not imply automatic circle equivalence.",
    "AlarmImportPreviewController": "sourceTimezone is mandatory. XLSX requires an exact raw sheet; CSV must not specify one. Maximum 10 MiB/5000 rows/80 columns; response sample is at most 50 valid rows and 100 issues. This is read-only.",
    "AlarmWorkflowController": "Requires migrations 008/009 and a matching rebuilt backend; imports also require ALARM_WORKFLOW_ENABLED and an active feed. SNAPSHOT only: DELTA/OSS clearing is not implemented. Same-slot conflicts are 409. Unknown catalogue is UNMAPPED; unknown circle fails import. Native thresholds/severity are customer-defined, not inferred from source severity.",
    "KpiIngestionController": "Targets an existing KPI fileId. identityFields is a JSON-encoded form string, not a JSON request body. Replays are namespaced by dataset/source identity.",
    "NightlyWorkflowController": "Uses Asia/Kolkata. Pattern must contain {date}; enabled profiles need an accessible absolute folder. Input cutoff is 01:00–01:59 IST; report target is 02:00 next day. Running backend/service and configuration are required; this is not an inbox-delivery guarantee.",
    "DailyAnalysisController": "groupBy must contain cellName. Aggregations MEAN/SUM/RATIO and directions HIGHER_IS_BETTER/LOWER_IS_BETTER/NEUTRAL are explicit. RATIO requires numeratorKey/denominatorKey; percentages must not be summed. Missing baseline/sample information is reported explicitly.",
    "ReportMailController": "Default examples are disabled CAPTURE previews. Sending requires an enabled reviewed profile. UNKNOWN must be reconciled; ACCEPTED is SMTP-provider acceptance, not inbox delivery. Reconciliation outcome vocabulary differs from the native OSS outbox.",
    "CommonReportController": "Requires a ready daily snapshot. Optional modules: thresholds, alarmCandidates, worstCells, rca, prediction. RCA requires actual reviewed mapping/config. Artifact format is pdf/xlsx/json/configuration as supported by report storage.",
    "RecommendationController": "Dataset-specific columns/KPI keys must match the source; the illustrative mappings are not universal. dateMode single or pre_post. Java-facing fields are camelCase, unlike the Python engine's internal snake_case. Prefer jobs + polling for analysis and /rca-exports for export.",
    "RecommendationExportController": "Uses the captured-result export path and persisted job status; download only once the export is ready. fileId is a dataset ID; export id is a different ID.",
    "KpiController": "Discover actual dynamic metricKeys/filterKeys before selection. GET metricKey is declared optional but blank is rejected by getDynamicData. Legacy fixed-metric responses and dynamic-metric responses have different shapes. Excel export supports standard/date_matrix; row budgets apply.",
    "SettingController": "updateSetting accepts flat numeric metric keys, not a nested thresholds object; thresholdOperators/operators and dynamicThresholds are separate maps. thresholds/export instead requires the nested thresholds/ operators export wrapper. Preserve endpoint capitalization: updateSetting.",
    "KpiThresholdRuleController": "Rule directions are LOWER_IS_BAD/HIGHER_IS_BAD. These are not DailyKpiCalculator's direction values. Severity matching is inclusive at the configured band boundary.",
    "KpiAlarmController": "KPI-generated alarm state is distinct from native OSS observation/operator history. Controller-level role checks are not declared here; frontend admin visibility is not an API authorization guarantee.",
    "CompanyController": "Service calls requireSuperAdmin. GET list is nevertheless in the legacy JWT-filter bypass set; a supplied Bearer header may not populate the security context on that path. Do not mistake filter bypass for successful administrator authorization.",
    "UserController": "Current service accepts USER or SUPER_ADMIN roles only. ADMIN is recognized by newer alarm authorization but is not accepted by this account-creation API. Invalid roles/statuses are rejected.",
    "LocalLicenseController": "License-filter exempt but still requires JWT/session authentication. Upload a real issued license.dat; no license contents, signing keys or actual device IDs are included in this handbook.",
    "UploadController": "KPI uploads return a job to poll even when the HTTP status is 200. Site/alarm uploads use the legacy upload path. targetFileId takes precedence over fileId. Read paging/filter query fields are separate from multipart upload fields.",
    "SitePredictionController": "Recommendations are model/rule outputs, not observed network facts. LB-WCF accepts multipart/form parameters; optional method defaults to both, quantile to 0.10 and mlMode to 3 in its service.",
    "DashboardController": "Only stats/performance methods accept explicit fileId/companyId here; distinct count endpoints have no such parameters. GET is in the legacy public-read JWT bypass, while the license guard still applies.",
}

PURPOSES = {
    ("AlarmWorkflowController", "replace"): "Create a corrected revision of the latest FAILED snapshot using a replacement file and current configuration; successful history cannot be overwritten.",
    ("AlarmWorkflowController", "retry"): "Requeue a FAILED run using exactly its staged source and frozen configuration.",
    ("AlarmWorkflowController", "national"): "Download national/circle PDF or CSV for the active-feed cohort at the requested asOf time.",
    ("AlarmWorkflowController", "event"): "Record a NOTE/ACKNOWLEDGED/RESOLVED operator action without inventing an OSS state transition.",
    ("AlarmWorkflowController", "reconcile"): "Resolve an UNKNOWN send result using confirmed provider evidence.",
    ("AlarmConfigurationController", "resolve"): "Resolve a raw circle alias to MAPPED, UNMAPPED or INACTIVE within one company.",
    ("KpiController", "getDynamicBadDays"): "Count bad days for an explicit grouping dimension using the supplied threshold contexts.",
    ("KpiController", "exportDynamicReport"): "Download a dynamic KPI Excel workbook with selected metrics, grouping, filters and comparison ranges.",
    ("SettingController", "importThresholdWorkbook"): "Parse a threshold workbook and return its values; do not confuse parsing with saving updateSetting.",
}

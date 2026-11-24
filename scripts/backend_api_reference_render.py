"""Render the reviewed controller inventory to Markdown, Word, PDF and Postman."""
from __future__ import annotations
import json
import re
from collections import Counter
from datetime import date, datetime, timezone, timedelta
from html import escape
from pathlib import Path
from urllib.parse import urlencode

from backend_api_contracts import MODULES, NOTES, PURPOSES, request_example, PROFILE, RCA, OPTIONS

GENERATED_DATE = datetime.now(timezone(timedelta(hours=5, minutes=30))).date()
DATE = GENERATED_DATE.strftime("%d %B %Y")
ID_EXAMPLES = {"companyId": 100, "fileId": 500, "targetFileId": 500, "feedId": 20, "siteId": 30, "snapshotId": 40, "reportId": 50, "jobId": 60, "id": 1}


def sample(param, entry):
    name, java = param["name"], param["javaType"]
    defaults = {"date": "2026-11-15", "startDate": "2026-11-01", "endDate": "2026-11-15", "snapshotAt": "2026-11-15T00:00:00Z", "asOf": "2026-11-16T00:00:00Z", "sourceTimezone": "Asia/Kolkata", "oem": "NOKIA", "technology": "4G", "sheet": "Alarm Dump", "sourceName": "synthetic-daily", "identityFields": '["date","cellName"]', "columnMapping": "{}", "appendOption": "append", "remarks": "Synthetic documentation sample", "uploadedBy": "Demo operator", "metricKey": "rrcsetup", "metricName": "RRC_Establishment_Success_Rate", "metric": "rrcsetup", "value": 97.0, "site": "SYNTHETIC-SITE", "sourceSiteId": "SYNTHETIC-SITE", "cellName": "SYNTHETIC-CELL", "severity": "CRITICAL", "status": "OPEN", "pci": 10, "page": 1, "limit": 20, "afterId": 0, "key": "a" * 64, "name": "synthetic-review", "method": "both", "quantile": 0.10, "mlMode": 3, "circle": "MU", "search": "synthetic", "expectedProfileHash": "PROFILE_HASH_FROM_PROFILE_RESPONSE", "format": "pdf", "cleanKpiData": False, "allowLate": False, "rediscoverInput": False, "refreshMailProfile": False}
    if name == "value" and entry["controller"] == "AlarmConfigurationController": return "MU"
    if "MultipartFile" in java: return "<select a local file>"
    if param["default"] is not None: return param["default"]
    if name in ID_EXAMPLES: return ID_EXAMPLES[name]
    if name in defaults: return defaults[name]
    if "boolean" in java.lower(): return False
    if any(t in java.lower() for t in ["long", "int", "double", "float"]): return 1
    return "REPLACE_WITH_VALID_VALUE"


def param_description(param, entry):
    n = param["name"]
    lookup = {"companyId": "Explicit persisted company/tenant ID; does not grant access by itself.", "fileId": "Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID.", "targetFileId": "Existing KPI dataset to append/merge/overwrite; takes precedence over fileId in legacy upload.", "feedId": "Native OSS source-feed profile ID.", "snapshotId": "Persisted daily-analysis snapshot ID, not fileId.", "reportId": "Persisted common-report ID, not fileId or snapshotId.", "jobId": "Job identifier belonging to this controller's workflow.", "id": "Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable.", "afterId": "Keyset pagination: last returned row ID; zero starts from the beginning.", "page": "One-based page number.", "limit": "Requested page/result size; service-specific bounds apply.", "snapshotAt": "Source snapshot time as ISO timestamp with explicit offset, whole seconds; alarm workflow rejects future times beyond tolerance.", "asOf": "ISO timestamp with explicit offset; select snapshots at/before this time.", "date": "Business/report date in YYYY-MM-DD, not a timestamp.", "startDate": "Date-filter start in YYYY-MM-DD.", "endDate": "Date-filter end in YYYY-MM-DD.", "identityFields": "JSON array encoded as a multipart text field; canonical observation identity fields.", "columnMapping": "JSON object encoded as multipart text; use reviewed source-column mappings; {} means no override.", "appendOption": "append, merge or overwrite (legacy KPI upload); default APPEND.", "metricKey": "Actual discovered dynamic metric key, not its friendly display label.", "metric": "Actual KPI metric to rank; obtain it from this dataset's discovered metrics.", "sourceTimezone": "Explicit Java ZoneId for source local timestamps, e.g. Asia/Kolkata.", "sheet": "Exact raw XLSX worksheet name; do not merge summary and raw sheets.", "expectedProfileHash": "Hash returned by the reviewed common-mail profile; protects against sending with changed recipients.", "file": "Binary upload; use multipart/form-data, never a JSON filesystem path.", "taFile": "Optional timing-advance file for LB worst-cell prediction.", "format": "Artifact format varies by module: OSS pdf/csv; common reports pdf/xlsx/json/configuration.", "key": "64-character SHA-256 provisional OSS alarm identity from an observation response.", "value": "Circle alias for resolution, or numeric KPI value for threshold evaluation, as indicated by this API."}
    return lookup.get(n, "See the module contract and controller/service validation for accepted values.")


def security(entry):
    path, method, controller = entry["path"], entry["method"], entry["controller"]
    public = path.startswith(("/api/v1/auth", "/api/v1/health")) or method == "GET" and (path.startswith(("/api/v1/dashboard", "/api/v1/kpi", "/api/v1/sites", "/api/v1/upload")) or path in {"/api/v1/setting/getsettings", "/api/v1/companies"})
    license_exempt = path.startswith(("/api/v1/auth", "/api/v1/health", "/api/v1/local-license", "/api/v1/companies", "/api/v1/users", "/api/v1/licenses"))
    role = "No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented."
    if controller in {"CompanyController", "UserController", "LicenseController", "KpiIngestionController", "AlarmImportPreviewController"}: role = "Legacy requireSuperAdmin check (blank legacy roles are treated as super admin by that helper)."
    if controller in {"AlarmConfigurationController", "AlarmWorkflowController"}: role = "Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed."
    if controller in {"DailyAnalysisController", "CommonReportController", "ReportMailController", "NightlyWorkflowController", "RecommendationExportController"}:
        admin = "admin();" in entry["_body"]
        role = ("Legacy SUPER_ADMIN mutation/profile check plus " if admin else "") + "dataset ownership/company scope checked in the controller."
    if controller == "ReportDatasetController": role = "SUPER_ADMIN can discover datasets across companies; other authenticated users need a company assignment and see only that company's matching KPI datasets."
    return {"jwt": ("JWT filter bypass; controller/service checks still apply" if controller in {"AuthController", "HealthController"} else "Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated") if public else "Bearer JWT and active session required", "license": "License-filter exempt" if license_exempt else "Valid installed license required (otherwise HTTP 402)", "authorization": role}


def purpose(entry):
    key = (entry["controller"], entry["handler"])
    if key in PURPOSES: return PURPOSES[key]
    readable = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", entry["handler"]).replace("Kpi", "KPI").replace("Rrc", "RRC")
    return readable[0].upper() + readable[1:] + ". Module: " + MODULES[entry["controller"]][0] + "."


def response(entry):
    c, h, method = entry["controller"], entry["handler"], entry["method"]
    binary = (c, h) in {("AlarmWorkflowController", "report"), ("AlarmWorkflowController", "national"), ("AlarmWorkflowController", "capture"), ("LicenseController", "downloadLicense"), ("CommonReportController", "download"), ("ReportMailController", "captured"), ("RecommendationController", "exportRecommendation"), ("RecommendationExportController", "download"), ("KpiController", "exportDynamicReport"), ("SettingController", "exportThresholdWorkbook")}
    if binary: return {"status": 200, "kind": "download", "example": None, "description": "Binary/artifact response with Content-Type and Content-Disposition; save bytes rather than parsing JSON. Common JSON/configuration downloads are files too."}
    if c == "AlarmConfigurationController" and h == "deleteAlias": return {"status": 204, "kind": "empty", "example": None, "description": "No response body."}
    if c == "AlarmWorkflowController" and h in {"removeCatalog", "removeSite"}: return {"status": 204, "kind": "empty", "example": None, "description": "No response body."}
    data, partial = {}, True
    example = {"success": True, "data": data}
    code = 201 if c in {"AlarmConfigurationController", "AlarmWorkflowController"} and method == "POST" and h in {"createZone", "createCircle", "createAlias", "createFeed", "catalog", "site", "captureOperationalInputs"} else 202 if c == "AlarmWorkflowController" and h in {"submit", "replace"} else 200
    if c == "HealthController": example = {"success": True, "data": {"status": "UP", "service": "network-monitoring-backend", "timestamp": "2026-11-16T00:00:00Z"}}; partial = False
    elif c == "AuthController":
        example = {"message": "Logged out successfully"} if h == "logout" else {"message": "User logged in successfully" if h == "login" else "User signed up successfully", "success": True, "data": {"token": "JWT_FROM_SUCCESSFUL_LOGIN", "user": {"id": 1, "email": "operator@example.invalid", "companyId": 100, "role": "USER", "status": "ACTIVE"}}}; partial = False
    elif c == "AlarmConfigurationController":
        zone = {"id": 1, "companyId": 100, "code": "WEST", "name": "Synthetic West", "active": True, "version": 1}
        circle = {"id": 10, "companyId": 100, "zoneId": 1, "code": "MU", "name": "Synthetic Circle", "active": True, "version": 1}
        alias = {"id": 2, "companyId": 100, "circleId": 10, "alias": "SYNTHETIC-ALIAS", "canonical": False}
        feed = {"id": 20, "companyId": 100, **request_example({**entry, "handler": "createFeed", "parameters": [{"location": "body"}]}), "active": False, "version": 1}
        if "Zone" in h or h == "zones": data = [zone] if h == "zones" else {**zone, "version": 2 if method == "PUT" else 1}
        elif "Circle" in h or h == "circles": data = [circle] if h == "circles" else {**circle, "version": 2 if method == "PUT" else 1}
        elif h in {"aliases", "createAlias"}: data = [alias] if h == "aliases" else alias
        elif h == "resolve": data = {"input": "MU", "status": "MAPPED", "circle": circle}
        else: data = [feed] if h == "feeds" else {**feed, "active": method == "PUT", "version": 2 if method == "PUT" else 1}
        example = {"success": True, "data": data}; partial = False
    elif c == "AlarmWorkflowController":
        job = {"id": 1, "feed_id": 20, "snapshot_at": "2026-11-15T00:00:00", "revision": 1, "supersedes_id": None, "status": "QUEUED", "attempts": 0, "message": None, "result_json": None}
        if h in {"submit", "run", "retry", "replace"}: data = job
        elif h == "runs": data = [job]
        elif h == "observations": data = [{"id": 1, "identity_key": "a" * 64, "circle_id": 10, "circle_name": "Synthetic Circle", "resource_name": "PLMN/MRBTS-SYNTHETIC", "source_severity": "Major", "classification": "UNMAPPED", "included": None, "raised_at": "2026-11-15T00:00:00"}]
        elif h in {"catalog", "site"}: data = {"id": 1, **request_example(entry)} if method == "POST" else []
        elif h in {"sites", "deliveries"}: data = []
        elif h == "operationalInputs": data = {"enabled": False, "snapshot": {"id": None, "revision": 0, "circles": []}}
        elif h == "captureOperationalInputs": data = {"id": 1, "effectiveAt": "2026-11-17T00:00:00Z", "revision": 1, "basedOnId": None, "sourceReference": "Synthetic approved NOC register", "checksum": "PAYLOAD_SHA256", "createdBy": 1, "circles": []}
        elif h == "operationalHistory": data = []
        elif h == "readiness": data = {"schema": {"008": False, "009": False, "010": False}, "workflowEnabled": False, "operationalInputsEnabled": False, "activeFeeds": None, "catalogueRules": None, "mappedSiteObjects": None, "smtpOutboundEnabled": False, "inputFolderConfigured": False, "automaticClearingEnabled": False, "deltaProcessingSupported": False}
        elif h == "event": data = {"recorded": True}; partial = False
        elif h == "reconcile": data = {"recorded": True}; partial = False
        elif h == "retryDelivery": data = {"queued": True}; partial = False
        elif h == "settings":
            data = request_example({**entry, "parameters": [{"location": "body"}]})
            data["version"] = 1 if method == "PUT" else 0
            if method == "GET": data["reports"] = []
            partial = False
        elif h == "history": data = {"snapshots": [], "operatorEvents": [], "warning": "Operator actions are distinct from OSS state. Absence from a dump is not confirmed clearance."}
        elif h == "status": data = {"workflowEnabled": False, "inputRootConfigured": False, "smtpEnabled": False, "folderStatus": [], "imports": [], "deliveries": []}; partial = False
        elif h == "dashboard": data = {"asOf": "2026-11-16T00:00:00Z", "sources": [], "circles": [], "configuredActiveFeeds": 0, "reportSummary": {"contractVersion": "alarm-summary-v1", "zones": [], "circles": [], "observedRecords": 0, "includedRecords": 0, "unmappedCatalogue": 0, "knownDownSites": 0, "pendingOperationalInputs": ["MS2 pending 2G/4G", "LR pending status/count"], "notes": ["Counts are source observations; missing observations do not confirm clearance."]}, "warning": "Inspect source timestamps; missing alarms are not confirmed cleared."}
        example = {"success": True, "data": data}
    elif c == "AlarmImportPreviewController": example = {"success": True, "data": {"contractVersion": "alarm-preview-v1", "sheet": "CSV", "sourceTimezone": "Asia/Kolkata", "rowsRead": 2, "validRows": 1, "rejectedRows": 1, "missingSourceSeverity": 1, "missingAlarmCode": 1, "sample": [{"sourceRow": 2, "oem": "NOKIA", "technology": "4G", "circle": "MU", "resource": "SYNTHETIC-NODE", "name": "", "alarmCode": "", "alarmText": "Synthetic source alarm", "sourceSeverity": "", "customerClassification": "UNMAPPED", "raisedAt": "2026-11-15T00:00:00Z", "supplementaryInformation": ""}], "issues": [{"sourceRow": 3, "field": "raisedAt", "message": "Use an ISO timestamp or yyyy-MM-dd/dd-MM-yyyy HH:mm:ss."}], "issuesTruncated": False, "warnings": ["Read-only validation: no alarms imported or cleared."]}}
    elif c == "KpiController":
        if h == "getAvailableMetrics": example = {"success": True, "fileId": 500, "availableMetrics": [], "metricCounts": {}, "dynamicAvailableMetrics": []}
        elif h == "getAllKpis": example = {"success": True, "count": 0, "data": []}
        elif h in {"getDynamicBatchData", "getDynamicMetrics", "getDynamicSummary"}: example = {"success": True, "fileId": 500, "metrics": []}
        elif h == "getDynamicData": example = {"success": True, "fileId": 500, "metricKey": "rrcsetup", "kpi": "RRC Setup SR", "data": [], "statistics": {"count": 0, "average": 0, "maximum": 0, "minimum": 0, "median": 0}}
        elif h == "getDynamicColumns": example = {"success": True, "fileId": 500, "columns": [], "selectedMetricKeys": [], "selectedFilterKeys": []}
        elif h == "getDynamicBadDays": example = {"success": True, "fileId": 500, "mode": "bad_days", "rows": [], "totalComparedCells": 0}
        elif h == "updateDynamicSelection": example = {"success": True, "fileId": 500, "selectedMetricKeys": ["rrcsetup"], "selectedFilterKeys": ["site", "tech"]}
        else: example = {"success": True, "kpi": "Illustrative metric label", "data": [], "statistics": {}}
    elif c == "DashboardController":
        if h == "getDashboardStats": data = {"totalCells": 10, "totalSites": 4, "totalBands": 2, "totalTech": 1, "totalSectors": 10}
        elif h.startswith("getTotal"): data = {"total": 0, h[len("getTotal"):].lower(): []}
        example = {"success": True, "data": data}
    elif c in {"CompanyController", "UserController", "LicenseController", "LocalLicenseController", "KpiThresholdRuleController", "KpiAlarmController", "SettingController", "SiteAnalyticsController", "SitePredictionController", "WorstPerformanceController", "KpiValidationReportController"}:
        if c == "CompanyController": data = [{"id": 100, **deepcopy_local_company()}] if h == "listCompanies" else {"id": 100, **deepcopy_local_company()}
        elif c == "UserController": data = [] if h == "listUsers" else {"id": 1, "email": "reader@example.invalid", "companyId": 100, "role": "USER", "status": "ACTIVE"}
        elif c == "LocalLicenseController": data = {"deviceId": "DEVICE_ID_FROM_THIS_INSTALLATION"} if h == "deviceId" else {"valid": False}
        elif h.startswith("list"): data = []
        example = {"success": True, "message": "Illustrative success message; exact wording varies by operation.", "data": data}
    elif c == "RecommendationController":
        if h == "listPresets": example = {"success": True, "presets": []}
        elif h == "savePreset": example = {"success": True, "name": "synthetic-review"}
        elif h == "loadPreset": example = {"success": True, "name": "synthetic-review", "config": RCA}
        elif h in {"getRecommendationJob", "startRecommendationJob"}: example = {"success": True, "message": "Illustrative job response", "data": {"jobId": 60, "status": "QUEUED", "progressPercent": 5}}
        else: example = {"success": True, "fileId": 500}
    elif c == "UploadController":
        if h in {"uploadAndParseFile", "uploadKpiData", "retryUploadJob", "getUploadJob"}: data = {"jobId": 60, "status": "QUEUED", "progressPercent": 5}
        elif h in {"listUploadJobs", "getUploadHistory", "getUploadHistorySlim", "getUploadHistorySlimAlias", "getSiteData", "getAlarmData", "getNetworkData"}: data = []
        example = {"success": True, "data": data}
    elif c == "ReportDatasetController": example = {"success": True, "data": {"items": [], "total": 0, "page": 1, "limit": 50}}; partial = False
    elif c == "DailyAnalysisController": example = {"success": True, "data": {"fileId": 500, "status": "CONFIGURATION_REQUIRED"}} if h == "profile" else {"success": True, "data": {"fileId": 500, "profileHash": "SERVER_PROFILE_HASH", "profile": PROFILE}} if h == "configure" else {"success": True, "data": [] if h in {"list", "compare"} else {"id": 40, "fileId": 500, "status": "QUEUED"}}
    elif c == "CommonReportController": example = {"success": True, "data": [] if h == "list" else {"id": 50, "snapshotId": 40, "fileId": 500, "status": "QUEUED"}}
    elif c == "ReportMailController": example = {"success": True, "data": [] if h == "list" else {"fileId": 500, "status": "CONFIGURATION_REQUIRED", "outboundEnabled": False}} if h in {"list", "profile"} else {"success": True, "data": {}}
    elif c == "NightlyWorkflowController": example = {"success": True, "data": [] if h == "list" else {}}
    elif c == "RecommendationExportController": example = {"success": True, "data": {"id": 1, "fileId": 500, "status": "QUEUED"}}
    elif c == "KpiIngestionController": example = {"success": True, "data": [] if h == "list" else {"id": 1, "fileId": 500, "status": "QUEUED"}}
    return {"status": code, "kind": "json", "example": example, "description": "Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state." if partial else "Synthetic shape example; replace identifiers/values with this installation's real response data."}


def deepcopy_local_company():
    from backend_api_contracts import COMPANY
    return dict(COMPANY)


def enrich(entries, live):
    for entry in entries:
        entry["purpose"] = purpose(entry)
        entry["security"] = security(entry)
        entry["requestExample"] = request_example(entry)
        entry["response"] = response(entry)
        entry["liveOpenApiListed"] = entry["method"].lower() in live["paths"].get(entry["path"], {}) if live.get("paths") else None
        entry["notes"] = NOTES.get(entry["controller"], "")
    return entries


def call_url(entry, postman=False):
    path = entry["path"]
    for param in entry["parameters"]:
        if param["location"] == "path": path = path.replace("{" + param["name"] + "}", "{{" + param["name"] + "}}" if postman else str(sample(param, entry)))
    query = [(p["name"], "{{" + p["name"] + "}}" if postman else str(sample(p, entry)).lower() if isinstance(sample(p, entry), bool) else sample(p, entry)) for p in entry["parameters"] if p["location"] == "query" and p["required"]]
    prefix = "{{baseUrl}}" if postman else "http://localhost:3001"
    return prefix + path + ("?" + urlencode(query, safe="{}") if query else "")


def records(root):
    from build_backend_api_documentation import annotations, balanced, split_top, parameter, without_comments
    locations = ["alarm/dto/AlarmConfiguration.java", "alarm/dto/AlarmReport.java", "alarm/dto/AlarmOperational.java", "alarm/dto/AlarmImport.java", "alarm/dto/AlarmModels.java", "alarm/dto/AlarmAutomation.java", "reporting/service/DailyKpiCalculator.java", "reporting/service/CommonReportModules.java", "reporting/mail/service/ReportMailProfile.java", "nightly/service/NightlyProfile.java", "reporting/mail/api/ReportMailController.java"]
    result = []
    for path in locations:
        file = root / "backend-java/src/main/java/com/network/monitoring" / path
        text = without_comments(file.read_text(encoding="utf-8-sig"))
        for match in re.finditer(r"public record (\w+)\s*\(", text):
            exposed = {"alarm/dto/AlarmReport.java": {"AgeBands", "Group", "Summary"}, "alarm/dto/AlarmImport.java": {"AlarmRow", "Preview"},
                "alarm/dto/AlarmModels.java": {"Catalog", "Site", "Outcome"},
                "alarm/dto/AlarmAutomation.java": {"Route", "Policy", "Watch", "Settings"},
                "reporting/service/DailyKpiCalculator.java": {"Metric", "Profile"}}
            if path in exposed and match[1] not in exposed[path]: continue
            end = balanced(text, match.end() - 1)
            fields = [parameter(raw) for raw in split_top(text[match.end():end])]
            result.append((path + ":" + match[1], fields))
    return result


def make_sections(entries, root):
    sections = []
    controller_count=len({entry["controller"] for entry in entries})
    operation_count=len(entries)
    expanded_count=sum(1+len(entry["aliases"]) for entry in entries)
    live_count=sum(entry["liveOpenApiListed"] is True for entry in entries)
    source_only=sum(entry["liveOpenApiListed"] is False for entry in entries)
    def add(kind, text, extra=None): sections.append((kind, text, extra))
    add("title", "Network Monitoring Backend API Handbook")
    add("p", f"Source-verified developer reference | {DATE} | Java / Spring Boot")
    add("p", f"Coverage: {controller_count} controllers, {operation_count} logical operations and {expanded_count} expanded method/URL combinations including legacy aliases. All examples use synthetic identifiers and reserved example.invalid addresses. No actual credentials, customer data or license contents are included.")
    add("h1", "1. How to use this document")
    add("p", "For each API: find its purpose, method/URL, authorization, input parameters, request JSON or multipart fields, response type/example and source location. API IDs make the Word, PDF, JSON inventory and Postman collection easy to cross-reference.")
    add("p", "API = a backend operation called by the frontend or another client. Request JSON is the data you send; response JSON is the data returned by the server. JSON supports strings, numbers, booleans, null, arrays and objects; keys are case-sensitive. GET normally uses path/query parameters, not a JSON body.")
    add("code", json.dumps({"companyId": 100, "enabled": False, "recipients": ["reviewer@example.invalid"], "circleId": None}, indent=2))
    availability=f"At capture, {live_count} canonical method/URL pairs were listed in the running localhost OpenAPI and {source_only} were not listed." if live_count+source_only else "No runtime OpenAPI snapshot was available; runtime availability has not been checked."
    add("p", f"Read-only/live check: current source has {operation_count} operations. "+availability+" Presence in OpenAPI is not proof a request succeeds or its migration/configuration is ready. No POST/PUT/PATCH/DELETE business requests were executed to produce this handbook.")
    add("table", ["Item", "Value"], [["Typical local base URL", "http://localhost:3001 (frontend default); server application.yml fallback is port 3000; PORT may override."], ["Swagger UI", "/swagger-ui/index.html"], ["Runtime OpenAPI", "/v3/api-docs"], ["Preferred URLs", "/api/v1/...; legacy aliases are listed for each operation."], ["JSON request header", "Content-Type: application/json"], ["Authentication", "Authorization: Bearer <token from login>; active session required where JWT filter applies."], ["File upload", "multipart/form-data; let the client generate its boundary. File content is not a JSON path."], ["Downloads", "Save response bytes; inspect Content-Type and Content-Disposition."], ["Async APIs", "A successful HTTP response can mean QUEUED. Poll the returned job/run ID; do not repeatedly submit the same action."]])
    add("h1", "2. Authentication, roles and response conventions")
    add("p", "POST /api/v1/auth/login with email/password. Read data.token and send it in Authorization. Logout invalidates that token's session. First self-registration becomes SUPER_ADMIN; later self-registration becomes USER and does not automatically acquire a company assignment.")
    add("p", "Native alarm operational inputs use append-only effective-time revisions with expectedRevision and expectedBaseSnapshotId for concurrency. Migration 010 and ALARM_OPERATIONAL_ENABLED are required for capture. Null counts remain unknown; fractions/numeric strings are rejected. Report emails pin operationalSnapshotId; old envelopes without it retain no operational context. Readiness is read-only and checks schema presence without running migrations. Native alarm report formats are csv/pdf/xlsx. The dashboard adds reportSummary for zone/circle/RAT counts and source-snapshot age bands. Excel includes evidence and blank operational inputs; its structured layout is not a certification of customer-template parity. V2 imports retain sourceAnnotations; frozen v1 imports preserve their older parsing policy.")
    add("p", "Security is documented in layers: JWT filter, installed-license filter and controller/service authorization. Legacy public GET routes bypass JWT parsing only without an Authorization header; supplied tokens are verified and populate the authenticated principal. A bypass is not a declaration of tenant safety or successful service authorization. Native alarm APIs use strict persisted company/role checks.")
    add("table", ["Role", "Important behaviour"], [["SUPER_ADMIN", "Cross-company administration only where the corresponding API authorizes it."], ["ADMIN", "Recognized by native OSS authorization for own-company mutations. Current /users creation accepts only USER/SUPER_ADMIN."], ["USER", "Native OSS own-company reads; dataset access varies by module."], ["Legacy blank role", "Legacy requireSuperAdmin helper permits it; strict native OSS APIs reject it."]])
    add("p", "Not all responses use the same envelope. Some use success/data/message, KPI endpoints include statistics/metrics at the top level, auth logout returns message only, and binary/204 responses have no JSON. Partial response examples are explicitly labelled and are not exhaustive schemas.")
    add("code", json.dumps({"success": True, "data": {"id": 1, "status": "QUEUED"}}, indent=2))
    add("table", ["HTTP", "Interpretation"], [["200", "Success; an asynchronous job can still be queued or processing."], ["201 / 202", "Created resource / durable request accepted in APIs that explicitly return these codes."], ["204", "Success without a body."], ["400", "Missing/invalid parameters, JSON, mapping or domain validation; some legacy missing-resource cases also use 400."], ["401", "Missing/invalid/expired JWT or inactive session."], ["402", "Installed-license guard rejected the request."], ["403", "Role, company or dataset access denied."], ["404", "Resource not found where controller/domain handling uses 404."], ["409", "Duplicate/conflicting state, stale version or invalid lifecycle transition."], ["429 / 503", "Native backlog limit / disabled feature, capacity or storage dependency unavailable. Some job APIs include Retry-After."], ["500", "Unexpected server/service error. Read the body; envelopes vary by controller."]])
    add("p", "JWT-filter error example (not the standard success/data envelope):")
    add("code", '{"message":"Missing or invalid token"}')
    add("p", "Native domain error example:")
    add("code", json.dumps({"success": False, "code": "ALARM_CONFIGURATION_CONFLICT", "message": "Configuration changed. Reload its current version before saving."}, indent=2))
    add("h1", "3. IDs, dates, pagination and workflow order")
    add("table", ["Identifier", "Meaning"], [["companyId", "Tenant/company primary key."], ["fileId", "Uploaded dataset; KPI/site/alarm file type depends on the API."], ["feedId", "Native OSS source-feed profile."], ["snapshotId", "Daily KPI analysis snapshot."], ["reportId", "Generated common-report record."], ["jobId", "Upload or RCA job; not a dataset ID."], ["id", "Controller-specific rule, import, report, delivery, circle, zone or license ID."], ["identity key", "64-character provisional native OSS alarm hash returned with an observation."]])
    add("p", "Dates use YYYY-MM-DD. Native snapshotAt/asOf use ISO timestamps with an explicit offset; snapshotAt must use whole seconds. Source local alarm timestamps use the feed's sourceTimezone. Native SQL datetime strings are UTC semantics even if a serialized value has no Z. Legacy data shapes may differ; preserve the documented field names.")
    add("p", "Page/limit pagination is one-based where declared. Native afterId pagination is keyset-based: start at zero, then use the last returned ID. Omitted optional parameters are not the same as explicit null/blank; follow each parameter table. Version fields protect full PUT replacements against lost updates.")
    add("table", ["Workflow", "Call order"], [["KPI charts", "login -> upload/kpi/preview -> upload/kpi -> upload/jobs/{jobId} -> kpi/available-metrics and dynamic/columns -> charts/exports"], ["RCA", "discover dataset columns -> review mapping/preset -> recommendation/jobs -> poll job -> rca-exports -> poll -> download"], ["Common daily report", "ingestion/kpi -> daily-analysis/profile -> daily-analysis/snapshots -> poll -> reports -> poll -> download -> reviewed report-mail profile -> deliveries"], ["Native OSS", "company -> zones -> circles/aliases -> source profile -> catalogue/site mappings -> activate feed -> feeds/{id}/imports -> poll -> evidence/history -> report -> automation/outbox"], ["Nightly KPI", "configure analysis/report/mail prerequisites -> save reviewed nightly profile -> inspect nightly runs and events"]])
    add("h1", "4. Module and API index")
    counts = Counter(e["controller"] for e in entries)
    add("table", ["Module", "Operations", "Controller"], [[MODULES[c][0], str(counts[c]), c] for c in sorted(counts)])
    add("table", ["API ID", "Method", "Canonical URL"], [[e["id"], e["method"], e["path"]] for e in entries])
    number = 5
    for controller in sorted(counts):
        title, overview = MODULES[controller]
        add("h1", f"{number}. {title}"); number += 1
        add("p", overview)
        if controller in NOTES: add("note", NOTES[controller])
        for entry in (e for e in entries if e["controller"] == controller):
            add("h2", f"{entry['id']} | {entry['method']} {entry['path']}")
            add("p", entry["purpose"])
            declared="listed in running OpenAPI at capture" if entry["liveOpenApiListed"] is True else "current source only; not listed in running OpenAPI at capture" if entry["liveOpenApiListed"] is False else "current source; runtime availability not checked"
            add("p", "Declaration: " + declared + ". Source: " + entry["source"] + ":" + str(entry["sourceLine"]) + " -> " + entry["handler"] + "().")
            if entry["aliases"]: add("p", "Other declared aliases: " + "; ".join(entry["aliases"]))
            sec = entry["security"]
            add("p", "Access: " + sec["jwt"] + ". " + sec["license"] + ". " + sec["authorization"])
            params = [p for p in entry["parameters"] if p["location"] != "server-context"]
            if params:
                add("table", ["Field", "Where / Java type", "Required / default", "Meaning"], [[p["name"], p["location"] + " / " + p["javaType"], ("Yes" if p["required"] else "No") + ("; default=" + str(p["default"]) if p["default"] is not None else ""), param_description(p, entry)] for p in params])
            else: add("p", "Inputs: no declared client parameters or JSON body.")
            add("p", "Example URL (synthetic IDs; required query fields shown):")
            add("code", entry["method"] + " " + call_url(entry))
            if entry["requestContentType"] == "multipart/form-data":
                add("p", "Request: multipart/form-data. These are form fields, not a JSON request body:")
                add("table", ["Form field", "Illustrative value"], [[p["name"], str(sample(p, entry))] for p in params if p["location"] == "form"])
            elif entry["requestExample"] is not None:
                add("p", "Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):")
                add("code", json.dumps(entry["requestExample"], indent=2, ensure_ascii=True))
            else: add("p", "Request JSON: none. Send path/query parameters or an empty body as declared above.")
            res = entry["response"]
            add("p", f"Success response: HTTP {res['status']}. {res['description']}")
            if res["example"] is not None: add("code", json.dumps(res["example"], indent=2, ensure_ascii=True))
    add("h1", "Appendix A. Request/response record field reference")
    add("p", "These fields are extracted from actual Java record declarations used by the public contracts. Outcome describes the JSON stored inside a native run's result_json string, not an extra top-level object. Constraint names are copied from source; a request object's required flag does not mean every property inside it is mandatory. Service-level cross-field checks in the module notes also apply. Entity/Map response content can contain additional fields.")
    for name, fields in records(root):
        add("h2", name)
        add("table", ["JSON property", "Java type", "Declared annotations"], [[f["name"], f["javaType"], "; ".join(f["validation"]) or "No bean constraint on this field; see service validation"] for f in fields])
    add("h1", "Appendix B. Important JSON contracts and limits")
    add("p", "RCA Java-facing keys: vendor, preset, sheetName, columnMapping, kpiMapping, selectedKpis, kpis, rcaThresholds, severityThresholds, anomalyThresholds, dateMode, startDate/endDate, preStartDate/preEndDate, postStartDate/postEndDate, inputRowLimit, responseRowLimit. pre_post needs both date windows. KPI target/weight/direction definitions must match the reviewed engine configuration.")
    add("p", "RCA result fields are engine/vendor-dependent, including Primary Cause, Secondary Causes, Evidence, Top KPI Drivers and vendor recommendation columns. Observed is source-row evidence; period mean/median are descriptive values for that cell/KPI/period, not interchangeable with observed. Evidence may be returned as text; do not assume a separate universal target JSON field.")
    add("p", "Native OSS provisional identity: OEM + RAT + resource + code (or text when code absent) + raised time. Missing alarms are not confirmed cleared. DELTA/clear adapters, customer operational MS2/LR inputs and exact customer-template acceptance remain outside the completed snapshot path.")
    add("p", "Native ingestion: 10 MiB, 5000 non-empty rows, 80 columns, 4000 characters/cell. Preview returns at most 50 valid samples and 100 issues; durable import parses the full bounded input. Catalogue rules require explicit included/serviceAffecting/siteDown flags. Native files and configuration are frozen; retry keeps them, while explicit failed-run replacement captures a new revision.")
    add("p", "Native Settings example includes empty watches/escalations for a disabled preview. Optional watch declaration (requires administrator-configured ALARM_INPUT_ROOT and an existing approved relative folder):")
    add("code", json.dumps({"feedId": 20, "folder": "customer-a/nokia-4g", "timestampSource": "SIDECAR", "windowStart": "01:00", "windowEnd": "02:00", "timezone": "Asia/Kolkata"}, indent=2))
    add("p", "Watch timestampSource: SIDECAR or FILE_MODIFIED_UTC. SIDECAR needs <source-file>.ready.json containing snapshotAt and the source SHA-256 checksum. Windows are local-time, end-exclusive; optional schedule needs complete start/end/timezone fields. SMTP outside the sending window is suppressed; source freshness and completeness checks also apply.")
    add("p", "Native escalation policy declaration (example values, not the customer's approved policy):")
    add("code", json.dumps({"key": "synthetic-critical-stage", "circleId": 10, "classification": "CRITICAL", "minAgeHours": 12, "recipients": ["reviewer@example.invalid"], "mode": "CAPTURE"}, indent=2))
    add("p", "Common-report mail reconciliation: outcome ACCEPTED/CAPTURED/NOT_ACCEPTED plus evidence. Native OSS reconciliation: outcome CONFIRMED_SENT/CONFIRMED_NOT_SENT plus note. These are different contracts. ACCEPTED is not inbox confirmation; UNKNOWN is not safe to auto-resend.")
    add("h1", "Appendix C. Deployment and integration status")
    add("p", "See each operation's declaration status for the source/runtime difference. New native-alarm operations require migrations 008/009 through the deployment process and a matching rebuilt/restarted backend. Enable ALARM_WORKFLOW_ENABLED only after the schema is ready. Folder and SMTP remain opt-in; the BRD's XXX/YYY/ZZZ placeholders are not real recipient lists.")
    add("p", "Known source-level integration distinctions: the /companies GET legacy filter bypass can conflict with its service's authenticated super-admin check; /users creation does not accept ADMIN although newer alarm checks recognize it; global Swagger Bearer configuration does not model all legacy read bypasses. This documentation records current behaviour and does not change application authorization.")
    add("p", "Supporting operating guides: reports/brd-review/alarm-workflow-delivery.md and alarm-master-data.md. Runtime Swagger can differ until the current build is deployed. Configuration availability, real OEM export coverage, customer-volume performance, backup/restore and production mail delivery require separate acceptance checks.")
    add("h1", "Appendix D. Included files and validation")
    add("table", ["File", "Use"], [["Backend_API_Handbook.docx", "Editable Word handbook."], ["Backend_API_Handbook.pdf", "Shareable/readable PDF."], ["Backend_API_Handbook.md", "Searchable source-oriented Markdown."], ["backend-api-inventory.json", "All source-derived parameters, aliases, examples, source locations and controller hashes."], ["Network_Monitoring.postman_collection.json", "One canonical request per logical operation; configure baseUrl/token/IDs before use."], ["documentation-validation.json", "Coverage counts, source/OpenAPI comparison, artifact validation and generation time."]])
    add("p", "No business mutations or external emails were executed. Endpoint examples are illustrative rather than captured customer responses; partial envelopes are labelled. The generator verifies every mapped controller operation is included, every JSON-body operation has a reviewed request example, alias/parameter coverage is consistent and all API IDs appear in generated Word/PDF output.")
    return sections


def markdown(sections, path):
    lines = []
    for kind, text, extra in sections:
        if kind in {"title", "h1", "h2"}: lines.extend([("#" if kind == "title" else "##" if kind == "h1" else "###") + " " + text, ""])
        elif kind == "code": lines.extend(["```" + ("json" if text.startswith(("{", "[")) else "text"), text, "```", ""])
        elif kind == "table":
            def clean(value): return str(value).replace("|", "\\|").replace("\n", "<br>")
            lines.append("| " + " | ".join(map(clean, text)) + " |")
            lines.append("| " + " | ".join("---" for _ in text) + " |")
            lines.extend("| " + " | ".join(map(clean, row)) + " |" for row in extra)
            lines.append("")
        else: lines.extend([("> " if kind == "note" else "") + text, ""])
    path.write_text("\n".join(lines), encoding="utf-8")


def word(sections, path):
    from docx import Document
    from docx.shared import Inches, Pt, RGBColor
    from docx.oxml import OxmlElement
    from docx.oxml.ns import qn
    document = Document()
    sec = document.sections[0]; sec.top_margin = sec.bottom_margin = Inches(.65); sec.left_margin = sec.right_margin = Inches(.65)
    normal = document.styles["Normal"]; normal.font.name = "Calibri"; normal.font.size = Pt(9)
    normal.paragraph_format.space_after = Pt(6)
    for name in ["Heading 1", "Heading 2", "Title"]: document.styles[name].font.color.rgb = RGBColor.from_string("17365D")
    code = document.styles.add_style("API Code", 1); code.font.name = "Consolas"; code.font.size = Pt(7.5); code.paragraph_format.space_after = Pt(1)
    header = sec.header.paragraphs[0]; header.text = "NETWORK MONITOR | BACKEND API REFERENCE"; header.style = document.styles["Caption"]
    footer = sec.footer.paragraphs[0]; footer.text = DATE + " | Source-verified / synthetic examples | Page "
    field = OxmlElement("w:fldSimple"); field.set(qn("w:instr"), "PAGE"); footer._p.append(field)
    for kind, text, extra in sections:
        if kind == "title": document.add_heading(text, 0)
        elif kind in {"h1", "h2"}: document.add_heading(text, 1 if kind == "h1" else 2)
        elif kind == "code":
            for line in text.splitlines(): document.add_paragraph(line or " ", "API Code")
        elif kind == "table":
            table = document.add_table(rows=1, cols=len(text)); table.style = "Light Shading Accent 1"
            for cell, value in zip(table.rows[0].cells, text): cell.text = str(value)
            repeat = OxmlElement("w:tblHeader"); table.rows[0]._tr.get_or_add_trPr().append(repeat)
            for row in extra:
                for cell, value in zip(table.add_row().cells, row):
                    cell.text = str(value)
                    for paragraph in cell.paragraphs:
                        for run in paragraph.runs: run.font.size = Pt(7.5)
            document.add_paragraph()
        else:
            paragraph = document.add_paragraph(text)
            if kind == "note":
                for run in paragraph.runs: run.italic = True
    document.save(path)


def pdf(sections, path):
    from reportlab.lib import colors
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, LongTable, TableStyle, Preformatted
    font, mono = "Helvetica", "Courier"
    arial, consola = Path("C:/Windows/Fonts/arial.ttf"), Path("C:/Windows/Fonts/consola.ttf")
    if arial.exists(): pdfmetrics.registerFont(TTFont("ApiBody", str(arial))); font = "ApiBody"
    if consola.exists(): pdfmetrics.registerFont(TTFont("ApiMono", str(consola))); mono = "ApiMono"
    styles = getSampleStyleSheet()
    body = ParagraphStyle("ApiBodyStyle", fontName=font, fontSize=8, leading=11, spaceAfter=6, splitLongWords=True)
    headings = {"title": ParagraphStyle("ApiTitle", parent=body, fontSize=23, leading=28, textColor=colors.HexColor("#17365d"), spaceAfter=18), "h1": ParagraphStyle("ApiH1", parent=body, fontSize=15, leading=19, textColor=colors.HexColor("#17365d"), spaceBefore=17, spaceAfter=9, keepWithNext=True), "h2": ParagraphStyle("ApiH2", parent=body, fontSize=10, leading=13, textColor=colors.HexColor("#24578a"), spaceBefore=12, spaceAfter=6, keepWithNext=True)}
    cell = ParagraphStyle("ApiCell", parent=body, fontSize=6.7, leading=8.5, spaceAfter=0)
    code = ParagraphStyle("ApiCode", fontName=mono, fontSize=6.8, leading=8.8, leftIndent=8, rightIndent=8, spaceAfter=7, backColor=colors.HexColor("#f2f5fa"))
    flow = []
    for kind, text, extra in sections:
        if kind in headings: flow.append(Paragraph(escape(text), headings[kind]))
        elif kind == "code": flow.append(Preformatted(text, code, maxLineLength=110, splitChars=" /,", newLineChars=""))
        elif kind == "table":
            width = A4[0] - 80
            count = len(text)
            widths = [width * x for x in ([.17, .24, .19, .40] if count == 4 else [.15, .12, .73] if count == 3 and text[0] == "API ID" else [1 / count] * count)]
            rows = [[Paragraph(escape(str(value)), cell) for value in row] for row in [text] + extra]
            table = LongTable(rows, colWidths=widths, repeatRows=1, hAlign="LEFT")
            table.setStyle(TableStyle([("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#e5edf7")), ("GRID", (0, 0), (-1, -1), .3, colors.HexColor("#d7e0ec")), ("VALIGN", (0, 0), (-1, -1), "TOP"), ("LEFTPADDING", (0, 0), (-1, -1), 5), ("RIGHTPADDING", (0, 0), (-1, -1), 5), ("TOPPADDING", (0, 0), (-1, -1), 5), ("BOTTOMPADDING", (0, 0), (-1, -1), 5)]))
            flow.extend([table, Spacer(1, 8)])
        else: flow.append(Paragraph(escape(text), body))
    def footer(canvas, doc):
        canvas.saveState(); canvas.setFont(font, 7); canvas.setFillColor(colors.HexColor("#61738b"))
        canvas.drawString(40, 22, f"Network Monitor | {DATE} | Synthetic examples")
        canvas.drawRightString(A4[0] - 40, 22, str(doc.page)); canvas.restoreState()
    SimpleDocTemplate(str(path), pagesize=A4, leftMargin=40, rightMargin=40, topMargin=36, bottomMargin=36, title="Network Monitoring Backend API Handbook", author="Network Monitoring Engineering").build(flow, onFirstPage=footer, onLaterPages=footer)


def postman(entries, path):
    groups = []
    for controller in sorted(MODULES):
        items = []
        for entry in (e for e in entries if e["controller"] == controller):
            raw_url=call_url(entry,True)
            request_path=raw_url[len("{{baseUrl}}/"):].split("?",1)[0]
            url={"raw":raw_url,"host":["{{baseUrl}}"],"path":request_path.split("/")}
            query=[]
            for param in entry["parameters"]:
                if param["location"]!="query":continue
                value=sample(param,entry)
                query.append({"key":param["name"],"value":"{{"+param["name"]+"}}" if param["required"] else str(value).lower() if isinstance(value,bool) else str(value),"disabled":not param["required"],"description":param_description(param,entry)})
            if query:url["query"]=query
            request = {"method": entry["method"], "header": [], "url": url, "description": entry["purpose"] + "\n" + entry["notes"] + "\n" + entry["security"]["authorization"]}
            if entry["requestContentType"] == "multipart/form-data":
                request["body"] = {"mode": "formdata", "formdata": [{"key": p["name"], "type": "file" if "MultipartFile" in p["javaType"] else "text", **({"src": []} if "MultipartFile" in p["javaType"] else {"value": str(sample(p, entry)).lower() if isinstance(sample(p, entry), bool) else str(sample(p, entry))}), "description": param_description(p, entry), "disabled": not p["required"]} for p in entry["parameters"] if p["location"] == "form"]}
            elif entry["requestExample"] is not None:
                request["header"] = [{"key": "Content-Type", "value": "application/json"}]
                request["body"] = {"mode": "raw", "raw": json.dumps(entry["requestExample"], indent=2), "options": {"raw": {"language": "json"}}}
            if entry["security"]["jwt"].startswith("JWT filter bypass") and entry["controller"] in {"AuthController", "HealthController"} and entry["handler"] != "logout": request["auth"] = {"type": "noauth"}
            item = {"name": entry["id"] + " | " + entry["method"] + " " + entry["path"], "request": request, "response": []}
            if entry["controller"] == "AuthController" and entry["handler"] == "login": item["event"] = [{"listen": "test", "script": {"type": "text/javascript", "exec": ["const payload = pm.response.json();", "if (pm.response.code === 200 && payload.data && payload.data.token) pm.collectionVariables.set('token', payload.data.token);"]}}]
            items.append(item)
        groups.append({"name": MODULES[controller][0], "item": items})
    names = sorted({p["name"] for e in entries for p in e["parameters"] if p["location"] == "path" or p["location"] == "query" and p["required"]})
    collection = {"info": {"name": "Network Monitoring - Source Verified Backend APIs", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json", "description": f"{len(entries)} source-derived canonical operations. Examples are synthetic. Set baseUrl/token and real test-resource IDs. This file does not run requests. Check the handbook for runtime availability; native-alarm APIs need the matching build/migrations."}, "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{token}}", "type": "string"}]}, "variable": [{"key": "baseUrl", "value": "http://localhost:3001"}, {"key": "token", "value": ""}] + [{"key": name, "value": "", "description": "Set the correct test resource/value; IDs are not interchangeable."} for name in names], "item": groups}
    path.write_text(json.dumps(collection, indent=2), encoding="utf-8")


def render(entries, root, out):
    live_file = root / "backend-java/target/api-docs-live.json"
    live = json.loads(live_file.read_text(encoding="utf-8")) if live_file.exists() else {}
    enrich(entries, live)
    sections = make_sections(entries, root)
    markdown(sections, out / "Backend_API_Handbook.md")
    word(sections, out / "Backend_API_Handbook.docx")
    pdf(sections, out / "Backend_API_Handbook.pdf")
    postman(entries, out / "Network_Monitoring.postman_collection.json")
    return entries

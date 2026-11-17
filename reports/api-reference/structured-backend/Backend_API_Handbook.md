# Network Monitoring Backend API Handbook

Source-verified developer reference | 17 November 2026 | Java / Spring Boot

Coverage: 28 controllers, 186 logical operations and 304 expanded method/URL combinations including legacy aliases. All examples use synthetic identifiers and reserved example.invalid addresses. No actual credentials, customer data or license contents are included.

## 1. How to use this document

For each API: find its purpose, method/URL, authorization, input parameters, request JSON or multipart fields, response type/example and source location. API IDs make the Word, PDF, JSON inventory and Postman collection easy to cross-reference.

API = a backend operation called by the frontend or another client. Request JSON is the data you send; response JSON is the data returned by the server. JSON supports strings, numbers, booleans, null, arrays and objects; keys are case-sensitive. GET normally uses path/query parameters, not a JSON body.

```json
{
  "companyId": 100,
  "enabled": false,
  "recipients": [
    "reviewer@example.invalid"
  ],
  "circleId": null
}
```

Read-only/live check: current source has 186 operations. At capture, 147 canonical method/URL pairs were listed in the running localhost OpenAPI and 39 were not listed. Presence in OpenAPI is not proof a request succeeds or its migration/configuration is ready. No POST/PUT/PATCH/DELETE business requests were executed to produce this handbook.

| Item | Value |
| --- | --- |
| Typical local base URL | http://localhost:3001 (frontend default); server application.yml fallback is port 3000; PORT may override. |
| Swagger UI | /swagger-ui/index.html |
| Runtime OpenAPI | /v3/api-docs |
| Preferred URLs | /api/v1/...; legacy aliases are listed for each operation. |
| JSON request header | Content-Type: application/json |
| Authentication | Authorization: Bearer <token from login>; active session required where JWT filter applies. |
| File upload | multipart/form-data; let the client generate its boundary. File content is not a JSON path. |
| Downloads | Save response bytes; inspect Content-Type and Content-Disposition. |
| Async APIs | A successful HTTP response can mean QUEUED. Poll the returned job/run ID; do not repeatedly submit the same action. |

## 2. Authentication, roles and response conventions

POST /api/v1/auth/login with email/password. Read data.token and send it in Authorization. Logout invalidates that token's session. First self-registration becomes SUPER_ADMIN; later self-registration becomes USER and does not automatically acquire a company assignment.

Native alarm report formats are csv/pdf/xlsx. The dashboard adds reportSummary for zone/circle/RAT counts and source-snapshot age bands. Excel includes evidence and blank operational inputs; its structured layout is not a certification of customer-template parity. V2 imports retain sourceAnnotations; frozen v1 imports preserve their older parsing policy.

Security is documented in layers: JWT filter, installed-license filter and controller/service authorization. Legacy public GET routes bypass JWT parsing only without an Authorization header; supplied tokens are verified and populate the authenticated principal. A bypass is not a declaration of tenant safety or successful service authorization. Native alarm APIs use strict persisted company/role checks.

| Role | Important behaviour |
| --- | --- |
| SUPER_ADMIN | Cross-company administration only where the corresponding API authorizes it. |
| ADMIN | Recognized by native OSS authorization for own-company mutations. Current /users creation accepts only USER/SUPER_ADMIN. |
| USER | Native OSS own-company reads; dataset access varies by module. |
| Legacy blank role | Legacy requireSuperAdmin helper permits it; strict native OSS APIs reject it. |

Not all responses use the same envelope. Some use success/data/message, KPI endpoints include statistics/metrics at the top level, auth logout returns message only, and binary/204 responses have no JSON. Partial response examples are explicitly labelled and are not exhaustive schemas.

```json
{
  "success": true,
  "data": {
    "id": 1,
    "status": "QUEUED"
  }
}
```

| HTTP | Interpretation |
| --- | --- |
| 200 | Success; an asynchronous job can still be queued or processing. |
| 201 / 202 | Created resource / durable request accepted in APIs that explicitly return these codes. |
| 204 | Success without a body. |
| 400 | Missing/invalid parameters, JSON, mapping or domain validation; some legacy missing-resource cases also use 400. |
| 401 | Missing/invalid/expired JWT or inactive session. |
| 402 | Installed-license guard rejected the request. |
| 403 | Role, company or dataset access denied. |
| 404 | Resource not found where controller/domain handling uses 404. |
| 409 | Duplicate/conflicting state, stale version or invalid lifecycle transition. |
| 429 / 503 | Native backlog limit / disabled feature, capacity or storage dependency unavailable. Some job APIs include Retry-After. |
| 500 | Unexpected server/service error. Read the body; envelopes vary by controller. |

JWT-filter error example (not the standard success/data envelope):

```json
{"message":"Missing or invalid token"}
```

Native domain error example:

```json
{
  "success": false,
  "code": "ALARM_CONFIGURATION_CONFLICT",
  "message": "Configuration changed. Reload its current version before saving."
}
```

## 3. IDs, dates, pagination and workflow order

| Identifier | Meaning |
| --- | --- |
| companyId | Tenant/company primary key. |
| fileId | Uploaded dataset; KPI/site/alarm file type depends on the API. |
| feedId | Native OSS source-feed profile. |
| snapshotId | Daily KPI analysis snapshot. |
| reportId | Generated common-report record. |
| jobId | Upload or RCA job; not a dataset ID. |
| id | Controller-specific rule, import, report, delivery, circle, zone or license ID. |
| identity key | 64-character provisional native OSS alarm hash returned with an observation. |

Dates use YYYY-MM-DD. Native snapshotAt/asOf use ISO timestamps with an explicit offset; snapshotAt must use whole seconds. Source local alarm timestamps use the feed's sourceTimezone. Native SQL datetime strings are UTC semantics even if a serialized value has no Z. Legacy data shapes may differ; preserve the documented field names.

Page/limit pagination is one-based where declared. Native afterId pagination is keyset-based: start at zero, then use the last returned ID. Omitted optional parameters are not the same as explicit null/blank; follow each parameter table. Version fields protect full PUT replacements against lost updates.

| Workflow | Call order |
| --- | --- |
| KPI charts | login -> upload/kpi/preview -> upload/kpi -> upload/jobs/{jobId} -> kpi/available-metrics and dynamic/columns -> charts/exports |
| RCA | discover dataset columns -> review mapping/preset -> recommendation/jobs -> poll job -> rca-exports -> poll -> download |
| Common daily report | ingestion/kpi -> daily-analysis/profile -> daily-analysis/snapshots -> poll -> reports -> poll -> download -> reviewed report-mail profile -> deliveries |
| Native OSS | company -> zones -> circles/aliases -> source profile -> catalogue/site mappings -> activate feed -> feeds/{id}/imports -> poll -> evidence/history -> report -> automation/outbox |
| Nightly KPI | configure analysis/report/mail prerequisites -> save reviewed nightly profile -> inspect nightly runs and events |

## 4. Module and API index

| Module | Operations | Controller |
| --- | --- | --- |
| Native OSS master data | 14 | AlarmConfigurationController |
| Native OSS preview | 1 | AlarmImportPreviewController |
| Native OSS snapshot workflow | 24 | AlarmWorkflowController |
| Authentication | 3 | AuthController |
| Common KPI reports | 5 | CommonReportController |
| Companies | 5 | CompanyController |
| Daily KPI comparison | 7 | DailyAnalysisController |
| Dashboard | 9 | DashboardController |
| Service health | 1 | HealthController |
| KPI-generated alarms | 9 | KpiAlarmController |
| KPI data and exports | 27 | KpiController |
| Durable KPI ingestion | 4 | KpiIngestionController |
| KPI severity thresholds | 9 | KpiThresholdRuleController |
| KPI validation | 1 | KpiValidationReportController |
| License administration | 6 | LicenseController |
| Installed license | 3 | LocalLicenseController |
| Nightly KPI automation | 6 | NightlyWorkflowController |
| PCI analysis | 1 | PciAnalysisController |
| Root cause analysis | 7 | RecommendationController |
| Durable RCA export | 3 | RecommendationExportController |
| Report dataset discovery | 1 | ReportDatasetController |
| Common-report email outbox | 9 | ReportMailController |
| Dashboard threshold settings | 4 | SettingController |
| Site analytics and map intelligence | 4 | SiteAnalyticsController |
| Predictions | 3 | SitePredictionController |
| Uploads and parsing | 15 | UploadController |
| User administration | 2 | UserController |
| Worst-performance analysis | 3 | WorstPerformanceController |

| API ID | Method | Canonical URL |
| --- | --- | --- |
| API-001 | GET | /api/v1/alarm-config/companies/{companyId}/zones |
| API-002 | POST | /api/v1/alarm-config/companies/{companyId}/zones |
| API-003 | PUT | /api/v1/alarm-config/companies/{companyId}/zones/{id} |
| API-004 | GET | /api/v1/alarm-config/companies/{companyId}/circles |
| API-005 | POST | /api/v1/alarm-config/companies/{companyId}/circles |
| API-006 | PUT | /api/v1/alarm-config/companies/{companyId}/circles/{id} |
| API-007 | GET | /api/v1/alarm-config/companies/{companyId}/circle-aliases |
| API-008 | POST | /api/v1/alarm-config/companies/{companyId}/circle-aliases |
| API-009 | DELETE | /api/v1/alarm-config/companies/{companyId}/circle-aliases/{id} |
| API-010 | GET | /api/v1/alarm-config/companies/{companyId}/circle-resolution |
| API-011 | GET | /api/v1/alarm-config/companies/{companyId}/feeds |
| API-012 | GET | /api/v1/alarm-config/companies/{companyId}/feeds/{id} |
| API-013 | POST | /api/v1/alarm-config/companies/{companyId}/feeds |
| API-014 | PUT | /api/v1/alarm-config/companies/{companyId}/feeds/{id} |
| API-015 | POST | /api/v1/alarm-ingestion/preview |
| API-016 | POST | /api/v1/alarm-workflow/companies/{companyId}/feeds/{feedId}/imports |
| API-017 | GET | /api/v1/alarm-workflow/companies/{companyId}/imports |
| API-018 | GET | /api/v1/alarm-workflow/companies/{companyId}/imports/{id} |
| API-019 | POST | /api/v1/alarm-workflow/companies/{companyId}/imports/{id}/retry |
| API-020 | POST | /api/v1/alarm-workflow/companies/{companyId}/imports/{id}/replace |
| API-021 | GET | /api/v1/alarm-workflow/companies/{companyId}/imports/{id}/observations |
| API-022 | GET | /api/v1/alarm-workflow/companies/{companyId}/dashboard |
| API-023 | GET | /api/v1/alarm-workflow/companies/{companyId}/catalogue |
| API-024 | POST | /api/v1/alarm-workflow/companies/{companyId}/catalogue |
| API-025 | DELETE | /api/v1/alarm-workflow/companies/{companyId}/catalogue/{id} |
| API-026 | GET | /api/v1/alarm-workflow/companies/{companyId}/feeds/{id}/sites |
| API-027 | POST | /api/v1/alarm-workflow/companies/{companyId}/feeds/{id}/sites |
| API-028 | DELETE | /api/v1/alarm-workflow/companies/{companyId}/feeds/{id}/sites/{siteId} |
| API-029 | GET | /api/v1/alarm-workflow/companies/{companyId}/imports/{id}/report/{format} |
| API-030 | GET | /api/v1/alarm-workflow/companies/{companyId}/dashboard/report/{format} |
| API-031 | GET | /api/v1/alarm-workflow/companies/{companyId}/feeds/{id}/alarms/{key}/history |
| API-032 | POST | /api/v1/alarm-workflow/companies/{companyId}/feeds/{id}/alarms/{key}/events |
| API-033 | GET | /api/v1/alarm-workflow/companies/{companyId}/automation |
| API-034 | PUT | /api/v1/alarm-workflow/companies/{companyId}/automation |
| API-035 | GET | /api/v1/alarm-workflow/companies/{companyId}/status |
| API-036 | GET | /api/v1/alarm-workflow/companies/{companyId}/deliveries |
| API-037 | POST | /api/v1/alarm-workflow/companies/{companyId}/deliveries/{id}/retry |
| API-038 | POST | /api/v1/alarm-workflow/companies/{companyId}/deliveries/{id}/reconcile |
| API-039 | GET | /api/v1/alarm-workflow/companies/{companyId}/deliveries/{id}/capture |
| API-040 | POST | /api/v1/auth/register |
| API-041 | POST | /api/v1/auth/login |
| API-042 | POST | /api/v1/auth/logout |
| API-043 | GET | /api/v1/companies |
| API-044 | GET | /api/v1/companies/{id} |
| API-045 | POST | /api/v1/companies |
| API-046 | PUT | /api/v1/companies/{id} |
| API-047 | PATCH | /api/v1/companies/{id}/block |
| API-048 | GET | /api/v1/users |
| API-049 | POST | /api/v1/users |
| API-050 | POST | /api/v1/ingestion/kpi |
| API-051 | GET | /api/v1/ingestion/runs |
| API-052 | GET | /api/v1/ingestion/runs/{id} |
| API-053 | POST | /api/v1/ingestion/runs/{id}/retry |
| API-054 | POST | /api/v1/upload/upload |
| API-055 | POST | /api/v1/upload/kpi |
| API-056 | GET | /api/v1/upload/jobs/{jobId} |
| API-057 | GET | /api/v1/upload/jobs |
| API-058 | POST | /api/v1/upload/jobs/{jobId}/retry |
| API-059 | POST | /api/v1/upload/kpi/preview |
| API-060 | POST | /api/v1/upload/site |
| API-061 | POST | /api/v1/upload/alarm |
| API-062 | GET | /api/v1/upload/uploads |
| API-063 | DELETE | /api/v1/upload/uploads/{id} |
| API-064 | GET | /api/v1/upload/network-data |
| API-065 | GET | /api/v1/upload/uploads/history |
| API-066 | GET | /api/v1/upload/history |
| API-067 | GET | /api/v1/upload/site-data |
| API-068 | GET | /api/v1/upload/alarm-data |
| API-069 | GET | /api/v1/dashboard/stats |
| API-070 | GET | /api/v1/dashboard/stats/cells |
| API-071 | GET | /api/v1/dashboard/stats/sites |
| API-072 | GET | /api/v1/dashboard/stats/bands |
| API-073 | GET | /api/v1/dashboard/stats/tech |
| API-074 | GET | /api/v1/dashboard/stats/sectors |
| API-075 | GET | /api/v1/dashboard/stats/groups |
| API-076 | GET | /api/v1/dashboard/stats/detailed |
| API-077 | GET | /api/v1/dashboard/performance |
| API-078 | GET | /api/v1/alarms/rules |
| API-079 | POST | /api/v1/alarms/rules |
| API-080 | PUT | /api/v1/alarms/rules/{id} |
| API-081 | DELETE | /api/v1/alarms/rules/{id} |
| API-082 | POST | /api/v1/alarms/generate |
| API-083 | GET | /api/v1/alarms/summary |
| API-084 | GET | /api/v1/alarms |
| API-085 | POST | /api/v1/alarms/{id}/acknowledge |
| API-086 | POST | /api/v1/alarms/{id}/resolve |
| API-087 | GET | /api/v1/kpi/available-metrics |
| API-088 | GET | /api/v1/kpi/all |
| API-089 | GET | /api/v1/kpi/ul-prb-utilization |
| API-090 | GET | /api/v1/kpi/dl-prb-utilization |
| API-091 | GET | /api/v1/kpi/data-volume |
| API-092 | GET | /api/v1/kpi/ul-throughput |
| API-093 | GET | /api/v1/kpi/dl-throughput |
| API-094 | GET | /api/v1/kpi/erab-drop-rate |
| API-095 | GET | /api/v1/kpi/erab-success-rate |
| API-096 | GET | /api/v1/kpi/rrc-success-rate |
| API-097 | GET | /api/v1/kpi/mean-rrc-users |
| API-098 | GET | /api/v1/kpi/max-rrc-users |
| API-099 | GET | /api/v1/kpi/erab-setup-rate |
| API-100 | GET | /api/v1/kpi/rrc-drop-rate |
| API-101 | GET | /api/v1/kpi/volte-cssr |
| API-102 | GET | /api/v1/kpi/volte-dcr |
| API-103 | GET | /api/v1/kpi/inter-freq-hosr |
| API-104 | GET | /api/v1/kpi/intra-freq-hosr |
| API-105 | GET | /api/v1/kpi/csfb-success-rate |
| API-106 | GET | /api/v1/kpi/dynamic/metrics |
| API-107 | GET | /api/v1/kpi/dynamic/summary |
| API-108 | GET | /api/v1/kpi/dynamic/columns |
| API-109 | GET | /api/v1/kpi/dynamic/data |
| API-110 | GET | /api/v1/kpi/dynamic/batch-data |
| API-111 | POST | /api/v1/kpi/dynamic/selection |
| API-112 | POST | /api/v1/kpi/dynamic/bad-days |
| API-113 | POST | /api/v1/kpi/dynamic/export |
| API-114 | GET | /api/v1/threshold-rules |
| API-115 | POST | /api/v1/threshold-rules |
| API-116 | PUT | /api/v1/threshold-rules/{id} |
| API-117 | DELETE | /api/v1/threshold-rules/{id} |
| API-118 | GET | /api/v1/threshold-rules/evaluate |
| API-119 | GET | /api/v1/threshold-rules/evaluate-file |
| API-120 | POST | /api/v1/threshold-rules/ensure-file |
| API-121 | GET | /api/v1/threshold-rules/results |
| API-122 | GET | /api/v1/threshold-rules/results/{fileId} |
| API-123 | GET | /api/v1/kpi/validation-report/{fileId} |
| API-124 | GET | /api/v1/setting/getsettings |
| API-125 | POST | /api/v1/setting/updateSetting |
| API-126 | POST | /api/v1/setting/thresholds/export |
| API-127 | POST | /api/v1/setting/thresholds/import |
| API-128 | GET | /api/v1/kpi/worst-cells |
| API-129 | GET | /api/v1/kpi/worst-sites |
| API-130 | GET | /api/v1/kpi/worst-cells/detail |
| API-131 | GET | /api/v1/licenses |
| API-132 | GET | /api/v1/licenses/{id} |
| API-133 | POST | /api/v1/licenses |
| API-134 | PATCH | /api/v1/licenses/{id}/renew |
| API-135 | PATCH | /api/v1/licenses/{id}/block |
| API-136 | GET | /api/v1/licenses/{id}/download |
| API-137 | POST | /api/v1/local-license/upload |
| API-138 | GET | /api/v1/local-license/status |
| API-139 | GET | /api/v1/local-license/device-id |
| API-140 | GET | /api/v1/nightly/profile |
| API-141 | PUT | /api/v1/nightly/profile |
| API-142 | POST | /api/v1/nightly/runs |
| API-143 | GET | /api/v1/nightly/runs |
| API-144 | GET | /api/v1/nightly/runs/{id} |
| API-145 | POST | /api/v1/nightly/runs/{id}/retry |
| API-146 | GET | /api/v1/health |
| API-147 | POST | /api/v1/kpi/recommendation/jobs |
| API-148 | GET | /api/v1/kpi/recommendation/jobs/{jobId} |
| API-149 | POST | /api/v1/kpi/recommendation/run |
| API-150 | POST | /api/v1/kpi/recommendation/export |
| API-151 | GET | /api/v1/kpi/recommendation/presets |
| API-152 | POST | /api/v1/kpi/recommendation/presets |
| API-153 | GET | /api/v1/kpi/recommendation/presets/{name} |
| API-154 | POST | /api/v1/rca-exports |
| API-155 | GET | /api/v1/rca-exports/{id} |
| API-156 | GET | /api/v1/rca-exports/{id}/download |
| API-157 | POST | /api/v1/reports |
| API-158 | GET | /api/v1/reports |
| API-159 | GET | /api/v1/reports/{id} |
| API-160 | POST | /api/v1/reports/{id}/retry |
| API-161 | GET | /api/v1/reports/{id}/download/{format} |
| API-162 | PUT | /api/v1/daily-analysis/profile |
| API-163 | GET | /api/v1/daily-analysis/profile |
| API-164 | POST | /api/v1/daily-analysis/snapshots |
| API-165 | GET | /api/v1/daily-analysis/snapshots |
| API-166 | GET | /api/v1/daily-analysis/snapshots/{id} |
| API-167 | GET | /api/v1/daily-analysis/snapshots/{id}/comparisons |
| API-168 | POST | /api/v1/daily-analysis/snapshots/{id}/retry |
| API-169 | GET | /api/v1/report-datasets |
| API-170 | PUT | /api/v1/report-mail/profile |
| API-171 | GET | /api/v1/report-mail/profile |
| API-172 | POST | /api/v1/report-mail/deliveries |
| API-173 | GET | /api/v1/report-mail/deliveries |
| API-174 | GET | /api/v1/report-mail/deliveries/{id} |
| API-175 | POST | /api/v1/report-mail/deliveries/{id}/retry |
| API-176 | POST | /api/v1/report-mail/deliveries/{id}/cancel |
| API-177 | POST | /api/v1/report-mail/deliveries/{id}/reconcile |
| API-178 | GET | /api/v1/report-mail/deliveries/{id}/message.eml |
| API-179 | GET | /api/v1/pci/analysis |
| API-180 | GET | /api/v1/sites/summary |
| API-181 | GET | /api/v1/sites/worst |
| API-182 | GET | /api/v1/sites/details |
| API-183 | GET | /api/v1/sites/intelligence |
| API-184 | GET | /api/v1/site-prediction/recommendations |
| API-185 | GET | /api/v1/site-prediction/summary |
| API-186 | POST | /api/v1/site-prediction/lb-wcf |

## 5. Native OSS master data

Tenant-owned zones, circles, exact aliases and source profiles; version checks protect updates.

> Codes/source keys are immutable; PUT sends the full editable representation and current version. New feeds are inactive. Exact aliases do not imply automatic circle equivalence.

### API-001 | GET /api/v1/alarm-config/companies/{companyId}/zones

Zones. Module: Native OSS master data.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmConfigurationController.java:16 -> zones().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| afterId | query / long | No; default=0 | Keyset pagination: last returned row ID; zero starts from the beginning. |
| limit | query / int | No; default=100 | Requested page/result size; service-specific bounds apply. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/alarm-config/companies/100/zones
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic shape example; replace identifiers/values with this installation's real response data.

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "companyId": 100,
      "code": "WEST",
      "name": "Synthetic West",
      "active": true,
      "version": 1
    }
  ]
}
```

### API-002 | POST /api/v1/alarm-config/companies/{companyId}/zones

Create Zone. Module: Native OSS master data.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmConfigurationController.java:19 -> createZone().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| body | body / ZoneCreate | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/alarm-config/companies/100/zones
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "code": "WEST",
  "name": "Synthetic West"
}
```

Success response: HTTP 201. Synthetic shape example; replace identifiers/values with this installation's real response data.

```json
{
  "success": true,
  "data": {
    "id": 1,
    "companyId": 100,
    "code": "WEST",
    "name": "Synthetic West",
    "active": true,
    "version": 1
  }
}
```

### API-003 | PUT /api/v1/alarm-config/companies/{companyId}/zones/{id}

Update Zone. Module: Native OSS master data.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmConfigurationController.java:23 -> updateZone().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |
| body | body / ZoneUpdate | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
PUT http://localhost:3001/api/v1/alarm-config/companies/100/zones/1
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "name": "Synthetic West",
  "active": true,
  "version": 1
}
```

Success response: HTTP 200. Synthetic shape example; replace identifiers/values with this installation's real response data.

```json
{
  "success": true,
  "data": {
    "id": 1,
    "companyId": 100,
    "code": "WEST",
    "name": "Synthetic West",
    "active": true,
    "version": 2
  }
}
```

### API-004 | GET /api/v1/alarm-config/companies/{companyId}/circles

Circles. Module: Native OSS master data.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmConfigurationController.java:27 -> circles().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| afterId | query / long | No; default=0 | Keyset pagination: last returned row ID; zero starts from the beginning. |
| limit | query / int | No; default=100 | Requested page/result size; service-specific bounds apply. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/alarm-config/companies/100/circles
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic shape example; replace identifiers/values with this installation's real response data.

```json
{
  "success": true,
  "data": [
    {
      "id": 10,
      "companyId": 100,
      "zoneId": 1,
      "code": "MU",
      "name": "Synthetic Circle",
      "active": true,
      "version": 1
    }
  ]
}
```

### API-005 | POST /api/v1/alarm-config/companies/{companyId}/circles

Create Circle. Module: Native OSS master data.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmConfigurationController.java:30 -> createCircle().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| body | body / CircleCreate | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/alarm-config/companies/100/circles
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "zoneId": 1,
  "code": "MU",
  "name": "Synthetic Circle"
}
```

Success response: HTTP 201. Synthetic shape example; replace identifiers/values with this installation's real response data.

```json
{
  "success": true,
  "data": {
    "id": 10,
    "companyId": 100,
    "zoneId": 1,
    "code": "MU",
    "name": "Synthetic Circle",
    "active": true,
    "version": 1
  }
}
```

### API-006 | PUT /api/v1/alarm-config/companies/{companyId}/circles/{id}

Update Circle. Module: Native OSS master data.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmConfigurationController.java:34 -> updateCircle().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |
| body | body / CircleUpdate | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
PUT http://localhost:3001/api/v1/alarm-config/companies/100/circles/1
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "zoneId": 1,
  "name": "Synthetic Circle",
  "active": true,
  "version": 1
}
```

Success response: HTTP 200. Synthetic shape example; replace identifiers/values with this installation's real response data.

```json
{
  "success": true,
  "data": {
    "id": 10,
    "companyId": 100,
    "zoneId": 1,
    "code": "MU",
    "name": "Synthetic Circle",
    "active": true,
    "version": 2
  }
}
```

### API-007 | GET /api/v1/alarm-config/companies/{companyId}/circle-aliases

Aliases. Module: Native OSS master data.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmConfigurationController.java:38 -> aliases().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| afterId | query / long | No; default=0 | Keyset pagination: last returned row ID; zero starts from the beginning. |
| limit | query / int | No; default=100 | Requested page/result size; service-specific bounds apply. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/alarm-config/companies/100/circle-aliases
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic shape example; replace identifiers/values with this installation's real response data.

```json
{
  "success": true,
  "data": [
    {
      "id": 2,
      "companyId": 100,
      "circleId": 10,
      "alias": "SYNTHETIC-ALIAS",
      "canonical": false
    }
  ]
}
```

### API-008 | POST /api/v1/alarm-config/companies/{companyId}/circle-aliases

Create Alias. Module: Native OSS master data.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmConfigurationController.java:41 -> createAlias().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| body | body / AliasCreate | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/alarm-config/companies/100/circle-aliases
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "circleId": 10,
  "alias": "SYNTHETIC-ALIAS"
}
```

Success response: HTTP 201. Synthetic shape example; replace identifiers/values with this installation's real response data.

```json
{
  "success": true,
  "data": {
    "id": 2,
    "companyId": 100,
    "circleId": 10,
    "alias": "SYNTHETIC-ALIAS",
    "canonical": false
  }
}
```

### API-009 | DELETE /api/v1/alarm-config/companies/{companyId}/circle-aliases/{id}

Delete Alias. Module: Native OSS master data.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmConfigurationController.java:45 -> deleteAlias().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
DELETE http://localhost:3001/api/v1/alarm-config/companies/100/circle-aliases/1
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 204. No response body.

### API-010 | GET /api/v1/alarm-config/companies/{companyId}/circle-resolution

Resolve a raw circle alias to MAPPED, UNMAPPED or INACTIVE within one company.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmConfigurationController.java:49 -> resolve().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| value | query / String | Yes | Circle alias for resolution, or numeric KPI value for threshold evaluation, as indicated by this API. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/alarm-config/companies/100/circle-resolution?value=MU
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic shape example; replace identifiers/values with this installation's real response data.

```json
{
  "success": true,
  "data": {
    "input": "MU",
    "status": "MAPPED",
    "circle": {
      "id": 10,
      "companyId": 100,
      "zoneId": 1,
      "code": "MU",
      "name": "Synthetic Circle",
      "active": true,
      "version": 1
    }
  }
}
```

### API-011 | GET /api/v1/alarm-config/companies/{companyId}/feeds

Feeds. Module: Native OSS master data.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmConfigurationController.java:51 -> feeds().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| afterId | query / long | No; default=0 | Keyset pagination: last returned row ID; zero starts from the beginning. |
| limit | query / int | No; default=100 | Requested page/result size; service-specific bounds apply. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/alarm-config/companies/100/feeds
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic shape example; replace identifiers/values with this installation's real response data.

```json
{
  "success": true,
  "data": [
    {
      "id": 20,
      "companyId": 100,
      "sourceKey": "SYNTHETIC-4G",
      "name": "Synthetic Nokia 4G",
      "oem": "NOKIA",
      "technology": "4G",
      "fileFormat": "CSV",
      "sheet": null,
      "sourceTimezone": "Asia/Kolkata",
      "dumpType": "SNAPSHOT",
      "circleId": 10,
      "active": false,
      "version": 1
    }
  ]
}
```

### API-012 | GET /api/v1/alarm-config/companies/{companyId}/feeds/{id}

Feed. Module: Native OSS master data.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmConfigurationController.java:54 -> feed().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/alarm-config/companies/100/feeds/1
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic shape example; replace identifiers/values with this installation's real response data.

```json
{
  "success": true,
  "data": {
    "id": 20,
    "companyId": 100,
    "sourceKey": "SYNTHETIC-4G",
    "name": "Synthetic Nokia 4G",
    "oem": "NOKIA",
    "technology": "4G",
    "fileFormat": "CSV",
    "sheet": null,
    "sourceTimezone": "Asia/Kolkata",
    "dumpType": "SNAPSHOT",
    "circleId": 10,
    "active": false,
    "version": 1
  }
}
```

### API-013 | POST /api/v1/alarm-config/companies/{companyId}/feeds

Create Feed. Module: Native OSS master data.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmConfigurationController.java:56 -> createFeed().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| body | body / FeedCreate | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/alarm-config/companies/100/feeds
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "sourceKey": "SYNTHETIC-4G",
  "name": "Synthetic Nokia 4G",
  "oem": "NOKIA",
  "technology": "4G",
  "fileFormat": "CSV",
  "sheet": null,
  "sourceTimezone": "Asia/Kolkata",
  "dumpType": "SNAPSHOT",
  "circleId": 10
}
```

Success response: HTTP 201. Synthetic shape example; replace identifiers/values with this installation's real response data.

```json
{
  "success": true,
  "data": {
    "id": 20,
    "companyId": 100,
    "sourceKey": "SYNTHETIC-4G",
    "name": "Synthetic Nokia 4G",
    "oem": "NOKIA",
    "technology": "4G",
    "fileFormat": "CSV",
    "sheet": null,
    "sourceTimezone": "Asia/Kolkata",
    "dumpType": "SNAPSHOT",
    "circleId": 10,
    "active": false,
    "version": 1
  }
}
```

### API-014 | PUT /api/v1/alarm-config/companies/{companyId}/feeds/{id}

Update Feed. Module: Native OSS master data.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmConfigurationController.java:60 -> updateFeed().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |
| body | body / FeedUpdate | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
PUT http://localhost:3001/api/v1/alarm-config/companies/100/feeds/1
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "name": "Synthetic Nokia 4G",
  "oem": "NOKIA",
  "technology": "4G",
  "fileFormat": "CSV",
  "sheet": null,
  "sourceTimezone": "Asia/Kolkata",
  "dumpType": "SNAPSHOT",
  "circleId": 10,
  "active": true,
  "version": 1
}
```

Success response: HTTP 200. Synthetic shape example; replace identifiers/values with this installation's real response data.

```json
{
  "success": true,
  "data": {
    "id": 20,
    "companyId": 100,
    "sourceKey": "SYNTHETIC-4G",
    "name": "Synthetic Nokia 4G",
    "oem": "NOKIA",
    "technology": "4G",
    "fileFormat": "CSV",
    "sheet": null,
    "sourceTimezone": "Asia/Kolkata",
    "dumpType": "SNAPSHOT",
    "circleId": 10,
    "active": true,
    "version": 2
  }
}
```

## 6. Native OSS preview

Read-only bounded CSV/XLSX validation. Does not import alarms or clear missing records.

> sourceTimezone is mandatory. XLSX requires an exact raw sheet; CSV must not specify one. Maximum 10 MiB/5000 rows/80 columns; response sample is at most 50 valid rows and 100 issues. This is read-only.

### API-015 | POST /api/v1/alarm-ingestion/preview

Preview. Module: Native OSS preview.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmImportPreviewController.java:21 -> preview().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Legacy requireSuperAdmin check (blank legacy roles are treated as super admin by that helper).

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| file | form / MultipartFile | Yes | Binary upload; use multipart/form-data, never a JSON filesystem path. |
| sheet | form / String | No | Exact raw XLSX worksheet name; do not merge summary and raw sheets. |
| oem | form / String | No | See the module contract and controller/service validation for accepted values. |
| technology | form / String | No | See the module contract and controller/service validation for accepted values. |
| sourceTimezone | form / String | Yes | Explicit Java ZoneId for source local timestamps, e.g. Asia/Kolkata. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/alarm-ingestion/preview
```

Request: multipart/form-data. These are form fields, not a JSON request body:

| Form field | Illustrative value |
| --- | --- |
| file | <select a local file> |
| sheet | Alarm Dump |
| oem | NOKIA |
| technology | 4G |
| sourceTimezone | Asia/Kolkata |

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "contractVersion": "alarm-preview-v1",
    "sheet": "CSV",
    "sourceTimezone": "Asia/Kolkata",
    "rowsRead": 2,
    "validRows": 1,
    "rejectedRows": 1,
    "missingSourceSeverity": 1,
    "missingAlarmCode": 1,
    "sample": [
      {
        "sourceRow": 2,
        "oem": "NOKIA",
        "technology": "4G",
        "circle": "MU",
        "resource": "SYNTHETIC-NODE",
        "name": "",
        "alarmCode": "",
        "alarmText": "Synthetic source alarm",
        "sourceSeverity": "",
        "customerClassification": "UNMAPPED",
        "raisedAt": "2026-11-15T00:00:00Z",
        "supplementaryInformation": ""
      }
    ],
    "issues": [
      {
        "sourceRow": 3,
        "field": "raisedAt",
        "message": "Use an ISO timestamp or yyyy-MM-dd/dd-MM-yyyy HH:mm:ss."
      }
    ],
    "issuesTruncated": false,
    "warnings": [
      "Read-only validation: no alarms imported or cleared."
    ]
  }
}
```

## 7. Native OSS snapshot workflow

Frozen-source ingestion, corrections, catalogue/site mapping, source comparisons/history, scoped reports, folder scheduling and delivery/escalation settings.

> Requires migrations 008/009 and a matching rebuilt backend; imports also require ALARM_WORKFLOW_ENABLED and an active feed. SNAPSHOT only: DELTA/OSS clearing is not implemented. Same-slot conflicts are 409. Unknown catalogue is UNMAPPED; unknown circle fails import. Native thresholds/severity are customer-defined, not inferred from source severity.

### API-016 | POST /api/v1/alarm-workflow/companies/{companyId}/feeds/{feedId}/imports

Submit. Module: Native OSS snapshot workflow.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmWorkflowController.java:25 -> submit().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| feedId | path / long | Yes | Native OSS source-feed profile ID. |
| snapshotAt | form / String | Yes | Source snapshot time as ISO timestamp with explicit offset, whole seconds; alarm workflow rejects future times beyond tolerance. |
| file | form / MultipartFile | Yes | Binary upload; use multipart/form-data, never a JSON filesystem path. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/alarm-workflow/companies/100/feeds/20/imports
```

Request: multipart/form-data. These are form fields, not a JSON request body:

| Form field | Illustrative value |
| --- | --- |
| snapshotAt | 2026-11-15T00:00:00Z |
| file | <select a local file> |

Success response: HTTP 202. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "id": 1,
    "feed_id": 20,
    "snapshot_at": "2026-11-15T00:00:00",
    "revision": 1,
    "supersedes_id": null,
    "status": "QUEUED",
    "attempts": 0,
    "message": null,
    "result_json": null
  }
}
```

### API-017 | GET /api/v1/alarm-workflow/companies/{companyId}/imports

Runs. Module: Native OSS snapshot workflow.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmWorkflowController.java:30 -> runs().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| limit | query / int | No; default=50 | Requested page/result size; service-specific bounds apply. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/alarm-workflow/companies/100/imports
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "feed_id": 20,
      "snapshot_at": "2026-11-15T00:00:00",
      "revision": 1,
      "supersedes_id": null,
      "status": "QUEUED",
      "attempts": 0,
      "message": null,
      "result_json": null
    }
  ]
}
```

### API-018 | GET /api/v1/alarm-workflow/companies/{companyId}/imports/{id}

Run. Module: Native OSS snapshot workflow.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmWorkflowController.java:32 -> run().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/alarm-workflow/companies/100/imports/1
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "id": 1,
    "feed_id": 20,
    "snapshot_at": "2026-11-15T00:00:00",
    "revision": 1,
    "supersedes_id": null,
    "status": "QUEUED",
    "attempts": 0,
    "message": null,
    "result_json": null
  }
}
```

### API-019 | POST /api/v1/alarm-workflow/companies/{companyId}/imports/{id}/retry

Requeue a FAILED run using exactly its staged source and frozen configuration.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmWorkflowController.java:33 -> retry().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/alarm-workflow/companies/100/imports/1/retry
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "id": 1,
    "feed_id": 20,
    "snapshot_at": "2026-11-15T00:00:00",
    "revision": 1,
    "supersedes_id": null,
    "status": "QUEUED",
    "attempts": 0,
    "message": null,
    "result_json": null
  }
}
```

### API-020 | POST /api/v1/alarm-workflow/companies/{companyId}/imports/{id}/replace

Create a corrected revision of the latest FAILED snapshot using a replacement file and current configuration; successful history cannot be overwritten.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmWorkflowController.java:34 -> replace().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |
| file | form / MultipartFile | Yes | Binary upload; use multipart/form-data, never a JSON filesystem path. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/alarm-workflow/companies/100/imports/1/replace
```

Request: multipart/form-data. These are form fields, not a JSON request body:

| Form field | Illustrative value |
| --- | --- |
| file | <select a local file> |

Success response: HTTP 202. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "id": 1,
    "feed_id": 20,
    "snapshot_at": "2026-11-15T00:00:00",
    "revision": 1,
    "supersedes_id": null,
    "status": "QUEUED",
    "attempts": 0,
    "message": null,
    "result_json": null
  }
}
```

### API-021 | GET /api/v1/alarm-workflow/companies/{companyId}/imports/{id}/observations

Observations. Module: Native OSS snapshot workflow.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmWorkflowController.java:35 -> observations().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |
| afterId | query / long | No; default=0 | Keyset pagination: last returned row ID; zero starts from the beginning. |
| limit | query / int | No; default=100 | Requested page/result size; service-specific bounds apply. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/alarm-workflow/companies/100/imports/1/observations
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "identity_key": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "circle_id": 10,
      "circle_name": "Synthetic Circle",
      "resource_name": "PLMN/MRBTS-SYNTHETIC",
      "source_severity": "Major",
      "classification": "UNMAPPED",
      "included": null,
      "raised_at": "2026-11-15T00:00:00"
    }
  ]
}
```

### API-022 | GET /api/v1/alarm-workflow/companies/{companyId}/dashboard

Dashboard. Module: Native OSS snapshot workflow.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmWorkflowController.java:37 -> dashboard().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| asOf | query / String | No | ISO timestamp with explicit offset; select snapshots at/before this time. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/alarm-workflow/companies/100/dashboard
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "asOf": "2026-11-16T00:00:00Z",
    "sources": [],
    "circles": [],
    "configuredActiveFeeds": 0,
    "reportSummary": {
      "contractVersion": "alarm-summary-v1",
      "zones": [],
      "circles": [],
      "observedRecords": 0,
      "includedRecords": 0,
      "unmappedCatalogue": 0,
      "knownDownSites": 0,
      "pendingOperationalInputs": [
        "MS2 pending 2G/4G",
        "LR pending status/count"
      ],
      "notes": [
        "Counts are source observations; missing observations do not confirm clearance."
      ]
    },
    "warning": "Inspect source timestamps; missing alarms are not confirmed cleared."
  }
}
```

### API-023 | GET /api/v1/alarm-workflow/companies/{companyId}/catalogue

Catalog. Module: Native OSS snapshot workflow.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmWorkflowController.java:39 -> catalog().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/alarm-workflow/companies/100/catalogue
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": []
}
```

### API-024 | POST /api/v1/alarm-workflow/companies/{companyId}/catalogue

Catalog. Module: Native OSS snapshot workflow.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmWorkflowController.java:40 -> catalog().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| rule | body / AlarmModels.Catalog | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/alarm-workflow/companies/100/catalogue
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "oem": "NOKIA",
  "technology": "4G",
  "matchField": "CODE",
  "matchValue": "SYNTHETIC-100",
  "classification": "CRITICAL",
  "included": true,
  "serviceAffecting": true,
  "siteDown": false
}
```

Success response: HTTP 201. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "id": 1,
    "oem": "NOKIA",
    "technology": "4G",
    "matchField": "CODE",
    "matchValue": "SYNTHETIC-100",
    "classification": "CRITICAL",
    "included": true,
    "serviceAffecting": true,
    "siteDown": false
  }
}
```

### API-025 | DELETE /api/v1/alarm-workflow/companies/{companyId}/catalogue/{id}

Remove Catalog. Module: Native OSS snapshot workflow.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmWorkflowController.java:41 -> removeCatalog().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
DELETE http://localhost:3001/api/v1/alarm-workflow/companies/100/catalogue/1
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 204. No response body.

### API-026 | GET /api/v1/alarm-workflow/companies/{companyId}/feeds/{id}/sites

Sites. Module: Native OSS snapshot workflow.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmWorkflowController.java:42 -> sites().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/alarm-workflow/companies/100/feeds/1/sites
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": []
}
```

### API-027 | POST /api/v1/alarm-workflow/companies/{companyId}/feeds/{id}/sites

Site. Module: Native OSS snapshot workflow.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmWorkflowController.java:43 -> site().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |
| site | body / AlarmModels.Site | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/alarm-workflow/companies/100/feeds/1/sites
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "circleId": 10,
  "objectKey": "PLMN/MRBTS-SYNTHETIC",
  "siteCode": "SYNTHETIC-SITE"
}
```

Success response: HTTP 201. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "id": 1,
    "circleId": 10,
    "objectKey": "PLMN/MRBTS-SYNTHETIC",
    "siteCode": "SYNTHETIC-SITE"
  }
}
```

### API-028 | DELETE /api/v1/alarm-workflow/companies/{companyId}/feeds/{id}/sites/{siteId}

Remove Site. Module: Native OSS snapshot workflow.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmWorkflowController.java:44 -> removeSite().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |
| siteId | path / long | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
DELETE http://localhost:3001/api/v1/alarm-workflow/companies/100/feeds/1/sites/30
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 204. No response body.

### API-029 | GET /api/v1/alarm-workflow/companies/{companyId}/imports/{id}/report/{format}

Report. Module: Native OSS snapshot workflow.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmWorkflowController.java:45 -> report().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |
| format | path / String | Yes | Artifact format varies by module: OSS pdf/csv; common reports pdf/xlsx/json/configuration. |
| circleId | query / Long | No | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/alarm-workflow/companies/100/imports/1/report/pdf
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Binary/artifact response with Content-Type and Content-Disposition; save bytes rather than parsing JSON. Common JSON/configuration downloads are files too.

### API-030 | GET /api/v1/alarm-workflow/companies/{companyId}/dashboard/report/{format}

Download national/circle PDF or CSV for the active-feed cohort at the requested asOf time.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmWorkflowController.java:50 -> national().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| format | path / String | Yes | Artifact format varies by module: OSS pdf/csv; common reports pdf/xlsx/json/configuration. |
| asOf | query / String | Yes | ISO timestamp with explicit offset; select snapshots at/before this time. |
| circleId | query / Long | No | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/alarm-workflow/companies/100/dashboard/report/pdf?asOf=2026-11-16T00%3A00%3A00Z
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Binary/artifact response with Content-Type and Content-Disposition; save bytes rather than parsing JSON. Common JSON/configuration downloads are files too.

### API-031 | GET /api/v1/alarm-workflow/companies/{companyId}/feeds/{id}/alarms/{key}/history

History. Module: Native OSS snapshot workflow.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmWorkflowController.java:54 -> history().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |
| key | path / String | Yes | 64-character SHA-256 provisional OSS alarm identity from an observation response. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/alarm-workflow/companies/100/feeds/1/alarms/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/history
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "snapshots": [],
    "operatorEvents": [],
    "warning": "Operator actions are distinct from OSS state. Absence from a dump is not confirmed clearance."
  }
}
```

### API-032 | POST /api/v1/alarm-workflow/companies/{companyId}/feeds/{id}/alarms/{key}/events

Record a NOTE/ACKNOWLEDGED/RESOLVED operator action without inventing an OSS state transition.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmWorkflowController.java:55 -> event().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |
| key | path / String | Yes | 64-character SHA-256 provisional OSS alarm identity from an observation response. |
| body | body / Map<String,String> | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/alarm-workflow/companies/100/feeds/1/alarms/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/events
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "action": "NOTE",
  "note": "Replace with the actual operator review evidence"
}
```

Success response: HTTP 200. Synthetic shape example; replace identifiers/values with this installation's real response data.

```json
{
  "success": true,
  "data": {
    "recorded": true
  }
}
```

### API-033 | GET /api/v1/alarm-workflow/companies/{companyId}/automation

Settings. Module: Native OSS snapshot workflow.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmWorkflowController.java:56 -> settings().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/alarm-workflow/companies/100/automation
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic shape example; replace identifiers/values with this installation's real response data.

```json
{
  "success": true,
  "data": {
    "version": 0,
    "enabled": false,
    "freshnessMinutes": 60,
    "reports": [],
    "escalations": [],
    "watches": []
  }
}
```

### API-034 | PUT /api/v1/alarm-workflow/companies/{companyId}/automation

Settings. Module: Native OSS snapshot workflow.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmWorkflowController.java:57 -> settings().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| body | body / AlarmAutomation.Settings | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
PUT http://localhost:3001/api/v1/alarm-workflow/companies/100/automation
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "version": 0,
  "enabled": false,
  "freshnessMinutes": 60,
  "reports": [
    {
      "key": "national-preview",
      "circleId": null,
      "recipients": [
        "reviewer@example.invalid"
      ],
      "mode": "CAPTURE"
    }
  ],
  "escalations": [],
  "watches": []
}
```

Success response: HTTP 200. Synthetic shape example; replace identifiers/values with this installation's real response data.

```json
{
  "success": true,
  "data": {
    "version": 1,
    "enabled": false,
    "freshnessMinutes": 60,
    "reports": [
      {
        "key": "national-preview",
        "circleId": null,
        "recipients": [
          "reviewer@example.invalid"
        ],
        "mode": "CAPTURE"
      }
    ],
    "escalations": [],
    "watches": []
  }
}
```

### API-035 | GET /api/v1/alarm-workflow/companies/{companyId}/status

Status. Module: Native OSS snapshot workflow.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmWorkflowController.java:58 -> status().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/alarm-workflow/companies/100/status
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic shape example; replace identifiers/values with this installation's real response data.

```json
{
  "success": true,
  "data": {
    "workflowEnabled": false,
    "inputRootConfigured": false,
    "smtpEnabled": false,
    "folderStatus": [],
    "imports": [],
    "deliveries": []
  }
}
```

### API-036 | GET /api/v1/alarm-workflow/companies/{companyId}/deliveries

Deliveries. Module: Native OSS snapshot workflow.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmWorkflowController.java:59 -> deliveries().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/alarm-workflow/companies/100/deliveries
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": []
}
```

### API-037 | POST /api/v1/alarm-workflow/companies/{companyId}/deliveries/{id}/retry

Retry Delivery. Module: Native OSS snapshot workflow.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmWorkflowController.java:60 -> retryDelivery().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/alarm-workflow/companies/100/deliveries/1/retry
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic shape example; replace identifiers/values with this installation's real response data.

```json
{
  "success": true,
  "data": {
    "queued": true
  }
}
```

### API-038 | POST /api/v1/alarm-workflow/companies/{companyId}/deliveries/{id}/reconcile

Resolve an UNKNOWN send result using confirmed provider evidence.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmWorkflowController.java:61 -> reconcile().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |
| body | body / Map<String,String> | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/alarm-workflow/companies/100/deliveries/1/reconcile
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "outcome": "CONFIRMED_NOT_SENT",
  "note": "Replace with actual provider reconciliation evidence"
}
```

Success response: HTTP 200. Synthetic shape example; replace identifiers/values with this installation's real response data.

```json
{
  "success": true,
  "data": {
    "recorded": true
  }
}
```

### API-039 | GET /api/v1/alarm-workflow/companies/{companyId}/deliveries/{id}/capture

Capture. Module: Native OSS snapshot workflow.

Declaration: current source only; not listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/alarm/api/AlarmWorkflowController.java:62 -> capture().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Strict persisted user/company check: USER reads own company; ADMIN writes own company; SUPER_ADMIN selects an explicit company. Blank/blocked/unassigned access fails closed.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| companyId | path / long | Yes | Explicit persisted company/tenant ID; does not grant access by itself. |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/alarm-workflow/companies/100/deliveries/1/capture
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Binary/artifact response with Content-Type and Content-Disposition; save bytes rather than parsing JSON. Common JSON/configuration downloads are files too.

## 8. Authentication

Register, log in and invalidate a session token. The first registered account becomes SUPER_ADMIN; later self-registered accounts are USER.

### API-040 | POST /api/v1/auth/register

Register. Module: Authentication.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/identity/api/AuthController.java:35 -> register().

Other declared aliases: /auth/register

Access: JWT filter bypass; controller/service checks still apply. License-filter exempt. No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| body | body / Map<String, Object> | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/auth/register
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "email": "operator@example.invalid",
  "password": "REPLACE_WITH_A_TEST_PASSWORD"
}
```

Success response: HTTP 200. Synthetic shape example; replace identifiers/values with this installation's real response data.

```json
{
  "message": "User signed up successfully",
  "success": true,
  "data": {
    "token": "JWT_FROM_SUCCESSFUL_LOGIN",
    "user": {
      "id": 1,
      "email": "operator@example.invalid",
      "companyId": 100,
      "role": "USER",
      "status": "ACTIVE"
    }
  }
}
```

### API-041 | POST /api/v1/auth/login

Login. Module: Authentication.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/identity/api/AuthController.java:51 -> login().

Other declared aliases: /auth/login

Access: JWT filter bypass; controller/service checks still apply. License-filter exempt. No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| body | body / Map<String, Object> | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/auth/login
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "email": "operator@example.invalid",
  "password": "REPLACE_WITH_A_TEST_PASSWORD"
}
```

Success response: HTTP 200. Synthetic shape example; replace identifiers/values with this installation's real response data.

```json
{
  "message": "User logged in successfully",
  "success": true,
  "data": {
    "token": "JWT_FROM_SUCCESSFUL_LOGIN",
    "user": {
      "id": 1,
      "email": "operator@example.invalid",
      "companyId": 100,
      "role": "USER",
      "status": "ACTIVE"
    }
  }
}
```

### API-042 | POST /api/v1/auth/logout

Logout. Module: Authentication.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/identity/api/AuthController.java:65 -> logout().

Other declared aliases: /auth/logout

Access: JWT filter bypass; controller/service checks still apply. License-filter exempt. No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| body | body / Map<String, Object> | No | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/auth/logout
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{}
```

Success response: HTTP 200. Synthetic shape example; replace identifiers/values with this installation's real response data.

```json
{
  "message": "Logged out successfully"
}
```

## 9. Common KPI reports

Generate persisted common reports from daily snapshots, inspect status and download PDF/XLSX/JSON artifacts.

> Requires a ready daily snapshot. Optional modules: thresholds, alarmCandidates, worstCells, rca, prediction. RCA requires actual reviewed mapping/config. Artifact format is pdf/xlsx/json/configuration as supported by report storage.

### API-157 | POST /api/v1/reports

Create. Module: Common KPI reports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/reporting/api/CommonReportController.java:23 -> create().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Legacy SUPER_ADMIN mutation/profile check plus dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| snapshotId | query / long | Yes | Persisted daily-analysis snapshot ID, not fileId. |
| options | body / CommonReportModules.Options | No | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/reports?snapshotId=40
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "modules": [
    "thresholds",
    "alarmCandidates",
    "worstCells",
    "prediction"
  ],
  "rcaConfig": {}
}
```

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "id": 50,
    "snapshotId": 40,
    "fileId": 500,
    "status": "QUEUED"
  }
}
```

### API-158 | GET /api/v1/reports

List. Module: Common KPI reports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/reporting/api/CommonReportController.java:26 -> list().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| limit | query / int | No; default=20 | Requested page/result size; service-specific bounds apply. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/reports?fileId=500
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": []
}
```

### API-159 | GET /api/v1/reports/{id}

Get. Module: Common KPI reports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/reporting/api/CommonReportController.java:27 -> get().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/reports/1
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "id": 50,
    "snapshotId": 40,
    "fileId": 500,
    "status": "QUEUED"
  }
}
```

### API-160 | POST /api/v1/reports/{id}/retry

Retry. Module: Common KPI reports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/reporting/api/CommonReportController.java:28 -> retry().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Legacy SUPER_ADMIN mutation/profile check plus dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/reports/1/retry
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "id": 50,
    "snapshotId": 40,
    "fileId": 500,
    "status": "QUEUED"
  }
}
```

### API-161 | GET /api/v1/reports/{id}/download/{format}

Download. Module: Common KPI reports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/reporting/api/CommonReportController.java:29 -> download().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |
| format | path / String | Yes | Artifact format varies by module: OSS pdf/csv; common reports pdf/xlsx/json/configuration. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/reports/1/download/pdf
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Binary/artifact response with Content-Type and Content-Disposition; save bytes rather than parsing JSON. Common JSON/configuration downloads are files too.

## 10. Companies

Customer/tenant master records, contact details and blocking. Company is not a geographical circle.

> Service calls requireSuperAdmin. GET list is nevertheless in the legacy JWT-filter bypass set; a supplied Bearer header may not populate the security context on that path. Do not mistake filter bypass for successful administrator authorization.

### API-043 | GET /api/v1/companies

List Companies. Module: Companies.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/identity/api/CompanyController.java:26 -> listCompanies().

Other declared aliases: /companies

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. License-filter exempt. Legacy requireSuperAdmin check (blank legacy roles are treated as super admin by that helper).

Inputs: no declared client parameters or JSON body.

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/companies
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": [
    {
      "id": 100,
      "companyName": "Synthetic Customer",
      "contactName": "Demo Operator",
      "email": "operator@example.invalid",
      "phone": "",
      "address": "Synthetic address",
      "status": "ACTIVE"
    }
  ]
}
```

### API-044 | GET /api/v1/companies/{id}

Get Company. Module: Companies.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/identity/api/CompanyController.java:31 -> getCompany().

Other declared aliases: /companies/{id}

Access: Bearer JWT and active session required. License-filter exempt. Legacy requireSuperAdmin check (blank legacy roles are treated as super admin by that helper).

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / Long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/companies/1
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {
    "id": 100,
    "companyName": "Synthetic Customer",
    "contactName": "Demo Operator",
    "email": "operator@example.invalid",
    "phone": "",
    "address": "Synthetic address",
    "status": "ACTIVE"
  }
}
```

### API-045 | POST /api/v1/companies

Create Company. Module: Companies.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/identity/api/CompanyController.java:36 -> createCompany().

Other declared aliases: /companies

Access: Bearer JWT and active session required. License-filter exempt. Legacy requireSuperAdmin check (blank legacy roles are treated as super admin by that helper).

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| body | body / Map<String, Object> | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/companies
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "companyName": "Synthetic Customer",
  "contactName": "Demo Operator",
  "email": "operator@example.invalid",
  "phone": "",
  "address": "Synthetic address",
  "status": "ACTIVE"
}
```

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {
    "id": 100,
    "companyName": "Synthetic Customer",
    "contactName": "Demo Operator",
    "email": "operator@example.invalid",
    "phone": "",
    "address": "Synthetic address",
    "status": "ACTIVE"
  }
}
```

### API-046 | PUT /api/v1/companies/{id}

Update Company. Module: Companies.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/identity/api/CompanyController.java:41 -> updateCompany().

Other declared aliases: /companies/{id}

Access: Bearer JWT and active session required. License-filter exempt. Legacy requireSuperAdmin check (blank legacy roles are treated as super admin by that helper).

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / Long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |
| body | body / Map<String, Object> | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
PUT http://localhost:3001/api/v1/companies/1
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "companyName": "Synthetic Customer",
  "contactName": "Demo Operator",
  "email": "operator@example.invalid",
  "phone": "",
  "address": "Synthetic address",
  "status": "ACTIVE"
}
```

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {
    "id": 100,
    "companyName": "Synthetic Customer",
    "contactName": "Demo Operator",
    "email": "operator@example.invalid",
    "phone": "",
    "address": "Synthetic address",
    "status": "ACTIVE"
  }
}
```

### API-047 | PATCH /api/v1/companies/{id}/block

Block Company. Module: Companies.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/identity/api/CompanyController.java:46 -> blockCompany().

Other declared aliases: /companies/{id}/block

Access: Bearer JWT and active session required. License-filter exempt. Legacy requireSuperAdmin check (blank legacy roles are treated as super admin by that helper).

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / Long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
PATCH http://localhost:3001/api/v1/companies/1/block
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {
    "id": 100,
    "companyName": "Synthetic Customer",
    "contactName": "Demo Operator",
    "email": "operator@example.invalid",
    "phone": "",
    "address": "Synthetic address",
    "status": "ACTIVE"
  }
}
```

## 11. Daily KPI comparison

Configure aggregation, create daily snapshots and compare with observed prior-day/seven-day baselines.

> groupBy must contain cellName. Aggregations MEAN/SUM/RATIO and directions HIGHER_IS_BETTER/LOWER_IS_BETTER/NEUTRAL are explicit. RATIO requires numeratorKey/denominatorKey; percentages must not be summed. Missing baseline/sample information is reported explicitly.

### API-162 | PUT /api/v1/daily-analysis/profile

Configure. Module: Daily KPI comparison.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/reporting/api/DailyAnalysisController.java:26 -> configure().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Legacy SUPER_ADMIN mutation/profile check plus dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| profile | body / DailyKpiCalculator.Profile | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
PUT http://localhost:3001/api/v1/daily-analysis/profile?fileId=500
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "groupBy": [
    "cellName"
  ],
  "metrics": [
    {
      "key": "rrcsetup",
      "aggregation": "MEAN",
      "direction": "HIGHER_IS_BETTER",
      "unit": "PERCENT",
      "numeratorKey": null,
      "denominatorKey": null,
      "scale": null,
      "relativeChangeEnabled": false
    }
  ],
  "minimumBaselineDays": 7,
  "minimumSamplesPerDay": 1,
  "requireEqualSampleCounts": true
}
```

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "fileId": 500,
    "profileHash": "SERVER_PROFILE_HASH",
    "profile": {
      "groupBy": [
        "cellName"
      ],
      "metrics": [
        {
          "key": "rrcsetup",
          "aggregation": "MEAN",
          "direction": "HIGHER_IS_BETTER",
          "unit": "PERCENT",
          "numeratorKey": null,
          "denominatorKey": null,
          "scale": null,
          "relativeChangeEnabled": false
        }
      ],
      "minimumBaselineDays": 7,
      "minimumSamplesPerDay": 1,
      "requireEqualSampleCounts": true
    }
  }
}
```

### API-163 | GET /api/v1/daily-analysis/profile

Profile. Module: Daily KPI comparison.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/reporting/api/DailyAnalysisController.java:31 -> profile().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/daily-analysis/profile?fileId=500
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "fileId": 500,
    "status": "CONFIGURATION_REQUIRED"
  }
}
```

### API-164 | POST /api/v1/daily-analysis/snapshots

Build. Module: Daily KPI comparison.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/reporting/api/DailyAnalysisController.java:36 -> build().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Legacy SUPER_ADMIN mutation/profile check plus dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| date | query / String | No | Business/report date in YYYY-MM-DD, not a timestamp. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/daily-analysis/snapshots?fileId=500
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "id": 40,
    "fileId": 500,
    "status": "QUEUED"
  }
}
```

### API-165 | GET /api/v1/daily-analysis/snapshots

List. Module: Daily KPI comparison.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/reporting/api/DailyAnalysisController.java:45 -> list().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| limit | query / int | No; default=20 | Requested page/result size; service-specific bounds apply. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/daily-analysis/snapshots?fileId=500
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": []
}
```

### API-166 | GET /api/v1/daily-analysis/snapshots/{id}

Get. Module: Daily KPI comparison.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/reporting/api/DailyAnalysisController.java:50 -> get().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/daily-analysis/snapshots/1
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "id": 40,
    "fileId": 500,
    "status": "QUEUED"
  }
}
```

### API-167 | GET /api/v1/daily-analysis/snapshots/{id}/comparisons

Compare. Module: Daily KPI comparison.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/reporting/api/DailyAnalysisController.java:55 -> compare().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |
| page | query / int | No; default=1 | One-based page number. |
| limit | query / int | No; default=50 | Requested page/result size; service-specific bounds apply. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/daily-analysis/snapshots/1/comparisons
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": []
}
```

### API-168 | POST /api/v1/daily-analysis/snapshots/{id}/retry

Retry. Module: Daily KPI comparison.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/reporting/api/DailyAnalysisController.java:62 -> retry().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Legacy SUPER_ADMIN mutation/profile check plus dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/daily-analysis/snapshots/1/retry
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "id": 40,
    "fileId": 500,
    "status": "QUEUED"
  }
}
```

## 12. Dashboard

Network inventory counts, distinct dimensions and aggregated KPI performance.

> Only stats/performance methods accept explicit fileId/companyId here; distinct count endpoints have no such parameters. GET is in the legacy public-read JWT bypass, while the license guard still applies.

### API-069 | GET /api/v1/dashboard/stats

Get Dashboard Stats. Module: Dashboard.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/DashboardController.java:24 -> getDashboardStats().

Other declared aliases: /api/v1/dashboard/summary; /dashboard/stats; /dashboard/summary

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| companyId | query / Long | No | Explicit persisted company/tenant ID; does not grant access by itself. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/dashboard/stats
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "totalCells": 10,
    "totalSites": 4,
    "totalBands": 2,
    "totalTech": 1,
    "totalSectors": 10
  }
}
```

### API-070 | GET /api/v1/dashboard/stats/cells

Get Total Cells. Module: Dashboard.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/DashboardController.java:45 -> getTotalCells().

Other declared aliases: /dashboard/stats/cells

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

Inputs: no declared client parameters or JSON body.

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/dashboard/stats/cells
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "total": 0,
    "cells": []
  }
}
```

### API-071 | GET /api/v1/dashboard/stats/sites

Get Total Sites. Module: Dashboard.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/DashboardController.java:61 -> getTotalSites().

Other declared aliases: /dashboard/stats/sites

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

Inputs: no declared client parameters or JSON body.

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/dashboard/stats/sites
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "total": 0,
    "sites": []
  }
}
```

### API-072 | GET /api/v1/dashboard/stats/bands

Get Total Bands. Module: Dashboard.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/DashboardController.java:77 -> getTotalBands().

Other declared aliases: /dashboard/stats/bands

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

Inputs: no declared client parameters or JSON body.

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/dashboard/stats/bands
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "total": 0,
    "bands": []
  }
}
```

### API-073 | GET /api/v1/dashboard/stats/tech

Get Total Tech. Module: Dashboard.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/DashboardController.java:93 -> getTotalTech().

Other declared aliases: /dashboard/stats/tech

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

Inputs: no declared client parameters or JSON body.

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/dashboard/stats/tech
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "total": 0,
    "tech": []
  }
}
```

### API-074 | GET /api/v1/dashboard/stats/sectors

Get Total Sectors. Module: Dashboard.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/DashboardController.java:109 -> getTotalSectors().

Other declared aliases: /dashboard/stats/sectors

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

Inputs: no declared client parameters or JSON body.

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/dashboard/stats/sectors
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "total": 0,
    "sectors": []
  }
}
```

### API-075 | GET /api/v1/dashboard/stats/groups

Get Total Groups. Module: Dashboard.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/DashboardController.java:125 -> getTotalGroups().

Other declared aliases: /dashboard/stats/groups

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

Inputs: no declared client parameters or JSON body.

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/dashboard/stats/groups
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "total": 0,
    "groups": []
  }
}
```

### API-076 | GET /api/v1/dashboard/stats/detailed

Get Detailed Stats. Module: Dashboard.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/DashboardController.java:141 -> getDetailedStats().

Other declared aliases: /dashboard/stats/detailed

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

Inputs: no declared client parameters or JSON body.

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/dashboard/stats/detailed
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {}
}
```

### API-077 | GET /api/v1/dashboard/performance

Get Performance Metrics. Module: Dashboard.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/DashboardController.java:158 -> getPerformanceMetrics().

Other declared aliases: /api/v1/dashboard/stats/performance; /dashboard/performance; /dashboard/stats/performance

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| companyId | query / Long | No | Explicit persisted company/tenant ID; does not grant access by itself. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/dashboard/performance
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {}
}
```

## 13. Service health

Liveness of the Java process. UP does not establish database, scheduler, mail or source-file health.

### API-146 | GET /api/v1/health

Health. Module: Service health.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/platform/health/api/HealthController.java:15 -> health().

Other declared aliases: /health

Access: JWT filter bypass; controller/service checks still apply. License-filter exempt. No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

Inputs: no declared client parameters or JSON body.

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/health
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic shape example; replace identifiers/values with this installation's real response data.

```json
{
  "success": true,
  "data": {
    "status": "UP",
    "service": "network-monitoring-backend",
    "timestamp": "2026-11-16T00:00:00Z"
  }
}
```

## 14. KPI-generated alarms

Alarm rules, generation, summary, paging, acknowledgement and resolution based on KPI threshold evaluation. These are not raw OSS alarm observations.

> KPI-generated alarm state is distinct from native OSS observation/operator history. Controller-level role checks are not declared here; frontend admin visibility is not an API authorization guarantee.

### API-078 | GET /api/v1/alarms/rules

List Rules. Module: KPI-generated alarms.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiAlarmController.java:27 -> listRules().

Other declared aliases: /alarms/rules

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

Inputs: no declared client parameters or JSON body.

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/alarms/rules
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": []
}
```

### API-079 | POST /api/v1/alarms/rules

Create Rule. Module: KPI-generated alarms.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiAlarmController.java:32 -> createRule().

Other declared aliases: /alarms/rules

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| request | body / Map<String, Object> | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/alarms/rules
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "metricName": "RRC_Establishment_Success_Rate",
  "technology": "LTE",
  "severity": "CRITICAL",
  "messageTemplate": "Review the matched KPI threshold evidence",
  "recommendation": "Inspect the source counters and transport",
  "enabled": true
}
```

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-080 | PUT /api/v1/alarms/rules/{id}

Update Rule. Module: KPI-generated alarms.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiAlarmController.java:37 -> updateRule().

Other declared aliases: /alarms/rules/{id}

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / Long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |
| request | body / Map<String, Object> | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
PUT http://localhost:3001/api/v1/alarms/rules/1
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "metricName": "RRC_Establishment_Success_Rate",
  "technology": "LTE",
  "severity": "CRITICAL",
  "messageTemplate": "Review the matched KPI threshold evidence",
  "recommendation": "Inspect the source counters and transport",
  "enabled": true
}
```

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-081 | DELETE /api/v1/alarms/rules/{id}

Delete Rule. Module: KPI-generated alarms.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiAlarmController.java:42 -> deleteRule().

Other declared aliases: /alarms/rules/{id}

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / Long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
DELETE http://localhost:3001/api/v1/alarms/rules/1
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-082 | POST /api/v1/alarms/generate

Generate. Module: KPI-generated alarms.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiAlarmController.java:50 -> generate().

Other declared aliases: /alarms/generate

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/alarms/generate?fileId=500
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-083 | GET /api/v1/alarms/summary

Summary. Module: KPI-generated alarms.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiAlarmController.java:55 -> summary().

Other declared aliases: /alarms/summary

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/alarms/summary?fileId=500
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-084 | GET /api/v1/alarms

List Alarms. Module: KPI-generated alarms.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiAlarmController.java:60 -> listAlarms().

Other declared aliases: /alarms

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| severity | query / String | No | See the module contract and controller/service validation for accepted values. |
| status | query / String | No | See the module contract and controller/service validation for accepted values. |
| page | query / Integer | No | One-based page number. |
| limit | query / Integer | No | Requested page/result size; service-specific bounds apply. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/alarms?fileId=500
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": []
}
```

### API-085 | POST /api/v1/alarms/{id}/acknowledge

Acknowledge. Module: KPI-generated alarms.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiAlarmController.java:69 -> acknowledge().

Other declared aliases: /alarms/{id}/acknowledge

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / Long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |
| request | body / Map<String, Object> | No | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/alarms/1/acknowledge
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "user": "Demo reviewer",
  "remarks": "Replace with actual operator evidence"
}
```

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-086 | POST /api/v1/alarms/{id}/resolve

Resolve. Module: KPI-generated alarms.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiAlarmController.java:75 -> resolve().

Other declared aliases: /alarms/{id}/resolve

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / Long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |
| request | body / Map<String, Object> | No | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/alarms/1/resolve
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "user": "Demo reviewer",
  "remarks": "Replace with actual operator evidence"
}
```

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

## 15. KPI data and exports

Uploaded fixed-schema KPI samples, dynamic metric discovery/selection, statistics, bad-day analysis and Excel export.

> Discover actual dynamic metricKeys/filterKeys before selection. GET metricKey is declared optional but blank is rejected by getDynamicData. Legacy fixed-metric responses and dynamic-metric responses have different shapes. Excel export supports standard/date_matrix; row budgets apply.

### API-087 | GET /api/v1/kpi/available-metrics

Get Available Metrics. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:35 -> getAvailableMetrics().

Other declared aliases: /kpi/available-metrics

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/available-metrics
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "fileId": 500,
  "availableMetrics": [],
  "metricCounts": {},
  "dynamicAvailableMetrics": []
}
```

### API-088 | GET /api/v1/kpi/all

Get All KPIs. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:62 -> getAllKpis().

Other declared aliases: /kpi/all

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/all
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "count": 0,
  "data": []
}
```

### API-089 | GET /api/v1/kpi/ul-prb-utilization

Get Ul Prb Utilization. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:74 -> getUlPrbUtilization().

Other declared aliases: /kpi/ul-prb-utilization

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/ul-prb-utilization
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "kpi": "Illustrative metric label",
  "data": [],
  "statistics": {}
}
```

### API-090 | GET /api/v1/kpi/dl-prb-utilization

Get Dl Prb Utilization. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:80 -> getDlPrbUtilization().

Other declared aliases: /kpi/dl-prb-utilization

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/dl-prb-utilization
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "kpi": "Illustrative metric label",
  "data": [],
  "statistics": {}
}
```

### API-091 | GET /api/v1/kpi/data-volume

Get Data Volume. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:86 -> getDataVolume().

Other declared aliases: /kpi/data-volume

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/data-volume
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "kpi": "Illustrative metric label",
  "data": [],
  "statistics": {}
}
```

### API-092 | GET /api/v1/kpi/ul-throughput

Get Ul Throughput. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:92 -> getUlThroughput().

Other declared aliases: /kpi/ul-throughput

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/ul-throughput
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "kpi": "Illustrative metric label",
  "data": [],
  "statistics": {}
}
```

### API-093 | GET /api/v1/kpi/dl-throughput

Get Dl Throughput. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:98 -> getDlThroughput().

Other declared aliases: /kpi/dl-throughput

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/dl-throughput
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "kpi": "Illustrative metric label",
  "data": [],
  "statistics": {}
}
```

### API-094 | GET /api/v1/kpi/erab-drop-rate

Get Erab Drop Rate. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:104 -> getErabDropRate().

Other declared aliases: /kpi/erab-drop-rate

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/erab-drop-rate
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "kpi": "Illustrative metric label",
  "data": [],
  "statistics": {}
}
```

### API-095 | GET /api/v1/kpi/erab-success-rate

Get Erab Success Rate. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:110 -> getErabSuccessRate().

Other declared aliases: /kpi/erab-success-rate

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/erab-success-rate
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "kpi": "Illustrative metric label",
  "data": [],
  "statistics": {}
}
```

### API-096 | GET /api/v1/kpi/rrc-success-rate

Get RRC Success Rate. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:116 -> getRrcSuccessRate().

Other declared aliases: /kpi/rrc-success-rate

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/rrc-success-rate
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "kpi": "Illustrative metric label",
  "data": [],
  "statistics": {}
}
```

### API-097 | GET /api/v1/kpi/mean-rrc-users

Get Mean RRC Users. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:122 -> getMeanRrcUsers().

Other declared aliases: /kpi/mean-rrc-users

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/mean-rrc-users
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "kpi": "Illustrative metric label",
  "data": [],
  "statistics": {}
}
```

### API-098 | GET /api/v1/kpi/max-rrc-users

Get Max RRC Users. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:128 -> getMaxRrcUsers().

Other declared aliases: /kpi/max-rrc-users

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/max-rrc-users
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "kpi": "Illustrative metric label",
  "data": [],
  "statistics": {}
}
```

### API-099 | GET /api/v1/kpi/erab-setup-rate

Get Erab Setup Rate. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:134 -> getErabSetupRate().

Other declared aliases: /kpi/erab-setup-rate

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/erab-setup-rate
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "kpi": "Illustrative metric label",
  "data": [],
  "statistics": {}
}
```

### API-100 | GET /api/v1/kpi/rrc-drop-rate

Get RRC Drop Rate. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:140 -> getRrcDropRate().

Other declared aliases: /kpi/rrc-drop-rate

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/rrc-drop-rate
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "kpi": "Illustrative metric label",
  "data": [],
  "statistics": {}
}
```

### API-101 | GET /api/v1/kpi/volte-cssr

Get Volte Cssr. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:146 -> getVolteCssr().

Other declared aliases: /kpi/volte-cssr

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/volte-cssr
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "kpi": "Illustrative metric label",
  "data": [],
  "statistics": {}
}
```

### API-102 | GET /api/v1/kpi/volte-dcr

Get Volte Dcr. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:152 -> getVolteDcr().

Other declared aliases: /kpi/volte-dcr

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/volte-dcr
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "kpi": "Illustrative metric label",
  "data": [],
  "statistics": {}
}
```

### API-103 | GET /api/v1/kpi/inter-freq-hosr

Get Inter Freq Hosr. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:158 -> getInterFreqHosr().

Other declared aliases: /kpi/inter-freq-hosr

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/inter-freq-hosr
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "kpi": "Illustrative metric label",
  "data": [],
  "statistics": {}
}
```

### API-104 | GET /api/v1/kpi/intra-freq-hosr

Get Intra Freq Hosr. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:164 -> getIntraFreqHosr().

Other declared aliases: /kpi/intra-freq-hosr

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/intra-freq-hosr
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "kpi": "Illustrative metric label",
  "data": [],
  "statistics": {}
}
```

### API-105 | GET /api/v1/kpi/csfb-success-rate

Get Csfb Success Rate. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:170 -> getCsfbSuccessRate().

Other declared aliases: /kpi/csfb-success-rate

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/csfb-success-rate
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "kpi": "Illustrative metric label",
  "data": [],
  "statistics": {}
}
```

### API-106 | GET /api/v1/kpi/dynamic/metrics

Get Dynamic Metrics. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:176 -> getDynamicMetrics().

Other declared aliases: /kpi/dynamic/metrics

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/dynamic/metrics?fileId=500
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "fileId": 500,
  "metrics": []
}
```

### API-107 | GET /api/v1/kpi/dynamic/summary

Get Dynamic Summary. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:193 -> getDynamicSummary().

Other declared aliases: /kpi/dynamic/summary

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/dynamic/summary?fileId=500
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "fileId": 500,
  "metrics": []
}
```

### API-108 | GET /api/v1/kpi/dynamic/columns

Get Dynamic Columns. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:210 -> getDynamicColumns().

Other declared aliases: /kpi/dynamic/columns

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/dynamic/columns?fileId=500
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "fileId": 500,
  "columns": [],
  "selectedMetricKeys": [],
  "selectedFilterKeys": []
}
```

### API-109 | GET /api/v1/kpi/dynamic/data

Get Dynamic Data. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:257 -> getDynamicData().

Other declared aliases: /kpi/dynamic/data

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| metricKey | query / String | No | Actual discovered dynamic metric key, not its friendly display label. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/dynamic/data?fileId=500
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "fileId": 500,
  "metricKey": "rrcsetup",
  "kpi": "RRC Setup SR",
  "data": [],
  "statistics": {
    "count": 0,
    "average": 0,
    "maximum": 0,
    "minimum": 0,
    "median": 0
  }
}
```

### API-110 | GET /api/v1/kpi/dynamic/batch-data

Get Dynamic Batch Data. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:325 -> getDynamicBatchData().

Other declared aliases: /kpi/dynamic/batch-data

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/dynamic/batch-data?fileId=500
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "fileId": 500,
  "metrics": []
}
```

### API-111 | POST /api/v1/kpi/dynamic/selection

Update Dynamic Selection. Module: KPI data and exports.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:346 -> updateDynamicSelection().

Other declared aliases: /kpi/dynamic/selection

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| body | body / Map<String, Object> | No | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/kpi/dynamic/selection?fileId=500
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "metricKeys": [
    "rrcsetup"
  ],
  "filterKeys": [
    "site",
    "tech"
  ]
}
```

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "fileId": 500,
  "selectedMetricKeys": [
    "rrcsetup"
  ],
  "selectedFilterKeys": [
    "site",
    "tech"
  ]
}
```

### API-112 | POST /api/v1/kpi/dynamic/bad-days

Count bad days for an explicit grouping dimension using the supplied threshold contexts.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:385 -> getDynamicBadDays().

Other declared aliases: /kpi/dynamic/bad-days

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| body | body / Map<String, Object> | No | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/kpi/dynamic/bad-days?fileId=500
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "dimensionKey": "site",
  "thresholdContexts": [
    {
      "metricKey": "rrcsetup",
      "thresholdValue": 98.0,
      "thresholdOperator": "<",
      "metricLabel": "RRC Setup SR"
    }
  ]
}
```

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "fileId": 500,
  "mode": "bad_days",
  "rows": [],
  "totalComparedCells": 0
}
```

### API-113 | POST /api/v1/kpi/dynamic/export

Download a dynamic KPI Excel workbook with selected metrics, grouping, filters and comparison ranges.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiController.java:412 -> exportDynamicReport().

Other declared aliases: /kpi/dynamic/export

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| body | body / Map<String, Object> | No | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/kpi/dynamic/export
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "metricKeys": [
    "rrcsetup"
  ],
  "groupByKeys": [
    "site"
  ],
  "activeFilters": {},
  "compareRanges": {
    "preStart": "2026-11-01",
    "preEnd": "2026-11-07",
    "postStart": "2026-11-08",
    "postEnd": "2026-11-15"
  },
  "exportFormat": "standard",
  "maxLongRows": 10000
}
```

Success response: HTTP 200. Binary/artifact response with Content-Type and Content-Disposition; save bytes rather than parsing JSON. Common JSON/configuration downloads are files too.

## 16. Durable KPI ingestion

Append/replay-safe KPI import into an existing dataset with explicit identity fields and run history.

> Targets an existing KPI fileId. identityFields is a JSON-encoded form string, not a JSON request body. Replays are namespaced by dataset/source identity.

### API-050 | POST /api/v1/ingestion/kpi

Start. Module: Durable KPI ingestion.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/ingestion/api/KpiIngestionController.java:27 -> start().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Legacy requireSuperAdmin check (blank legacy roles are treated as super admin by that helper).

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| file | form / MultipartFile | Yes | Binary upload; use multipart/form-data, never a JSON filesystem path. |
| fileId | form / long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| identityFields | form / String | Yes | JSON array encoded as a multipart text field; canonical observation identity fields. |
| sourceName | form / String | No; default=common | See the module contract and controller/service validation for accepted values. |
| columnMapping | form / String | No | JSON object encoded as multipart text; use reviewed source-column mappings; {} means no override. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/ingestion/kpi
```

Request: multipart/form-data. These are form fields, not a JSON request body:

| Form field | Illustrative value |
| --- | --- |
| file | <select a local file> |
| fileId | 500 |
| identityFields | ["date","cellName"] |
| sourceName | common |
| columnMapping | {} |

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "id": 1,
    "fileId": 500,
    "status": "QUEUED"
  }
}
```

### API-051 | GET /api/v1/ingestion/runs

List. Module: Durable KPI ingestion.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/ingestion/api/KpiIngestionController.java:38 -> list().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Legacy requireSuperAdmin check (blank legacy roles are treated as super admin by that helper).

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| limit | query / int | No; default=20 | Requested page/result size; service-specific bounds apply. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/ingestion/runs
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": []
}
```

### API-052 | GET /api/v1/ingestion/runs/{id}

Get. Module: Durable KPI ingestion.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/ingestion/api/KpiIngestionController.java:43 -> get().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Legacy requireSuperAdmin check (blank legacy roles are treated as super admin by that helper).

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/ingestion/runs/1
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "id": 1,
    "fileId": 500,
    "status": "QUEUED"
  }
}
```

### API-053 | POST /api/v1/ingestion/runs/{id}/retry

Retry. Module: Durable KPI ingestion.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/ingestion/api/KpiIngestionController.java:48 -> retry().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Legacy requireSuperAdmin check (blank legacy roles are treated as super admin by that helper).

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/ingestion/runs/1/retry
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "id": 1,
    "fileId": 500,
    "status": "QUEUED"
  }
}
```

## 17. KPI severity thresholds

Configure per-metric severity bands and evaluate individual values or a complete uploaded KPI file.

> Rule directions are LOWER_IS_BAD/HIGHER_IS_BAD. These are not DailyKpiCalculator's direction values. Severity matching is inclusive at the configured band boundary.

### API-114 | GET /api/v1/threshold-rules

List Rules. Module: KPI severity thresholds.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiThresholdRuleController.java:28 -> listRules().

Other declared aliases: /threshold-rules

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

Inputs: no declared client parameters or JSON body.

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/threshold-rules
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": []
}
```

### API-115 | POST /api/v1/threshold-rules

Create Rule. Module: KPI severity thresholds.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiThresholdRuleController.java:33 -> createRule().

Other declared aliases: /threshold-rules

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| request | body / KpiThresholdRuleRequest | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/threshold-rules
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "metricName": "RRC_Establishment_Success_Rate",
  "technology": "LTE",
  "direction": "LOWER_IS_BAD",
  "criticalValue": 90.0,
  "majorValue": 95.0,
  "minorValue": 98.0,
  "warningValue": 99.0,
  "enabled": true
}
```

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-116 | PUT /api/v1/threshold-rules/{id}

Update Rule. Module: KPI severity thresholds.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiThresholdRuleController.java:38 -> updateRule().

Other declared aliases: /threshold-rules/{id}

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / Long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |
| request | body / KpiThresholdRuleRequest | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
PUT http://localhost:3001/api/v1/threshold-rules/1
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "metricName": "RRC_Establishment_Success_Rate",
  "technology": "LTE",
  "direction": "LOWER_IS_BAD",
  "criticalValue": 90.0,
  "majorValue": 95.0,
  "minorValue": 98.0,
  "warningValue": 99.0,
  "enabled": true
}
```

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-117 | DELETE /api/v1/threshold-rules/{id}

Delete Rule. Module: KPI severity thresholds.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiThresholdRuleController.java:43 -> deleteRule().

Other declared aliases: /threshold-rules/{id}

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / Long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
DELETE http://localhost:3001/api/v1/threshold-rules/1
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-118 | GET /api/v1/threshold-rules/evaluate

Evaluate. Module: KPI severity thresholds.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiThresholdRuleController.java:49 -> evaluate().

Other declared aliases: /threshold-rules/evaluate

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| metricName | query / String | Yes | See the module contract and controller/service validation for accepted values. |
| value | query / Double | Yes | Circle alias for resolution, or numeric KPI value for threshold evaluation, as indicated by this API. |
| technology | query / String | No | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/threshold-rules/evaluate?metricName=RRC_Establishment_Success_Rate&value=97.0
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-119 | GET /api/v1/threshold-rules/evaluate-file

Evaluate File. Module: KPI severity thresholds.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiThresholdRuleController.java:57 -> evaluateFile().

Other declared aliases: /threshold-rules/evaluate-file

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/threshold-rules/evaluate-file?fileId=500
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-120 | POST /api/v1/threshold-rules/ensure-file

Ensure File Rules. Module: KPI severity thresholds.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiThresholdRuleController.java:62 -> ensureFileRules().

Other declared aliases: /threshold-rules/ensure-file

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/threshold-rules/ensure-file?fileId=500
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-121 | GET /api/v1/threshold-rules/results

List Results. Module: KPI severity thresholds.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiThresholdRuleController.java:67 -> listResults().

Other declared aliases: /threshold-rules/results

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| limit | query / Integer | No | Requested page/result size; service-specific bounds apply. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/threshold-rules/results
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": []
}
```

### API-122 | GET /api/v1/threshold-rules/results/{fileId}

Get Result. Module: KPI severity thresholds.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiThresholdRuleController.java:72 -> getResult().

Other declared aliases: /threshold-rules/results/{fileId}

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | path / Long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/threshold-rules/results/500
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

## 18. KPI validation

Uploaded-file validation report and data-quality results.

### API-123 | GET /api/v1/kpi/validation-report/{fileId}

Get Validation Report. Module: KPI validation.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/KpiValidationReportController.java:22 -> getValidationReport().

Other declared aliases: /kpi/validation-report/{fileId}

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | path / Long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/validation-report/500
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

## 19. License administration

Issue, renew, block and download company/device-bound license records.

### API-131 | GET /api/v1/licenses

List Licenses. Module: License administration.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/licensing/api/LicenseController.java:28 -> listLicenses().

Other declared aliases: /licenses

Access: Bearer JWT and active session required. License-filter exempt. Legacy requireSuperAdmin check (blank legacy roles are treated as super admin by that helper).

Inputs: no declared client parameters or JSON body.

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/licenses
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": []
}
```

### API-132 | GET /api/v1/licenses/{id}

Get License. Module: License administration.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/licensing/api/LicenseController.java:33 -> getLicense().

Other declared aliases: /licenses/{id}

Access: Bearer JWT and active session required. License-filter exempt. Legacy requireSuperAdmin check (blank legacy roles are treated as super admin by that helper).

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / Long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/licenses/1
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-133 | POST /api/v1/licenses

Create License. Module: License administration.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/licensing/api/LicenseController.java:38 -> createLicense().

Other declared aliases: /licenses

Access: Bearer JWT and active session required. License-filter exempt. Legacy requireSuperAdmin check (blank legacy roles are treated as super admin by that helper).

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| body | body / Map<String, Object> | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/licenses
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "companyId": 100,
  "deviceId": "DEVICE_ID_FROM_LOCAL_LICENSE_API",
  "plan": "BASIC",
  "maxUsers": 5,
  "validFrom": "2026-11-16",
  "expiresAt": "2027-11-15",
  "status": "ACTIVE"
}
```

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-134 | PATCH /api/v1/licenses/{id}/renew

Renew License. Module: License administration.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/licensing/api/LicenseController.java:43 -> renewLicense().

Other declared aliases: /licenses/{id}/renew

Access: Bearer JWT and active session required. License-filter exempt. Legacy requireSuperAdmin check (blank legacy roles are treated as super admin by that helper).

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / Long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |
| body | body / Map<String, Object> | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
PATCH http://localhost:3001/api/v1/licenses/1/renew
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "expiresAt": "2027-11-15"
}
```

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-135 | PATCH /api/v1/licenses/{id}/block

Block License. Module: License administration.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/licensing/api/LicenseController.java:48 -> blockLicense().

Other declared aliases: /licenses/{id}/block

Access: Bearer JWT and active session required. License-filter exempt. Legacy requireSuperAdmin check (blank legacy roles are treated as super admin by that helper).

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / Long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
PATCH http://localhost:3001/api/v1/licenses/1/block
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-136 | GET /api/v1/licenses/{id}/download

Download License. Module: License administration.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/licensing/api/LicenseController.java:53 -> downloadLicense().

Other declared aliases: /licenses/{id}/download

Access: Bearer JWT and active session required. License-filter exempt. Legacy requireSuperAdmin check (blank legacy roles are treated as super admin by that helper).

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / Long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/licenses/1/download
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Binary/artifact response with Content-Type and Content-Disposition; save bytes rather than parsing JSON. Common JSON/configuration downloads are files too.

## 20. Installed license

Upload the signed license file, inspect validity and obtain this installation's device ID.

> License-filter exempt but still requires JWT/session authentication. Upload a real issued license.dat; no license contents, signing keys or actual device IDs are included in this handbook.

### API-137 | POST /api/v1/local-license/upload

Upload License. Module: Installed license.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/licensing/api/LocalLicenseController.java:25 -> uploadLicense().

Other declared aliases: /local-license/upload

Access: Bearer JWT and active session required. License-filter exempt. No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| file | form / MultipartFile | Yes | Binary upload; use multipart/form-data, never a JSON filesystem path. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/local-license/upload
```

Request: multipart/form-data. These are form fields, not a JSON request body:

| Form field | Illustrative value |
| --- | --- |
| file | <select a local file> |

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {
    "valid": false
  }
}
```

### API-138 | GET /api/v1/local-license/status

Status. Module: Installed license.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/licensing/api/LocalLicenseController.java:30 -> status().

Other declared aliases: /local-license/status

Access: Bearer JWT and active session required. License-filter exempt. No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

Inputs: no declared client parameters or JSON body.

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/local-license/status
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {
    "valid": false
  }
}
```

### API-139 | GET /api/v1/local-license/device-id

Device Id. Module: Installed license.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/licensing/api/LocalLicenseController.java:35 -> deviceId().

Other declared aliases: /local-license/device-id

Access: Bearer JWT and active session required. License-filter exempt. No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

Inputs: no declared client parameters or JSON body.

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/local-license/device-id
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {
    "deviceId": "DEVICE_ID_FROM_THIS_INSTALLATION"
  }
}
```

## 21. Nightly KPI automation

Configure daily folder discovery, import, analysis, report and delivery stages with IST cutoffs and durable run tracking.

> Uses Asia/Kolkata. Pattern must contain {date}; enabled profiles need an accessible absolute folder. Input cutoff is 01:00–01:59 IST; report target is 02:00 next day. Running backend/service and configuration are required; this is not an inbox-delivery guarantee.

### API-140 | GET /api/v1/nightly/profile

Profile. Module: Nightly KPI automation.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/nightly/api/NightlyWorkflowController.java:18 -> profile().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Legacy SUPER_ADMIN mutation/profile check plus dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/nightly/profile?fileId=500
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {}
}
```

### API-141 | PUT /api/v1/nightly/profile

Configure. Module: Nightly KPI automation.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/nightly/api/NightlyWorkflowController.java:19 -> configure().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Legacy SUPER_ADMIN mutation/profile check plus dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| profile | body / NightlyProfile | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
PUT http://localhost:3001/api/v1/nightly/profile?fileId=500
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "enabled": false,
  "folder": "",
  "filePattern": "kpi-{date}.csv",
  "identityFields": [
    "date",
    "cellName"
  ],
  "columnMapping": {},
  "deliveryMode": "REPORT_ONLY",
  "cutoffTime": "01:30",
  "reportOptions": {
    "modules": [
      "thresholds",
      "alarmCandidates",
      "worstCells",
      "prediction"
    ],
    "rcaConfig": {}
  }
}
```

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {}
}
```

### API-142 | POST /api/v1/nightly/runs

Create. Module: Nightly KPI automation.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/nightly/api/NightlyWorkflowController.java:20 -> create().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Legacy SUPER_ADMIN mutation/profile check plus dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| date | query / String | Yes | Business/report date in YYYY-MM-DD, not a timestamp. |
| allowLate | query / boolean | No; default=false | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/nightly/runs?fileId=500&date=2026-11-15
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {}
}
```

### API-143 | GET /api/v1/nightly/runs

List. Module: Nightly KPI automation.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/nightly/api/NightlyWorkflowController.java:21 -> list().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| limit | query / int | No; default=20 | Requested page/result size; service-specific bounds apply. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/nightly/runs?fileId=500
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": []
}
```

### API-144 | GET /api/v1/nightly/runs/{id}

Get. Module: Nightly KPI automation.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/nightly/api/NightlyWorkflowController.java:22 -> get().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/nightly/runs/1
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {}
}
```

### API-145 | POST /api/v1/nightly/runs/{id}/retry

Retry. Module: Nightly KPI automation.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/nightly/api/NightlyWorkflowController.java:23 -> retry().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Legacy SUPER_ADMIN mutation/profile check plus dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |
| rediscoverInput | query / boolean | No; default=false | See the module contract and controller/service validation for accepted values. |
| refreshMailProfile | query / boolean | No; default=false | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/nightly/runs/1/retry
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {}
}
```

## 22. PCI analysis

Inspect a selected PCI and optionally a source site in an uploaded site dataset.

### API-179 | GET /api/v1/pci/analysis

Analysis. Module: PCI analysis.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/spatial/api/PciAnalysisController.java:22 -> analysis().

Other declared aliases: /pci/analysis

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| pci | query / Integer | Yes | See the module contract and controller/service validation for accepted values. |
| sourceSiteId | query / String | No | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/pci/analysis?fileId=500&pci=10
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {}
}
```

## 23. Root cause analysis

Run the Python RCA engine, submit/poll jobs, manage presets and use the older blocking Excel export.

> Dataset-specific columns/KPI keys must match the source; the illustrative mappings are not universal. dateMode single or pre_post. Java-facing fields are camelCase, unlike the Python engine's internal snake_case. Prefer jobs + polling for analysis and /rca-exports for export.

### API-147 | POST /api/v1/kpi/recommendation/jobs

Start Recommendation Job. Module: Root cause analysis.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/rca/api/RecommendationController.java:35 -> startRecommendationJob().

Other declared aliases: /kpi/recommendation/jobs

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| body | body / Map<String, Object> | No | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/kpi/recommendation/jobs
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "vendor": "ericsson",
  "dateMode": "single",
  "startDate": "2026-11-01",
  "endDate": "2026-11-15",
  "columnMapping": {
    "Short name": "cellName",
    "Date": "date",
    "Cell I'd": "cellName",
    "Site I'd": "site",
    "Tech": "tech"
  },
  "kpiMapping": {
    "MV_PUSCH SINR": "sinr"
  },
  "selectedKpis": [
    "MV_PUSCH SINR"
  ],
  "kpis": {},
  "rcaThresholds": {},
  "severityThresholds": {},
  "anomalyThresholds": {},
  "inputRowLimit": 10000,
  "responseRowLimit": 1000
}
```

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative job response",
  "data": {
    "jobId": 60,
    "status": "QUEUED",
    "progressPercent": 5
  }
}
```

### API-148 | GET /api/v1/kpi/recommendation/jobs/{jobId}

Get Recommendation Job. Module: Root cause analysis.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/rca/api/RecommendationController.java:54 -> getRecommendationJob().

Other declared aliases: /kpi/recommendation/jobs/{jobId}

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| jobId | path / Long | Yes | Job identifier belonging to this controller's workflow. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/recommendation/jobs/60
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative job response",
  "data": {
    "jobId": 60,
    "status": "QUEUED",
    "progressPercent": 5
  }
}
```

### API-149 | POST /api/v1/kpi/recommendation/run

Run Recommendation. Module: Root cause analysis.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/rca/api/RecommendationController.java:65 -> runRecommendation().

Other declared aliases: /kpi/recommendation/run

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| body | body / Map<String, Object> | No | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/kpi/recommendation/run
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "vendor": "ericsson",
  "dateMode": "single",
  "startDate": "2026-11-01",
  "endDate": "2026-11-15",
  "columnMapping": {
    "Short name": "cellName",
    "Date": "date",
    "Cell I'd": "cellName",
    "Site I'd": "site",
    "Tech": "tech"
  },
  "kpiMapping": {
    "MV_PUSCH SINR": "sinr"
  },
  "selectedKpis": [
    "MV_PUSCH SINR"
  ],
  "kpis": {},
  "rcaThresholds": {},
  "severityThresholds": {},
  "anomalyThresholds": {},
  "inputRowLimit": 10000,
  "responseRowLimit": 1000
}
```

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "fileId": 500
}
```

### API-150 | POST /api/v1/kpi/recommendation/export

Export Recommendation. Module: Root cause analysis.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/rca/api/RecommendationController.java:91 -> exportRecommendation().

Other declared aliases: /kpi/recommendation/export

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| body | body / Map<String, Object> | No | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/kpi/recommendation/export
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "vendor": "ericsson",
  "dateMode": "single",
  "startDate": "2026-11-01",
  "endDate": "2026-11-15",
  "columnMapping": {
    "Short name": "cellName",
    "Date": "date",
    "Cell I'd": "cellName",
    "Site I'd": "site",
    "Tech": "tech"
  },
  "kpiMapping": {
    "MV_PUSCH SINR": "sinr"
  },
  "selectedKpis": [
    "MV_PUSCH SINR"
  ],
  "kpis": {},
  "rcaThresholds": {},
  "severityThresholds": {},
  "anomalyThresholds": {},
  "inputRowLimit": 10000,
  "responseRowLimit": 1000
}
```

Success response: HTTP 200. Binary/artifact response with Content-Type and Content-Disposition; save bytes rather than parsing JSON. Common JSON/configuration downloads are files too.

### API-151 | GET /api/v1/kpi/recommendation/presets

List Presets. Module: Root cause analysis.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/rca/api/RecommendationController.java:104 -> listPresets().

Other declared aliases: /kpi/recommendation/presets

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

Inputs: no declared client parameters or JSON body.

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/recommendation/presets
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "presets": []
}
```

### API-152 | POST /api/v1/kpi/recommendation/presets

Save Preset. Module: Root cause analysis.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/rca/api/RecommendationController.java:114 -> savePreset().

Other declared aliases: /kpi/recommendation/presets

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| body | body / Map<String, Object> | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/kpi/recommendation/presets
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "name": "synthetic-review",
  "config": {
    "vendor": "ericsson",
    "dateMode": "single",
    "startDate": "2026-11-01",
    "endDate": "2026-11-15",
    "columnMapping": {
      "Short name": "cellName",
      "Date": "date",
      "Cell I'd": "cellName",
      "Site I'd": "site",
      "Tech": "tech"
    },
    "kpiMapping": {
      "MV_PUSCH SINR": "sinr"
    },
    "selectedKpis": [
      "MV_PUSCH SINR"
    ],
    "kpis": {},
    "rcaThresholds": {},
    "severityThresholds": {},
    "anomalyThresholds": {},
    "inputRowLimit": 10000,
    "responseRowLimit": 1000
  }
}
```

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "name": "synthetic-review"
}
```

### API-153 | GET /api/v1/kpi/recommendation/presets/{name}

Load Preset. Module: Root cause analysis.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/rca/api/RecommendationController.java:132 -> loadPreset().

Other declared aliases: /kpi/recommendation/presets/{name}

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| name | path / String | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/recommendation/presets/synthetic-review
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "name": "synthetic-review",
  "config": {
    "vendor": "ericsson",
    "dateMode": "single",
    "startDate": "2026-11-01",
    "endDate": "2026-11-15",
    "columnMapping": {
      "Short name": "cellName",
      "Date": "date",
      "Cell I'd": "cellName",
      "Site I'd": "site",
      "Tech": "tech"
    },
    "kpiMapping": {
      "MV_PUSCH SINR": "sinr"
    },
    "selectedKpis": [
      "MV_PUSCH SINR"
    ],
    "kpis": {},
    "rcaThresholds": {},
    "severityThresholds": {},
    "anomalyThresholds": {},
    "inputRowLimit": 10000,
    "responseRowLimit": 1000
  }
}
```

## 24. Durable RCA export

Queue and poll captured-result Excel export, then download its artifact. Prefer this route for interactive exports.

> Uses the captured-result export path and persisted job status; download only once the export is ready. fileId is a dataset ID; export id is a different ID.

### API-154 | POST /api/v1/rca-exports

Start. Module: Durable RCA export.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/rca/api/RecommendationExportController.java:17 -> start().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| body | body / Map<String,Object> | No | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/rca-exports?fileId=500
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "vendor": "ericsson",
  "dateMode": "single",
  "startDate": "2026-11-01",
  "endDate": "2026-11-15",
  "columnMapping": {
    "Short name": "cellName",
    "Date": "date",
    "Cell I'd": "cellName",
    "Site I'd": "site",
    "Tech": "tech"
  },
  "kpiMapping": {
    "MV_PUSCH SINR": "sinr"
  },
  "selectedKpis": [
    "MV_PUSCH SINR"
  ],
  "kpis": {},
  "rcaThresholds": {},
  "severityThresholds": {},
  "anomalyThresholds": {},
  "inputRowLimit": 10000,
  "responseRowLimit": 1000
}
```

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "id": 1,
    "fileId": 500,
    "status": "QUEUED"
  }
}
```

### API-155 | GET /api/v1/rca-exports/{id}

Get. Module: Durable RCA export.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/rca/api/RecommendationExportController.java:18 -> get().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/rca-exports/1
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "id": 1,
    "fileId": 500,
    "status": "QUEUED"
  }
}
```

### API-156 | GET /api/v1/rca-exports/{id}/download

Download. Module: Durable RCA export.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/rca/api/RecommendationExportController.java:19 -> download().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/rca-exports/1/download
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Binary/artifact response with Content-Type and Content-Disposition; save bytes rather than parsing JSON. Common JSON/configuration downloads are files too.

## 25. Report dataset discovery

Bounded, company-scoped dataset picker for the reporting workspace.

### API-169 | GET /api/v1/report-datasets

List. Module: Report dataset discovery.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/reporting/api/ReportDatasetController.java:15 -> list().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). SUPER_ADMIN can discover datasets across companies; other authenticated users need a company assignment and see only that company's matching KPI datasets.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| page | query / int | No; default=1 | One-based page number. |
| limit | query / int | No; default=50 | Requested page/result size; service-specific bounds apply. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/report-datasets
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic shape example; replace identifiers/values with this installation's real response data.

```json
{
  "success": true,
  "data": {
    "items": [],
    "total": 0,
    "page": 1,
    "limit": 50
  }
}
```

## 26. Common-report email outbox

Configure reviewed recipients, queue mail, inspect/cancel/retry/reconcile deliveries and download captured MIME.

> Default examples are disabled CAPTURE previews. Sending requires an enabled reviewed profile. UNKNOWN must be reconciled; ACCEPTED is SMTP-provider acceptance, not inbox delivery. Reconciliation outcome vocabulary differs from the native OSS outbox.

### API-170 | PUT /api/v1/report-mail/profile

Configure. Module: Common-report email outbox.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/reporting/mail/api/ReportMailController.java:21 -> configure().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Legacy SUPER_ADMIN mutation/profile check plus dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| profile | body / ReportMailProfile | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
PUT http://localhost:3001/api/v1/report-mail/profile?fileId=500
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "mode": "CAPTURE",
  "recipients": [
    "reviewer@example.invalid"
  ],
  "attachments": [
    "pdf",
    "xlsx"
  ],
  "allowPartial": false,
  "enabled": false
}
```

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {}
}
```

### API-171 | GET /api/v1/report-mail/profile

Profile. Module: Common-report email outbox.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/reporting/mail/api/ReportMailController.java:24 -> profile().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Legacy SUPER_ADMIN mutation/profile check plus dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/report-mail/profile?fileId=500
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "fileId": 500,
    "status": "CONFIGURATION_REQUIRED",
    "outboundEnabled": false
  }
}
```

### API-172 | POST /api/v1/report-mail/deliveries

Submit. Module: Common-report email outbox.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/reporting/mail/api/ReportMailController.java:25 -> submit().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Legacy SUPER_ADMIN mutation/profile check plus dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| reportId | query / long | Yes | Persisted common-report ID, not fileId or snapshotId. |
| expectedProfileHash | query / String | No | Hash returned by the reviewed common-mail profile; protects against sending with changed recipients. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/report-mail/deliveries?reportId=50
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {}
}
```

### API-173 | GET /api/v1/report-mail/deliveries

List. Module: Common-report email outbox.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/reporting/mail/api/ReportMailController.java:29 -> list().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| limit | query / int | No; default=20 | Requested page/result size; service-specific bounds apply. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/report-mail/deliveries?fileId=500
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": []
}
```

### API-174 | GET /api/v1/report-mail/deliveries/{id}

Get. Module: Common-report email outbox.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/reporting/mail/api/ReportMailController.java:30 -> get().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/report-mail/deliveries/1
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {}
}
```

### API-175 | POST /api/v1/report-mail/deliveries/{id}/retry

Retry. Module: Common-report email outbox.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/reporting/mail/api/ReportMailController.java:31 -> retry().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Legacy SUPER_ADMIN mutation/profile check plus dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/report-mail/deliveries/1/retry
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {}
}
```

### API-176 | POST /api/v1/report-mail/deliveries/{id}/cancel

Cancel. Module: Common-report email outbox.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/reporting/mail/api/ReportMailController.java:32 -> cancel().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Legacy SUPER_ADMIN mutation/profile check plus dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/report-mail/deliveries/1/cancel
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {}
}
```

### API-177 | POST /api/v1/report-mail/deliveries/{id}/reconcile

Reconcile. Module: Common-report email outbox.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/reporting/mail/api/ReportMailController.java:34 -> reconcile().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). Legacy SUPER_ADMIN mutation/profile check plus dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |
| resolution | body / Resolution | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/report-mail/deliveries/1/reconcile
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "outcome": "NOT_ACCEPTED",
  "evidence": "Replace with actual provider verification"
}
```

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {}
}
```

### API-178 | GET /api/v1/report-mail/deliveries/{id}/message.eml

Captured. Module: Common-report email outbox.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/reporting/mail/api/ReportMailController.java:35 -> captured().

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). dataset ownership/company scope checked in the controller.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/report-mail/deliveries/1/message.eml
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Binary/artifact response with Content-Type and Content-Disposition; save bytes rather than parsing JSON. Common JSON/configuration downloads are files too.

## 27. Dashboard threshold settings

Global/file-scoped threshold values/operators and threshold workbook import/export. These differ from severity-band rules.

> updateSetting accepts flat numeric metric keys, not a nested thresholds object; thresholdOperators/operators and dynamicThresholds are separate maps. thresholds/export instead requires the nested thresholds/ operators export wrapper. Preserve endpoint capitalization: updateSetting.

### API-124 | GET /api/v1/setting/getsettings

Get Settings. Module: Dashboard threshold settings.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/SettingController.java:25 -> getSettings().

Other declared aliases: /setting/getsettings

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/setting/getsettings
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-125 | POST /api/v1/setting/updateSetting

Update Settings. Module: Dashboard threshold settings.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/SettingController.java:39 -> updateSettings().

Other declared aliases: /setting/updateSetting

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| body | body / Map<String, Object> | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/setting/updateSetting
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "RRC_Establishment_Success_Rate": 98.0,
  "thresholdOperators": {
    "RRC_Establishment_Success_Rate": ">="
  },
  "dynamicThresholds": {
    "rrcsetup": 98.0
  }
}
```

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-126 | POST /api/v1/setting/thresholds/export

Export Threshold Workbook. Module: Dashboard threshold settings.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/SettingController.java:55 -> exportThresholdWorkbook().

Other declared aliases: /setting/thresholds/export

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| body | body / Map<String, Object> | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/setting/thresholds/export
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "thresholds": {
    "RRC_Establishment_Success_Rate": 98.0
  },
  "operators": {
    "RRC_Establishment_Success_Rate": ">="
  },
  "scopeFileId": "global"
}
```

Success response: HTTP 200. Binary/artifact response with Content-Type and Content-Disposition; save bytes rather than parsing JSON. Common JSON/configuration downloads are files too.

### API-127 | POST /api/v1/setting/thresholds/import

Parse a threshold workbook and return its values; do not confuse parsing with saving updateSetting.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/SettingController.java:81 -> importThresholdWorkbook().

Other declared aliases: /setting/thresholds/import

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| file | form / MultipartFile | Yes | Binary upload; use multipart/form-data, never a JSON filesystem path. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/setting/thresholds/import
```

Request: multipart/form-data. These are form fields, not a JSON request body:

| Form field | Illustrative value |
| --- | --- |
| file | <select a local file> |

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

## 28. Site analytics and map intelligence

Site summary, worst sites, detail and PCI/handover/overshoot/neighbour intelligence for map and site views.

### API-180 | GET /api/v1/sites/summary

Summary. Module: Site analytics and map intelligence.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/spatial/api/SiteAnalyticsController.java:22 -> summary().

Other declared aliases: /sites/summary

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/sites/summary?fileId=500
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-181 | GET /api/v1/sites/worst

Worst. Module: Site analytics and map intelligence.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/spatial/api/SiteAnalyticsController.java:27 -> worst().

Other declared aliases: /sites/worst

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| limit | query / Integer | No | Requested page/result size; service-specific bounds apply. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/sites/worst?fileId=500
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-182 | GET /api/v1/sites/details

Details. Module: Site analytics and map intelligence.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/spatial/api/SiteAnalyticsController.java:33 -> details().

Other declared aliases: /sites/details

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| site | query / String | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/sites/details?fileId=500&site=SYNTHETIC-SITE
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-183 | GET /api/v1/sites/intelligence

Intelligence. Module: Site analytics and map intelligence.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/spatial/api/SiteAnalyticsController.java:39 -> intelligence().

Other declared aliases: /api/v1/sites/site-intelligence; /sites/intelligence; /sites/site-intelligence

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| siteFileId | query / Long | No | See the module contract and controller/service validation for accepted values. |
| limit | query / Integer | No | Requested page/result size; service-specific bounds apply. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/sites/intelligence
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

## 29. Predictions

Site recommendations/summary and the LB worst-cell prediction workflow, optionally with timing-advance data.

> Recommendations are model/rule outputs, not observed network facts. LB-WCF accepts multipart/form parameters; optional method defaults to both, quantile to 0.10 and mlMode to 3 in its service.

### API-184 | GET /api/v1/site-prediction/recommendations

Recommendations. Module: Predictions.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/spatial/api/SitePredictionController.java:28 -> recommendations().

Other declared aliases: /site-prediction/recommendations

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| site | query / String | No | See the module contract and controller/service validation for accepted values. |
| limit | query / Integer | No | Requested page/result size; service-specific bounds apply. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/site-prediction/recommendations?fileId=500
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-185 | GET /api/v1/site-prediction/summary

Summary. Module: Predictions.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/spatial/api/SitePredictionController.java:39 -> summary().

Other declared aliases: /site-prediction/summary

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/site-prediction/summary?fileId=500
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-186 | POST /api/v1/site-prediction/lb-wcf

Lb Wcf Prediction. Module: Predictions.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/spatial/api/SitePredictionController.java:47 -> lbWcfPrediction().

Other declared aliases: /site-prediction/lb-wcf

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | form / Long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| taFile | form / MultipartFile | No | Optional timing-advance file for LB worst-cell prediction. |
| method | form / String | No | See the module contract and controller/service validation for accepted values. |
| quantile | form / Double | No | See the module contract and controller/service validation for accepted values. |
| mlMode | form / Integer | No | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/site-prediction/lb-wcf
```

Request: multipart/form-data. These are form fields, not a JSON request body:

| Form field | Illustrative value |
| --- | --- |
| fileId | 500 |
| taFile | <select a local file> |
| method | both |
| quantile | 0.1 |
| mlMode | 3 |

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

## 30. Uploads and parsing

KPI upload jobs, source preview, site/alarm imports, upload history and paged uploaded data. Legacy alarm upload is separate from the new durable OSS workflow.

> KPI uploads return a job to poll even when the HTTP status is 200. Site/alarm uploads use the legacy upload path. targetFileId takes precedence over fileId. Read paging/filter query fields are separate from multipart upload fields.

### API-054 | POST /api/v1/upload/upload

Upload And Parse File. Module: Uploads and parsing.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/ingestion/api/UploadController.java:37 -> uploadAndParseFile().

Other declared aliases: /upload/upload

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| file | form / MultipartFile | Yes | Binary upload; use multipart/form-data, never a JSON filesystem path. |
| remarks | form / String | No | See the module contract and controller/service validation for accepted values. |
| uploadedBy | form / String | No | See the module contract and controller/service validation for accepted values. |
| appendOption | form / String | No | append, merge or overwrite (legacy KPI upload); default APPEND. |
| fileId | form / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| targetFileId | form / Long | No | Existing KPI dataset to append/merge/overwrite; takes precedence over fileId in legacy upload. |
| columnMapping | form / String | No | JSON object encoded as multipart text; use reviewed source-column mappings; {} means no override. |
| cleanKpiData | form / Boolean | No | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/upload/upload
```

Request: multipart/form-data. These are form fields, not a JSON request body:

| Form field | Illustrative value |
| --- | --- |
| file | <select a local file> |
| remarks | Synthetic documentation sample |
| uploadedBy | Demo operator |
| appendOption | append |
| fileId | 500 |
| targetFileId | 500 |
| columnMapping | {} |
| cleanKpiData | False |

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "jobId": 60,
    "status": "QUEUED",
    "progressPercent": 5
  }
}
```

### API-055 | POST /api/v1/upload/kpi

Upload KPI Data. Module: Uploads and parsing.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/ingestion/api/UploadController.java:50 -> uploadKpiData().

Other declared aliases: /upload/kpi

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| file | form / MultipartFile | Yes | Binary upload; use multipart/form-data, never a JSON filesystem path. |
| remarks | form / String | No | See the module contract and controller/service validation for accepted values. |
| uploadedBy | form / String | No | See the module contract and controller/service validation for accepted values. |
| appendOption | form / String | No | append, merge or overwrite (legacy KPI upload); default APPEND. |
| fileId | form / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| targetFileId | form / Long | No | Existing KPI dataset to append/merge/overwrite; takes precedence over fileId in legacy upload. |
| columnMapping | form / String | No | JSON object encoded as multipart text; use reviewed source-column mappings; {} means no override. |
| cleanKpiData | form / Boolean | No | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/upload/kpi
```

Request: multipart/form-data. These are form fields, not a JSON request body:

| Form field | Illustrative value |
| --- | --- |
| file | <select a local file> |
| remarks | Synthetic documentation sample |
| uploadedBy | Demo operator |
| appendOption | append |
| fileId | 500 |
| targetFileId | 500 |
| columnMapping | {} |
| cleanKpiData | False |

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "jobId": 60,
    "status": "QUEUED",
    "progressPercent": 5
  }
}
```

### API-056 | GET /api/v1/upload/jobs/{jobId}

Get Upload Job. Module: Uploads and parsing.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/ingestion/api/UploadController.java:63 -> getUploadJob().

Other declared aliases: /upload/jobs/{jobId}

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| jobId | path / Long | Yes | Job identifier belonging to this controller's workflow. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/upload/jobs/60
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "jobId": 60,
    "status": "QUEUED",
    "progressPercent": 5
  }
}
```

### API-057 | GET /api/v1/upload/jobs

List Upload Jobs. Module: Uploads and parsing.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/ingestion/api/UploadController.java:74 -> listUploadJobs().

Other declared aliases: /upload/jobs

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| limit | query / Integer | No | Requested page/result size; service-specific bounds apply. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/upload/jobs
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": []
}
```

### API-058 | POST /api/v1/upload/jobs/{jobId}/retry

Retry Upload Job. Module: Uploads and parsing.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/ingestion/api/UploadController.java:83 -> retryUploadJob().

Other declared aliases: /upload/jobs/{jobId}/retry

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| jobId | path / Long | Yes | Job identifier belonging to this controller's workflow. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/upload/jobs/60/retry
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {
    "jobId": 60,
    "status": "QUEUED",
    "progressPercent": 5
  }
}
```

### API-059 | POST /api/v1/upload/kpi/preview

Preview KPI Data. Module: Uploads and parsing.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/ingestion/api/UploadController.java:96 -> previewKpiData().

Other declared aliases: /upload/kpi/preview

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| file | form / MultipartFile | Yes | Binary upload; use multipart/form-data, never a JSON filesystem path. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/upload/kpi/preview
```

Request: multipart/form-data. These are form fields, not a JSON request body:

| Form field | Illustrative value |
| --- | --- |
| file | <select a local file> |

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {}
}
```

### API-060 | POST /api/v1/upload/site

Upload Site Data. Module: Uploads and parsing.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/ingestion/api/UploadController.java:101 -> uploadSiteData().

Other declared aliases: /upload/site

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| file | form / MultipartFile | Yes | Binary upload; use multipart/form-data, never a JSON filesystem path. |
| remarks | form / String | No | See the module contract and controller/service validation for accepted values. |
| uploadedBy | form / String | No | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/upload/site
```

Request: multipart/form-data. These are form fields, not a JSON request body:

| Form field | Illustrative value |
| --- | --- |
| file | <select a local file> |
| remarks | Synthetic documentation sample |
| uploadedBy | Demo operator |

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {}
}
```

### API-061 | POST /api/v1/upload/alarm

Upload Alarm Data. Module: Uploads and parsing.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/ingestion/api/UploadController.java:109 -> uploadAlarmData().

Other declared aliases: /upload/alarm

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| file | form / MultipartFile | Yes | Binary upload; use multipart/form-data, never a JSON filesystem path. |
| remarks | form / String | No | See the module contract and controller/service validation for accepted values. |
| uploadedBy | form / String | No | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/upload/alarm
```

Request: multipart/form-data. These are form fields, not a JSON request body:

| Form field | Illustrative value |
| --- | --- |
| file | <select a local file> |
| remarks | Synthetic documentation sample |
| uploadedBy | Demo operator |

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {}
}
```

### API-062 | GET /api/v1/upload/uploads

Get Upload History. Module: Uploads and parsing.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/ingestion/api/UploadController.java:117 -> getUploadHistory().

Other declared aliases: /upload/uploads

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

Inputs: no declared client parameters or JSON body.

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/upload/uploads
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": []
}
```

### API-063 | DELETE /api/v1/upload/uploads/{id}

Delete Upload. Module: Uploads and parsing.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/ingestion/api/UploadController.java:122 -> deleteUpload().

Other declared aliases: /upload/uploads/{id}

Access: Bearer JWT and active session required. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| id | path / Long | Yes | Resource identifier named by this URL (rule/import/report/delivery/zone/circle/feed/license). IDs are not interchangeable. |

Example URL (synthetic IDs; required query fields shown):

```text
DELETE http://localhost:3001/api/v1/upload/uploads/1
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": {}
}
```

### API-064 | GET /api/v1/upload/network-data

Get Network Data. Module: Uploads and parsing.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/ingestion/api/UploadController.java:134 -> getNetworkData().

Other declared aliases: /upload/network-data

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| page | query / int | No; default=1 | One-based page number. |
| limit | query / int | No; default=50 | Requested page/result size; service-specific bounds apply. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/upload/network-data
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": []
}
```

### API-065 | GET /api/v1/upload/uploads/history

Get Upload History Slim. Module: Uploads and parsing.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/ingestion/api/UploadController.java:140 -> getUploadHistorySlim().

Other declared aliases: /upload/uploads/history

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

Inputs: no declared client parameters or JSON body.

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/upload/uploads/history
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": []
}
```

### API-066 | GET /api/v1/upload/history

Get Upload History Slim Alias. Module: Uploads and parsing.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/ingestion/api/UploadController.java:145 -> getUploadHistorySlimAlias().

Other declared aliases: /upload/history

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

Inputs: no declared client parameters or JSON body.

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/upload/history
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": []
}
```

### API-067 | GET /api/v1/upload/site-data

Get Site Data. Module: Uploads and parsing.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/ingestion/api/UploadController.java:150 -> getSiteData().

Other declared aliases: /upload/site-data

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| page | query / int | No; default=1 | One-based page number. |
| limit | query / int | No; default=10000 | Requested page/result size; service-specific bounds apply. |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/upload/site-data
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": []
}
```

### API-068 | GET /api/v1/upload/alarm-data

Get Alarm Data. Module: Uploads and parsing.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/ingestion/api/UploadController.java:157 -> getAlarmData().

Other declared aliases: /upload/alarm-data

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| page | query / int | No; default=1 | One-based page number. |
| limit | query / int | No; default=10 | Requested page/result size; service-specific bounds apply. |
| fileId | query / Long | No | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| circle | query / String | No | See the module contract and controller/service validation for accepted values. |
| severity | query / String | No | See the module contract and controller/service validation for accepted values. |
| search | query / String | No | See the module contract and controller/service validation for accepted values. |
| startDate | query / String | No | Date-filter start in YYYY-MM-DD. |
| endDate | query / String | No | Date-filter end in YYYY-MM-DD. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/upload/alarm-data
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "data": []
}
```

## 31. User administration

List accounts and create company-assigned users. Current create-user service accepts USER or SUPER_ADMIN, not ADMIN.

> Current service accepts USER or SUPER_ADMIN roles only. ADMIN is recognized by newer alarm authorization but is not accepted by this account-creation API. Invalid roles/statuses are rejected.

### API-048 | GET /api/v1/users

List Users. Module: User administration.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/identity/api/UserController.java:23 -> listUsers().

Other declared aliases: /users

Access: Bearer JWT and active session required. License-filter exempt. Legacy requireSuperAdmin check (blank legacy roles are treated as super admin by that helper).

Inputs: no declared client parameters or JSON body.

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/users
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": []
}
```

### API-049 | POST /api/v1/users

Create User. Module: User administration.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/identity/api/UserController.java:32 -> createUser().

Other declared aliases: /users

Access: Bearer JWT and active session required. License-filter exempt. Legacy requireSuperAdmin check (blank legacy roles are treated as super admin by that helper).

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| body | body / Map<String, Object> | Yes | See the module contract and controller/service validation for accepted values. |

Example URL (synthetic IDs; required query fields shown):

```text
POST http://localhost:3001/api/v1/users
```

Request JSON example (synthetic; dataset-specific mappings and business thresholds must be replaced):

```json
{
  "email": "reader@example.invalid",
  "password": "REPLACE_WITH_A_TEST_PASSWORD",
  "companyId": 100,
  "role": "USER",
  "status": "ACTIVE"
}
```

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {
    "id": 1,
    "email": "reader@example.invalid",
    "companyId": 100,
    "role": "USER",
    "status": "ACTIVE"
  }
}
```

## 32. Worst-performance analysis

Rank cells/sites by an explicit KPI metric and inspect cell-level bad-day evidence.

### API-128 | GET /api/v1/kpi/worst-cells

Worst Cells. Module: Worst-performance analysis.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/WorstPerformanceController.java:22 -> worstCells().

Other declared aliases: /kpi/worst-cells

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| metric | query / String | Yes | Actual KPI metric to rank; obtain it from this dataset's discovered metrics. |
| limit | query / Integer | No | Requested page/result size; service-specific bounds apply. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/worst-cells?fileId=500&metric=rrcsetup
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-129 | GET /api/v1/kpi/worst-sites

Worst Sites. Module: Worst-performance analysis.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/WorstPerformanceController.java:30 -> worstSites().

Other declared aliases: /kpi/worst-sites

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| metric | query / String | Yes | Actual KPI metric to rank; obtain it from this dataset's discovered metrics. |
| limit | query / Integer | No | Requested page/result size; service-specific bounds apply. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/worst-sites?fileId=500&metric=rrcsetup
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

### API-130 | GET /api/v1/kpi/worst-cells/detail

Worst Cell Detail. Module: Worst-performance analysis.

Declaration: listed in running OpenAPI at capture. Source: backend-java/src/main/java/com/network/monitoring/kpi/api/WorstPerformanceController.java:38 -> worstCellDetail().

Other declared aliases: /kpi/worst-cells/detail

Access: Anonymous GET bypasses parsing only without Authorization; supplied tokens are validated. Valid installed license required (otherwise HTTP 402). No additional role assertion is declared in this controller; do not infer frontend route guards as backend checks. Service/data-scope checks still apply where implemented.

| Field | Where / Java type | Required / default | Meaning |
| --- | --- | --- | --- |
| fileId | query / Long | Yes | Uploaded dataset ID; use a KPI or site upload appropriate to this API, not a job/report ID. |
| cellName | query / String | Yes | See the module contract and controller/service validation for accepted values. |
| metric | query / String | Yes | Actual KPI metric to rank; obtain it from this dataset's discovered metrics. |

Example URL (synthetic IDs; required query fields shown):

```text
GET http://localhost:3001/api/v1/kpi/worst-cells/detail?fileId=500&cellName=SYNTHETIC-CELL&metric=rrcsetup
```

Request JSON: none. Send path/query parameters or an empty body as declared above.

Success response: HTTP 200. Synthetic partial example: demonstrates envelope/key fields only; dynamic data may add fields and depend on job state.

```json
{
  "success": true,
  "message": "Illustrative success message; exact wording varies by operation.",
  "data": {}
}
```

## Appendix A. Request/response record field reference

These fields are extracted from actual Java record declarations used by the public contracts. Outcome describes the JSON stored inside a native run's result_json string, not an extra top-level object. Constraint names are copied from source; a request object's required flag does not mean every property inside it is mandatory. Service-level cross-field checks in the module notes also apply. Entity/Map response content can contain additional fields.

### alarm/dto/AlarmConfiguration.java:Zone

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| id | long | No bean constraint on this field; see service validation |
| companyId | long | No bean constraint on this field; see service validation |
| code | String | No bean constraint on this field; see service validation |
| name | String | No bean constraint on this field; see service validation |
| active | boolean | No bean constraint on this field; see service validation |
| version | int | No bean constraint on this field; see service validation |

### alarm/dto/AlarmConfiguration.java:Circle

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| id | long | No bean constraint on this field; see service validation |
| companyId | long | No bean constraint on this field; see service validation |
| zoneId | long | No bean constraint on this field; see service validation |
| code | String | No bean constraint on this field; see service validation |
| name | String | No bean constraint on this field; see service validation |
| active | boolean | No bean constraint on this field; see service validation |
| version | int | No bean constraint on this field; see service validation |

### alarm/dto/AlarmConfiguration.java:Alias

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| id | long | No bean constraint on this field; see service validation |
| companyId | long | No bean constraint on this field; see service validation |
| circleId | long | No bean constraint on this field; see service validation |
| alias | String | No bean constraint on this field; see service validation |
| canonical | boolean | No bean constraint on this field; see service validation |

### alarm/dto/AlarmConfiguration.java:Feed

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| id | long | No bean constraint on this field; see service validation |
| companyId | long | No bean constraint on this field; see service validation |
| sourceKey | String | No bean constraint on this field; see service validation |
| name | String | No bean constraint on this field; see service validation |
| oem | String | No bean constraint on this field; see service validation |
| technology | String | No bean constraint on this field; see service validation |
| fileFormat | String | No bean constraint on this field; see service validation |
| sheet | String | No bean constraint on this field; see service validation |
| sourceTimezone | String | No bean constraint on this field; see service validation |
| dumpType | String | No bean constraint on this field; see service validation |
| circleId | Long | No bean constraint on this field; see service validation |
| active | boolean | No bean constraint on this field; see service validation |
| version | int | No bean constraint on this field; see service validation |

### alarm/dto/AlarmConfiguration.java:Resolution

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| input | String | No bean constraint on this field; see service validation |
| status | String | No bean constraint on this field; see service validation |
| circle | Circle | No bean constraint on this field; see service validation |

### alarm/dto/AlarmConfiguration.java:ZoneCreate

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| code | String | No bean constraint on this field; see service validation |
| name | String | No bean constraint on this field; see service validation |

### alarm/dto/AlarmConfiguration.java:ZoneUpdate

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| name | String | No bean constraint on this field; see service validation |
| active | boolean | No bean constraint on this field; see service validation |
| version | int | No bean constraint on this field; see service validation |

### alarm/dto/AlarmConfiguration.java:CircleCreate

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| zoneId | long | No bean constraint on this field; see service validation |
| code | String | No bean constraint on this field; see service validation |
| name | String | No bean constraint on this field; see service validation |

### alarm/dto/AlarmConfiguration.java:CircleUpdate

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| zoneId | long | No bean constraint on this field; see service validation |
| name | String | No bean constraint on this field; see service validation |
| active | boolean | No bean constraint on this field; see service validation |
| version | int | No bean constraint on this field; see service validation |

### alarm/dto/AlarmConfiguration.java:AliasCreate

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| circleId | long | No bean constraint on this field; see service validation |
| alias | String | No bean constraint on this field; see service validation |

### alarm/dto/AlarmConfiguration.java:FeedCreate

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| sourceKey | String | No bean constraint on this field; see service validation |
| name | String | No bean constraint on this field; see service validation |
| oem | String | No bean constraint on this field; see service validation |
| technology | String | No bean constraint on this field; see service validation |
| fileFormat | String | No bean constraint on this field; see service validation |
| sheet | String | No bean constraint on this field; see service validation |
| sourceTimezone | String | No bean constraint on this field; see service validation |
| dumpType | String | No bean constraint on this field; see service validation |
| circleId | Long | No bean constraint on this field; see service validation |

### alarm/dto/AlarmConfiguration.java:FeedUpdate

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| name | String | No bean constraint on this field; see service validation |
| oem | String | No bean constraint on this field; see service validation |
| technology | String | No bean constraint on this field; see service validation |
| fileFormat | String | No bean constraint on this field; see service validation |
| sheet | String | No bean constraint on this field; see service validation |
| sourceTimezone | String | No bean constraint on this field; see service validation |
| dumpType | String | No bean constraint on this field; see service validation |
| circleId | Long | No bean constraint on this field; see service validation |
| active | boolean | No bean constraint on this field; see service validation |
| version | int | No bean constraint on this field; see service validation |

### alarm/dto/AlarmReport.java:AgeBands

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| under12Hours | long | No bean constraint on this field; see service validation |
| from12To48Hours | long | No bean constraint on this field; see service validation |
| above48Under72Hours | long | No bean constraint on this field; see service validation |
| atLeast72Hours | long | No bean constraint on this field; see service validation |

### alarm/dto/AlarmReport.java:Group

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| zone | String | No bean constraint on this field; see service validation |
| circleId | Long | No bean constraint on this field; see service validation |
| circle | String | No bean constraint on this field; see service validation |
| technology | String | No bean constraint on this field; see service validation |
| observedRecords | long | No bean constraint on this field; see service validation |
| includedRecords | long | No bean constraint on this field; see service validation |
| critical | long | No bean constraint on this field; see service validation |
| major | long | No bean constraint on this field; see service validation |
| minor | long | No bean constraint on this field; see service validation |
| serviceAffectingRecords | long | No bean constraint on this field; see service validation |
| knownDownSites | long | No bean constraint on this field; see service validation |
| unmappedCatalogue | long | No bean constraint on this field; see service validation |
| unmappedSites | long | No bean constraint on this field; see service validation |
| downSiteAge | AgeBands | No bean constraint on this field; see service validation |

### alarm/dto/AlarmReport.java:Summary

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| contractVersion | String | No bean constraint on this field; see service validation |
| zones | List<Group> | No bean constraint on this field; see service validation |
| circles | List<Group> | No bean constraint on this field; see service validation |
| observedRecords | long | No bean constraint on this field; see service validation |
| includedRecords | long | No bean constraint on this field; see service validation |
| unmappedCatalogue | long | No bean constraint on this field; see service validation |
| knownDownSites | long | No bean constraint on this field; see service validation |
| pendingOperationalInputs | List<String> | No bean constraint on this field; see service validation |
| notes | List<String> | No bean constraint on this field; see service validation |

### alarm/dto/AlarmImport.java:AlarmRow

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| sourceRow | int | No bean constraint on this field; see service validation |
| oem | String | No bean constraint on this field; see service validation |
| technology | String | No bean constraint on this field; see service validation |
| circle | String | No bean constraint on this field; see service validation |
| resource | String | No bean constraint on this field; see service validation |
| name | String | No bean constraint on this field; see service validation |
| alarmCode | String | No bean constraint on this field; see service validation |
| alarmText | String | No bean constraint on this field; see service validation |
| sourceSeverity | String | No bean constraint on this field; see service validation |
| customerClassification | String | No bean constraint on this field; see service validation |
| raisedAt | Instant | No bean constraint on this field; see service validation |
| supplementaryInformation | String | No bean constraint on this field; see service validation |
| sourceAnnotations | Map<String,String> | No bean constraint on this field; see service validation |

### alarm/dto/AlarmImport.java:Preview

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| contractVersion | String | No bean constraint on this field; see service validation |
| sheet | String | No bean constraint on this field; see service validation |
| sourceTimezone | String | No bean constraint on this field; see service validation |
| rowsRead | int | No bean constraint on this field; see service validation |
| validRows | int | No bean constraint on this field; see service validation |
| rejectedRows | int | No bean constraint on this field; see service validation |
| missingSourceSeverity | int | No bean constraint on this field; see service validation |
| missingAlarmCode | int | No bean constraint on this field; see service validation |
| sample | List<AlarmRow> | No bean constraint on this field; see service validation |
| issues | List<RowIssue> | No bean constraint on this field; see service validation |
| issuesTruncated | boolean | No bean constraint on this field; see service validation |
| warnings | List<String> | No bean constraint on this field; see service validation |

### alarm/dto/AlarmModels.java:Catalog

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| id | long | No bean constraint on this field; see service validation |
| oem | String | No bean constraint on this field; see service validation |
| technology | String | No bean constraint on this field; see service validation |
| matchField | String | No bean constraint on this field; see service validation |
| matchValue | String | No bean constraint on this field; see service validation |
| classification | String | No bean constraint on this field; see service validation |
| included | Boolean | No bean constraint on this field; see service validation |
| serviceAffecting | Boolean | No bean constraint on this field; see service validation |
| siteDown | Boolean | No bean constraint on this field; see service validation |

### alarm/dto/AlarmModels.java:Site

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| id | long | No bean constraint on this field; see service validation |
| circleId | long | No bean constraint on this field; see service validation |
| objectKey | String | No bean constraint on this field; see service validation |
| siteCode | String | No bean constraint on this field; see service validation |

### alarm/dto/AlarmModels.java:Outcome

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| inputRows | int | No bean constraint on this field; see service validation |
| distinctAlarms | int | No bean constraint on this field; see service validation |
| duplicateRows | int | No bean constraint on this field; see service validation |
| unmappedCatalogue | int | No bean constraint on this field; see service validation |
| unmappedSites | int | No bean constraint on this field; see service validation |
| previousRunId | Long | No bean constraint on this field; see service validation |
| newAlarms | int | No bean constraint on this field; see service validation |
| continuingAlarms | int | No bean constraint on this field; see service validation |
| notObservedAgain | int | No bean constraint on this field; see service validation |
| identityPolicy | String | No bean constraint on this field; see service validation |
| automaticClearingEnabled | boolean | No bean constraint on this field; see service validation |

### alarm/dto/AlarmAutomation.java:Route

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| key | String | No bean constraint on this field; see service validation |
| circleId | Long | No bean constraint on this field; see service validation |
| recipients | List<String> | No bean constraint on this field; see service validation |
| mode | String | No bean constraint on this field; see service validation |

### alarm/dto/AlarmAutomation.java:Policy

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| key | String | No bean constraint on this field; see service validation |
| circleId | Long | No bean constraint on this field; see service validation |
| classification | String | No bean constraint on this field; see service validation |
| minAgeHours | int | No bean constraint on this field; see service validation |
| recipients | List<String> | No bean constraint on this field; see service validation |
| mode | String | No bean constraint on this field; see service validation |

### alarm/dto/AlarmAutomation.java:Watch

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| feedId | long | No bean constraint on this field; see service validation |
| folder | String | No bean constraint on this field; see service validation |
| timestampSource | String | No bean constraint on this field; see service validation |
| windowStart | String | No bean constraint on this field; see service validation |
| windowEnd | String | No bean constraint on this field; see service validation |
| timezone | String | No bean constraint on this field; see service validation |

### alarm/dto/AlarmAutomation.java:Settings

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| version | int | No bean constraint on this field; see service validation |
| enabled | boolean | No bean constraint on this field; see service validation |
| freshnessMinutes | int | No bean constraint on this field; see service validation |
| reports | List<Route> | No bean constraint on this field; see service validation |
| escalations | List<Policy> | No bean constraint on this field; see service validation |
| watches | List<Watch> | No bean constraint on this field; see service validation |

### reporting/service/DailyKpiCalculator.java:Metric

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| key | String | No bean constraint on this field; see service validation |
| aggregation | Aggregation | No bean constraint on this field; see service validation |
| direction | Direction | No bean constraint on this field; see service validation |
| unit | String | No bean constraint on this field; see service validation |
| numeratorKey | String | No bean constraint on this field; see service validation |
| denominatorKey | String | No bean constraint on this field; see service validation |
| scale | Double | No bean constraint on this field; see service validation |
| relativeChangeEnabled | Boolean | No bean constraint on this field; see service validation |

### reporting/service/DailyKpiCalculator.java:Profile

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| groupBy | List<String> | No bean constraint on this field; see service validation |
| metrics | List<Metric> | No bean constraint on this field; see service validation |
| minimumBaselineDays | int | No bean constraint on this field; see service validation |
| minimumSamplesPerDay | int | No bean constraint on this field; see service validation |
| requireEqualSampleCounts | Boolean | No bean constraint on this field; see service validation |

### reporting/service/CommonReportModules.java:Options

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| modules | List<String> | No bean constraint on this field; see service validation |
| rcaConfig | Map<String, Object> | No bean constraint on this field; see service validation |

### reporting/mail/service/ReportMailProfile.java:ReportMailProfile

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| mode | String | No bean constraint on this field; see service validation |
| recipients | List<String> | No bean constraint on this field; see service validation |
| attachments | List<String> | No bean constraint on this field; see service validation |
| allowPartial | boolean | No bean constraint on this field; see service validation |
| enabled | boolean | No bean constraint on this field; see service validation |

### nightly/service/NightlyProfile.java:NightlyProfile

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| enabled | boolean | No bean constraint on this field; see service validation |
| folder | String | No bean constraint on this field; see service validation |
| filePattern | String | No bean constraint on this field; see service validation |
| identityFields | List<String> | No bean constraint on this field; see service validation |
| columnMapping | Map<String, String> | No bean constraint on this field; see service validation |
| deliveryMode | String | No bean constraint on this field; see service validation |
| cutoffTime | String | No bean constraint on this field; see service validation |
| reportOptions | CommonReportModules.Options | No bean constraint on this field; see service validation |

### reporting/mail/api/ReportMailController.java:Resolution

| JSON property | Java type | Declared annotations |
| --- | --- | --- |
| outcome | String | No bean constraint on this field; see service validation |
| evidence | String | No bean constraint on this field; see service validation |

## Appendix B. Important JSON contracts and limits

RCA Java-facing keys: vendor, preset, sheetName, columnMapping, kpiMapping, selectedKpis, kpis, rcaThresholds, severityThresholds, anomalyThresholds, dateMode, startDate/endDate, preStartDate/preEndDate, postStartDate/postEndDate, inputRowLimit, responseRowLimit. pre_post needs both date windows. KPI target/weight/direction definitions must match the reviewed engine configuration.

RCA result fields are engine/vendor-dependent, including Primary Cause, Secondary Causes, Evidence, Top KPI Drivers and vendor recommendation columns. Observed is source-row evidence; period mean/median are descriptive values for that cell/KPI/period, not interchangeable with observed. Evidence may be returned as text; do not assume a separate universal target JSON field.

Native OSS provisional identity: OEM + RAT + resource + code (or text when code absent) + raised time. Missing alarms are not confirmed cleared. DELTA/clear adapters, customer operational MS2/LR inputs and exact customer-template acceptance remain outside the completed snapshot path.

Native ingestion: 10 MiB, 5000 non-empty rows, 80 columns, 4000 characters/cell. Preview returns at most 50 valid samples and 100 issues; durable import parses the full bounded input. Catalogue rules require explicit included/serviceAffecting/siteDown flags. Native files and configuration are frozen; retry keeps them, while explicit failed-run replacement captures a new revision.

Native Settings example includes empty watches/escalations for a disabled preview. Optional watch declaration (requires administrator-configured ALARM_INPUT_ROOT and an existing approved relative folder):

```json
{
  "feedId": 20,
  "folder": "customer-a/nokia-4g",
  "timestampSource": "SIDECAR",
  "windowStart": "01:00",
  "windowEnd": "02:00",
  "timezone": "Asia/Kolkata"
}
```

Watch timestampSource: SIDECAR or FILE_MODIFIED_UTC. SIDECAR needs <source-file>.ready.json containing snapshotAt and the source SHA-256 checksum. Windows are local-time, end-exclusive; optional schedule needs complete start/end/timezone fields. SMTP outside the sending window is suppressed; source freshness and completeness checks also apply.

Native escalation policy declaration (example values, not the customer's approved policy):

```json
{
  "key": "synthetic-critical-stage",
  "circleId": 10,
  "classification": "CRITICAL",
  "minAgeHours": 12,
  "recipients": [
    "reviewer@example.invalid"
  ],
  "mode": "CAPTURE"
}
```

Common-report mail reconciliation: outcome ACCEPTED/CAPTURED/NOT_ACCEPTED plus evidence. Native OSS reconciliation: outcome CONFIRMED_SENT/CONFIRMED_NOT_SENT plus note. These are different contracts. ACCEPTED is not inbox confirmation; UNKNOWN is not safe to auto-resend.

## Appendix C. Deployment and integration status

See each operation's declaration status for the source/runtime difference. New native-alarm operations require migrations 008/009 through the deployment process and a matching rebuilt/restarted backend. Enable ALARM_WORKFLOW_ENABLED only after the schema is ready. Folder and SMTP remain opt-in; the BRD's XXX/YYY/ZZZ placeholders are not real recipient lists.

Known source-level integration distinctions: the /companies GET legacy filter bypass can conflict with its service's authenticated super-admin check; /users creation does not accept ADMIN although newer alarm checks recognize it; global Swagger Bearer configuration does not model all legacy read bypasses. This documentation records current behaviour and does not change application authorization.

Supporting operating guides: reports/brd-review/alarm-workflow-delivery.md and alarm-master-data.md. Runtime Swagger can differ until the current build is deployed. Configuration availability, real OEM export coverage, customer-volume performance, backup/restore and production mail delivery require separate acceptance checks.

## Appendix D. Included files and validation

| File | Use |
| --- | --- |
| Backend_API_Handbook.docx | Editable Word handbook. |
| Backend_API_Handbook.pdf | Shareable/readable PDF. |
| Backend_API_Handbook.md | Searchable source-oriented Markdown. |
| backend-api-inventory.json | All source-derived parameters, aliases, examples, source locations and controller hashes. |
| Network_Monitoring.postman_collection.json | One canonical request per logical operation; configure baseUrl/token/IDs before use. |
| documentation-validation.json | Coverage counts, source/OpenAPI comparison, artifact validation and generation time. |

No business mutations or external emails were executed. Endpoint examples are illustrative rather than captured customer responses; partial envelopes are labelled. The generator verifies every mapped controller operation is included, every JSON-body operation has a reviewed request example, alias/parameter coverage is consistent and all API IDs appear in generated Word/PDF output.

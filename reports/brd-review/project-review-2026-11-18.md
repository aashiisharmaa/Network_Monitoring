# Project review and remaining work

Reviewed 18 November 2026. The user's instruction separates pending functional implementation from optional appearance/usability additions. Only the functional operational-input increment and supporting checks/documentation were implemented. New UX ideas below were not implemented.

## Scope and evidence

The source inventory covers 167 Java files across bootstrap, identity, platform, licensing, ingestion, KPI, spatial, RCA, reporting, nightly, shared and alarm modules, plus 117 frontend source files. Critical paths reviewed include tenant authorization, ingestion/job boundaries, native alarm classification/reporting/delivery, operational inputs, portal navigation, dashboard, map loading and report flows. This is a module-wide source and executable-check review, not a guarantee that every screen and customer-scale scenario is correct.

The customer BRD audit and embedded workbook evidence remain in `brd-code-audit-2026-11-17.md`. This increment adds the previously missing explicit MS2/LR/site operational model, role-controlled capture/history, Excel/PDF context, frozen email input revisions and schema readiness. See `alarm-operational-inputs.md` for implementation, JSON and deployment.

| Area | Current evidence | Remaining work |
| --- | --- | --- |
| Native alarm workflow | Source snapshots, catalogue/mapping, cohort summaries, outbox/escalations and operational revision integration tested | Real OEM raw-file validation; approved alarm identity and delta/clear semantics; customer policy/template UAT |
| Tenant security | New native/operational endpoints check persisted identity, role and company | Legacy public read routes and legacy blank-role behavior need a dedicated access-policy migration before customer deployment |
| KPI/RCA/prediction | Existing modules and contract preserved; full backend build/tests and frontend unit suite run | Customer-scale benchmark, statistical acceptance and domain threshold sign-off; no claim that synthetic tests validate prediction accuracy |
| Reporting/nightly | Existing report portal synthetic flow checks cover generation, download and delivery reconciliation | Real schedule/deadline/volume acceptance, SMTP staging acceptance, customer recipient configuration and recovery drills |
| Map/portal | Synthetic map loading check and navigation/dashboard checks | Real provider/network/offline map acceptance and realistic site-count profiling |
| Runtime | Frontend and backend health return 200 | Running backend is older than source: its OpenAPI has no native alarm paths; deploy/migrate before live functional acceptance |
| Delivery quality | Backend 172 tests: 141 passed, 31 skipped, zero failures/errors; frontend 27 passed; production build passed | 31 skipped checks are not passing evidence. Lint has 15 existing warnings; bundle-size warning remains |

## Customer deployment blockers and technical priorities

1. **Legacy access policy:** SecurityConfig intentionally permits anonymous GET access to several dashboard/KPI/site/upload/settings/company routes. RoleAuthorizationService can return a null company for anonymous/unassigned users, and some downstream queries add a company predicate only when nonnull. Blank legacy roles can be treated as super admin. This static review identifies potential unscoped access; it is not an exploit test. Native alarm guards do not repair unrelated legacy routes. Require explicit persisted roles/company assignments, decide permitted public health/auth endpoints, migrate the legacy contract and run negative access tests across all modules before exposing customer data. This broad policy change was not silently applied to existing public contracts in this increment.
2. **Deploy the built source and schema:** migration 010 and the operational flag are pending on the real environment. Existing process health does not prove the new features are running. Keep database backups and an explicit rollback plan.
3. **Real customer configuration/UAT:** catalogue, site mapping, routing, approved responsibility rules, alarm cadence and escalation reset semantics must come from accepted customer data. Example workbooks with hardcoded counts or external formulas cannot supply missing facts. Native delta/clear lifecycle support remains unaccepted; reported UP notes never clear alarms.
4. **Production configuration:** validate signing/license/database settings without development defaults; choose explicit schema validation/none and approved migrations. The runner's legacy ORM update fallback is not an appropriate production migration strategy. No credential values were inspected or printed.
5. **Capacity and recovery:** benchmark representative upload size, retained history, simultaneous exports, memory/DB pool pressure, file readiness and the 02:00 KPI report deadline. Test backup restoration, worker lease recovery and uncertain email outcomes. SMTP acceptance is different from confirmed inbox delivery.
6. **Maintainability:** split very large existing files through focused, behavior-preserving increments. Current hotspots include MapPage (~7,010 lines), WorstCell (~3,795), KpiView (~3,027), UploadService (~1,979) and KpiController (~1,808). Extract domain use cases and view/data hooks around stable contracts; avoid mass renames while functionality is being accepted. Add repeatable CI for Maven, frontend checks and synthetic browser fixtures. These broader refactors were not bundled into this functional change.

## Optional improvements: suggestions only

| Idea | User benefit | Suitable place |
| --- | --- | --- |
| Consistent company/file/date scope and freshness presentation | Makes the active data context and stale data easier to understand | Existing page heading/filter areas |
| Guided source onboarding with mapping/validation checklist | Helps admins complete configuration without technical guidance | Existing Uploads/Admin pages; do not restore the removed Alarm Operations page |
| 7/30-day native alarm trend charts | Makes recurring alarms and improvement visible | Existing Alarms summary |
| PDF preview and saved report revisions | Lets users inspect and later retrieve the exact downloaded report | Existing Reports/Alarms views |
| A focused needs-attention list for missing, failed or unmapped data | Reduces searching across job/report screens | Existing dashboard/job history |
| Saved column/filter presets and sticky site identifiers | Improves large-table work and narrow-screen scanning | Existing KPI, site and alarm tables |
| Contextual actions in the existing Ctrl+K search | Speeds common tasks after page navigation | Existing quick-navigation dialog |
| Clear SMTP accepted / uncertain / confirmed-delivery explanations | Prevents users interpreting transport acceptance as inbox receipt | Existing report delivery history |

These additions are not present as new features from this task. Visual redesign, new dashboards/charts and extra workflow pages were deliberately left as recommendations in accordance with the user's request.

## Local verification evidence

Artifacts are under `reports/performance/project-review-2026-11-18/`: source inventory, XML-derived backend totals, build/lint/unit logs, synthetic alarm browser results, map loading and report portal logs, and read-only runtime checks. Generated API documentation is in `reports/api-reference/structured-backend/` (190 operations, 308 expanded paths, 114 PDF pages). The main handbook opened in the IDE was not overwritten.

The old dashboard browser check expected an overview card that is absent from current DashboardPage source. It was updated to check current performance/inventory content, navigation and keyboard/mobile behavior, and its API mock now recognizes canonical `/api/v1` paths. No overview card was restored. The review also found that account management was advertised to ordinary users despite its existing page being super-admin only; the shared navigation/search registry now respects that existing restriction. Worst-cell analysis remains a shared analysis route. Mocked browser checks do not contact real customers or write customer records.

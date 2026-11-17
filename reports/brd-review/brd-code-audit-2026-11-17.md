# Customer alarm BRD: full reread, code audit and applied changes

Review date: 17 November 2026, Asia/Kolkata.
Source: `C:\Users\Vinfocom\Downloads\Alarm Monitoring BRD_Document.docx`.
SHA-256: `b2068ff800f1e6f22a9ff0f6dfa96d8a96568776c4a222537ec1fafee0ddbb15`.

## Evidence reviewed

The reread covered all 24 nonempty paragraphs, the OSS workflow diagram and all
three embedded workbooks: 12 sheets, including six hidden sheets, 29,362 nonempty
cells, 7,791 formulas and their cached values, merged ranges and external-link
metadata. There were no cell comments. Full cell/formula evidence is retained
locally in `reports/performance/brd-review-2026-11-17/full-document-audit.json`.
Workbook formulas/macros were not executed; external referenced workbooks/services
were not opened. The customer source file was not modified.

Sheets: `Summary.`, `Site Down & LR Sites`, `LR Pending`, `Unique_Alarm`, `Sheet2`,
`SA Alarm 4G`, `Sheet1` in the national example; `Critical Alarms`, `Alarm Dump`,
`Down Sites`, `Summary Data` in the circle example; and `Sheet1` in the 2G example.

The hidden national sheets include examples for Nokia, Ericsson, ZTE, Huawei and
Samsung. They are useful fixtures; they are not proof of every vendor's unmodified
native OSS layout or a complete customer classification/inclusion catalogue.

## Requirement-to-code status after this change

| BRD requirement | Existing capability | Applied in this change | Remaining for acceptance |
| --- | --- | --- | --- |
| 1. Native OEM dumps, 2G/4G, mutually agreed cadence | Bounded CSV/XLSX parsing, durable frozen imports, explicit source timezone/OEM/RAT, approved-folder discovery, local-time windows | Preserve source Remarks, L2 Remarks and Circle Update; explicit v2 adapter with compatible v1 replay, including old unmapped-formula handling | Validate full native-format matrix, completeness and identity/lifecycle cases. Configure real source paths/cadence. Delta processing remains rejected; absence is not automatic clearance. |
| 2. Customer Critical/Major/Minor classification and inclusion | Frozen catalogue rules, explicit inclusion/service-affecting/site-down flags, ambiguous matches rejected, UNMAPPED preserved | Structured summaries keep source severity separate; source notes such as Resolved do not change classification or imply clearance | Populate the authoritative classification/inclusion rules. The BRD says the lists will be provided; individual source severities and sheet titles are not an approved catalogue. |
| 3. National/circle dashboards and supplied report formats | Source snapshots, basic PDF/CSV and source comparisons | National/circle/zone/RAT summaries in the existing Alarms page; typed Excel workbook; richer national/circle PDF; known mapped down-site age bands; source coverage and mapping warnings | Exact customer-template parity, authoritative MS2/LR/site-up/ownership/recovery inputs, responsibility/GPS/VSWR mapping and historical time-slot comparison acceptance remain open. Current Excel exposes missing inputs as blanks and a pending-input sheet. |
| 4. National/circle auto email | Scoped recipient routes, captured report cohort, PDF/CSV attachments, CAPTURE/SMTP, fenced outbox claims, retries and UNKNOWN reconciliation | National/circle PDF attachments benefit from the same new summaries; authenticated company discovery now parses supplied bearer tokens | Actual XXX/YYY recipients, approved final email layout and provider/recipient end-to-end delivery. The embedded circle example has a greeting, but not an approved configurable mail specification. Excel is available for download; automatic email attachments remain PDF/CSV. |
| 5. Escalation email | Explicit age/classification/circle policies and per-identity/policy decisions | Operational notes remain evidence; they cannot silently clear/reset escalation state | Actual ZZZ recipients, frequency, escalation stages/reset rules and lifecycle acceptance. Defaults are not a substitute for an agreed policy. |

The existing common KPI 01:00–02:00 requirement is separate. This alarm BRD explicitly
leaves dump frequency to agreement; it must not be silently converted into the
KPI nightly schedule.

## User-facing changes

Open **Alarms → National & Circle Summary** on `/alarms`.

- Super Admin explicitly selects a company. Assigned users use their allowed company;
  the UI does not discover other companies for an ordinary assigned user.
- Select a report cutoff in local time; the request sends an explicit UTC timestamp.
- Review zone/circle/2G/4G counts, customer inclusion and classification, service-affecting
  records, known mapped down sites and source-based age bands.
- Choose national or circle scope and download Excel/PDF/CSV.
- Inspect source timestamps, missing source coverage and unresolved mapping warnings.
- Summary tables page at 25 rows; switching company clears old data, cancels stale
  requests and exposes loading/error/retry states. Mobile tables scroll within their
  container rather than widening the page.

The separately removed `/alarm-operations` page, sidebar entry and its files remain
removed. Existing uploaded-alarm and generated-KPI views remain in the Alarms page.
Their search requests commit on Load/Enter rather than sending a request per keystroke.

## Calculation and evidence rules

Known down-site identity is `(circle ID, technology, mapped site code)`. Multiple
included alarms on that site count once. Unmapped sites are reported separately;
zero known down sites is not proof that all actual sites are up.

Age is measured from raised time to that record's source snapshot, not the browser
clock. For repeated observations of one mapped site, use the greatest observed age
across selected sources. The explicit bands follow the hidden source formula:
`<12h`, `12h <= age <= 48h`, `48h < age < 72h`, `age >=72h`. Original labels/formulas
elsewhere in the workbook disagree; no conflicting example labels are silently adopted.

Alarm totals are source records deduplicated within their feed. They are not certified
cross-feed unique incident totals. Source absence and a textual Resolved annotation
do not prove OSS clearance. Missing source severity/code and customer classifications
remain distinguishable.

Excel contains Zone Summary, Circle Summary, Alarm Evidence, Sources and Quality,
and Pending Inputs. Evidence includes source/import timestamps, full source text and
operational annotations. MS2/LR/owner/reason fields remain blank when not provided;
no old historical example counts or workbook hardcoded additions are seeded.

Source strings are written as text cells rather than Excel formulas. CSV escaping
retains formula-injection protection. XLSX streaming retains all evidence rows beyond
the in-memory window. Existing PDF font limitations remain: Unicode source evidence
is retained in Excel/UTF-8 CSV; the summary PDF uses the existing ASCII-safe font path.

## Backend structure and compatibility

- `AlarmReportRepository` owns scoped lightweight metadata, summary and evidence reads.
  Summary/PDF aggregation omits large raw JSON and alarm-text payloads. Repeated reads
  of entire frozen configuration JSON are avoided. This is not a benchmark claim.
- `AlarmSummaryCalculator` is deterministic and side-effect free. `AlarmWorkbookRenderer`
  owns typed streaming Excel output. HTTP controllers retain binding/content types.
- Existing limits remain: 200 sources, 5,000 observations per imported source, 50,000
  observations per combined report, and 20 MiB Excel/CSV artifacts. Summary/export
  admission is bounded; exceeding a budget produces an explicit error.
- Existing routes/parameters are preserved: 186 logical HTTP operations and 304 expanded
  method/path combinations. `xlsx` is an added allowed format on existing alarm export
  paths; `reportSummary` and `sourceAnnotations` are additive JSON fields; CSV appends
  three annotation columns after the existing fields.
- New imports freeze `oss-snapshot-v2`. Existing v1 queued/failed imports replay with
  their original annotation-column/formula policy. Existing successful history remains
  immutable; old JSON without sourceAnnotations reads as an empty map.
- Public read-only GET routes still allow their existing anonymous path. If a bearer
  header is supplied it is validated and populates the principal. An invalid supplied
  token does not silently become an authenticated request. Existing client public-read
  fallback remains; native company/report requests require authentication explicitly.

## Verification

- Backend: **162 tests, 131 passed, 31 environment-dependent skips, zero failures/errors**.
  Passing checks include real disposable-MySQL scope/export tests, v1/v2 replay,
  annotations, exact age boundaries, distinct site/RAT handling, streamed Excel,
  text-cell safety and authentication/filter behaviour.
- Frontend: **27 existing tests passed**, changed files lint-clean, production build
  passed. Browser checks use intercepted synthetic APIs and verify both admin and
  company-user scope, downloads, failures/retries, stale-state clearing, paging and
  desktop/mobile layout. They do not prove a deployed backend or customer-data UAT.
- API source contract check passed for all 186 existing operations. The updated handbook
  records XLSX, summary/annotation contracts and optional-authentication behaviour.
- Synthetic reports generated through production renderer code are available locally:
  [Excel](../performance/brd-review-2026-11-17/sample-oss-national-report.xlsx),
  [PDF](../performance/brd-review-2026-11-17/sample-oss-national-report.pdf), and
  [CSV](../performance/brd-review-2026-11-17/sample-oss-national-report.csv).

Reproduce from the repository root:

```powershell
.\scripts\test_alarm_configuration.ps1 -FullSuite -BuildDirectory target/brd-verification-20261117
python scripts/verify_backend_api_contract.py
python scripts/test_alarm_summaries_browser.py
```

## Deployment and remaining work

The application/customer database was not changed and real SMTP messages were not
sent. Test migrations ran only against a disposable database. New code must be built
and the backend restarted on the intended environment; verify existing migrations
008/009 and company/master/source configuration there. No new schema migration is
required by these changes. Runtime OpenAPI information retained by the handbook is
an earlier capture, not evidence that the newly changed source is deployed.

Customer acceptance is still incomplete. Remaining code/data includes full native
OEM/lifecycle validation, an agreed immutable operational-input model and sources for
MS2/LR/site inventory/recovery, exact template/time-slot parity, any final mail-template
customization, deployment volume/SMTP/restore testing and customer UAT. Configuration
APIs exist, but the user-removed configuration workspace has not been reintroduced.
Do not describe these changes as complete delivery of every BRD requirement.

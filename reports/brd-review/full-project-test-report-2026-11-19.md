# Full project testing report

19 November 2026, Asia/Kolkata. This report distinguishes tested source, synthetic browser fixtures, real external-map checks and the currently running older backend.

## Result

| Layer | Result | Evidence |
| --- | --- | --- |
| Java/JUnit including real disposable MySQL and Python-engine integration | **187 passed, zero failures/errors** | 188 discovered; the one skipped case is the opt-in database bootstrap helper, executed separately before the suite |
| Frontend unit tests | **35 passed** | Cache/request isolation, cancellation, mutation generations, aggregation, evidence and report utilities |
| Python unit tests | **12 passed** | RCA means/export/calculation behavior and temporary HTTP benchmark-client tests |
| Synthetic browser suites | **8 passed** | Navigation/dashboard, Alarms, KPI/Worst Cell, general frontend, map controls, map readiness, RCA export, report portal |
| Real Google Maps production startup | **Passed** | Real SDK/tiles, deferred synthetic data/renderer, camera usable, no recorded provider/runtime errors |
| API contract inventory | **190 contracts preserved** | Source method/path/parameter checks; this is not 190 successful live business requests |
| Frontend production build | **Passed** | Current source builds successfully |
| Frontend lint | **Zero errors, 15 existing warnings** | Primarily existing hook-dependency warnings; not reported as warning-free |

Database tests use an owned temporary MySQL server at localhost:13316. Native alarm tests use `alarm_config_test`; KPI/report/nightly tests use a separate `kpi_project_test`. Neither URL is the application database. The test server is stopped after the run; its logs/data are retained under backend target for inspection. No customer database was seeded, reset or migrated, and no external email was sent.

## Functional coverage

| Area | What was exercised |
| --- | --- |
| Registration/login/session | Strong/weak/missing/duplicate registration inputs; existing first-account role policy; BCrypt hashing; invalid and blocked logins; legacy password upgrade; session creation/logout; JWT identity, unique session IDs, expiry, malformed/foreign signatures and missing/default secret rejection |
| Local license | Missing/valid license; tampering; expired/future/revoked licenses; wrong device; wrong signing key; rejected upload leaves the previously installed valid license unchanged |
| Site upload | Actual CSV parsing and MySQL persistence; file/company provenance; PCI/TAC preservation; duplicate rows; invalid coordinates and missing identities rejected |
| KPI ingestion | Real CSV ingestion, duplicate/merge protection, persistence, captured datasets, transaction rollback and failed-run behavior |
| Dashboard/KPI | Dimension SQL equivalence across file/company scopes and collations; cache correctness; frontend aggregate calculations, filters, large synthetic observation fixtures and stale-response handling |
| Worst Cell | Real worker aggregation, source changes cancelling obsolete work, failure badges, 50-row results, paged heatmaps/search and responsive layout |
| RCA | Real Python analysis with captured-input identity; period mean/evidence; retained analysis and workbook rendering; repeated export reuses artifact bytes; progress, failed-job recovery and authenticated browser download |
| Native alarms | Tenant access, configuration, catalogue/site mappings, import/snapshot comparisons, correction revisions, classification/age summaries, scope isolation, bounded batched report reads, operational zero/unknown values, stale-edit conflicts and no automatic clearance inferred from missing rows |
| Native report delivery | Pinned source/operational revisions, captured MIME/PDF/CSV, retries, uncertain-send reconciliation and escalation idempotency |
| Daily comparison/reporting | Real calculators, D-1/baseline comparisons, threshold/worst-cell/RCA report adapters, PDF/XLSX/JSON rendering, checksum/tamper checks and bounded partial coverage |
| Nightly workflow | Stable-folder input, duplicate/replay handling, ingestion → analysis → report → captured mail integration, missing input, retries, stale profile/import identity and 02:00 IST missed-deadline handling |
| Background work | Capacity rejection, recovery after interruption, bounded concurrency, artifact paths/integrity and optional prediction warmup |
| Map | All source pages, coordinates, filtering/fit, legend search, layer toggles, handovers, independent scaling, source retries, actual PDF exporter, mobile overflow, overlays/listener cleanup and real-provider startup readiness |
| Common frontend | Sidebar/search roles, Ctrl+K keyboard selection, focus restoration, mobile navigation, binary downloads, pending states and failure recovery |

Warning/ERROR log lines from deliberately injected failed imports, missing nightly input and missed deadlines are expected negative-case evidence. They are not JUnit failures; inspect the final XML totals when assessing success.

## New test support added

- `scripts/test_alarm_configuration.ps1 -ProjectSuite` now creates an additional disposable KPI schema instead of disabling all KPI DB tests. It never inherits an unrelated KPI database URL. Original environment values are restored after execution.
- `IsolatedProjectDatabaseTest` is an explicit, guarded fixture bootstrap: it only accepts localhost:13316/kpi_project_test, generates entity tables and applies the project's migrations. It is not an application feature and is skipped during normal discovery.
- Added actual site-upload MySQL validation, authentication-service, JWT and local-license regression tests.
- `scripts/generate_project_test_data.py` produces 1,152 synthetic hourly observations, six cells, three sites, two OSS example dumps, an RCA cache/config and deliberately invalid fixtures. Nothing is automatically uploaded. The generated KPI sample uses 12–19 November; the report renderer's separate built-in fixture uses its own clearly labelled reporting date.
- `scripts/test-project.ps1` provides a repeatable entry point for database/source tests, frontend unit/lint/build, Python tests, contract checks, aggregation benchmark and browser suites. Its exit-code handling was checked on Windows PowerShell, including unittest's normal stderr progress output.

## View the outputs

All files below are local, explicitly synthetic demonstration evidence:

- `reports/performance/full-test-2026-11-19/samples/proper-common-report-demo.pdf`: **11-page** common report through the actual calculators, real Nokia rule engine and PDF renderer; six cells, three sites, eight metrics, 1,152 observations and 48 comparison rows. Coverage limitations are printed in the report.
- Companion `proper-common-report-demo.xlsx` and `.json`: complete comparison/evidence artifacts.
- `sample-oss-national-report.pdf`, `.xlsx` and `.csv`: native snapshot/classification summary from isolated alarm integration tests.
- `sample-report-mail.eml` and `nightly-replay-sample.eml`: locally captured messages; not inbox-delivery proof.
- `test-data/`: clearly named synthetic CSV inputs, invalid files, expected-count manifest and RCA benchmark config.
- `backend-summary.json`, `browser-suite.json` and individual logs: totals and execution evidence.
- Browser screenshots are also retained under `reports/performance/` and `reports/performance/project-review-2026-11-18/`; these depict intercepted synthetic APIs, not customer imports.

The synthetic RCA benchmark used a 324,176-byte cache. The run records actual analysis, first workbook creation and repeated cached export timings in `test-data/rca-export-benchmark.json`. It is a small local sample, not a production-volume SLA.

## Re-run

From the repository root in PowerShell:

```powershell
.\scripts\test-project.ps1 -PythonExecutable "C:\Users\Vinfocom\AppData\Local\Programs\Python\Python313\python.exe" -LiveMap
```

Requirements: local MySQL binaries used by the disposable runner, Java/Maven, Node/npm, Python analysis dependencies, Playwright/Chrome, and existing Vite dev/preview servers on 5173/4173. `-LiveMap` additionally needs internet and a working configured Google browser key. Without that flag the eight browser suites use instrumented/intercepted providers. `-SkipBackend` / `-SkipBrowser` allow explicitly scoped reruns. Review skipped tests in Surefire XML; do not interpret process exit 0 as proof of every external integration.

## Still not established

1. **Running-app deployment/authentication:** the existing local backend's read-only OpenAPI still exposes zero native alarm-workflow/operational-input paths. The previously observed authenticated company-list error is not declared fixed by source tests. Deploy/restart and verify the intended current build before customer onboarding; do not treat the old running process as this tested source.
2. **One connected browser-to-backend acceptance run across the complete portal:** browser suites intercept business APIs; Java integration tests separately use real MySQL/services. They do not replace an unmocked full-stack customer UAT session.
3. **Real email delivery:** capture/transport-state logic is tested. Actual customer's SMTP credentials/recipients, inbox delivery, bounces and mailbox presentation are not verified.
4. **Customer semantics and volume:** raw OEM variants, approved classifications/targets, alarm identity and delta/clear rules, representative concurrent load and SLA acceptance still need customer-specific evidence. Rule-based prediction scores are not validated future-outcome accuracy.
5. **Security/deployment scope:** native tenant guards and auth/license behavior have regression coverage; the previously identified legacy public-read/blank-role policy is not a completed penetration test or policy migration. Offline installer/upgrade/restore and clock-tampering resistance are not certified here. A live internet-backed Google map does not prove offline maps work.

No removed Alarm Operations page was restored, no synthetic findings were presented as customer results, and this test run did not publish or push code.

## Later connected portal setup on the same date

The stale-runtime finding in item 1 above was subsequently addressed by activating
the current Java backend on port 3001 with native alarm endpoints. A dedicated
company ADMIN account successfully loads native summaries and operational inputs.
Rwanda datasets and labelled synthetic fixtures are now in the application database.
Connected dashboard, KPI, map and alarm pages were checked without business API
interception. The live map reached its ready state with the demo site/KPI sources.

See [Portal self-testing](portal-self-testing-2026-11-19.md) for actual dataset IDs,
manual test steps, account scope and remaining external-integration limitations.
This updates runtime/setup evidence; it does not turn the earlier mocked browser
suites into an unmocked complete customer acceptance test.

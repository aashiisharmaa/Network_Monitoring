# Alarm snapshot workflow: implementation and operating guide

16 November 2026. Requirements source: customer-supplied
`Alarm Monitoring BRD_Document.docx`, including its embedded workbooks. The BRD is
treated as the requirements baseline. Its statement that Vedang will provide the
catalogue, inclusion list, XXX/YYY/ZZZ recipients, email format and escalation rules
does not supply those actual values. No fabricated customer rules or addresses are
seeded by this implementation.

## Delivered software

- Native OSS source workflow, separate from the existing KPI-generated alarms.
- Tenant-owned catalogue rules and explicit object-to-physical-site mappings.
- Authenticated multipart import, bounded immutable source staging, SHA-256 checks,
  durable run ledger and captured feed/circle/site/catalogue/automation configuration.
- Full bounded parsing, rather than persisting the preview's first 50 records.
  CSV/XLSX aliases support the five OEMs represented in the customer workbooks.
- Atomic observation commits in batches of 250. Invalid imports commit no partial
  observations. Unknown catalogue/site mapping is reported explicitly; unknown circle
  or conflicting identity records fail validation.
- Same feed/snapshot/content reuses its existing run. Identical content in a genuinely
  new snapshot is retained as a new observation. Different content cannot overwrite
  a successful snapshot. Explicit correction creates a new revision only for the
  latest FAILED run; the older revision becomes SUPERSEDED and its source is retained.
- Frozen-source retry, lease-based worker recovery and fencing tokens. Parsing is
  outside database transactions; final ownership and tenant/feed state are checked
  before the observation/result transaction commits.
- Previous-snapshot comparison: new, continuing and not-observed-again identities.
  The baseline is the closest earlier successful snapshot of that feed, captured
  when importing; it is not represented as an invented previous calendar day.
- Source observation history plus explicit operator ACKNOWLEDGED/RESOLVED/NOTE
  events. Operator review is recorded separately from source OSS state.
- National/circle overview using the latest successful snapshot at/before asOf
  for each currently active feed, with source timestamps visible.
- Source PDF summary + complete UTF-8 CSV, national/circle cohort PDF/CSV exports,
  spreadsheet formula escaping, age in hours at the source snapshot time, and raw
  supplementary evidence. PDFs limit detailed source rows to 100 and point to the
  full CSV. Non-ASCII source text remains complete in CSV; PDF uses a portable ASCII
  rendering and explicitly notes that limitation.
- National/circle mail routing with frozen source-run cohorts. Identical route and
  cohort combinations are deduplicated even when multiple feeds finish together.
- Configurable classification/age escalation stages, grouped into scoped messages,
  with durable once-per-identity/policy decisions rather than per-tick mail spam.
- Real MIME capture with PDF/CSV attachments; reuse of the existing SMTP transport
  when explicitly configured. Pre-send failures use a 30-second backoff with at most
  five attempts (or the lower configured budget). Sending uncertainty is UNKNOWN,
  never an automatic resend. Provider reconciliation has explicit authenticated APIs.
- Folder polling restricted to an approved input root, stable-file detection,
  optional checksum sidecars and explicit timestamp conventions. Optional local-time
  processing window/deadline supports the requested 01:00–02:00 use case without
  imposing that cadence on every alarm feed.
- Dedicated bounded executor, two source-staging slots, one interactive export slot,
  parser capacity control, per-company queued-import cap and explicit processing limits.
- Portal `/alarm-operations`: company scope, configuration forms, catalogue/site
  setup, source activation, import/error/replacement flows, evidence/history drawer,
  comparison cards, national/circle overview, downloads, automation settings and
  delivery history. Reads are available to the owning USER; mutations require ADMIN
  for that company or SUPER_ADMIN with explicit scope.
- Shared sidebar layout fallback fixes content being hidden beneath the fixed
  sidebar when generated utility CSS did not provide its margin rule.

This is a usable **snapshot-processing path**, not a claim that every native vendor
format or all customer business definitions have passed acceptance.

## Release and configuration

1. Back up the target database and inspect its existing schema. Apply numbered
   migrations `008_alarm_master_data.sql`, then `009_alarm_workflow.sql`. Both are
   additive; MySQL DDL commits implicitly. The existing `companies.id` must be a
   compatible signed BIGINT. Do not use `IF NOT EXISTS` as a schema-repair mechanism.
2. Build the Java backend using bundled Maven `package`, and build the frontend with
   `npm run build`. Deploy/restart the rebuilt backend/frontend together.
3. Set `ALARM_WORKFLOW_ENABLED=true`. Its default is false so a build does not start
   querying new tables before an upgrade. `ALARM_WORKFLOW_STORAGE` defaults to
   `uploads/alarm-workflow`; retain this directory with the DB in backups.
4. In Alarm Operations, select the company, create zones/circles, enter approved
   aliases, and create the source profile. Specify the actual source timezone and
   raw sheet explicitly. Add the customer's inclusion/classification rules and
   exact OSS-object site mappings, then activate the reviewed source profile.
5. Import a representative file with an explicit UTC/offset snapshot time. Inspect
   every row error, unmapped rule/site count, duplicate count and source comparison.
   Verify counts against the customer's expected output before relying on reports.
6. Configure report/escalation recipients using CAPTURE first. Enable automation
   before submitting a new snapshot: each run captures the automation settings at
   submission, so changing routes does not retroactively send historical imports.
   Download the captured `.eml` and inspect its PDF/CSV attachments and circle scope.
7. For automatic folder input, set `ALARM_INPUT_ROOT` to the administrator-approved
   root. Add a relative watch folder and explicit timestamp convention in the UI.
   Until a path is supplied, folder input stays unconfigured; manual imports work.
8. SMTP additionally requires existing `REPORT_MAIL_OUTBOUND_ENABLED`, SMTP host,
   sender, credentials/TLS settings and approved SMTP routes. No real addresses were
   configured or emails sent during development/testing.
9. Keep the backend running as a service during scheduled processing. An open browser
   is not required. The computer/service, source files, DB and mail provider must be
   available; a 02:00 inbox-delivery guarantee is not established by these tests.

The application's/customer's database was not migrated and its running backend was
not restarted by this change. The new APIs need the release steps above before the
live portal can use them. Synthetic browser checks use intercepted APIs.

## Folder contract and schedule example

The folder is polled once per minute, inside its configured local window if present.
Each watch examines at most the 200 newest matching files and stages at most two new
slots per poll. Original input files are not moved/deleted. Files must be regular,
not symlinks, at most 10 MiB, and stable for 60 seconds. A ready sidecar is preferred
when the publisher can provide one; file-modified mode requires agreement that the
modification time is an appropriate source snapshot time.

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

For `daily.csv`, publish `daily.csv.ready.json` after the complete file:

```json
{"snapshotAt":"2026-11-16T01:00:00+05:30","checksum":"64-character SHA-256 of the source bytes"}
```

SIDECAR uses that timestamp/checksum. FILE_MODIFIED_UTC instead uses the stable file's
UTC modification time. Missing/invalid readiness metadata or unavailable folders are
not treated as an empty healthy snapshot. Folder status is available in the portal.
The schedule end is exclusive, and midnight-crossing windows are supported. SMTP
outside the configured origin-feed window is SKIPPED_DEADLINE. A stale/incomplete
cohort is suppressed for SMTP; CAPTURE remains available for historical previews.

## API additions

Prefix: `/api/v1/alarm-workflow/companies/{companyId}`. All endpoints are authenticated
and scoped server-side, including downloads, job lookup, history and outbox lookup.

| API | Purpose |
| --- | --- |
| POST `/feeds/{id}/imports` | Multipart file + explicit snapshotAt, returns durable run (202) |
| GET `/imports`, `/imports/{id}` | Run status, attempts, frozen-source checksum, result and errors |
| POST `/imports/{id}/retry` | Retry FAILED run with original source/configuration |
| POST `/imports/{id}/replace` | Explicit corrected file/current configuration as a new failed-slot revision |
| GET `/imports/{id}/observations` | Keyset evidence pagination, afterId/limit, max 200 |
| GET `/imports/{id}/report/pdf` or `/csv` | Source report, optional circleId |
| GET `/dashboard` | As-of source cohort and national/circle aggregates |
| GET `/dashboard/report/pdf` or `/csv` | National/circle report, required asOf, optional circleId |
| GET/POST/DELETE `/catalogue[/{id}]` | Exact CODE/TEXT customer rules; conflicting multiple matches fail |
| GET/POST/DELETE `/feeds/{id}/sites[/{siteId}]` | Exact object-to-site mapping |
| GET `/feeds/{id}/alarms/{key}/history` | Bounded source observation and operator history |
| POST `/feeds/{id}/alarms/{key}/events` | Explicit action and required reason |
| GET/PUT `/automation` | Versioned routes, escalation stages, folder watches and freshness limit |
| GET `/status`, `/deliveries` | Processing/folder/delivery diagnostics |
| POST `/deliveries/{id}/retry` | Retry FAILED delivery, never UNKNOWN |
| POST `/deliveries/{id}/reconcile` | UNKNOWN + CONFIRMED_SENT/CONFIRMED_NOT_SENT and evidence note |
| GET `/deliveries/{id}/capture` | Tenant-scoped CAPTURED MIME download |

SMTP ACCEPTED records provider acceptance, not arrival in the recipient's inbox.
Operator reconciliation requires actual provider evidence; it does not perform a
provider lookup on the user's behalf.

## Boundaries still requiring implementation/acceptance

- Catalogue/inclusion values, recipient lists, final mail format, escalation stages,
  source cadence/completeness, and source/site mappings must be populated from the
  customer inputs identified by the BRD. The supplied source severity is preserved
  independently; it is not assumed to be the approved customer catalogue.
- Provisional identity is OEM + RAT + resource + code (text when code is absent) +
  raised time. No automatic OSS clearing/reopening is enabled. Vendor-specific event
  identity, delta/clear adapters and approved completeness semantics remain future
  work. DELTA profiles can be configured but this importer rejects DELTA processing,
  rather than displaying a change batch as complete current network state.
- MS2/LR pending, circle/remote-doable categories and recovery reasons require their
  authoritative operational inputs and agreed counting rules. No fictional counts
  are placed into the customer template. Operator notes do not substitute for them.
- CSV/XLSX parsing is bounded to 5,000 source records, 80 columns and 4,000 characters
  per cell; larger native exports require streaming work and volume validation.
  National exports are bounded to 200 active feeds, 50,000 records and a 20 MiB CSV
  artifact budget. Do not split one logical snapshot arbitrarily to bypass limits.
- Overlapping feeds may contain the same incident. National totals are explicitly
  labelled observed records, deduplicated within each source; cross-feed incident
  reconciliation is not certified by the provisional identity policy.
- Interactive national reports choose currently active feeds at the requested asOf;
  retired feeds remain available through individual import history. Previously
  planned mail cohorts are pinned by run IDs. Historical master activation intervals
  and exact customer-template equivalence have not been implemented/approved.
- Adapter version `oss-snapshot-v1` is frozen with each run. A build that does not
  support a saved version refuses its retry. Future adapter changes must bump the
  version and supply explicit compatibility or corrected-revision handling.
- Customer-volume performance, production SMTP, restore drills, exact report-format
  UAT, Windows service/desktop installer alignment and offline-map/licensing hardening
  are not established by this snapshot feature's tests.

## Verification and samples

`scripts/test_alarm_configuration.ps1 -FullSuite` creates an isolated local MySQL
instance on port 13316, bypasses the application's datasource, and stops only its
owned test process. Tests exercise atomicity, idempotency, corrections, frozen retry,
checksums, leases/concurrency, tenant isolation, site/catalogue mapping, historical
comparison, PDF/CSV rendering, scoped multi-OEM cohorts, captured MIME, escalation
deduplication, pre-send retry backoff and approved-root folder discovery. Schedule
boundary tests include the 01:00–02:00 Asia/Kolkata window.

Production build, changed-file ESLint, the existing frontend unit suite, synthetic
desktop/mobile alarm browser tests, existing portal navigation checks and map-loading
ordering checks were run. Customer DB imports and external email sends were not used.

Final verified run: 145 backend tests discovered, 114 passed, 31 environment-dependent
checks skipped, zero failures/errors. All 16 workflow MySQL integration cases and
both schedule-boundary tests executed. The existing 27 frontend unit tests passed;
production build, changed-file lint and the three browser scripts above passed.
These are development verification results, not production load or delivery-SLA evidence.

Synthetic examples (not customer network results):

- `reports/performance/sample-oss-national-report.pdf` and companion CSV.
- `reports/performance/sample-oss-alarm-report.pdf` and companion CSV.
- `reports/performance/oss-alarm-operations-desktop.png` / `oss-alarm-operations-mobile.png`.
- `reports/performance/oss-alarm-evidence-desktop.png`.

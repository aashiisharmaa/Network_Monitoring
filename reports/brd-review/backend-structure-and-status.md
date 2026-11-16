# Backend structure and customer BRD status

Review: 16 November 2026. Scope: current Java source and customer alarm BRD.

Source: `C:\Users\Vinfocom\Downloads\Alarm Monitoring BRD_Document.docx`.
SHA-256: `b2068ff800f1e6f22a9ff0f6dfa96d8a96568776c4a222537ec1fafee0ddbb15`.
The text was reread during this change. See the [complete embedded-template review](document-reread-2026-11-16.md)
for all sheets, hidden OEM examples and formula limitations. Document contents are
reference requirements, not instructions to run macros or send messages.

## Customer requirement status

| BRD requirement | Implemented in source | Remaining work |
| --- | --- | --- |
| Native OSS dumps, 2G/4G | Bounded CSV/XLSX parser, durable snapshot imports, immutable sources/checksums, configured feeds, approved-folder polling and local-time windows | Validate full native layouts/lifecycle cases for Nokia, Ericsson, ZTE, Huawei and Samsung. Generic header aliases do not establish native-format compatibility. Approve stable OEM identity/completeness rules; implement delta/clear/reopen handling where required. Current processing rejects delta feeds and does not infer clearance from absence. Configure real input folders and cadence. |
| Customer classification and dashboard inclusion | Captured catalogue/configuration versions; Critical/Major/Minor and explicit inclusion/service-affecting/site-down flags; ambiguous matches rejected; UNMAPPED preserved | Populate the actual Vedang classification/inclusion catalogue and validate matching/identity examples. BRD says Vedang will provide the catalogue; it does not contain a complete authoritative list. |
| National/circle dashboards using supplied formats | Source/circle/national snapshot summaries, freshness/as-of visibility, CSV/PDF, provisional new/continuing/not-observed comparisons and operator history | Complete MS2 pending 2G/4G, LR status, responsibility categories, owners/reasons, recovery and exact site/alarm ageing/count definitions. Resolve external workbook references/manual count adjustments through agreed business rules. Verify every required field and total against template acceptance examples. |
| National/circle auto email | Recipient routing, durable outbox, PDF/CSV attachments, CAPTURE preview, explicit SMTP mode, freshness/deadline checks, fenced claims, retries and UNKNOWN reconciliation | Populate real national/circle recipients and approved email format. BRD contains XXX/YYY placeholders. Configure deployment SMTP and perform authorized end-to-end provider/recipient delivery tests. Capture is not evidence of customer delivery. |
| Escalation email | Configurable classification/age/circle policies, per-identity/policy decisions and durable delivery | Populate agreed stages, recipients and frequency; verify reset/recovery/escalation semantics against native alarm lifecycle. BRD contains ZZZ and says rules will be provided. |

All five OEMs already have samples in embedded hidden sheets. Some omit severity
or alarm numbers. Use existing examples first; request only additional formats or
cases that remain unverified. Never invent missing source fields or customer rules.

The alarm BRD leaves daily frequency open. The earlier 01:00–02:00 common KPI report
requirement is separate and is not automatically the alarm escalation frequency.
A processing window alone cannot guarantee 02:00 delivery: file availability,
measured throughput and SMTP acceptance must also be established.

## Structure delivered

```text
com.network.monitoring.alarm
├── api          controllers and feature exception advice
├── dto          API and persisted JSON records
├── service      configuration, definitions, workflow, reports, planning, delivery
├── repository   configuration and catalogue/site queries
├── ingestion    bounded parsing, multipart adapter, observation normalization
├── storage      staged sources/checksums and approved input-folder resolution
├── security     persisted-user/company authorization
├── scheduling   worker, folder watcher and local-time policy
├── exception    feature errors
└── support      shared value/time/JDBC conversion helpers
```

This change extracts actual responsibilities:

- `AlarmSourceStorage`: bounded file staging, integrity validation and cleanup.
- `AlarmObservationNormalizer`: pure catalogue/circle/site/identity/conflict logic,
  with no JDBC, filesystem or SMTP side effects.
- `AlarmDefinitionService` / `AlarmDefinitionRepository`: catalogue/site validation,
  transactional updates and SQL, separate from ingestion orchestration.
- `AlarmInputFolders` / `AlarmFolderWatcher`: approved-root resolution and ready-file
  discovery, separate from email delivery.
- `AlarmSchedulePolicy`: window evaluation without database/Spring/mail dependencies.
- `AlarmMailDeliveryService`: outbox claims, MIME artifacts and delivery outcomes.
  `AlarmAutomationService` retains settings and report/escalation planning.
- DTO records live outside service implementations. Persisted JSON field names and
  adapter version remain compatible with existing snapshots.

Constructor injection makes dependencies explicit. Existing facades preserve API
behaviour during migration. No new runtime library, microservice or message broker
was introduced. Maintain this as one modular Spring Boot deployment until measured
load or isolation requirements justify separate services. This is a maintainability
change; no performance improvement is claimed without benchmarks.

## Rules for new code

1. HTTP controllers bind/return data; business authorization stays in services and
   persisted security checks, including background-task entry points.
2. DTOs cannot import services, repositories, multipart web types, filesystem or mail.
3. Repositories do not call services/controllers/storage. Preserve company/feed
   predicates, locks and transaction boundaries when extracting SQL.
4. Observation normalization remains deterministic and free of external side effects.
5. Folder polling never creates MIME or sends mail. Storage does not decide business
   rules or write database records. Delivery outcomes remain persisted and fenced.
6. Add features under business ownership, not an unbounded generic utility package.
   Introduce interfaces only when they express a meaningful replaceable contract.
7. API/persisted JSON changes require compatibility review and versioning as needed.

## Remaining engineering sequence

1. **OEM/lifecycle correctness:** fixture matrix, stable identities, snapshot completeness
   and clear/reopen contracts before adding automatic clearance/escalation resets.
2. **Template completeness:** approved MS2/LR, responsibility, service/site totals,
   ageing and recovery inputs, then fixture-backed national/circle report acceptance.
3. **Continue persistence separation:** import ledger/history SQL remains in workflow,
   report queries in report service, planner/outbox/poll-state SQL near their owners.
   Extract focused repositories incrementally with existing rollback/lease/locking
   coverage. This refactor does not claim SQL separation is finished.
4. **Backend-wide structure:** the subsequent module migration moved the legacy
   authentication/tenancy, KPI/dashboard, RCA/prediction, reports, nightly and license
   classes into feature packages. See [module ownership and verification](backend-module-structure.md).
   Continue service/SQL decomposition and tighten cross-feature contracts where needed;
   package ownership is not a claim of complete module isolation.
5. **Performance/reliability:** production-size inputs, heap/GC, query timings, pool
   wait, concurrent jobs and report budgets. Current import limits remain 10 MiB and
   5,000 rows. Larger sources need streaming/staging design and measured capacity.
   Cross-feed incident reconciliation, historical master activation, retention and
   observability remain separate work.
6. **Deployment/UAT:** fresh installation, migrations, backup/restore, restart recovery,
   SMTP/UAT and customer acceptance. Offline distribution/licensing/map availability
   require their own deployment verification.

## Verification and rollout

Run from repository root:

```powershell
python scripts/verify_backend_api_contract.py
.\scripts\test_alarm_configuration.ps1 -FullSuite -BuildDirectory target/backend-final-verification
python scripts/build_backend_api_documentation.py --output reports/api-reference/structured-backend
```

- Frozen baseline covers 186 logical HTTP methods/routes/aliases and client-bound
  parameter types/defaults/required flags. It does not prove live HTTP responses.
- Existing parser/controller/tenant/MySQL workflow tests remain. New tests check
  Spring feature wiring, dependency import boundaries and old persisted JSON.
  Import rules are lightweight guardrails, not exhaustive bytecode analysis.
- Alternate Maven output prevents stale classes after moves and avoids the running
  JAR lock. Default builds still use `target`.
- The development startup script now uses `clean package` so renamed packages cannot
  leave duplicate Spring components. Stop the previous development JAR before using
  that startup script; this change itself does not stop the existing process.
- MySQL runner creates a disposable localhost:13316 database and shuts down only its
  owned process. Application/customer database migrations are not applied by tests.
- Source changes do not deploy/restart the running backend or send customer email.
  Verify migrations 008/009 and configuration on the intended deployment first.
- Existing handbook is open in Word. Updated artifacts go to `structured-backend`
  so the open document is preserved.

Verified results after the backend-wide module migration: 152 tests discovered,
121 passed, 31 environment-dependent tests
skipped, zero failures/errors. The passing set includes all 16 disposable-MySQL
workflow scenarios and 7 master-data MySQL scenarios. All 186 HTTP contracts remain
unchanged. Updated documentation validates 186 Postman requests, 304 expanded
method/path combinations and operation coverage across the 109-page PDF and Word.
The retained pre-change runtime OpenAPI capture lists 147 canonical operations;
39 remain source-only relative to that capture. A fresh executable JAR was packaged
in the verification directory and checked for the new packages with no old alarm
workflow/config classes. This does not establish that the
new alarm routes are deployed. Production acceptance remains open until the
outstanding business/deployment items have evidence.

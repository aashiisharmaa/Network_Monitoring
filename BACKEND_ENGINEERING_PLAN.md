# Backend optimization and nightly reporting plan

Status: foundation work started on 2026-11-05. Dashboard optimization, RCA cache
correctness, background-worker limits/restart recovery and the manual daily-ingestion
foundation are implemented locally. Versioned daily KPI snapshots and calendar-day /
seven-day comparison APIs are also implemented; portal/report presentation remains separate.

Update: first dashboard optimization implemented and verified locally on 2026-11-05.
Nightly ingestion, comparisons, reporting and email are still planned, not implemented.

## Agreed outcome

- Pick up daily files from a configurable folder; no folder path supplied yet.
- Append historical KPI data with duplicate and correction handling.
- Compare the latest complete data day with the previous day and previous seven days.
- Run applicable dashboard, threshold, alarm, worst-cell, RCA and prediction analyses.
- Show the same version of results in the portal and common customer report.
- Send a common report by email, targeting delivery by 02:00 Asia/Kolkata.
- Later allow customer-specific templates without rebuilding the pipeline.
- Missing data is missing, never an artificial zero. Date refers to source data date.

## Findings from the current source

- Spring Boot 3.5.0 / Java 17 target; MySQL and a configurable Hikari pool already exist.
- Async and scheduling are already enabled in Application.java.
- Upload and RCA jobs already persist status in MySQL. RCA reuses completed results
  by fileId and configuration hash. Do not rebuild existing functionality.
- Upload supports append/merge/overwrite and invalidates site predictions.
- Site prediction caches master/alarm context, but loads full repository tables.
  Any replacement must preserve cross-file neighbour context and company isolation.
- KpiCacheService serializes full JSON files; measure read/parse time and peak memory.
- Existing PERF_LOGS instrumentation is useful but not a complete performance baseline.
- Review RCA cache validity after appends, mapping changes and engine upgrades;
  fileId/configuration alone does not establish a complete data version.
- Job execution, stale-job handling and retries need review before sharing infrastructure
  with a nightly workflow. Existing async jobs are not automatically restartable batch jobs.

## Phase 0: baseline and correctness inventory (first)

Deliverables:

1. Read-only HTTP baseline script: scripts/benchmark-backend.py.
2. Capture first request separately from warmed requests; median, p95, errors and bytes.
3. Inventory dataset row counts, date span, columns, file size, hardware, commit and
   concurrent load for each representative small/medium/large fixture.
4. Extend measurements to authorized KPI, site, RCA and upload workflows with fixtures.
   Record RCA submit-to-completion time separately from HTTP job-submission latency.
5. Collect JVM heap/GC, CPU, DB query count/time and pool wait using protected
   Actuator/Micrometer or JFR instrumentation. Benchmark script alone cannot measure these.
6. Record regression fixtures: totals, cell counts, thresholds, RCA and date comparisons.

Run from project root:

    python scripts/benchmark-backend.py --fixture current-local-data
    python scripts/benchmark-backend.py --file-id 123 --fixture representative-medium

Optional authorization is read from KPI_BENCHMARK_TOKEN, never written to results.
Do not put credentials in URLs or fixture labels. Results are ignored local artifacts.
The script measures health/dashboard only; it is not proof that all workflows are fast.
Compare like-for-like fixtures and cache conditions. Ten samples are a smoke baseline;
use larger samples and representative concurrent workloads for capacity decisions.

Exit gate: reproducible measurements and correct outputs, then choose the first
measured bottleneck. No fixed speedup claim before before/after evidence.

Initial local baseline (2026-11-05, existing running backend, unscoped dashboard,
10 measured sequential requests after a first request and one warmup):

| Endpoint | Successful | Median | p95 |
| --- | --- | --- | --- |
| /health | 10/10 | 3.982 ms | 6.659 ms |
| /api/v1/dashboard/stats | 10/10 | 6471.216 ms | 7060.816 ms |

Artifact: reports/performance/baseline-20261105T043801819476Z.json (local, ignored).
Dataset label is current-local-data; runtime build identity, dataset size and concurrent
load were not established, so this is an initial observation, not a release benchmark.
First measured optimization target: dashboard summary. Profile its six merged-distinct
counts, total-record count and latest-upload lookup before changing queries. Source also
loads all sorted upload-history records to select one latest record; narrow that query
without changing output/ordering or company visibility. Do not ascribe all observed
latency to that lookup without query timing evidence.

## Phase 1: optimize existing workloads

### First completed change: dashboard dimension counts

Problem/evidence:
- Local database: 422,247 uploaddata rows, 609,196 kpidynamicdata rows, 60 upload-history rows.
- Existing dimension counts normalize/UNION values from both tables for every request.
- Initial per-query samples: cells 1329ms, sites 1245ms, technology 1451ms, groups 2340ms.
- A combined UNION ALL with six distinct aggregates was slower (~8493ms); rejected.
- Distinct raw dimension values without the leading fileId were also slower; rejected.

Implementation/reason:
- Preaggregate distinct (fileId, dimension) pairs before normalization and final UNION.
  This allows existing (fileId, dimension) indexes to use MySQL's loose index scan.
  EXPLAIN on the technology count confirmed "Using index for group-by".
- Read source-column collation metadata. Enable preaggregation only for
  utf8mb4_unicode_ci columns; otherwise keep original per-source normalization.
  Keep file/company predicates inside both source queries before deduplication.
- Preserve trimming, null/blank exclusion, case/accent comparison and cross-source deduplication.
- Add missing dynamic-data (fileId, groupsName) index via the additive SQL migration
  backend-java/db/migrations/001_dashboard_dynamic_groups_index.sql.
  Applied locally with nonblocking index creation; no historical rows modified.
  Other deployments must check INFORMATION_SCHEMA.STATISTICS and apply it once;
  this file is not automatically executed by Flyway or application startup.
- Use the existing limited latest-upload repository query instead of loading all history.

Validation:
- Maven tests: 4 passed, no skipped tests with KPI_TEST_DB_URL configured.
- MySQL integration fixture uses connection-local temporary tables. Covers six dimensions,
  16 file/company combinations, four optimized/fallback combinations, Unicode, whitespace,
  nulls, duplicates across files/sources and empty scopes; matches original query counts.
- Complete dashboard response data matches the previous running API for global scope,
  fileId=41 and a nonexistent file. Latest-upload output preserved.
- Rebuilt and restarted the local backend. Backend :3001 and frontend :5173 remain running.
- Ten warmed sequential HTTP measurements on current-local-data:

| Dashboard summary | Before | After |
| --- | --- | --- |
| Median | 6471.216 ms | 1467.652 ms |
| p95 | 7060.816 ms | 1591.687 ms |
| Successful measured requests | 10/10 | 10/10 |

Approximately 77% lower local median. These are sequential observations on a running
development machine, not a controlled concurrent-load or all-module capacity claim.
Before runtime build identity was not established; exact API result equality was checked.
Health timing fluctuated from ~4ms to ~13ms across runs; no health speedup claim.
Artifacts: reports/performance/dashboard-after.json, dashboard-correctness.json,
dashboard-query-profile.json and dashboard-index-migration.json (local/ignored).

Rollback/limits:
- Revert DashboardService/query-helper changes to restore original SQL.
- The added index can remain with old code; rollback DROP INDEX is included in the migration.
- Extra index consumes storage and adds write maintenance. Re-measure upload throughput
  and company-scoped/concurrent workloads in the next pass.
- No result-cache TTL introduced, so there is no new stale-dashboard cache behavior.
- No API/schema payload change. Existing company authorization and latest-upload scoping
  behavior are unchanged; audit those policies separately before customer deployment.

Next: scoped/concurrent dashboard profiling and bounded background processing before
enabling scheduled imports. RCA cache correctness is implemented below.

### Second completed change: RCA result-cache correctness

Problem: completed recommendation jobs were reusable by fileId and settings only.
Appending or replacing data under the same fileId could return old RCA output.

Implementation/reason:
- Reuse existing recommendation_jobs.configHash and its index; no schema migration
  or deletion of historical jobs is necessary for this change.
- New versioned SHA-256 identity includes primary cache bytes, recursively ordered
  request maps, Python engine sources (main.py/analyser.py/lb_service.py), presets,
  requirements and an explicit runtime revision. Array order is preserved.
- Old request-only hashes no longer match; a first new run recomputes the result.
- Settings are detached before asynchronous scheduling to avoid caller mutation.
- Validate/hash primary cache through a streaming JSON parser without retaining rows.
  Missing, malformed or empty primary data bypass result reuse, including database-only
  fallback workflows. Never assume file size/mtime proves unchanged contents.
- Stream cache read/write instead of holding both a large JSON string and its parsed map.
- Publish serialized cache with same-directory atomic replacement. Serialization failure
  preserves the previous file and cleans the staging file.
- A fixed set of 64 fair read/write lock stripes coordinates same-process readers and
  replacements on Windows, without an unbounded per-file lock map. Read-modify-write
  metadata updates hold the write lock as well. Atomic publication requires filesystem support.
- Hash the exact bytes used to load the RCA snapshot. The worker checks the queued key,
  actual loaded-input key and current key before completion. Changed inputs/engine fail
  with a rerun message instead of completing with a mismatched cached result.
- Recheck identity before returning a completed cache hit. Explicit historical job-ID
  lookup remains historical; it does not rewrite old job results as current data.

Why content hashing now:
- Covers existing append/overwrite and cache-metadata update paths without missing an
  invalidation hook or inventing an incomplete database version counter.
- Requires reading the primary cache for each identity check. A validated hash of the
  existing 78,001,638-byte file took 393ms in one local measurement, not an SLA.
  A stable cache-hit normally performs two checks. This is an explicit correctness cost.
- A future durable ingestion ledger can provide immutable dataset revisions/checksums
  and avoid repeated reads; it must publish those revisions consistently with the data.

Validation:
- 21 Java tests passed, zero skipped with MySQL/Python/performance-test inputs enabled.
- Real MySQL integration uses temporary recommendation_jobs tables and verifies reuse,
  service recreation, append/settings/engine changes and uncached fallback. Production
  job history is not changed by these tests; Python is stubbed in this persistence fixture.
- A separate real Python RCA test verifies actual output and loaded-snapshot identity.
- Tests cover queue-time changes, mid-run changes, A-to-B-to-A snapshot races,
  canonical settings, same-size data edits, malformed/empty input, serialization failure,
  cleanup and concurrent Windows readers/replacements.
- Restarted the local backend to activate the new behavior; runtime smoke results are
  captured in reports/performance/rca-cache-smoke.json (local/ignored).

Configuration/operations:
- app.rca-cache-revision reads RCA_CACHE_REVISION (default 1). The Windows runner
  also imports this key from root .env. Increment it when installed Python dependencies
  or external runtime behavior change without modifying tracked engine files.
- Bump RecommendationCacheKey.PIPELINE_VERSION when Java RCA preparation/output changes.
- Do not edit/deploy engine files while jobs are running. Multi-instance leases and
  distributed deployment coordination remain part of the orchestration phase.
- DB-only fallback is intentionally uncached; it does not gain a verified immutable snapshot
  from this change. Out-of-band DB edits with a stale primary cache remain an ingestion
  consistency problem, not something a result-cache identity can correct.
- This does not make file publication and database replacement one transaction; durable
  staging/publication and failure recovery belong to the ingestion-ledger work.
- At this stage, one old upload job (id 7, last updated 2026-07-02) was still PROCESSING.
  The following background-job change now recovers it to FAILED for review without replay.

Rollback: revert cache-key/job changes together. Historical jobs remain in place.
Atomic cache files retain the existing JSON payload format. Retain result-cache version
separation when rolling back so old request-only keys are not silently trusted again.

### Third completed change: bounded background jobs and restart recovery

Problem/evidence:
- Upload/RCA used unspecified @Async executors; prediction warmup used a fixed pool
  with an unbounded queue and unmanaged lifecycle.
- A rejected async submission or failed file staging could leave a persistent QUEUED row.
- Upload retries used a non-conditional status update, permitting duplicate dispatch.
- RCA polling could fail a valid waiting job merely because it was over three minutes old.
- Python output was read before an unlimited waitFor(), so hung Python could occupy a worker indefinitely.

Implementation/reason:

| Pool | Default workers | Waiting capacity | Reason |
| --- | --- | --- | --- |
| uploadJobExecutor | 1 | 4 | Bound simultaneous file parsing and database writes |
| rcaJobExecutor | 1 | 4 | Bound background RCA/Python memory and CPU demand |
| predictionWarmupExecutor | 1 | 2 | Optional warmup must not overwhelm required work |

- Explicit @Async qualifiers select each pool. Core=max worker count; AbortPolicy
  rejects excess work instead of running it on an HTTP thread or silently discarding it.
- The submitting Spring Security context is propagated and restored, so background
  company/user lookups retain their existing authorization context without leaking to later tasks.
- Normal graceful executor shutdown drains accepted tasks with a configured 30-second
  await period. A forced process stop can interrupt work; startup recovery handles its metadata.
- Upload staging/submission failures become persisted FAILED jobs. Full queues return
  HTTP 503, Retry-After: 30, JOB_CAPACITY and the persisted jobId. Staged input is retained
  for a safe retry when submission was rejected before any worker ran.
- Workers claim QUEUED -> PROCESSING conditionally before doing side effects. Upload
  retries claim FAILED -> QUEUED conditionally, so a duplicate retry cannot dispatch twice.
- Optional warmup coalesces pending requests for one file. Saturation skips warmup;
  prediction remains available on demand and the upload is not falsely failed.
- Startup recovery marks unfinished jobs FAILED; it never automatically replays appends.
  Queued uploads may be retried if the complete staged file exists. Interrupted/failed
  processing can have partial writes, so retrySafe=false blocks blind replay.
  Legacy FAILED uploads without an explicit safe-retry marker require data review too.
- Removed the polling-age timeout: waiting/long-running RCA jobs are not falsely expired.
- Python execution has a configurable deadline, default 900 seconds. Output is redirected
  to a temporary file before waiting, avoiding output-pipe deadlock. Returned diagnostics
  are capped at 64 KiB; timeout/interruption requests termination of the process tree,
  closes streams and frees the worker. RCA work directories are cleaned on failure.
- Upload-job retention cleanup now uses Asia/Kolkata explicitly.

Why not automatic upload replay:
Current import is not yet a transactional, idempotent ingestion pipeline. Killing or
replaying a partially written append could duplicate or corrupt history. The durable
ledger/staging phase must establish safe resume before enabling unattended retries.

Validation:
- 39 Java tests passed with zero failures/errors/skips, including existing real Python,
  MySQL and cache tests plus queue saturation/isolation, preserved security context,
  overload response mapping, staging failure, conditional claims and retry safeguards.
- Process tests verify timeout and interruption actually stop their child process, and
  200KB stdout cannot block execution or create an unbounded returned diagnostic string.
- MySQL recovery fixture uses temporary tables: queued/processing recover; completed
  history is preserved; upload retry safety differs correctly; repeated recovery is idempotent.
- Restarted local backend. Old upload job 7 is now FAILED with BACKEND_RESTARTED and
  retryable=false. It was not replayed and historical KPI rows were not changed.
- Live health/frontend return 200. Global, existing-file and missing-file dashboard data
  still match the earlier regression snapshots.
- Artifact: reports/performance/background-jobs-smoke.json (local/ignored).

Configuration (also imported from root .env by the Windows runner):
- UPLOAD_JOB_WORKERS / UPLOAD_JOB_QUEUE_CAPACITY: 1 / 4.
- RCA_JOB_WORKERS / RCA_JOB_QUEUE_CAPACITY: 1 / 4.
- PREDICTION_WARMUP_WORKERS / PREDICTION_WARMUP_QUEUE_CAPACITY: 1 / 2.
- RCA_PROCESS_TIMEOUT_SECONDS: 900 (Python execution only, not queue/preparation time).
- JOB_RECOVERY_ON_STARTUP: true for the current single-instance deployment.

Limits/rollout:
- This is single-instance restart recovery, not distributed leasing or checkpoint resume.
  Do not run multiple backend instances with global startup recovery enabled. Disabling
  recovery alone does not make the import pipeline distributed-safe; add ownership/leases first.
- Existing synchronous import routes and synchronous RCA run/export routes do not use
  these background queues. RCA process deadlines apply to run/export too; shared admission
  control across all entrypoints is separate work before multi-client capacity certification.
- Worker limits do not cap concurrent HTTP multipart staging, staging disk usage, total
  Python log bytes on disk or query costs of requests. Retention/disk quotas and workload
  measurements remain required. Rejected staged files follow existing job retention.
- Do not increase upload workers without measuring database capacity and introducing
  per-dataset serialization/idempotency for overlapping writes.
- An executing upload is not killed by an arbitrary elapsed-time watchdog. Hung Java/DB
  operations need measured query timeouts and ingestion recovery boundaries, not blind replay.
- If the database is unavailable while recording a failure, persistence cannot be guaranteed;
  startup recovery and operational monitoring remain necessary.
- Rolling back code does not replay/reclassify recovered FAILED jobs. Preserve retry-safety
  markers; inspect data before manually retrying interrupted or legacy uploads.

Framework reference: [Spring ThreadPoolTaskExecutor](https://docs.spring.io/spring-framework/docs/6.2.5/javadoc-api/org/springframework/scheduling/concurrent/ThreadPoolTaskExecutor.html).

Next: durable ingestion ledger, duplicate/correction policy and staging/publication
boundaries, then shared daily snapshots/comparisons. Folder scanning and outbound
email remain disabled/unimplemented until their required configuration is supplied.

### Fourth completed change: daily-ingestion ledger and transactional snapshot publication

This is the new explicit daily-import API, not automatic folder pickup or report generation.
The existing portal upload screen remains an ordinary upload workflow until integrated.

Problems addressed:
- Existing private runUpload @Transactional was not a proxy transaction boundary.
- Runtime CREATE TABLE checks in dynamic replacement/deletion could implicitly commit
  MySQL work. Schema setup now remains at service startup, not those write operations.
- Publishing a mutable cache file separately from committed KPI rows could expose
  mismatched generations and make a failed append difficult to retry safely.
- File checksums alone do not handle overlapping days or changed values under one cell/date.

Chosen design:
- Migration 002_kpi_ingestion_ledger.sql creates a durable source/run ledger and an
  active immutable snapshot pointer per dataset. It was applied locally before activation.
- Ledger records include source label/name/path, file checksum, target/company, saved
  mapping/grain configuration, state, attempt count, timestamps and result/error counts.
- Each source is copied to an application-owned UUID-named file before queuing. Clients
  cannot submit a filesystem path. A unique import key covers bytes, target/company,
  mapping and identity configuration; a renamed exact repeat returns the existing run.
  A failed duplicate returns that failed run rather than silently creating another attempt.
- identityFields is required. It must include canonical date and cellName, with extra
  dimensions explicitly supplied for the actual row grain. No default hourly/band/sector
  grain is guessed from unavailable customer files. Field order is canonicalized.
- Identical observations within/across files skip. A same-identity observation with
  different values rejects the entire file. Corrections are not silently overwritten.
  Existing ambiguous identities also reject registration until the dataset grain is reviewed.
- New dynamic records and metric values append in batches, without deleting/reinserting
  the whole dynamic history. Supported legacy metric rows use strict inserts (not INSERT IGNORE).
- TransactionTemplate uses the application's transaction manager and an uploadhistory
  row lock. The transaction includes accepted legacy/dynamic/metric rows, grain policy,
  the active snapshot pointer, persisted prediction invalidation and COMPLETED ledger state.
- Snapshot JSON is fully serialized and flushed to an immutable version file first.
  The DB publishes only its filename/revision/checksum. Rollback retains the previous
  pointer/rows and removes the new uncommitted version where cleanup is possible.
  A crash before commit can leave an unreferenced file, but cannot make that file active.
- Old <fileId>.json caches remain readable when no version pointer exists. All app cache
  reads resolve the committed pointer after publication. Metadata updates serialize on
  the same dataset row lock. Past version files remain available for provenance.
- Once registered as a managed daily dataset, ordinary append/merge/overwrite routes
  reject changes to that target; they must use ingestion to preserve its duplicate policy.
- Workers share the bounded upload pool. Queue-full runs are recorded FAILED with a 503
  response. Safe retry conditionally claims FAILED; a worker conditionally claims QUEUED.
- Startup recovery marks unfinished ledger runs FAILED without auto-replay. COMMITTED
  data and COMPLETED run state share one transaction, so a retry never assumes partially
  committed ledger-managed chunks. Source checksums are verified again before import.
- Cache warmup/invalidation runs after commit. Analysis/report status is explicitly PENDING;
  this phase does not claim that thresholds, alarms, RCA or reports have been regenerated.

Manual API contract (authenticated super admin using existing app authorization):

| Request | Purpose |
| --- | --- |
| POST /api/v1/ingestion/kpi | Stage a multipart file and queue an import |
| GET /api/v1/ingestion/runs?limit=20 | Bounded run history, without exposing source paths |
| GET /api/v1/ingestion/runs/{id} | Run state, attempt count, checksum and result |
| POST /api/v1/ingestion/runs/{id}/retry | Retry a failed run using its saved source/configuration |

Multipart fields for POST:
- file: CSV/XLSX/XLS input.
- fileId: existing KPI upload ID (history remarks must identify a [kpi] upload).
- identityFields: JSON array, e.g. ["date","cellName","tech","band"] ONLY if those
  fields exist and this is the confirmed grain. Hourly data also needs the relevant
  canonical dimensions field such as dimensions.hour; do not collapse it to daily rows.
- sourceName: optional audit label, default common.
- columnMapping: optional JSON object using the same mapping semantics as ordinary upload.

Result includes sourceRows, addedRows, duplicateRows, totalRows, legacyRowsAdded,
source data date range, datasetRevision and analysisStatus. A successful HTTP submission
does not imply import completion; inspect its run status. Conflicts fail without publishing data.

Validation:
- 45 Java tests passed with zero failures/errors/skips, including previous MySQL/Python tests.
- New real-CSV/MySQL integration tests shadow production tables with connection-local
  temporary tables: first import, renamed repeat, overlapping rows, correction rejection,
  forced failure after data writes/snapshot pointer publication, rollback file cleanup,
  safe retry/attempt count, queue rejection, checksum tampering, fixed grain, managed-target
  bypass rejection, bounded history and interrupted-run recovery.
- Auth/config tests reject non-admin access and malformed JSON.
- Live restart/smoke: backend/frontend/KPI columns return 200; anonymous ingestion history
  returns 401; global/existing-file/missing-file dashboard data match previous snapshots.
- Actual KPI counts remain 422247 legacy and 609196 dynamic. No customer daily import was
  performed: production ledger/pointer tables remain empty until configured input is supplied.
- Artifacts: ingestion-migration.json, ingestion-before.json and ingestion-smoke.json under
  reports/performance (local/ignored).

Rollout and limits:
- Apply migration 002 before starting this build; startup verifies its required tables/columns.
  The SQL file is versioned but is not a Flyway-managed migration runner.
- This path requires a single backend instance and the configured common upload pool.
  Existing ordinary unmanaged uploads are not retroactively made transactional/idempotent.
  Their private transaction annotation was removed to avoid implying guarantees it never had.
- Row identity is exact trimmed text, with no automatic case folding. Numeric metric
  representation is normalized for equality. Ambiguous units/formats or changed dimension
  metadata may reject rather than silently reinterpret observations. Configure/test a real sample.
- The API must target an existing KPI dataset; source profiles, initial dataset creation UI,
  folder-ready detection and cron triggering are subsequent work.
- Full snapshot JSON is still rebuilt for a changed dataset, and existing observations are
  inspected for overlap. This bounds correctness first; benchmark large-file memory/time
  before claiming incremental ingestion is optimized end to end.
- Source files and old immutable snapshots are retained. Add explicit retention, orphan-file
  cleanup, disk quotas and coordinated DB/file backups; do not delete active/referenced versions.
- Ordinary API metadata edits may create new versions; external tools must not assume the
  legacy <fileId>.json is current after a pointer exists. Use app APIs/committed snapshot metadata.
- Filesystem power-loss durability and backup/restore need operational validation. File
  flushing and DB transactions are not distributed transactions across arbitrary storage systems.
- To roll back to code without snapshot support, export each ACTIVE snapshot to its legacy
  cache filename before reverting; otherwise older binaries could read stale legacy files.
  Preserve ledger/source history rather than dropping it as an automatic rollback.

Why the transaction/DDL decisions:
- [Spring JDBC transaction participation](https://docs.spring.io/spring-framework/reference/data-access/jdbc/connections.html)
- [MySQL implicit commits](https://dev.mysql.com/doc/refman/8.0/en/implicit-commit.html)

Next: shared daily analysis snapshots and previous-day/seven-day comparisons, then
common report generation and email outbox. Configure folder pickup only after receiving
the real path, arrival policy and tested identity/mapping configuration.

### Fifth completed change: versioned daily KPI analysis and comparisons

Scope: numeric KPI aggregation, eight-day trend points and comparisons. This does not
claim that alarms, worst-cell, RCA or prediction jobs have been orchestrated into a
nightly report, or that new portal screens have been built.

Design and reasons:
- Explicit dataset profiles declare grouping dimensions, metric aggregation, direction,
  unit, sample requirements and baseline coverage. No real customer profile is guessed.
- MEAN is an explicitly descriptive sample mean; SUM is for additive quantities.
  RATIO sums numerator/denominator counters first, then applies the configured scale.
  Percentages cannot use SUM. PERCENT ratios use scale 100; out-of-range percentage
  samples and invalid counters are marked invalid, not clamped or treated as zero.
- Seven-day baseline excludes the current day and covers exactly D-7 through D-1.
  For SUM/MEAN KPIs it is the mean of available daily values; RATIO pools counters
  across days. Default minimum baseline is all seven days, configurable explicitly.
- Previous day means D-1, never the last date that happens to be available.
- Default report date is the maximum canonical source data date, not server wall time.
  A requested date is supported, including explicit missing-current-day/no-data output.
- Signed difference, percentage-point change and relative-percent change are separate.
  Zero/negative baselines, disabled relative change and DB/DBM logarithmic units have
  explicit reasons for a null relative percentage. Non-finite changes are not serialized
  as misleading numeric values. Direction determines improved/degraded, not the sign alone.
- Missing/invalid/insufficient samples and changed sample counts are visible. Default
  equal-sample-count checks prevent unsupported improvement claims. Disappearing/new
  groups remain visible as missing current/baseline rather than becoming zeros.
- Each group/date stores values, sufficient statistics and sample coverage for each metric.
  Comparison/trend DTOs are shared outputs for the future portal and report renderer;
  neither client should reimplement the arithmetic.

Persistence / lifecycle:
- Migration 003_daily_analysis_snapshots.sql adds profiles, analysis manifests and indexed
  daily group rows. Applied locally before activation; no customer profiles were inserted.
- Snapshot identity includes input fingerprint, profile hash, requested data date and engine
  version. Configuration is copied into the manifest so historical results remain reproducible.
- Workers read the exact source version captured at submission. Immutable historical
  input remains usable after a newer import. Changed mutable legacy input is rejected.
- Queue workers are separate: dailyAnalysisExecutor defaults to 1 worker + 4 waiting jobs.
  Rejection is a persisted failure plus HTTP 503; retries use conditional claims.
- Group rows and COMPLETED manifest publish in one DB transaction. Failed row batches
  roll back and remain unavailable to comparison readers. Startup recovery marks unfinished
  work failed without changing completed snapshots.
- A committed ledger import requests analysis automatically when a profile exists. Without
  a profile, its analysisStatus becomes CONFIGURATION_REQUIRED; import itself stays COMPLETED.
  The ingestion result links analysisSnapshotId and its current analysis status.
- Metadata labels immutable versions CURRENT/HISTORICAL and indicates whether the profile
  is still current. Mutable legacy files have UNVERIFIED_LEGACY freshness, never a false
  current-data guarantee. Explicit snapshot IDs always identify the historical calculation.

API (authentication required; super admin configures/builds/retries; company-scoped users may read their dataset):

| Request | Purpose |
| --- | --- |
| PUT /api/v1/daily-analysis/profile?fileId=... | Save explicit aggregation/grouping policy |
| GET /api/v1/daily-analysis/profile?fileId=... | Inspect policy/configuration-required state |
| POST /api/v1/daily-analysis/snapshots?fileId=...&date=YYYY-MM-DD | Queue/reuse analysis; date optional |
| GET /api/v1/daily-analysis/snapshots?fileId=...&limit=20 | Bounded versioned history |
| GET /api/v1/daily-analysis/snapshots/{id} | State, source/profile version, coverage summary |
| GET /api/v1/daily-analysis/snapshots/{id}/comparisons?page=1&limit=50 | Paginated groups, metric deltas and eight-day trends |
| POST /api/v1/daily-analysis/snapshots/{id}/retry | Retry failed analysis from its original source/profile |

Example profile shape ONLY; confirm actual canonical keys, units and grain before use:

```json
{
  "groupBy": ["cellName", "tech", "band"],
  "minimumBaselineDays": 7,
  "minimumSamplesPerDay": 1,
  "requireEqualSampleCounts": true,
  "metrics": [
    {
      "key": "dlprbutilizationrate",
      "aggregation": "MEAN",
      "direction": "LOWER_IS_BETTER",
      "unit": "PERCENT",
      "relativeChangeEnabled": true
    },
    {
      "key": "rrcsetupsr",
      "aggregation": "RATIO",
      "direction": "HIGHER_IS_BETTER",
      "unit": "PERCENT",
      "numeratorKey": "rrcsetupsuccesses",
      "denominatorKey": "rrcsetupattempts",
      "scale": 100,
      "relativeChangeEnabled": true
    }
  ]
}
```

Counters absent from the source cannot be invented from a percentage-only export.
Use an explicitly labeled descriptive MEAN or supply the necessary counters.

Validation:
- 57 Java tests passed, zero failures/errors/skips. Includes previous import/cache/Python
  tests and new numeric-oracle tests for calendar windows, direction, percentage points,
  relative percentages, pooled ratios, zero denominators, missing days/metrics/current
  groups, partial coverage, negative/logarithmic units, profile validation and resource caps.
- MySQL temporary-table tests cover persistence across service recreation, page boundaries,
  source/profile changes, immutable historical input, legacy mutation rejection, queue
  overload, rollback after row writes, retry/recovery, no-data windows and import linkage.
- Auth tests verify company isolation and admin-only mutation.
- Updated local backend started successfully. Health/frontend/existing KPI columns return
  200; anonymous new analysis requests return 401; dashboard regression data match.
- Actual customer counts unchanged: 422247 legacy rows and 609196 dynamic rows. Analysis
  profile/snapshot/group tables remain empty; no profile was applied to customer data.
- Local artifacts: daily-analysis-migration.json, daily-analysis-before.json and
  daily-analysis-smoke.json under reports/performance (ignored).

Performance / limits:
- Cache bytes are parsed once per worker; no second deep copy of the history is made.
  Only the selected eight-day window is aggregated and persisted. SQL batches are 500
  groups; read pages cap at 100 groups (each can contain up to 50 configured KPIs).
- DAILY_ANALYSIS_MAX_GROUP_DAYS defaults to 25000. Exceeding it fails clearly instead
  of silently truncating cells. Worker/queue settings are DAILY_ANALYSIS_WORKERS and
  DAILY_ANALYSIS_QUEUE_CAPACITY; the Windows runner imports all three from root .env.
- Source JSON still loads into memory and snapshots can repeat seven overlapping days
  across versions. Benchmark realistic volumes before raising limits; incremental day
  reuse, retention/disk budgets and monthly portal trend presentation remain follow-up work.
- Equal sample counts do not prove matching observation hours or complete source coverage.
  Configure the actual grain and minimum samples (e.g. hourly completeness) from real files.
  No automatic timezone/unit/granularity inference is claimed.
- The build is a KPI-analysis phase, not an all-modules report completion signal. Common
  report orchestration must explicitly include or mark skipped alarm/RCA/worst-cell/prediction stages.

### Completed increment 6: common reports and snapshot module orchestration (2026-11-05)

Problem: daily comparisons alone are not a customer report, and calling existing live
analysis routes during export could mix data versions or overwrite live alarm state.

Implemented:

- Explicit report request from a completed daily analysis snapshot. The manifest fixes
  the data date, dataset version, source SHA-256 and analysis profile. The worker reads
  that exact source version and checks its digest; it never substitutes current data.
- Capture enabled threshold rules, alarm templates, requested modules, RCA configuration,
  engine fingerprint and execution limits before dispatch. Configuration is downloadable
  with the report. Repeated identical requests reuse the durable report ID.
- Current-day threshold counts, read-only alarm candidates with previous-day counts,
  five worst groups per KPI, configured RCA and KPI-only prediction actions. Worst-group
  ranking uses explicit daily profile direction; it is not the live combined radio score.
- All module results carry COMPLETED, LIMITED, SKIPPED, FAILED or NOT_REQUESTED status
  and a reason. Missing RCA mappings or threshold rules are visible. A module failure
  can produce a PARTIAL report with the remaining results; core export/source failure
  fails the build and publishes no artifacts.
- PDF summary with eight-day charts, numeric Excel comparison appendix and streaming
  JSON appendix. Missing chart points stay gaps and missing Excel values stay blank.
  Relative percent changes, absolute differences and baseline assessment remain distinct.
  Module detail sheets are added when tabular RCA/prediction results are available.
- Publication occurs only after all artifacts are closed and SHA-256 checked. Download
  rechecks integrity and path containment. Failed unpublished directories are cleaned;
  interrupted QUEUED/BUILDING jobs become FAILED on startup and can be retried.
- Dedicated bounded report executor (one worker, two waiting requests by default).
  RCA calls from reports and existing routes share a process concurrency gate, so adding
  reporting does not bypass the existing Python worker budget. Queue rejection returns
  HTTP 503 with Retry-After and a recoverable report ID.
- Administrator-only request/retry; company-scoped history, metadata and downloads.

Why these choices:

- Apache PDFBox 3.0.8 creates server-side vector PDFs without a browser process or a
  second Python reporting environment. Existing Apache POI SXSSF streams Excel rows;
  Jackson streams JSON. This reuses the existing Java deployment and limits retained
  comparison data to a page plus five worst groups per metric.
- SQL report ledger reuses existing MySQL transactions and operational conventions.
  A distributed queue adds no demonstrated benefit for this single-instance increment.
- Snapshot adapters reuse existing threshold/alarm/prediction algorithms without their
  live reads or writes. Existing interactive APIs retain their behavior.
- Official PDFBox reference: https://pdfbox.apache.org/download.cgi

Schema/API:

- Apply `backend-java/db/migrations/004_common_reports.sql` before deploying. Applied
  locally; the new real report table remains empty. Integration tests shadow writable
  tables with connection-local temporary tables.
- `POST /api/v1/reports?snapshotId=<completed-snapshot-id>` accepts an optional JSON body:

  ```json
  {"modules":["thresholds","alarmCandidates","worstCells","rca","prediction"],"rcaConfig":{}}
  ```

- Empty RCA configuration deliberately skips RCA. Supply the existing RCA vendor,
  columnMapping, kpiMapping and selectedKpis configuration to enable it. Report dates
  are fixed to the manifest day and the preceding seven days.
- `GET /api/v1/reports?fileId=<dataset-id>&limit=20`
- `GET /api/v1/reports/{id}`
- `POST /api/v1/reports/{id}/retry` (FAILED only)
- `GET /api/v1/reports/{id}/download/{pdf|xlsx|json|configuration}`
- Poll QUEUED/BUILDING. READY and PARTIAL both have downloadable artifacts; PARTIAL
  requires review of moduleStatuses/reasons. These are protected authenticated routes.

Limits and remaining work:

- Defaults: 100000 comparison rows, 50000 current-day/window module records, 200 module
  detail rows and 500 prediction cells. Required comparisons fail above their limit;
  oversized optional modules are explicitly skipped without sampling. Windows runner
  imports COMMON_REPORT_* and RCA_PERMIT_WAIT_SECONDS settings from root .env.
- Source JSON still loads into memory. Increasing limits requires realistic memory/CPU
  measurement. Prediction neighbor search is quadratic, which is why its cell cap is
  separate. This increment does not claim a new latency benchmark improvement.
- Alarm candidates are detected threshold conditions, not acknowledgement/resolution
  history. Prediction is KPI-only heuristic action guidance, not a future KPI forecast;
  site-master, TA and alarm context require separate pinned snapshots for a fuller report.
- RCA responses are explicitly LIMITED to 200 detail rows, while the independent daily
  comparison appendix remains complete within its configured export bound.
- PDF shows at most 20 ranked KPI charts and five detailed RCA/prediction findings per
  section; appendices retain the remaining exported details. Unicode uses an installed
  Arial/DejaVu font when available, with unsupported glyphs substituted in PDF only.
- Single-instance startup recovery remains the deployment assumption. Retention and
  orphan cleanup after abrupt termination need operational policies before automation.
- At this increment, email, nightly triggers, automatic report chaining and report UI
  remained pending. Increment 7 below adds the mail outbox with outbound SMTP disabled.

Validation/activation:

- 65 Java tests passed, zero failures/errors/skips, including real MySQL temporary-table
  integration and real Python RCA against pinned input after live data changed.
- Verified report request deduplication, historical source use, queue rejection, recovery,
  retry, integrity failure, scoped access, read-only adapters, invalid numeric handling,
  missing data preservation, Excel formula safety and PDF text extraction.
- Synthetic sample: `reports/performance/sample-common-report.pdf`, `.xlsx`, `.json`.
  Visually inspected the PDF rendering. Customer data was not used for sample generation.
- Updated backend active on port 3001; frontend remains on 5173. Health, existing KPI
  columns and all three dashboard regression scopes passed. Report endpoints and
  downloads return 401 without authentication. Customer rows unchanged: uploaddata
  422247, kpidynamicdata 609196. Evidence: `reports/performance/common-report-smoke.json`.
- Rollback binary: `reports/performance/backend-before-common-report.jar`. Keep migration
  004 in place on application rollback; it adds a separate table and changes no raw KPI data.

### Completed increment 7: durable report email outbox (2026-11-05)

Problem: sending email directly from report generation would lose delivery state after
restart and could resend accepted mail after an SMTP timeout or database failure.

Implemented:

- Per-dataset administrator-managed recipient profiles with CAPTURE/SMTP mode, PDF/Excel
  attachment selection, enablement and explicit permission to send PARTIAL reports.
  Recipients are validated plain addresses; CR/LF, display names and duplicate addresses
  are rejected. Profile settings do not include SMTP secrets.
- Durable per-recipient delivery envelopes capture report ID/status, sender, recipient,
  subject/body, profile hash and attachment checksums. A unique key on report + normalized
  recipient + transport mode prevents duplicate requests from creating another send.
  Configuration edits do not rewrite existing deliveries. A separate recipient delivery
  avoids resending accepted recipients because another address failed.
- Stable RFC Message-ID and immutable MIME message retained across retries. MIME is
  atomically published, size bounded and SHA-256 checked. Attachments are checked against
  the captured report artifacts before composing mail. No report is sent while building.
- Dedicated bounded mail executor: one worker and four queued deliveries by default.
  A batch queue rejection records all delivery IDs and marks rejected jobs FAILED with
  zero send attempts; HTTP 503 includes the batch result. Explicit retry is required.
- Separate attempt history and append-only request/retry/cancel/reconciliation audit
  events with actor IDs. SMTP acceptance timestamps, local capture timestamps, server
  response and stable Message-ID are distinct from inbox delivery confirmation.
- CAPTURE writes downloadable .eml files locally without connecting to SMTP. This is
  the active default; actual customer recipients and SMTP configuration remain absent.
- SMTP adapter supports authenticated STARTTLS or implicit TLS, hostname verification,
  finite connection/read/write timeouts, and one recipient per transaction. Passwords
  are read from environment configuration and are never stored in delivery envelopes.
- QUEUED -> PREPARING -> SENDING -> CAPTURED or ACCEPTED. Known failures before submission
  become FAILED and can be explicitly retried below the attempt cap (default five).
  Any failure after submission starts becomes UNKNOWN, including loss of the SMTP response
  or failure persisting acceptance. UNKNOWN cannot be retried without reconciliation.
- Startup fails interrupted queued/preparing work safely; interrupted SENDING becomes
  UNKNOWN. Accepted/captured deliveries are never automatically requeued. Queued/failed
  deliveries can be cancelled before sending. No retry loop or nightly trigger is enabled.
- Administrator reconciliation requires evidence: ACCEPTED for SMTP, CAPTURED for verified
  local MIME, or NOT_ACCEPTED before permitting a retry. Original attempt outcome remains
  in history. Manual acceptedAt/capturedAt records reconciliation time, not an inferred
  original provider delivery timestamp.
- Recipient profile reads/mutations and delivery mutations require administrator access.
  Delivery history and captured-message downloads are scoped to the dataset company.

Why these choices:

- The existing MySQL transaction infrastructure provides a durable outbox and audit trail
  without introducing a broker. Executor dispatch happens after the database transaction;
  a crash in between leaves a recoverable queued row. Startup recovery assumes one instance.
- Spring Boot's managed mail starter supplies Jakarta Mail/Angus and MIME helpers. SMTP
  socket timeouts are explicit because the default can be unbounded. The adapter connects
  before committing SENDING and treats send-stage exceptions conservatively as ambiguous.
- Neither SMTP acceptance nor a stable Message-ID guarantees inbox delivery or exactly-once
  delivery. A provider webhook/reconciliation integration is required to track delivery/bounce
  events. The API therefore always exposes inboxDeliveryConfirmed=false in this increment.
- Official references: https://docs.spring.io/spring-boot/3.5/reference/io/email.html and
  https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html

Schema/configuration/API:

- Apply `backend-java/db/migrations/005_report_delivery_outbox.sql` before deployment.
  It creates separate profiles, outbox, attempts and audit tables without changing KPI rows.
- Configuration template: `backend-java/report-mail.env.example`. The Windows runner imports
  REPORT_MAIL_* values from root .env. REPORT_MAIL_OUTBOUND_ENABLED remains false locally.
- `PUT /api/v1/report-mail/profile?fileId=<dataset-id>` accepts, for example:

  ```json
  {"mode":"CAPTURE","recipients":["qa@example.com"],"attachments":["pdf"],"allowPartial":false,"enabled":true}
  ```

- `GET /api/v1/report-mail/profile?fileId=<dataset-id>` (administrator)
- `POST /api/v1/report-mail/deliveries?reportId=<completed-report-id>`
- `GET /api/v1/report-mail/deliveries?fileId=<dataset-id>&limit=20`
- `GET /api/v1/report-mail/deliveries/{id}` includes attempt history and audit events.
- `POST /api/v1/report-mail/deliveries/{id}/retry` (FAILED only, below the attempt cap)
- `POST /api/v1/report-mail/deliveries/{id}/cancel` (QUEUED/FAILED only)
- `POST /api/v1/report-mail/deliveries/{id}/reconcile` accepts outcome and evidence.
- `GET /api/v1/report-mail/deliveries/{id}/message.eml` (CAPTURED only)
- Existing delivery identity is intentionally reused after profile changes. An explicit
  force-resend operation is not exposed. SMTP endpoint identity is pinned without secrets;
  restore the captured endpoint before retrying pending mail after an endpoint change.

Validation and practical limits:

- 74 Java tests passed, zero failures/errors/skips, including MySQL connection-local
  temporary-table integration, real MIME composition/attachment decoding and existing real
  Python RCA checks. SMTP acceptance/timeouts are simulated; no external email was sent.
- Verified per-recipient deduplication, captured envelope stability, disabled SMTP guard,
  partial-report policy, attachment/MIME integrity, safe retries, ambiguous send outcomes,
  acceptance-write failure, restart recovery, cancellation, attempt cap and scoped access.
- Synthetic captured mail with an actual generated PDF attachment is available at
  `reports/performance/sample-report-mail.eml`. Real profile/outbox/attempt/event tables remain
  empty; customer data counts remain 422247 raw rows and 609196 dynamic KPI rows.
- Updated backend active on 3001 and frontend on 5173. Health, existing KPI column route,
  registered OpenAPI mail routes and three dashboard regression scopes passed. Mail profile,
  history and captured-message downloads return 401 without authentication. Evidence:
  `reports/performance/mail-outbox-smoke.json`; migration checksum is recorded separately.
- Default total encoded MIME cap is 20 MB. An early attachment budget reserves encoding/header
  space; encoded output is independently bounded. Profiles allow at most 20 recipients.
- A long SMTP send is bounded by socket operations, not a strict end-to-end workflow deadline.
  DNS/provider behavior and sequential recipient volume must be measured before committing to
  the 02:00 IST target. The schedule/deadline monitor remains a later increment.
- Disk retention, provider events, operational reconciliation alerts and multi-instance leases
  remain follow-up work. Captured MIME contains report/customer data and uses the same scoped
  download authorization as report history; protect and retain its filesystem accordingly.
- Rollback binary: `reports/performance/backend-before-mail-outbox.jar`. Keep additive migration
  005 in place when rolling back the application; no prior report/KPI schema is altered.

### Completed increment 8: daily reporting portal (2026-11-05)

Implemented a protected `/reports` page, linked as Daily Reports in the sidebar, using
the existing React/Tailwind/Recharts stack. No new frontend runtime dependency was added.

- Company-scoped, paginated dataset picker. New `GET /api/v1/report-datasets?page=1&limit=50`
  applies company filtering in SQL to both counts and pages. Unassigned non-super-admin
  accounts are rejected. It does not reuse the legacy public upload-history selector.
- Daily analysis history with date, status, failure message, dataset version and freshness.
  Comparison pages load ten groups at a time. The selected row drives an interactive
  eight-day trend chart; missing observations stay gaps and unavailable baselines remain
  unassessed. Differences are explicitly in KPI units/percentage points.
- Structured administrator comparison settings: KPI selection, aggregation, units,
  direction, ratio counters, sample-count rules and minimum baseline coverage. Existing
  snapshot settings remain immutable. New analysis and failed-analysis retry are available.
- Generate a report from a completed snapshot with explicit module selection. A reviewed
  saved RCA preset can be loaded from the existing RCA Recommendation preset APIs, with
  vendor and selected KPIs shown before generation. Both existing preset naming formats
  are accepted; missing mappings fail without guessing. Dates remain fixed by the backend
  report manifest. No preset means RCA is not requested for that report.
- Report history shows READY/PARTIAL/FAILED and module coverage separately. PDF, Excel,
  JSON and captured settings downloads use bearer-authenticated fetches and Blob URLs.
  File URLs never contain the auth token. Failed reports support explicit retry.
- Email settings, recipient review, capture/send actions, delivery history, attempt/audit
  details, captured-message downloads, retry/cancel and evidence-based reconciliation.
  SMTP-disabled and partial-report policies disable unsuitable request actions.
- Added optional `expectedProfileHash` to mail delivery requests. The portal sends the
  reviewed profile version; the backend checks it under the dataset transaction lock.
  If recipients/settings changed after review, the request fails before creating deliveries.
  Existing non-portal callers remain compatible with the optional parameter.
- Company users and ADMIN accounts retain scoped reads/downloads; mutations require
  SUPER_ADMIN (including the backend's legacy blank-role convention). Read-only clients
  are not shown build, settings, send, retry or reconciliation controls.
- Requests are cancelled on dataset changes/unmount. Polling is read-only, every five
  seconds only while displayed jobs are active and the document is visible. History
  panels explicitly show the latest fifty records; this is not an unlimited history browser.
- The shared API client now supports protected GETs without anonymous retry and binary
  responses. Its existing default public-GET fallback remains unchanged. Structured error
  payloads retain persisted job IDs for queue-full responses; mutations are not auto-retried.
- Daily Reports is lazy-loaded as a separate route chunk (34.68 kB / 10.08 kB gzip in the
  checked build). The application's existing large main bundle warning remains; this
  increment does not claim whole-application bundle optimization.

Why: the UI reads persisted snapshots and delivery state instead of recomputing historical
results in the browser. Existing domain APIs remain authoritative. Bounded comparisons,
conditional polling and a separate route chunk avoid adding unbounded background reads
or report-page code to the initial navigation path. Recipient version checks close the gap
between the settings a user reviewed and the settings used to create a delivery.

Validation/activation:

- 77 Java tests passed, zero failures/errors/skips. Added SQL scope/bounds tests and a
  real temporary-table test proving a changed recipient profile cannot enqueue delivery.
- Eight frontend tests passed via `cd frontend; npm run test:reports`: protected session
  expiry, authenticated binary downloads/cancellation, queue error payloads, legacy GET
  compatibility, missing versus zero, delivery guards and both RCA preset formats.
- Targeted ESLint checks and the production frontend build passed.
- Chromium desktop/mobile checks passed with synthetic API fixtures: settings save,
  missing baseline, chart rendering, RCA preset report request, recipient profile hash,
  binary download, reconciliation, empty configuration and read-only customer controls.
  No browser runtime errors or mobile viewport overflow were detected. Reproduction:
  `python scripts/test_report_portal_browser.py` (requires local frontend plus Playwright
  and Chrome). Fixture requests are intercepted; no customer configuration/email is changed.
- Screenshot/evidence artifacts: `reports/performance/report-portal-analysis.png`,
  `report-portal-reports.png`, `report-portal-delivery.png`, `report-portal-mobile.png`,
  `report-portal-browser.json` and `report-portal-smoke.json`.
- Backend active on port 3001 and frontend on 5173. Live health, reporting route, existing
  KPI columns, registered OpenAPI routes and three dashboard regression scopes passed.
  New protected endpoints return 401 without authentication. Raw/dynamic customer rows
  remain 422247/609196; real analysis/report/mail configuration and history tables remain
  empty. Initial configuration is shown honestly instead of populating synthetic live data.
- No new database migration is required. Rollback binary:
  `reports/performance/backend-before-report-portal.jar`; frontend changes remain in its
  existing nested repository working tree. No commit, push or external email was performed.

### Completed increment 9: nightly workflow and 02:00 IST tracking (2026-11-05)

The daily pipeline now has a durable coordinator and an Automation tab in Daily Reports.
It remains disabled for automatic creation locally because no real source folder has
been supplied. No source path, recipient list or provider credentials were invented.

Schedule and completion semantics:

- Spring cron `0 0 1 * * *`, explicitly in `Asia/Kolkata`, opens the daily window at
  01:00 IST. A bounded coordinator tick (default 30 seconds) also catches up if the backend
  starts after the trigger. The data day is yesterday in IST, not the machine's UTC date.
- Each dataset has an explicit input cutoff (default 01:30 IST; configurable within
  01:00–01:59) and an original 02:00 IST completion target. Catch-up after the cutoff
  records a failed/missed run instead of silently treating a late file as on time.
- Completion goal is explicit: REPORT_ONLY means a published report, CAPTURE means all
  local test emails captured, SMTP means all intended recipients accepted by the provider.
  SMTP acceptance remains distinct from confirmed inbox delivery.
- Missing input fails at cutoff. A missed deadline is persisted with an audit event and
  an error log, once per observed miss. Active work is not killed at 02:00. Late completion
  remains MISSED. Explicit late/replay runs retain the original business-day deadline.
- Deadline checks run independently from the file-hashing/staging worker. A slow file
  read therefore does not occupy the thread that checks deadlines. Database outages or
  a stopped backend still prevent local monitoring; operational hosting remains necessary.
- Goal assessment uses persisted report-publication/recipient-acceptance timestamps,
  not only the time the coordinator noticed completion. New workflow times are stored
  as UTC text values in DATETIME columns and exposed with `Z`; the portal displays IST.
  Child timestamps are read as database epoch values to avoid JDBC/session-zone ambiguity.
  A prior unconfirmed deadline alarm remains in audit history if later evidence proves
  the goal was already met. Reconciliation time is used where an original time is unknown.

Source handling and data correctness:

- Per-dataset configuration fixes an absolute source folder and one exact filename
  pattern, e.g. `kpi-{date}.csv`. The placeholder is the data day in YYYY-MM-DD format.
  CSV/XLSX/XLS are accepted. There is no recursive search, wildcard newest-file guessing,
  arbitrary per-run path, source deletion or source archive move.
- Size and modification time must stay unchanged across checks for at least 60 seconds;
  the modification timestamp must also be old enough. Input bytes are capped at 200 MB.
  Symbolic-link files, nested paths and unavailable folders are not accepted as stable input.
- The coordinator records the raw SHA-256, and ingestion verifies the staged copy against
  it before creating the import. A changed file never substitutes another source silently.
  Staging streams bytes and enforces its size limit even if a file grows during copying.
- Observation identity and optional source-column mapping are explicitly configured.
  Existing strict append semantics remain: identical rows skip, changed overlaps reject.
  Identity includes date/cellName and must match the actual grain, including hour when needed.
- Nightly imports validate that every incoming observation belongs to the expected day
  before any raw/dynamic row is appended. Automatic daily analysis uses that same date,
  rather than blindly selecting the latest date in the whole dataset.
- The coordinator validates analysis profile hash, dataset revision, report data day and
  current-day data availability before reporting. It uses the existing immutable report
  source and reviewed RCA settings; it never builds comparisons from mixed live versions.
- If a crash loses the import-to-analysis link, a guarded repair request can attach the
  analysis only while the committed dataset revision and captured profile still match.
  Otherwise the run fails for review rather than switching to newer data.

Durability, capacity and retry policy:

- Migration 006 adds nightly profiles, runs and audit events. `(fileId,dataDate)` is unique.
  Repeated timer ticks/manual requests reuse that run, including its linked ingestion,
  analysis, report and per-recipient delivery IDs. Configuration edits affect future runs.
- A single coordinator worker with a one-slot queue is coalesced across timer triggers.
  It advances stages without sleeping or waiting for child workers. Existing upload,
  analysis, report and mail executors keep their own bounded queues and deadlines.
- Default cap: 20 enabled datasets / ACTIVE workflows, 3 total run attempts. BLOCKED mail
  runs have a separate bounded reconciliation scan and do not consume the next day's
  active capacity. Their last-check timestamp rotates the backlog fairly.
- Known queue rejection or backend-interrupted unsent work can retry the same child ID
  before the deadline, within the shared attempt budget. Invalid input, changed mappings,
  comparison/version mismatches and ordinary analysis errors require explicit review.
- UNKNOWN mail blocks that run; it never causes an automatic resend. Accepted/captured
  recipients are retained when another recipient is retried. Cancelled recipients are
  not resurrected by workflow retries. Mail's own attempt cap also remains in force.
- Administrator retry resumes a failed stage. Optional input rediscovery is allowed only
  before an import committed and before analysis/report creation; it explicitly captures
  corrected source settings and preserves the prior child ID in audit history.
- Optional mail-profile refresh is allowed only before recipient deliveries exist. Already
  created envelopes remain immutable. Changed recipients are never adopted silently.
- Active coordinators retain their state on restart. Child services independently mark
  interrupted work as failed/unknown; the coordinator then applies the safe retry policy.
  Engine/configuration errors that cannot be repaired within captured settings require
  operator review; no claim of universal automatic error recovery is made.

Why this implementation:

- Spring scheduling reuses the existing backend runtime and supports an explicit cron
  time zone. The cron is a trigger; MySQL records provide workflow state, deduplication
  and recovery. A timer alone cannot provide those guarantees.
- Domain services are reused directly; the backend does not call its own HTTP upload,
  report or mail endpoints. A new broker/Quartz deployment was not necessary for the
  current single-instance, daily-file workload. JDBC leases/distributed scheduling are
  required before running multiple backend instances; local coalescing is not a lease.
- Official scheduling reference:
  https://docs.spring.io/spring-framework/reference/6.2/integration/scheduling.html

Configuration/API/portal:

- Apply `backend-java/db/migrations/006_nightly_workflow.sql` before deploying. Applied
  locally; checksum recorded in `reports/performance/nightly-migration.json`.
- `backend-java/nightly.env.example` lists NIGHTLY_ENABLED (default false), tick interval,
  stability, input size, active-workflow cap and attempt cap. The Windows runner imports
  them from root .env. Global activation and per-dataset enablement are both required.
- `GET/PUT /api/v1/nightly/profile?fileId=<dataset-id>` (administrator) accepts:

  ```json
  {"enabled":false,"folder":"","filePattern":"kpi-{date}.csv","identityFields":["date","cellName"],"columnMapping":{},"deliveryMode":"REPORT_ONLY","cutoffTime":"01:30","reportOptions":{"modules":["thresholds","alarmCandidates","worstCells","prediction"],"rcaConfig":{}}}
  ```

- `POST /api/v1/nightly/runs?fileId=<id>&date=<data-day>&allowLate=false`
- `GET /api/v1/nightly/runs?fileId=<id>&limit=20`
- `GET /api/v1/nightly/runs/{id}` includes the latest 100 audit events.
- `POST /api/v1/nightly/runs/{id}/retry?rediscoverInput=false&refreshMailProfile=false`
- Profile and mutations are administrator-only; run history/details are company-scoped.
  Public run DTOs omit folder/configuration details and use explicit UTC timestamps.
- Daily Reports → Automation provides disabled draft settings, folder/pattern/grain,
  optional column mappings, report modules/RCA preset, delivery goal, manual late/replay,
  linked child IDs, deadline status and explicit retry controls. Active/blocked run history
  refreshes every 15 seconds while visible. The browser need not remain open for execution.

Validation and current activation state:

- 92 Java tests passed with zero failures/errors/skips. Includes real MySQL temporary-table
  tests for stable/changing/missing files, one daily run, date rejection before append,
  checksum changes, child recovery, version mismatch, bounded retry, unknown delivery,
  late completion, independent deadline checks and next-day progress with blocked mail.
- A synthetic CSV completed the actual ingestion → persisted analysis → PDF/Excel/JSON →
  MIME capture flow using the real services, connection-local writable tables and local
  artifacts. The replay intentionally used an older data day and correctly recorded MISSED;
  separate clock-controlled cases verified on-time goals. No customer tables were written.
- Synthetic replay evidence: `reports/performance/nightly-replay-sample.json` and
  `nightly-replay-sample.eml`. Original input remained present; repeating the request did
  not create another import or email. No SMTP connection or external message was made.
- Eight frontend API/logic tests, targeted ESLint and production build passed. Desktop/mobile
  browser checks verified the disabled automation draft, missed-deadline audit and existing
  report/download/reconciliation flows with no runtime errors or viewport overflow.
  Screenshot: `reports/performance/report-portal-automation.png`.
- Backend updated on 3001; frontend on 5173. Global NIGHTLY_ENABLED and outbound SMTP remain
  false; actual nightly profile/run/event tables remain empty. Raw/dynamic customer row
  counts remain 422247/609196. Smoke evidence: `reports/performance/nightly-smoke.json`.
- Rollback binary: `reports/performance/backend-before-nightly-workflow.jar`. Keep the
  additive migration tables in place when rolling back the application. Existing upload
  callers without an expected nightly date retain their prior behavior.

Activation inputs still pending: actual folder access under the backend service account,
representative daily file and its grain/mappings, expected volume/arrival time, recipients
and provider configuration. First verify REPORT_ONLY/CAPTURE with that source, then enable
the intended schedule/delivery goal. The backend/DB must run through the window; desktop
sleep or downtime cannot satisfy a 02:00 target. Multi-day offline backfill, retention/disk
budgets, provider delivery/bounce events and external operational alerts remain deployment
work. The current coordinator catches up the current business window, not an arbitrary
number of missed historical days, and a 02:00 inbox-delivery guarantee is not claimed.

- Scope expensive queries and select required columns; inspect EXPLAIN before adding indexes.
- Preserve business/company filters and neighbour lookup semantics.
- Push suitable aggregates to SQL; paginate lists and keep export as a separate workflow.
- Stream large inputs and use bounded batches where profiling shows memory pressure.
- Bound background workers, queues and Python concurrency; rejection must leave a
  recoverable job state, never an orphaned PROCESSING record.
- Add cache versioning, invalidation, size limits and expiry cleanup.
- Stream cache serialization and publish snapshots atomically if profiling justifies it.
- Tune pool size only from DB capacity/connection-wait evidence.

Exit gate: unchanged business outputs, measured latency/memory improvement, concurrency
and error-path verification. Change one workload at a time.

### Completed increment 10: customer-readable report presentation (2026-11-05)

User review identified that the old two-cell/one-KPI test PDF was not a useful customer
example and that the PDF presenter emitted technical map strings. Replaced that presenter
with a customer-facing layout used by normal report generation as well as the demo.

- Branded page headers/footers, executive comparison counts, section coverage, a priority
  KPI scorecard with current/previous-day/previous-seven-day values and percentage-point
  deltas, and four vector trend panels per page. Worst-group rows are clearly identified
  as priority cells rather than mislabeled network-wide averages.
- Current-day RCA is prioritised by distinct cell, up to four detailed cases. Suspected
  cause, interpretation, additional patterns, KPI evidence and vendor action are separate.
  Embedded KPI-evidence/top-driver lines are removed from the interpretation paragraph to
  avoid the duplicate cause/evidence presentation the user rejected.
- Actual RCA Explanation, Vendor Recommendation, Recommended Parameters/Values and
  Implementation Notes fields are presented. Equally numbered parameter/value/check lists
  become a readable three-column review matrix; unmatched lists retain their original text.
  Recommendations are investigation guidance, not approved parameter changes or confirmed faults.
- Alarm conditions show observed values, thresholds and severity without claiming active
  lifecycle/resolution. Three returned KPI-based action items are summarised; these remain
  current-data heuristics rather than forecasts. Full returned detail remains in Excel/JSON.
- Template engine identity is now customer-v2 and includes the new PDF presenter class,
  preventing new requests from reusing artifacts rendered with an older template. Existing
  immutable historical reports are preserved; comparison/export schemas remain common-v1.
- Generated a substantial demo through the real DailyKpiCalculator, threshold/alarm snapshot
  adapters, Nokia Python RCA, KPI-only action engine and the same CommonReportRenderer.
  It has 6 synthetic cells, 3 sites, 8 KPIs, 24 hourly observations per cell/day across 8 days
  (1152 observations), and 48 computed comparison rows. It uses explicitly synthetic rules.
- Demo data is labeled on the first page and every footer. It is not a customer diagnosis.
  RCA is calculated on that synthetic input, not populated with invented analysis outputs.
- Review artifacts: `reports/performance/proper-common-report-demo.pdf` (11 pages), matching
  `.xlsx` and `.json`, plus `proper-report-demo-configuration.json` and
  `proper-report-demo-checks.json`. First-page and contact-sheet PNGs were visually inspected.
- 93 Java tests passed, zero failures/errors/skips, including real MySQL/Python regression
  checks, PDF text extraction, numeric/missing-value preservation and a reproducible demo.
  No customer database writes or external email occurred. The updated renderer is deployed
  locally; rollback binary: `reports/performance/backend-before-customer-report-layout.jar`.

### Completed increment 11: RCA export reuse and progress (2026-11-05)

- The old synchronous export reran RCA and reopened/resaved its workbook to add
  recommendation columns. A captured local HTTP request took about 20 seconds.
- Normal recommendation jobs now retain compressed full scored frames before
  declaring completion. Export reads these frames and writes Excel once, with
  vendor guidance, wrapped long text, filters, date formats and formula-safe strings.
  All six scored/detail/summary tables are retained; Pre/Post exports contain both
  periods. The UI response-row limit never truncates these cached scored sheets.
  Configured input sampling/row limits and date filters still apply and are listed
  on Export_Info; this change does not reduce the amount of input analyzed.
- Workbook artifacts are reused for identical dataset bytes, complete settings and
  engine identity (including rca_export.py). SHA-256 validates artifacts before reuse;
  source/configuration changes cannot silently return the current export for old
  inputs. Old analysis caches need one fresh analysis after this engine change.
- Apply migration `backend-java/db/migrations/007_rca_export_jobs.sql` before startup.
  New authenticated, company-scoped `/api/v1/rca-exports` start/status/download routes
  persist progress and failures. A bounded export executor shares the Python gate
  with RCA. Same-key pending exports coalesce and completed workbooks are reused.
  The old synchronous export endpoint remains compatible with this renderer/cache.
- The portal polls job progress, downloads completed files and releases controls on
  failure. Run and Export cannot overlap from the same panel. Changing source/settings
  stops browser polling; the server job continues, and the same Export request resumes
  it. A dataset without a verifiable snapshot uses a fresh, uncached analysis instead.
- `RCA_ARTIFACT_DIR` optionally changes the cache directory (default uploads/rca-artifacts
  relative to the backend working directory). Per artifact budgets: compressed frames
  64 MiB, decompressed frames 256 MiB, workbook 100 MiB. Oversized exports fail explicitly.
  Total disk retention/cleanup and cross-instance coordination remain deployment work;
  these files are a rebuildable cache, not the immutable common-report archive.
- Read-only file 90 benchmark: 7,179,274 source bytes; analysis plus retention 7,450 ms;
  first workbook from completed analysis 5,059 ms; repeated saved-workbook lookup 64 ms;
  workbook 1,999,346 bytes. These are service timings on this machine, excluding browser
  transfer and queue wait; the previous HTTP timing is not a controlled like-for-like
  benchmark. Evidence: reports/performance/rca-export-benchmark.json.
- Tests cover full-row single/Pre/Post exports, one workbook save, no reanalysis,
  cached-byte reuse, tampering, changed data, queue saturation, restart recovery,
  tenant access and browser progress/download/failure recovery. Browser tests mock APIs
  and the benchmark uses a temporary read-only dataset copy, with zero customer DB writes.

### Completed increment 12: frontend loading and RCA interaction (2026-11-10)

- The production entry bundled almost every page, including Map, KPI charts and
  administrative screens. All routes now use dynamic imports. Suspense stays inside
  the protected layout, so navigation remains available while content loads. A page
  error boundary contains render/chunk failures, offers reload and resets on navigation.
- Entry JavaScript decreased from 2,185,524 to approximately 343,374 bytes (84.3%).
  This measures entry size, not total application size or navigation latency. The Map
  chunk is still approximately 1.10 MB and requires a separate map-specific profiling pass.
  Production-network browser checks confirm unrelated pages and RCA result/chart code
  are not requested on the initial recommendation configuration screen.
- Selected dataset restores in the state initializer, preventing an initial request for
  an empty/wrong scope. RCA ignores obsolete column responses and cancels job polling/
  fetches when source, settings or route change. Server jobs continue independently.
  Results are keyed to user/company, file and complete settings; stale or unscoped
  legacy saved results are hidden. Current results/configuration survive route/reload.
- RCA result/chart code loads only when results exist. Detail rendering is bounded to
  50 rows per page; every returned detail row remains accessible, replacing the old
  single-period 100-row display cap and unbounded Pre/Post DOM. This does not change
  backend response limits, analysis or full Excel exports. Recommendation visibility
  stays opt-in. Mobile Run/Export controls stack to avoid clipping.
- RCA localStorage serialization is debounced by 500 ms, flushes on route exit/pagehide
  and catches quota/storage errors. Abortable poll timers release their listeners.
- Production build, targeted ESLint and 11 frontend tests passed. Browser checks use
  synthetic API fixtures for polling cancellation, selected source, pagination, result
  persistence/isolation, mobile layout and lazy-chunk recovery. Existing RCA export and
  report-portal browser regressions passed. No customer writes or external messages.
  Evidence: reports/performance/frontend-before.json, frontend-after.json,
  frontend-smooth-browser.json and desktop/mobile screenshots.
- Remaining measured work: profile large Map/KPI/Worst Cell workloads with representative
  data, memory/long-task and interaction timings; session-cache invalidation; end-to-end
  API latency under load; and broader accessibility review. No claim that the entire
  frontend is now fully optimized or every page has production acceptance coverage.

### Completed increment 13: Map View correctness and responsiveness (2026-11-10)

- Replaced the initial-load zoom closure (which captured map=null) with a removable,
  debounced listener attached to the current map. Geometry now follows zoom changes.
  Site Size adjusts site markers; Cell Radius independently adjusts sector geometry.
- Turning relation/prediction overlays off no longer clears the base deck site layer.
  Native Google polylines are skipped while deck draws handovers; inferred pairs are
  deduplicated, and backend relation endpoints respect the selected site filters.
  Overlay finalization, native-line cleanup and zoom-listener/timer removal run on exit.
- Map site loading follows every server page, validates total counts and fails explicitly
  on incomplete/changed/oversized pagination. The old first-10,000-row truncation is gone.
  Limits: 2,000 rows/page, 100,000 rows total, existing 20-second source-request deadline;
  exceeding these gives a retryable error rather than presenting partial data as complete.
  Coordinate validation rejects missing/nonfinite/out-of-range lat/lon, retaining valid zero.
- Metrics, site summary/intelligence, alarms, predictions, worst cells and selected-site
  details use cancellation plus response guards. Clearing/changing a source removes
  stale selections/results. New manual LB runs supersede pending automatic recommendations;
  source/route changes cancel browser requests. No claim of server-side job cancellation.
  LB settings are disabled while running and invalid/empty quantiles are rejected.
- Added a spatial candidate index ahead of the existing exact distance/bearing rules;
  deterministic tests match brute-force neighbours, including antimeridian/polar cases.
  A synthetic 5,000-site lookup reduced potential pair candidates from 24,995,000 to
  579,090 (97.68%). Local index build: 2.68 ms; all lookups: 68.68 ms. These timings
  exclude exact relation evaluation, React, GPU rendering and API latency; no FPS claim.
- Removed unused hidden controls, stopped mounting closed analytics drawers, exposed
  the Worst Cell KPI selector in Filter, added source retry/loading feedback and switch
  labels. Header wraps on small screens and drawers fit the available map width.
  Removed the inactive Multi Map button rather than advertise an unavailable action.
- Validation: 16 frontend tests, targeted ESLint, production build; synthetic browser
  checks cover pagination, coordinates, stale metrics/manual-LB race, zoom geometry,
  layer visibility, one renderer, size controls, real jsPDF export, retry, mobile clipping
  and cleanup. Actual Google Maps SDK/deck integration also loaded one synthetic site
  with a real map container/canvas and zero provider/runtime errors. No customer writes.
  Evidence: reports/performance/map-browser-checks.json, map-live-sdk-smoke.json,
  map-spatial-benchmark.json, map-fixed-desktop.png and map-fixed-mobile.png.
- Remaining acceptance scope: sustained panning/zooming and memory/FPS on representative
  large customer datasets and deployment-specific Google billing/key/domain restrictions.
  The heavy map renderer still loads only on its route; this increment does not claim
  a total frontend bundle-size improvement or certify every possible map/provider failure.

### Completed increment 14: KPI View and Worst Cell workloads (2026-11-10)

- KPI batch/column requests now cancel when superseded and publish only to their
  selected file. A failed batch is an error with retry, not a permanently cached empty
  dataset. Cached data/configuration is isolated by browser session/user/company,
  expires after 60 seconds and has entry bounds (two datasets/configurations per cache,
  eight analysis results). Reload data invalidates both analytics pages and refetches.
  Expiry controls reuse when requested; this is not background polling or immediate
  ingestion-event invalidation. Entry limits are not a measured total byte budget.
- KPI charts are memoized so typing a filter search does not recompute unchanged
  charts. Cards are paged (12 in Performance mode, 24 otherwise), and searchable
  filter options mount at most 100 entries. Select All applies to every matching
  option across pages, preserving selections outside the search. Set membership
  avoids scanning a long selected-value array for every observation. Full chart data
  and export filter arrays are retained; PDF is labeled as current-page scope and
  retains its existing eight-chart cap. Data reload preserves active filters.
- Chart aggregation uses one-pass sums/counts, without per-group arrays retaining
  every source observation. Chart means/statistics and real worker averages exclude
  missing/blank/nonfinite measurements and preserve observed zero. Numeric zero and
  prototype-like dimension labels are safe. Chart pages clamp/reset after filter or
  Performance-mode changes. Removed raw chart-data console logging.
- Worst Cell file/configuration changes invalidate active analysis, clear stale
  results and terminate/recreate the worker. Metadata/threshold readiness gates Run;
  obsolete context, batch, detail and recompute completions cannot replace the new
  source. Failed batch/context responses are not retained as valid empty results.
- Bad Days date-range analysis calls the backend summary before fetching raw metrics.
  Full batch hydration occurs only for worker fallback/KPI-first analysis. The old
  background average audit and data logging require explicit development kpiDebug=1.
  Browser checks verify the successful backend path issues no batch/data download.
- Affected-cell tables render 50 rows/page; visual pages build at most 10 heatmap
  matrices, after searching raw result rows. No matrices are built for table-only
  display. KPI-first failure badges render 50 per metric with navigation. All returned
  results and existing complete-data exports remain available.
- Isolated 120,000-observation/1,500-group benchmark preserves observed numeric output:
  old aggregation median 5.65 ms, new median 5.87 ms on this run. No aggregation latency
  improvement is claimed; the change removes retained grouping arrays and corrects
  missing-value semantics. This is not an end-to-end or peak-memory benchmark.
- Validation: 23 frontend tests include actual worker-code threshold/pre-post results,
  chart null/zero means, TTL/LRU/session invalidation and existing regressions. Production
  build and targeted ESLint pass. Browser fixtures exercise 14 metrics/21,000 observations,
  retry, obsolete responses, chart/filter pagination, matching Select All, reload,
  Bad Days fast path, heatmap search, real workers and pending-source invalidation.
  Evidence: reports/performance/analytics-browser-checks.json,
  analytics-aggregation-benchmark.json and analytics-*-desktop/mobile.png.
- Remaining acceptance: customer-volume heap/long-task profiling, byte-based cache
  budgets, automatic source-version invalidation and wider end-to-end export/permission
  coverage. Synthetic browser tests do not certify performance for every customer file.

### Superseded increment 15: initial readiness gating (2026-11-10)

User review rejected the blocking cover/data-dependent map creation in this increment.
Increment 16 below replaces that approach with independent basemap startup and a
persistent instance; the record below explains the earlier design and its limitation.

- The site API could finish before Google Maps downloaded/rendered its basemap.
  SDK isLoaded and map onLoad only establish script/instance availability, not visible
  tiles. Layers previously attached at onLoad and counts/legends announced sites early.
- SDK and data fetch still start in parallel. A map instance mounts once the current
  upload list/site request has settled, with the correct source center. Site requests
  track upload/retry identity; a selected-source change recreates the map/overlay while
  reusing the loaded SDK. Ordinary filtering, pan and zoom keep the map instance.
- Google onTilesLoaded now gates deck layer submission. Counts, legends, export readiness
  and the loading cover use the same source-specific tiles/layers state. Source controls
  remain usable during loading. Late tiles events from an old upload cannot unlock a
  new source. Data errors remain visible and retryable rather than looking complete.
- A staged loading message replaces the blank basemap/site-first transition. SDK failure
  and a 30-second preparation timeout offer Reload map, including inside the mobile panel.
  This fixes display ordering/readiness; it does not claim faster Google network delivery.
- Validation: delayed SDK, delayed data, held tiles, old-source tile events, timeout/error
  recovery and existing Map interactions/PDF/cleanup checks passed. Actual Google Maps/deck
  smoke loaded two canvases, dismissed the readiness cover and enabled export after
  readiness, with zero provider/runtime errors. Production build, targeted ESLint and
  23 frontend tests passed. No customer writes. Evidence: map-loading-checks.json,
  map-waiting-for-tiles.png and map-live-sdk-smoke.json under reports/performance/.

### Completed increment 16: independent basemap startup and deferred GPU renderer (2026-11-10)

- Static deck imports put roughly 718 KB of GPU renderer code on the initial map route.
  The previous gate also delayed map construction until site data settled and recreated
  the Google map on upload/retry. Those dependencies were removed, together with the
  blocking cover and obsolete full-page network-data loading branch.
- The Maps SDK/map starts independently of the site API. The initial viewport is
  captured once; camera/options/map-ID/container props stay stable across data updates.
  A single Google map and deck overlay are reused for upload changes, filters and retry;
  new source data updates layers and the existing fitBounds flow instead of remounting.
- The GPU renderer has an explicit dynamic import after map creation. Production map
  route bytes dropped from 1,097,854 to about 380 KB, with about 718 KB deferred. Total
  renderer/application bytes were not eliminated, and this is not an end-to-end latency
  speedup claim. The first basemap tile paint gates overlay attachment only; ordinary
  camera interactions retain the live basemap rather than blocking on every tile batch.
- Fetched-site counts are shown as data counts; plotted counts reflect submitted layers.
  A small nonblocking status chip reports outstanding data/layers. Independent SDK and
  renderer timeouts/error recovery leave the basemap/filter controls usable. Source
  identities/response guards still prevent stale data from populating the current layers.
- Real production-build check deliberately held both site-data and deckRenderer downloads:
  Google tiles painted and Zoom in worked before either response was released. Releasing
  them populated the current source and enabled export, with zero provider/runtime errors.
  Deterministic tests cover SDK/data ordering, single-instance source updates, slow/stalled
  renderer, first-tile readiness and recovery. Existing Map interactions/PDF/cleanup,
  23 frontend tests, production build and targeted ESLint passed; no customer writes.
  Evidence: reports/performance/map-startup-production.json, map-loading-checks.json,
  map-basemap-before-data.png and map-progressive-ready.png.

## Phase 2: reliable import and historical storage

- Reuse UploadService parsing/mapping/validation through a shared ingestion service,
  rather than having the backend call its own HTTP upload endpoint.
- Add reviewed, versioned database migrations before automation schema changes.
- Store source-file ledger: source, checksum, data dates, mapping version, status,
  imported/rejected row counts and dataset version. Persist rejected-row reasons.
- File readiness: upstream atomic rename or completion marker preferred; otherwise
  stable-size/mtime checks plus validation, not just a folder-created event.
- Whole-file checksums stop exact reimports. Business-key uniqueness handles overlapping
  days across different files. Define the row grain before building a unique key.
- Stage and validate a file before publishing committed data; chunk-level restart must
  not duplicate rows or expose a partially completed dataset as ready.
- Decide explicit corrected-data policy (reject/update/version); never silently overwrite.
- Store raw input provenance and track aggregation rules per KPI. Ratio KPIs may require
  summed numerator/denominator counters rather than an average of daily percentages.

Exit gate: retry/crash/overlap/correction tests; old history and row totals preserved.

## Phase 3: one analysis pipeline and historical comparisons

- Build shared analysis services used by manual operations, portal APIs and daily jobs.
- Persist daily snapshots keyed by data date, scope and dataset/analysis version.
- Comparisons: D versus D-1 and D versus the seven preceding days (exclude D).
- Show signed absolute change, relative percent change when denominator is non-zero,
  and percentage-point change for percentage KPIs. Use configured higher/lower-is-better rules.
- Match technology, cell, time window and unit. Disclose missing days, samples and coverage.
- Compare new/resolved/persistent alarms and changes in worst cells and RCA patterns.
- Recompute affected snapshots after late/corrected data; preserve report provenance.
- Optional modules with missing prerequisites return SKIPPED with reasons, not fake success.
- Existing LB predictions classify current data; do not label them future forecasts.

Exit gate: portal and report use identical snapshots; hand-checked comparison fixtures pass.

## Phase 4: scheduled orchestration

Proposed sequence:

    DISCOVER -> VALIDATE -> IMPORT -> SNAPSHOT -> ANALYZE -> COMPARE -> REPORT -> EMAIL

- Use a timezone-explicit scheduler to launch a durable workflow; cron only triggers it.
- Prefer evaluating Spring Batch with its persistent job/step repository for restart/checkpoints.
  Adapt existing job tables/services; do not run two competing orchestration systems.
- Store business date, stage state, attempts, timestamps, heartbeat, error and next retry.
- A database-backed claim/lease plus unique run key prevents duplicate nightly runs.
  In-process locks are insufficient across restarts or multiple backend instances.
- Start time (initial proposal 01:00 IST) depends on measured large-file runtime.
  Reserve time for retries and email; monitor deadline slack at each stage.
- Define input cutoff and no-file/late-file behavior before enabling the scheduler.
- Bound retries with backoff. Distinguish invalid input from transient DB/mail failures.
- Persist catch-up policy for missed schedules; allow safe manual rerun by business date.
- Missing folder means disabled import configuration, not scanning a guessed directory.

Exit gate: crash/resume, concurrent triggers, missing file and deadline-overrun tests.

## Phase 5: common report and email

- One versioned common template: executive summary, trends, KPI deltas, alarms,
  worst cells, RCA and applicable prediction/actions; PDF plus optional detailed Excel.
- Identify data date, comparison windows, dataset version and incomplete modules.
- Store downloadable artifacts and a delivery outbox before sending email.
- Persist recipient configuration, attempt status, provider message ID and send timestamps.
- Avoid duplicate sends on retries; an SMTP timeout after acceptance is ambiguous and
  needs reconciliation, not a blind claim of exactly-once delivery.
- SMTP acceptance is not proof of inbox delivery. Track provider delivery/bounce events
  when supported; otherwise label status as submitted/accepted, not confirmed delivered.
- Monitor the 02:00 IST target; alert if input or processing makes it unattainable.
- No external messages until actual recipients, SMTP/provider settings and activation
  are explicitly configured. Develop with captured/test mail.

Exit gate: PDF/Excel checks, test mailbox, retry/deduplication and failure notifications.

## Phase 6: portal and operations

- Add daily run history, file status, comparison trends, report downloads and delivery status.
- Expose bounded, scoped APIs; paginate history and reuse daily aggregates.
- Show stale/incomplete data explicitly. Browser need not remain open for automation.
- Add retention/cleanup, backups, restore rehearsal and audit logs; preserve source provenance.
- Acceptance run: representative inputs, overlapping data, multiple days, restart and resend.

## Technology decisions and alternatives

| Decision | Why | Alternative / when to reconsider |
| --- | --- | --- |
| Retain modular Spring Boot backend | Reuses domain logic and transactions | Separate services only after measured isolation/scaling needs |
| MySQL durable workflow metadata | Existing DB; recovery and auditability | Quartz JDBC for complex trigger calendars; still needs workflow state |
| Evaluate Spring Batch | Checkpoints, persistent job/step state, restart | Existing custom jobs if they can meet the same guarantees with less complexity |
| Explicit cron timezone | Clear business schedule | OS cron/Task Scheduler as trigger; does not replace recovery logic |
| Daily aggregates + raw provenance | Fast trends and reproducible reports | Warehouse/columnar store only if volume/queries justify migration |
| Actuator/Micrometer + JFR | Established HTTP/JVM/pool observability | Prometheus/Grafana for retention/alerts when deployed |
| Bounded local executors first | Control memory/CPU with fewer moving parts | RabbitMQ/Kafka when distributed buffering/replay needs are demonstrated |
| Cache only measured repeated work | Faster reads without changing authoritative data | Redis when shared multi-instance cache is required; not automatically faster |

Official references:
- https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html
- https://docs.spring.io/spring-batch/reference/job/configuring-job.html

## Inputs needed later (not blocking Phase 0)

Folder path/access, sample daily files, expected volume and arrival time, row grain,
correction policy, saved column/KPI mappings, required site/TA data, customer recipients,
SMTP/provider settings, deployment hardware and missing-file policy.

## Review record for each change

Record problem, measured evidence, chosen approach, alternatives rejected with reason,
schema/API compatibility, correctness tests, before/after performance, rollback and remaining risk.
No dependency, infrastructure or performance setting is added just to claim optimization.

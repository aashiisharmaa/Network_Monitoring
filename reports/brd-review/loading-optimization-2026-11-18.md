# Loading optimization: changes, measurements and limits

18 November 2026. This increment targets shared startup/request overhead, dashboard refresh and native alarm report reads. It is not certification that every endpoint is optimal under customer load.

## Applied changes

1. Removed the unused global Material UI ThemeProvider/CssBaseline, its unused theme module and the unused Material UI/Emotion direct dependencies. No screen uses Material UI components; the application already has Tailwind resets and portal CSS. This removes theme runtime from the initial download/parse path.
2. Added bounded concurrent GET sharing to the common API client. Equivalent overlapping JSON reads share a network request. Identity headers, URL/query, auth-required policy and fetch options are part of the key. Each subscriber owns cancellation; the underlying request stops only when every subscriber has cancelled. Joined subscribers receive independent payload objects. Settled responses are not cached. Binary downloads, writes and explicit cache-policy requests remain independent. Mutations advance a read generation at start and completion so Refresh cannot join an older pending read across a write.
3. Dashboard refresh starts inventory, threshold settings and metric requests concurrently. Rule-target merging waits for base settings, preventing slower settings from overwriting the dynamic thresholds. Initial dashboard loading uses the same ordering guarantee.
4. The router and sidebar share route import functions. A deliberate 180 ms hover/focus can warm the destination chunk before navigation. Touch scrolling, data-saving mode and 2G connections do not trigger speculative imports. The page is not mounted and its effects/API calls do not run during preload. Normal page boundaries handle navigation errors.
5. Native alarm cohort reports/summaries batch metadata and observations across selected runs. Source reads now use two repository queries instead of two per run. Both SQL queries retain the company predicate. Every requested source must exist for that company and be succeeded before observation reads proceed. Per-source 5,000 / cohort 50,000 row limits and the 200-source limit remain enforced. Summary projection omits large raw JSON and avoids detail sorting; detailed workbook order stays compatible. No customer classifications, counts, ages or snapshot semantics were changed.
6. Enabled server compression for JSON/text/CSV responses of at least 2 KiB. Already-compressed PDF/XLSX artifacts are excluded from this MIME list. This becomes active when the new backend is deployed/restarted; the running older backend was not changed.

## Measurements

Browser samples use the existing production preview, new browser contexts and synthetic APIs with a fixed 180 ms response delay. Three samples per version are recorded. They do not measure real database processing, external map provider speed, WAN performance or customer volumes.

| Metric | Before | After | Interpretation |
| --- | ---: | ---: | --- |
| Dashboard loaded script bytes, median decoded size | 398,554 | 315,016 | **21.0% less JavaScript** on this route |
| Main startup JS chunk, approximate minified size | 349 KB | 265 KB | About 24% smaller; route byte total above is the broader measure |
| Initial sample including network-idle wait, median | 2,049 ms | 2,079 ms | Essentially unchanged within this small noisy sample; no proven end-to-end speed-up |
| Refresh click-to-ready sample, median | 1,025 ms | 1,016 ms | Essentially unchanged; do not market this as a latency reduction |
| Refresh API scheduling span, after | Not captured | 437 ms | First three requests start together; rule read follows its settings dependency |
| Source metadata/evidence queries for 30-run cohort | 60 | 2 | Query count established by service/repository regression checks; not total endpoint query count |

Initial API count remains seven and refresh count remains four in this production fixture. These requests represent different data needs. Concurrent duplicate-read reduction is separately verified by tests; no settled stale cache was added to force lower request counts.

The improvement is a verified reduction in bytes and repeated database/request work. Overall dashboard latency was **not** shown to improve materially by these samples. Production performance needs measurement against the deployed backend and representative data before promising a specific load time.

## Verification

- Backend full suite with disposable MySQL: **176 discovered, 145 passed, 31 skipped, zero failures/errors**. New tests verify a 30-source batch uses two repository reads, foreign/missing sources fail before evidence reads, budgets still apply, and real MySQL batch projection matches previous single-source results for summary/details and circle/tenant scopes.
- Frontend unit suite: **35 passed**, including request sharing, independent response objects, per-subscriber/all-subscriber cancellation, retry after error, token/file/auth-policy isolation, mutation generations and binary/no-store exclusions.
- Production build passed. Full lint: zero errors, 15 existing warnings. Large lazy map-renderer bundle warning remains; it was not hidden by changing the warning threshold.
- Synthetic browser checks passed for dashboard/navigation/mobile, native Alarms capture/history/download/conflict/read-only behavior, map readiness ordering, report generation/delivery history, and asynchronous RCA export.
- RCA browser fixture now subscribes to the download event before starting export. Its previous screenshot-before-subscription sequence could miss a download that completed during screenshot capture; product behavior was not changed to accommodate that race.
- The 190 HTTP contracts are preserved. The packaged backend includes the new repository and compression settings. No real emails were sent and no production customer database was migrated or load-tested.

Artifacts: `reports/performance/loading-2026-11-18/` contains bundle inventories, before/after browser samples, comparison JSON, test totals and build/browser logs. `scripts/profile_loading_browser.py` reproduces the synthetic production-browser sample against localhost:4173 by default. Use `--output <path>`; the backend requests are intercepted.

## Deployment and next evidence needed

Frontend development receives source changes through Vite; production requires the rebuilt frontend. Deploy/restart the newly packaged backend in the normal controlled release window to activate SQL/compression changes. The existing local backend was not restarted and its database was not modified.

Next performance work should follow production p50/p95 request traces and query plans: realistic upload/history volume, concurrent exports, largest KPI/map payloads, real provider latency, and frontend long tasks. Keep those separate from the already identified legacy tenant-access/customer-UAT gaps. Do not add blanket caches or increase DB/worker concurrency without correctness, memory and load evidence.

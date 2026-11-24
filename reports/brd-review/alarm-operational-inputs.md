# Alarm operational inputs: implementation and deployment

Reviewed: 18 November 2026. This increment implements explicit MS2/LR and site operational inputs missing from the previous BRD audit. It does not certify complete BRD acceptance.

## Delivered

- Append-only, company-scoped operational snapshots with effective time, actor, source reference, revision, predecessor/base IDs and payload SHA-256. Corrections preserve earlier evidence.
- Per circle and technology: MS2 pending, LR pending, site inventory, reported sites up, GPS/VSWR counts, zone/circle owner and major reason. Per-site updates record UP/DOWN/UNKNOWN, observation timestamp, LR status, owner, recovery reason and responsibility.
- Null means unknown; an explicitly reported zero remains zero. Counts accept JSON integers only. Inputs cannot claim more sites up than supplied inventory. Active circle ownership, timestamps, duplicate entries and payload bounds are validated.
- Concurrent edits require both the expected same-time revision and the expected base snapshot ID. A stale edit returns HTTP 409 instead of replacing somebody else's work.
- Existing **Alarms → National & Circle Summary** supports authenticated reading and admin capture/history. No removed Alarm Operations route/sidebar was restored. Ordinary users cannot capture inputs; backend authorization checks persisted identity/company membership.
- Excel and PDF include customer-reported values and provenance separately from OSS observations. Excel adds Operational Inputs and Reported Site Updates sheets when a snapshot exists. CSV remains source alarm evidence.
- Queued report/escalation envelopes freeze the operational snapshot ID. Retrying delivery uses that revision even after corrections. Legacy envelopes without an ID retain their original no-operational-input context. A pinned revision cannot silently disappear when the feature is disabled.
- Read-only readiness reports optional schema presence, feature flags and configuration counts without connecting to SMTP or exposing credentials.

Reported UP notes do **not** clear detected down alarms, change OEM lifecycle status or reset escalation decisions. Reported MS2/LR/GPS/VSWR values are not inferred from absent source columns. Responsibility classification still needs the customer's approved policy.

## Report rules

A report selects the latest operational snapshot at or before its source/cohort cutoff, then pins it for rendering. Circle reports exclude other circles' input rows. Zone totals only aggregate circle/technology rows represented in source evidence; every represented row must have a supplied value for a complete operational aggregate. Partial values stay unknown. A changed zone mapping is not joined under a historical zone name; the separate captured context remains visible. These are represented-circle totals, not a claim of complete national inventory.

Manual downloads can select a later correction when regenerated. They are not yet durable, saved report versions. The queued email path pins its operational revision; historical delivery metadata remains intact.

## API

Base: `/api/v1/alarm-workflow/companies/{companyId}`. Requests require authentication and company access; writes require an allowed administrator. The existing native alarm workflow feature gate also applies.

| Method and suffix | Purpose |
| --- | --- |
| GET `/operational-inputs?asOf=2026-11-17T19:00:00Z` | Feature status and latest applicable snapshot |
| POST `/operational-inputs` | Capture a new full company snapshot or corrected revision; HTTP 201 |
| GET `/operational-inputs/history?limit=50` | Bounded revision metadata history, at most 100 |
| GET `/readiness` | Read-only schema/configuration readiness |

Example request; IDs are illustrative and must reference the selected company's actual active circles:

```json
{
  "effectiveAt": "2026-11-17T19:00:00Z",
  "expectedRevision": 0,
  "expectedBaseSnapshotId": null,
  "sourceReference": "Approved operational update, 17 November",
  "circles": [{
    "circleId": 12,
    "technology": "4G",
    "ms2Pending": 0,
    "lrPending": null,
    "siteInventoryTotal": 100,
    "siteUp": 98,
    "gpsAlarms": null,
    "vswrAlarms": 0,
    "zoneOwner": "Operations team",
    "circleOwner": "Circle owner",
    "majorReason": "Awaiting confirmed transport diagnosis",
    "sites": [{
      "siteCode": "SITE-001",
      "state": "UP",
      "observedAt": "2026-11-17T18:55:00Z",
      "lrPending": false,
      "owner": "Field team",
      "recoveryReason": "Reported power restoration",
      "responsibility": "Pending approval"
    }]
  }]
}
```

The POST body is a **full company operational snapshot**: callers must retain other circle/technology entries they want to carry forward. The portal merges untouched entries before submitting. Obtain the current snapshot before writing; send its ID as `expectedBaseSnapshotId`. For a correction at the same effective timestamp, also send its revision. For a new timestamp use expectedRevision 0, with the applicable earlier base ID. Unknown fields may be omitted/null, never filled with invented counts. Timestamp strings require explicit offsets and whole seconds for effectiveAt.

## Deployment sequence

1. Back up the target database and verify migrations 008 and 009 are installed.
2. Apply `backend-java/db/migrations/010_alarm_operational_inputs.sql` using the approved migration process. Do not rely on ORM auto-update to create this append-only schema.
3. Deploy the newly built backend. Keep `ALARM_OPERATIONAL_ENABLED=false` until migration verification completes. Enable `ALARM_WORKFLOW_ENABLED` and `ALARM_OPERATIONAL_ENABLED` only for the configured native workflow. The PowerShell runner now passes these workflow settings through to Java.
4. Deploy the frontend, then check readiness under the intended authenticated account. Verify company assignment, active masters, data sources and permissions.
5. Capture approved input, inspect zero/unknown behavior and circle/national exports. Exercise a stale edit and a read-only user. Use SMTP capture/staging recipients for delivery acceptance before real customer dispatch.

The running local backend was checked read-only: health responds, but its live OpenAPI lists none of the native workflow paths. It is an older process. **Source/build completion is not live deployment.** This task did not migrate customer data, restart the user's running services or send external email. Credential files were not read or changed.

## Verification and limits

- Backend suite: 172 discovered, 141 passed, 31 environment-dependent checks skipped, zero failures/errors. Disposable MySQL integration tests cover tenant isolation, append-only corrections, conflict checks, report cells and email revision pinning using captured MIME/PDF.
- Frontend: 27 unit tests passed; production build passed; lint has zero errors and 15 existing warnings. Synthetic desktop/mobile checks cover capture, unknown/zero, reported site updates, history, conflict recovery and read-only users. API mocks do not replace live customer UAT.
- API contract: 190 logical operations, 308 expanded method/path combinations; the previous 186 contracts remain preserved. Generated handbook examples are synthetic.

Customer acceptance still needs actual OEM native files/identity and clear semantics, approved catalogue/classification/ownership policy, recipients and agreed alarm cadence, template reconciliation, capacity benchmarks and deployment/restore drills. No fixed nightly KPI schedule was imposed on the separate alarm BRD.

# Alarm import foundation: delivered increment

16 November 2026. Implements the first draft input contract from the BRD roadmap.
This is a read-only validation endpoint, not durable ingestion or a customer-approved
classification engine. Existing alarm/KPI uploads and reporting are unchanged.

## API

`POST /api/v1/alarm-ingestion/preview`, multipart form, existing authenticated
super-admin authorization. The existing role service also permits legacy accounts
with a blank role; this increment does not change that application-wide policy.

| Field | Meaning |
| --- | --- |
| `file` | UTF-8 CSV or XLSX, maximum 10 MiB |
| `sheet` | Exact raw worksheet name, mandatory for XLSX; never merge sheets |
| `oem` | Optional fallback: Nokia, Ericsson, ZTE, Huawei, Samsung; E/// aliases Ericsson |
| `technology` | Optional fallback: 2G or 4G; must otherwise be supplied in each row |
| `sourceTimezone` | Required ZoneId, for example Asia/Kolkata |

Example with an existing bearer token stored in a shell variable (do not paste
credentials into source files):

```powershell
curl.exe -X POST http://localhost:3001/api/v1/alarm-ingestion/preview `
  -H "Authorization: Bearer $token" `
  -F "file=@reports/brd-review/fixtures/alarm-contract-sample.csv" `
  -F "technology=4G" -F "sourceTimezone=Asia/Kolkata"
```

The synthetic fixture has five valid rows, one per OEM. Missing source codes and
severities are counted separately and never fabricated. Each customer classification
is `UNMAPPED` until an approved, versioned customer catalogue is implemented.
`validRows` means syntactically normalized, not approved for production ingestion.

## Contract and resource boundaries

- Required headers: Circle (or Circle Name), Alarm Text, Alarm Time, and Resource
  (EnodeB ID/Object DN/etc.) or Name. Name is retained separately from object DN.
- OEM/RAT in a row takes precedence over request fallbacks. Unsupported values
  produce row issues. No technology inference from a sheet title or object name.
- Source severity is preserved independently from future customer classification.
- Timestamps support ISO with an explicit offset, ISO local date/time, and strict
  yyyy-MM-dd or dd-MM-yyyy HH:mm:ss. Local times use the explicit source timezone.
  Ambiguous/nonexistent DST times require an offset. Reporting as-of/ageing is pending.
- Duplicate canonical header aliases fail explicitly rather than silently taking
  one of two competing columns. Customer aliases are based on embedded workbook
  headers; further native export formats need fixture-backed adapters.
- Never evaluate formulas or external links. Ignore formulas only in unmapped
  derived columns; formulas in mapped alarm fields are rejected. No cached formula
  values enter normalized alarm records.
- At most 5000 nonempty data rows, 80 columns, 4000 characters per cell, 50 returned
  valid samples and 100 returned issues. Workbook physical rows are capped at 5001;
  oversized formatted sheets require a smaller sample. XLSX archive limits are
  1000 entries and 50 MiB expanded content, in addition to POI protections.
- One active preview per application instance; concurrent requests receive 503
  with Retry-After. This bounds parsing work independently from other job pools.
- Invalid schema/file/options return 400; invalid rows return 200 with rejection
  counts/issues. Authorization failure returns 403 before parsing. No DB writes,
  source-file persistence, lifecycle clearing, email or scheduler activation.

## Verification and next increment

Service and MVC tests use synthetic CSV/XLSX fixtures and mocks, without a running
database. They cover five OEMs, optional source code/severity, timezone conversion,
DST rejection, malformed dates, explicit sheet selection, formula handling, bounds,
and authorization before parsing.

Tenant-owned Zone/Circle master data, circle aliases, source-feed configuration and
migration 008 are now implemented; see [master-data increment](alarm-master-data.md).
Mappings/profiles now connect to durable staged source files and ingestion runs with
snapshot-slot idempotency and explicit failed-run correction revisions. See the
[workflow delivery guide](alarm-workflow-delivery.md) for current behaviour and limits.
Do not reuse preview samples as persisted full imports or count them in reports.

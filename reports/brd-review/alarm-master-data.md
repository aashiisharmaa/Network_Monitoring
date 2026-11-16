# Alarm master data and feed profiles: delivered increment

16 November 2026. Implements Company → Zone → Circle, approved circle aliases and
tenant-owned source-feed profiles. Existing `Company`, KPI uploads, alarm uploads,
mail and scheduler behaviour are unchanged. No customer mappings are seeded.

## Deployment and migration

Apply `backend-java/db/migrations/008_alarm_master_data.sql` through the repository's
existing numbered SQL deployment process, after backing up the target database.
It adds four InnoDB tables and their indexes/foreign keys. It does not alter or
delete existing company records. `companies.id` must be a compatible signed BIGINT
primary key; confirm the target schema before applying. No Flyway baseline or
automatic startup DDL is introduced into an existing unmanaged schema.

MySQL DDL commits implicitly. If deployment is interrupted, verify all four tables
and constraints before proceeding; `CREATE TABLE IF NOT EXISTS` alone does not
repair a partially or incorrectly defined table. Existing numbered scripts plus
this migration must remain immutable after deployment. Re-running the complete
script on the expected schema preserves master data. Application rollback can leave
these additive tables in place; no destructive rollback is automated.

Migration was verified on a disposable MySQL 8.0.44 instance, including an existing
company record, reapplication with new configuration present, and real transaction
rollback. It has **not** been applied to the application's/customer's database.
Restart/redeploy the rebuilt backend after applying the migration. Before migration,
these APIs return a storage-unavailable response; other existing APIs are unaffected.

## Access rules

Every API is under `/api/v1/alarm-config/companies/{companyId}` and remains behind
the existing JWT/session and license filters. Services independently authorize each
operation using persisted user/company state:

| Role | Read | Create/update/delete aliases |
| --- | --- | --- |
| USER | Own active company | Denied |
| ADMIN | Own active company | Own active company |
| SUPER_ADMIN | Explicit active company | Explicit active company |
| Anonymous, unknown/blank role, blocked account/company | Denied | Denied |

The new feature deliberately does not inherit the old helper's blank-role super-admin
fallback. Users with missing roles or company assignments must be corrected through
existing account administration. Cross-company IDs fail before looking up another
company; resource IDs belonging to a different company return 404 within an otherwise
authorized company scope. Composite database foreign keys also prevent a circle,
alias or feed from referencing another tenant's parent record.

## API contracts

Responses use `{ "success": true, "data": ... }`. Lists use keyset pagination:
`?afterId=0&limit=100`, with a maximum limit of 200. Request the next page using the
last returned ID. IDs are database generated; none of the tenant/actor IDs are
accepted from request bodies.

| Method | Suffix | Request |
| --- | --- | --- |
| GET / POST | `/zones` | POST: `code`, `name` |
| PUT | `/zones/{id}` | `name`, `active`, `version` |
| GET / POST | `/circles` | POST: `zoneId`, `code`, `name` |
| PUT | `/circles/{id}` | `zoneId`, `name`, `active`, `version` |
| GET / POST | `/circle-aliases` | POST: `circleId`, `alias` |
| DELETE | `/circle-aliases/{id}` | Only noncanonical aliases; success 204 |
| GET | `/circle-resolution?value=MU` | Returns MAPPED, UNMAPPED or INACTIVE |
| GET / POST | `/feeds` | POST: feed contract below |
| GET | `/feeds/{id}` | Single tenant-scoped profile |
| PUT | `/feeds/{id}` | Full editable contract, `active`, current `version` |

Zone/circle/source codes use uppercase ASCII letters, digits, dots, underscores and
hyphens, maximum 64 characters. Aliases additionally support spaces, with whitespace
collapsed and case normalized. Punctuation is preserved. Codes/source keys are
immutable; display names can change. Circle creation atomically reserves its canonical
code in the alias table, so an existing alias cannot later become another circle's
canonical code. Unknown identifiers remain UNMAPPED. No MU/MUM or other customer
mapping is guessed or preloaded.

PUT is a full replacement of editable fields, not PATCH: send `active` explicitly.
Zone/circle updates can deactivate records. A circle only resolves when both it and
its parent zone are active. Parent deactivation does not silently rewrite child
flags. Re-enabling a circle or scoped feed requires active parents. Canonical aliases
cannot be removed; deactivate their circle instead. Existing source files and
previous reports are not rewritten by configuration changes.

All edits take a short tenant-level company-row lock to serialize namespace changes.
Updates additionally compare `version` and increment it atomically. Stale versions,
duplicate keys/aliases and inactive parent conflicts return 409. Invalid fields
return 400, denied access 401/403, absent tenant-scoped resources 404, unavailable
storage 503. SQL messages are not returned to clients.

## Feed example and semantics

```json
{
  "sourceKey": "SYNTHETIC-4G",
  "name": "Synthetic mixed OEM sample",
  "oem": "MIXED",
  "technology": "4G",
  "fileFormat": "XLSX",
  "sheet": "Alarm Dump",
  "sourceTimezone": "Asia/Kolkata",
  "dumpType": "SNAPSHOT",
  "circleId": null
}
```

- OEM: Nokia, Ericsson (E/// accepted), ZTE, Huawei, Samsung or MIXED. A MIXED
  profile requires explicit OEMs per row when future ingestion uses the profile.
- Technology: explicit 2G or 4G. Use separate profiles for different technologies.
- Format: CSV or XLSX; XLSX requires its exact valid worksheet name. CSV must not
  specify a sheet. Source timezone must be an explicit valid ZoneId.
- Dump type: explicitly SNAPSHOT or DELTA. SNAPSHOT does not certify completeness
  and does not enable automatic clearing. Completeness/lifecycle decisions are later
  ingestion work.
- Optional circleId restricts the future source scope; null represents a multi-circle
  source within the selected tenant, not access to other companies.
- New profiles always start inactive. PUT can change the configuration flag; it does
  not activate a scheduler, monitor a folder, upload alarms or send email.

The existing `/api/v1/alarm-ingestion/preview` remains an independent, read-only
contract validator. It does not yet consume persisted feed profiles. This increment
provides backend configuration APIs, not a frontend administration screen.

## Example setup in PowerShell

Use a token from existing authentication and your actual API port/company ID. These
examples use synthetic names and do not represent customer-approved mappings.

```powershell
$headers = @{ Authorization = "Bearer $token" }
$configurationUrl = "$apiBase/api/v1/alarm-config/companies/$companyId"
$zone = Invoke-RestMethod -Method Post -Uri "$configurationUrl/zones" -Headers $headers `
  -ContentType 'application/json' -Body (@{ code='SYNTHETIC-WEST'; name='Synthetic West' } | ConvertTo-Json)
$circle = Invoke-RestMethod -Method Post -Uri "$configurationUrl/circles" -Headers $headers `
  -ContentType 'application/json' -Body (@{ zoneId=$zone.data.id; code='SYNTHETIC-CIRCLE'; name='Synthetic Circle' } | ConvertTo-Json)
Invoke-RestMethod -Method Post -Uri "$configurationUrl/circle-aliases" -Headers $headers `
  -ContentType 'application/json' -Body (@{ circleId=$circle.data.id; alias='SYNTHETIC-ALIAS' } | ConvertTo-Json)
Invoke-RestMethod -Uri "$configurationUrl/circle-resolution?value=SYNTHETIC-ALIAS" -Headers $headers
```

## Verification

Run `scripts/test_alarm_configuration.ps1` in PowerShell. It initializes a fresh
workspace-local MySQL data directory, starts an owned localhost-only server on port
13316, runs the 17 module tests, shuts it down and retains logs under ignored
`backend-java/target`. `-FullSuite` runs backend regression tests as well. Requires
installed MySQL 8 binaries and the existing bundled Maven/JDK; `-MySqlBin` can override
the binary path. Port conflicts fail rather than reusing or stopping another server.
No application datasource credentials are read. Existing KPI database integration
tests are skipped by clearing their separate DB URL for the duration of the script.

Coverage: role/tenant isolation, blocked/blank-role accounts, bounded listing,
explicit feed validation, strict alias mapping, tenant foreign keys, migration
reapplication, stale update rejection, atomic circle/alias rollback, and simultaneous
alias assignments. API tests also cover malformed JSON/query inputs and conflict
responses without database details.

Verified run: `scripts/test_alarm_configuration.ps1 -FullSuite` completed successfully
on 16 November 2026: 127 tests discovered, 96 passed, 31 environment-dependent tests
skipped, zero failures/errors. All 17 new configuration tests, including seven real
MySQL integration tests, executed and passed. The owned test server shut down and
port 13316 was confirmed closed afterwards.

The bounded durable snapshot workflow, frozen catalogue/site configuration, reports,
mail/escalation outbox, folder/schedule controls and portal are now implemented; see
[workflow delivery guide](alarm-workflow-delivery.md). Master version numbers prevent
lost updates; they are not a full historical activation/audit catalogue. Native
delta/clear reconciliation, operational MS2/LR inputs and customer UAT remain pending.

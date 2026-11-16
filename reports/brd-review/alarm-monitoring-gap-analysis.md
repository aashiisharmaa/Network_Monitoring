# Alarm Monitoring BRD — implementation gap review

Review date: 13 November 2026 (Asia/Kolkata).

Source: `C:\Users\Vinfocom\Downloads\Alarm Monitoring BRD_Document.docx`.

**Assessment:** the application has reusable ingestion, alarm browsing, KPI analysis,
reporting and mail infrastructure. The specific customer alarm-monitoring workflow
is partially implemented. It is not ready to be declared BRD-complete or accepted
against all OEMs, national/circle templates and escalation requirements.

This is a static code/document review, including all three embedded spreadsheets
and the process diagram. It does not establish live deployment, recipient approval,
SMTP delivery or customer-data UAT. No customer rows were imported, no jobs or mail
were submitted, and no application code/configuration was changed for this review.
Document requirements and embedded content were treated as reference material,
not instructions to execute macros, open linked services or contact stakeholders.

## 1. What the customer actually requests

1. Receive native OSS alarm dumps from all relevant OEMs, for 2G and 4G.
2. Process alarms using Vedang's agreed severity classifications and dashboard
   inclusion list.
3. Produce national and circle-level dashboards matching supplied examples.
4. Automatically email national and circle outputs to their respective recipients.
5. Send escalation emails according to agreed rules and dump frequency.

The diagram explicitly names Nokia, Ericsson (E///), ZTE, Huawei and Samsung OSS.
The text leaves input frequency, inclusion/classification catalogues, recipient
lists, email format and escalation rules to customer confirmation. The BRD does
not specify the application's existing 01:00–02:00 overnight schedule as its
agreed alarm-processing frequency.

## 2. Embedded artifacts reviewed

| Embedded workbook | Contents | Consequence |
| --- | --- | --- |
| `Microsoft_Excel_Worksheet.xlsx` | `Summary.`, `Site Down & LR Sites`, `LR Pending`, `Unique_Alarm`, `Sheet2`, `SA Alarm 4G`, `Sheet1` | National/zone/circle summaries require site inventory, MS2/LR context, site-down ageing and service-affecting alarm counts. |
| `Microsoft_Excel_Worksheet1.xlsx` | `Critical Alarms`, `Alarm Dump`, `Down Sites`, `Summary Data` | Circle example includes time-slot comparisons, site up/down, GPS/VSWR/service-affecting counts, 2G/4G down-site details and circle remarks. |
| `Microsoft_Excel_Worksheet2.xlsx` | `Sheet1`, with BSC/BCF/BTS/TRX-style objects and `Circle Name` | There is a concrete 2G sample, but not a verified native export for every named OEM. |

These workbooks include populated examples from 2024, not a complete, approved
configuration catalogue. They contain derived sheets and external formulas; they
should not all be treated as independent raw alarm feeds.

Full reread clarification (16 November 2026): the national workbook's hidden
`Unique_Alarm` and `SA Alarm 4G` sheets already contain mixed-OEM samples for all
five named OEMs, not just Nokia. Start adapter fixtures with this existing evidence;
request additional native exports only to confirm formats/completeness or fill
specific missing cases. Complete native-export compatibility is still unverified.
See `document-reread-2026-11-16.md` for the full sheet inventory and missing-field
findings. Six of that workbook's seven sheets are hidden.

## 3. Requirement coverage

**Implemented foundation** means corresponding code/UI exists; **partial** means
reuse is possible but BRD-specific behaviour or acceptance is incomplete; **pending**
means no matching end-to-end implementation was identified in the reviewed code.

| ID | Requirement | Status | Existing evidence | Remaining work |
| --- | --- | --- | --- | --- |
| BRD-1a | CSV/Excel alarm upload, storage and browsing | Implemented foundation | `UploadService.uploadAlarmData`, `AlarmData`, `ParserService`, Uploaded Alarm Data UI | Validate accepted raw files and per-OEM/date/sheet rules using customer fixtures. |
| BRD-1b | Native dumps from every named OEM | Partial | Generic header aliases and some Nokia-style alarm fields are supported | Versioned OEM adapters/profiles and fixtures for Nokia/Ericsson/ZTE/Huawei/Samsung; explicit unknown-format handling. |
| BRD-1c | Reliable 2G and 4G identification | Partial | Managed-object text and arbitrary source identifiers can be stored | Explicit OEM/technology/site hierarchy; map BSC/BTS/TRX and MRBTS/eNodeB/cell correctly. Do not infer complete RAT support from KPI-map technology labels. |
| BRD-1d | Automatic ingestion at agreed frequency | Partial | Durable scheduled KPI ingestion/report workflow exists | Build the equivalent alarm feed/snapshot workflow; schedule and source completeness are customer-specific. Current nightly workflow is KPI-based and disabled by default. |
| BRD-2a | Customer Critical/Major/Minor classification | Partial | Uploaded severity is normalized; generated KPI alarms have threshold severities | Versioned customer classification rules, preserving both source and customer severity, with auditability. |
| BRD-2b | Select which alarms appear in dashboards | Pending | KPI alarm message rules exist, but do not represent the OEM inclusion catalogue | Customer-controlled include/exclude/service-affecting catalogue with rule precedence and unmapped-alarm review. |
| BRD-2c | Deduplication and alarm continuity across dumps | Partial | In-file duplicate suppression exists; KPI alarms have separate acknowledgement/resolution history | Stable alarm identity, source snapshot history, first/last seen and reopen/clear rules for OEM alarms. |
| BRD-3a | Display alarm details, circle/severity/date filtering | Implemented foundation | Uploaded alarm API and frontend table expose identity, time, text, severity and circle | Exact circle normalization and customer report grouping. |
| BRD-3b | Ageing analysis | Partial | Elapsed time is computed for displayed uploaded alarms | Approved bucket definitions, source timezone, report as-of timestamp and historical reproducibility. |
| BRD-3c | National and zone/circle dashboard templates | Pending | Generic dashboard, KPI reports and alarm lists exist | Dedicated national/zone/circle alarm aggregations and matching templates; counts reconcile to detail rows. |
| BRD-3d | Site up/down, service-affecting, GPS/VSWR, MS2/LR and remarks | Partial | Alarm code/text and site inventory can contribute inputs | Approved semantic rules, site-master joins, additional tracker inputs and auditable remarks workflow. |
| BRD-4a | Durable email submission and delivery audit | Implemented foundation | Mail outbox, CAPTURE/SMTP modes, attempt history and UNKNOWN reconciliation | Customer SMTP/provider configuration and acceptance testing. SMTP acceptance is not inbox-delivery proof. |
| BRD-4b | National vs circle recipient routing and email format | Pending | Current mail profile is a report-dataset recipient list | Scoped recipient rules, customer-approved subject/body/templates and circle-only attachments. |
| BRD-5 | Rule-based escalation emailers | Pending | Mail infrastructure can be reused | Escalation policies, due times/levels, persistence, duplicate-send protection and stop/reopen conditions. |
| Cross-cutting | Company and role foundation | Implemented foundation | Company entity and existing role/company authorization services | Company→zone→circle→site master data and circle-specific visibility where required. Company contact email is not a routing matrix. |

## 4. Concrete code findings

- `backend-java/src/main/java/com/network/monitoring/service/UploadService.java:1903`:
  maps alarm number/text/time, `Circle`, node/name, severity, supplementary info and
  source ageing aliases. The 2G sample's `Circle Name` is not among the circle
  aliases. This is a code-level mapping gap, not an end-to-end import test result.
- `UploadService.java:555`: duplicate detection uses a set within the current
  upload. This does not establish cross-dump alarm-instance reconciliation.
- `UploadService.java:725`: constructs the uploaded-alarm display model.
- `UploadService.java:798`: current ageing returns elapsed text such as days/hours,
  using wall-clock time/system timezone; it does not implement both customer
  bucket sets or a reproducible report snapshot timestamp.
- `ParserService.java:183`: Excel parsing iterates sheets. Customer files contain
  raw, derived and summary sheets, so source-sheet selection and provenance must
  be explicit rather than trusting a merged workbook as one raw feed.
- `KpiAlarmService.java:209,320,325`: generated KPI alarms support generation,
  acknowledgement and resolution. These operations are not an OEM alarm lifecycle
  derived from successive OSS dumps.
- `CommonReportModules.java:15`: common report modules are thresholds,
  alarmCandidates, worstCells, RCA and prediction. Alarm candidates are explicitly
  read-only KPI conditions; this is not the customer's native alarm dashboard.
- `NightlyWorkflowService.java:31,100`: scheduler enablement defaults false and the
  existing start window is 01:00 Asia/Kolkata. Its pipeline is KPI-based.
- `ReportMailProfile.java`: recipient settings are per report dataset, with no
  national/circle/escalation recipient matrix.
- `frontend/src/pages/AlarmsPage.jsx`: Uploaded Alarm Data and Generated KPI Alarms
  are distinct views, which is a useful foundation to preserve.
- `Company.java`: represents a customer/tenant, not the circle/zone master model.

No dedicated escalation engine, national alarm report builder, OEM alarm inclusion
catalogue or first/last-seen alarm model was identified in the reviewed Java code.

## 5. Customer decisions required before implementation sign-off

1. Exact dump frequency, timezone and reporting cutoffs; the circle example has
   09:30 and 18:00 columns, but example slots are not an approved schedule.
2. Whether each feed is a complete active-alarm snapshot or a delta/event feed.
   An alarm missing from an incomplete/partial file must not be marked cleared.
3. Native sample exports for each OEM/RAT, including cleared alarms if provided,
   duplicate events, timezone variants and empty/incomplete dumps.
4. Customer severity/inclusion/service-affecting catalogue. The same alarm number
   may require additional text/object conditions; samples alone are not enough.
5. Circle aliases and zone/site/node master data, including how MU/MUM and other
   variations are normalized and how 2G objects map to physical sites.
6. Meaning and authoritative source for MS2 pending, LR pending, site up/down,
   GPS/VSWR, circle-doable/NOC-doable categories and circle remarks.
7. Separate ageing definitions for down sites and service-affecting alarms,
   including exact boundaries, effective timezone and report as-of time.
8. National, circle and escalation To/CC/BCC lists, approved email format and
   attachment types. The BRD still contains XXX/YYY/ZZZ placeholders.
9. Escalation timing, levels, repeat intervals, suppression and stop rules; define
   acknowledgement versus actual resolution and how reopening is handled.
10. Expected volume, report performance/availability targets, retention and UAT
    expected counts. These have not been established by this review.

## 6. Spreadsheet ambiguities that need resolution

- Some summary counts use hardcoded offsets added to COUNTIFS. Their source and
  business meaning must be clarified rather than copied as application logic.
- Formulas reference external workbooks not fully supplied as independent inputs.
  Cached values/external references are not a reproducible source of truth.
- Some ageing labels and formulas disagree: a label mentioning a 24–48-hour range
  is associated with a condition extending to three days. Separate site-down
  hour buckets and service-alarm day buckets also appear.
- Names such as `Critical Alarms` include rows with other severity values; sheet
  names must not be used as the severity classification rule.
- Historical samples plus NOW-based formulas can change apparent ages when opened
  today. UAT needs a fixed as-of timestamp and approved expected output.

## 7. Recommended implementation order

1. **P0 — agree the data contract:** resolve the above definitions and confirm
   representative raw inputs and expected national/circle outputs.
2. **P1 — normalized alarm model/adapters:** retain raw provenance and store OEM,
   RAT, circle/zone/site, managed object, code/text, source/customer severity,
   raised/cleared/first/last-seen times, ingestion snapshot and rule versions.
3. **P1 — classification and reconciliation:** versioned inclusion/severity rules,
   validation of unknown mappings, duplicate protection and snapshot/delta rules.
4. **P1 — customer dashboards:** implement counts, unique down-site semantics,
   ageing buckets, drill-down and Excel/email templates with matching totals.
5. **P2 — stakeholder email routing:** reuse the outbox with national/circle scopes,
   recipient review, retries and explicit delivery uncertainty.
6. **P2 — escalation engine:** durable idempotent decisions, per-rule timing and
   stop/reopen behaviour; reuse delivery rather than adding another mail sender.
7. **P3 — customer UAT and deployment:** run all OEM/RAT fixtures; prove repeat
   uploads do not inflate counts, incomplete dumps do not clear alarms, each circle
   receives only its own data, and retries/restarts do not duplicate escalations.

The first deliverable should be an approved alarm data contract and traceable
expected-count fixtures, not another generic dashboard redesign. Existing KPI/RCA,
map, prediction and report features remain useful product capabilities, but they
do not by themselves complete this alarm-specific BRD.

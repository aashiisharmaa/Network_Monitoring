# Complete BRD reread — 16 November 2026

Source: `C:\Users\Vinfocom\Downloads\Alarm Monitoring BRD_Document.docx`

Source SHA-256: `b2068ff800f1e6f22a9ff0f6dfa96d8a96568776c4a222537ec1fafee0ddbb15`

The reread covered the Word document's 24 nonempty paragraphs, the OSS workflow
diagram, all three embedded Excel workbooks, all 12 sheets including six hidden
sheets, every nonempty cell/formula, cached cell values, merged ranges, external
link metadata and available comments/defined names. Formulas/macros were not
executed and referenced services/workbooks were not opened. The customer source
document and embedded workbooks were not modified.

## Requirements reconfirmed

1. Native OSS alarm dumps for 2G and 4G; daily frequency mutually agreed.
2. Customer-supplied Critical/Major/Minor classification and dashboard inclusion.
3. Post-processed national and circle dashboards using supplied example formats.
4. Separate national/circle recipient lists and customer-approved email format.
5. Escalation emails according to customer-supplied rules and frequency.

The diagram names Nokia, Ericsson/E///, ZTE, Huawei and Samsung and explicitly
marks the processing/output flow for automation. RCA algorithms, KPI forecasting,
map features and the existing 01:00–02:00 KPI schedule are not explicitly prescribed
as mandatory parts of this alarm BRD.

## All embedded sheets

| Workbook | Sheets | Visibility |
| --- | --- | --- |
| National/zone/circle example | `Summary.` | Visible |
| Same workbook | `Site Down & LR Sites`, `LR Pending`, `Unique_Alarm`, `Sheet2`, `SA Alarm 4G`, `Sheet1` | All six hidden |
| Circle example | `Critical Alarms`, `Alarm Dump`, `Down Sites`, `Summary Data` | Visible |
| 2G sample | `Sheet1` | Visible |

## Important clarification to the first review

The hidden national sheets already contain sample records for **all five OEMs**.
The earlier review did not clearly highlight that breadth. Do not tell the customer
that only Nokia samples were supplied, and do not ask them to resend all examples
without first using the existing ones.

`Unique_Alarm` contains the following populated OEM-labelled sample rows. These
are workbook row counts, not validated production alarm-instance totals:

| OEM | Sample rows | Missing source fields in those rows |
| --- | ---: | --- |
| Nokia | 424 | Name missing in 44 rows |
| Ericsson | 19 | Alarm Number missing in 19 rows |
| ZTE | 86 | Severity and Alarm Number missing in 86 rows |
| Samsung | 7 | Severity and Alarm Number missing in 7 rows |
| Huawei | 5 | Severity and Alarm Number missing in 5 rows |

`SA Alarm 4G` also includes every OEM. These are mixed/processed examples, not
proof that every native OSS export layout and lifecycle case has been validated.
Use them as initial fixtures and confirm additional raw formats only where needed.
An adapter/classification design cannot assume a numeric Alarm Number or supplied
severity is always present. Preserve unknowns and agree OEM-specific identity and
customer severity rules.

## Additional details to retain in the implementation plan

- Operational categories include Circle Doable, Field Doable, Remote Doable-NOC,
  combined responsibility categories, X2 alarms, hardware issues and GPS/VSWR.
- Hidden `Sheet2` contains L2 remarks such as Resolved, LR Pending, Site Down,
  Site Not Reachable, Mplane Not Available and WIP. These are sample workflow
  labels; customer policy must determine their operational meaning.
- Source severity varies in case and includes INDETERMINATE. Normalization must
  preserve unsupported values for review instead of guessing a BRD category.
- Circle aliases include MU/MUM and other variations; the 2G sample uses the
  distinct `Circle Name` header and BSC/BCF/BTS/TRX-style objects.
- The national summary needs separate MS2 pending 2G/4G, site-down ageing/counts,
  service-affecting totals, zone/circle owners and major reasons.
- The circle summary includes 09:30/18:00 example columns, site up/down, GPS/VSWR,
  service-affecting counts and down-site details/remarks for 2G and 4G.
- National workbook references two external workbooks and contains hardcoded
  count additions; their business definitions require confirmation.
- Sample cached data includes #N/A and negative ageing values. Some ageing labels
  disagree with formula ranges; validate against an approved as-of time.
- `Critical Alarms` is a sheet name, not a guarantee every row is Critical.
- Repeated rows occur in example detail sheets. A workbook's name `Unique_Alarm`
  or its formatted row dimensions do not establish the correct deduplication key.

## Effect on the roadmap

The core gap assessment remains: upload/browse and supporting job/report/mail
infrastructure exist, while customer catalogue, OEM verification, cross-dump alarm
lifecycle, exact national/circle outputs, recipient routing and escalation require
further work. Refine Phase 0 to use the existing mixed-OEM hidden-sheet data first.
Separate explicit BRD requirements, template-implied requirements and engineering
reliability measures when agreeing acceptance with the customer.

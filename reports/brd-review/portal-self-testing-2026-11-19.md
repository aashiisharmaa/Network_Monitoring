# Portal self-testing — 19 November 2026

These datasets are in the running application's database, not only in isolated test fixtures. The frontend runs on port 5173 and the backend on port 3001.

## Open the test workspace

Open **http://localhost:5173/** in your browser. Sign in using the dedicated demo account below if no saved session is present:

- Email: `portal.testing.20261119@example.test`
- Company: `DEMO - Portal self-testing (synthetic)` — company ID 1
- Role: ADMIN, scoped to that company
- Credentials: the local ignored file `reports/performance/rwanda-import-2026-11-19/demo-login.json`. Passwords and bearer tokens are intentionally excluded from this tracked guide.

Use your existing Super Admin account at **http://127.0.0.1:5173/** for report generation/configuration and user administration. The two hostnames have separate browser sessions. The demo account can read and download its company's reports. Its permissions have not been expanded to bypass these restrictions.

## Select the right data

| Workspace | Upload ID | Source | Expected content |
| --- | ---: | --- | --- |
| Demo company | 105 | DEMO_SYNTHETIC_sites.csv | 3 sites, 6 cells |
| Demo company | 106 | DEMO_SYNTHETIC_kpi_hourly.csv | 1,152 hourly observations; 12–19 November 2026 |
| Demo company | 107 | DEMO_SYNTHETIC_uploaded_alarms.csv | 3 labelled synthetic uploaded alarms |
| Global / Super Admin | 94 | Rwanda_all_sites_PCI_Tac__mapped.csv | 2,874 site/cell rows |
| Global / Super Admin | 95 | Physical_sitedb_Rw__mapped.csv | 5,102 site/cell rows |
| Global / Super Admin | 98 | RW_LTE_BBH.csv | 67,668 busy-hour KPI rows |
| Global / Super Admin | 99 | RW_LTE_24_Hours.csv | 39,114 hourly KPI rows |
| Global / Super Admin | 100 | RW_LTE_Daily.csv | 1,686 daily KPI rows |
| Global / Super Admin | 101 | RW_LTE_RSSI_CQI.csv | 1,056 KPI rows |
| Global / Super Admin | 102 / 103 / 104 | SYNTHETIC sites / KPI / uploaded alarms | Separate global copies of the synthetic fixtures |

For Rwanda charts, choose **17 January–7 February 2024**; RSSI/CQI ends 31 January. These files will not show data in a November 2026 date filter. Keep BBH, hourly, daily and RSSI/CQI separate. Site uploads 94 and 95 overlap; do not add their row counts as unique cell inventory. Original Downloads files were not modified.

## Manual test checklist

1. **Uploads:** confirm demo uploads 105, 106 and 107. Upload a copy of the local synthetic fixtures if you want to test a new upload. A new upload creates a new file scope; do not accidentally overwrite customer datasets.
2. **Dashboard:** select KPI file 106. Expect 3 sites and 6 cells. Review metrics and scope changes. A missing configured target should remain explicitly unassessed.
3. **KPI Analytics:** select file 106 and 12–19 November 2026. Compare the final day with previous days. Two synthetic cells deliberately degrade on the final day. Exercise filters, chart/table views and exports.
4. **Coverage Map:** select site file 105 and KPI file 106 independently. Fit sites, zoom, toggle coverage/cells, open site details and analytics. For real Rwanda geometry, use the Super Admin session, site file 95 and the desired Rwanda KPI file.
5. **Worst Cell / RCA:** select 106. The saved RCA preset is `DEMO - Nokia synthetic testing`. Review mappings before running; it maps the fixture's SINR, CQI, RRC setup success and DL PRB utilization. It does not supply every vendor counter. Compare 12–18 November against 19 November, then test evidence and recommendation visibility and export. ERAB drop is retained as its own daily metric; it is not relabelled as a PS drop-call counter.
6. **Alarms — uploaded/generated:** uploaded file 107 contains labelled test alarms. Generated KPI alarms are a separate calculation using configured rules; they are not native OSS source alarms.
7. **Alarms — National & circle summaries:** the demo company has 2G and 4G native OSS snapshots, catalogue rules, source-to-site mappings and operational MS2/LR inputs. Refresh the summary with a cutoff that includes the seeded snapshots. Expect 3 current source observations and 2 RAT-scoped down-site entries. The same physical site in different RATs is counted separately. Explore source evidence, operational history and PDF/XLSX/CSV downloads. A corrected failed import remains in the audit history intentionally.
8. **Daily Reports:** select file 106. Review daily comparisons with the previous day and seven-day baseline, module coverage, and downloadable reports. Generate/configure using the existing Super Admin session. The company ADMIN can view/download. Missing approved thresholds or unsupported module inputs must remain visible as missing/partial coverage.
9. **Access:** compare demo company and global sessions. Native alarm endpoints enforce company access, and user administration belongs to the existing Super Admin account. Legacy dataset selectors still list global upload history in the demo session; complete isolation of those legacy read endpoints is not established by this test setup. Select the explicit demo IDs above.

## Verified sample results

- Daily snapshot **2**, file **106**, data date **19 November 2026**: 6 cell groups across 8 days, 18 current metric values. Each of the 3 configured daily metrics shows 2 degraded cells against both the previous day and the seven-day baseline; 4 remain unchanged.
- Saved-preset RCA job **24**: 48 flagged hourly observations from the 2 deliberately degraded cells (24 observations per cell), with `Accessibility / setup failure` as the primary cause. These are 48 observations, not 48 unique sites or incidents.
- Common report **2** is downloadable with status **PARTIAL**. The PDF has 8 readable pages; the workbook includes daily comparisons, module coverage, RCA, prediction and the manifest. Partial coverage is explicit: some metrics lack approved threshold rules, alarm candidates are read-only and prediction lacks pinned site/TA/alarm context. RCA response bounds are also disclosed. Generation itself completed.
- Native summary exports are readable: a 2-page PDF, a 7-sheet workbook and CSV. The workbook includes source evidence and operational inputs.
- A fresh normal demo-account login successfully read/downloaded common report 2 and generated/downloaded RCA export **1** (93,426 bytes). The local workbook is `portal-rca-demo.xlsx` in the evidence directory.

Local sample downloads: [Common PDF](../performance/rwanda-import-2026-11-19/portal-common-demo.pdf), [Common Excel](../performance/rwanda-import-2026-11-19/portal-common-demo.xlsx), [Native alarm PDF](../performance/rwanda-import-2026-11-19/portal-native-demo.pdf).

Earlier failed date-analysis attempts and the earlier minimal-mapping report remain in history for traceability. Select snapshot/report **2** for the final daily demonstration.

## What is intentionally not activated

- No real customer email has been sent. SMTP/recipients and scheduled delivery need an explicitly configured test destination before a delivery test.
- No customer watch-folder path was invented. Folder pickup needs the actual accessible path and deployment permissions.
- Global threshold rules were not overwritten for a demo. Demo synthetic values are not customer findings or approved target definitions.
- Historical failed import attempts remain traceable. Do not retry jobs 23/24: their successful replacement is upload 98.

## Local evidence

`reports/performance/rwanda-import-2026-11-19/` contains upload journals, date reprocessing verification, native demo state, analysis/report IDs, exported files and screenshots. It also contains private browser/login state and is ignored by Git; do not publish that directory.

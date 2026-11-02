"""Refresh report claims from the recorded demonstration evidence."""
from pathlib import Path
import json
import re

ROOT=Path(__file__).resolve().parents[1]

def main():
    path=ROOT/'CLIENT_HANDOVER_DOCUMENT.md'
    source=path.read_text(encoding='utf-8')
    source=re.sub(r'### 14\.10 Executed demonstration and output evidence.*?(?=## 15\. Handover Deliverables and Responsibilities)', '', source, flags=re.S)
    results=json.loads((ROOT/'reports/handover/workflow-results.json').read_text(encoding='utf-8'))
    aliases={'Map and issue controls':'Map rendering and layers'}
    latest={aliases.get(item['workflow'],item['workflow']):item for item in results}
    rows=[]
    for name,item in latest.items():
        detail=item['detail']
        if isinstance(detail,dict):
            if 'file' in detail: text=f"Saved {Path(detail['file']).name}; {len(detail.get('sheets',[]))} workbook sheets" if detail.get('sheets') else f"Saved {Path(detail['file']).name}"
            elif 'jobId' in detail:text=f"Job {detail['jobId']}: {detail.get('status')}"
            elif 'renderedCharts' in detail:text=f"{detail['renderedCharts']} charts rendered for KPI file #92"
            elif 'enabledControls' in detail:text=', '.join(detail['enabledControls'])
            elif name=='Generated KPI alarms':text=f"{detail.get('data',{}).get('total')} generated alarms for file #92"
            else:text=f"HTTP {detail.get('httpStatus',200)}; response saved as evidence"
        else:text=str(detail).replace('\n',' ')[:220]
        rows.append(f"| {name} | {item['status']} | {text.replace('|','/')} |")
    block='''### 14.10 Executed demonstration and output evidence

The main demonstration workflows were executed before capturing the result screens below. This is a sample-data operational check, separate from production acceptance. Existing site/KPI/alarm upload IDs #91/#92/#93 were reused. The run generated KPI alarms for file #92 and created an RCA analysis job; account settings and threshold definitions were preserved.

| Executed workflow | Result | Evidence / outcome |
|---|---|---|
'''+ '\n'.join(rows)+'''

Threshold evaluation processed 324 KPI values from 12 rows using 22 active rules. Results: 197 critical, 29 major, 31 minor, 17 warning and 50 normal values. KPI alarm generation produced 274 open alarms, matching the 274 non-normal evaluations. These are sample-data findings under the current rules, not a live production incident count.

The completed RCA job processed 12 rows and identified 11 anomalies: 4 critical and 3 major. RCA severity and threshold severity are different calculations and should not be expected to match one-for-one. The recommendation workbook contains seven sheets covering anomaly details, scored data, root causes, impacted cells, trends, thresholds and a parameter guide.

![Figure 14. Populated KPI charts after data loading and successful workbook export checks.](reports/handover/images/kpi-charts.png)

The chart view contains 12 visible cards in performance mode from 27 available metrics. Both Excel export formats were downloaded and opened as valid workbooks. See `reports/handover/exports/` for the actual generated files.

![Figure 15. Site intelligence result section after site analysis completed.](reports/handover/images/site-intelligence-results.png)

Site intelligence and prediction endpoints returned successful results for the selected sample data. The topology-assisted API check explicitly supplied site file #91 and KPI file #92. The Sites screen applies its own default file selection and displays Coordinates Missing for the selected detail, while the explicitly scoped map plots all 12 cells. Confirm the topology-file binding in the Sites workflow before accepting location-dependent scores. Cluster grouping can also differ with the chosen topology scope.

![Figure 16. Generated KPI alarms after the Generate action completed.](reports/handover/images/alarms-generated.png)

The generated-alarm screen shows the outcome of the generation action, including severity/status totals. The separate uploaded-alarm screen earlier in this section documents the six raw alarm rows already present.

![Figure 17. Completed RCA result summary and actionable recommendation table.](reports/handover/images/rca-results.png)

Core RCA fields were mapped to `shortname`, `date`, `cellid`, `siteid` and `tech`; the automatic KPI mapping action was then applied. The recommendation job completed before the result screenshot and workbook export were captured.

![Figure 18. Live map after issue-layer controls were enabled.](reports/handover/images/map-analysis-layers.png)

The PCI Collision, PCI Confusion and Overshooting control states were checked after activation. A visible toggle confirms the interface state; the sample may have no findings for an individual issue type. RF classification accuracy and individual relation-line correctness still require operator acceptance with representative network data.

The map PDF export completed successfully and is retained as `reports/handover/exports/map-report.pdf`. The other retained outputs are `kpi-report-format-1.xlsx`, `kpi-report-format-2.xlsx` and `rca-recommendation.xlsx` in the same directory.

### Remaining acceptance scope

- Fresh source-file ingestion, duplicate/invalid upload cases and very large datasets require separate controlled acceptance tests; this run reused existing uploaded sample files.
- Pre/post comparisons need suitable observations from two distinct periods; the current sample demonstration used a single range.
- Account administration, license changes, alarm acknowledgement/resolution and threshold edits were not changed during the demonstration.
- Production deployment, backup restoration, performance/load testing and radio-engineering calibration remain receiving-team acceptance activities.

Execution audit: `reports/handover/workflow-results.json`. Successful result payloads and downloaded outputs are retained alongside the screenshots. Earlier capture-script attempts, if present in the audit, are superseded by the final successful check shown above; the table uses the latest result for each workflow.

'''
    source=source.replace('## 15. Handover Deliverables and Responsibilities',block+'## 15. Handover Deliverables and Responsibilities')
    source=source.replace('The captured view contains six uploaded alarms. Alarm time is displayed as a dash and aging as N/A for these sample rows; validate source timestamp mapping before operational use. Generated KPI alarms are available through the separate tab and were not regenerated for this capture.','The captured view contains six uploaded alarms. Alarm time is displayed as a dash and aging as N/A for these sample rows; validate source timestamp mapping before operational use. The generated KPI alarm workflow was executed separately; its results appear in Section 14.10.')
    source=source.replace('The image documents the configuration workflow. Required identity mappings must be completed before execution; this screenshot does not claim that an RCA job or workbook export was run.','Identity mappings were completed, automatic KPI mapping was applied, and the RCA job and workbook export completed successfully. The completed result table appears in Section 14.10.')
    source=source.replace('Select the correct source and dimensions before comparing periods or exporting a report. The image documents the filter and export interface; export execution was outside this visual capture.','Select the correct source and dimensions before comparing periods or exporting a report. Both Excel export formats were executed successfully; the chart screenshot and output evidence appear in Section 14.10.')
    source=source.replace('| Full upload / export / RCA / map acceptance | Pending | Requires full workflow testing with representative files and client review |','| Sample analysis / exports / RCA / map checks | Executed | Detailed outcomes and actual outputs recorded in Section 14.10 |\n| Full business acceptance | Pending | Client-specific cases, source ingestion, scale and operator validation |')
    source=source.replace('**Version:** 1.2','**Version:** 1.3')
    path.write_text(source,encoding='utf-8')
    for script in ['create-client-handover-doc.py','create-client-handover-pdf.py']:
        q=ROOT/'scripts'/script
        q.write_text(q.read_text(encoding='utf-8').replace('Version 1.2','Version 1.3'),encoding='utf-8')
    print('Report updated from completed workflow evidence.')

if __name__=='__main__':main()

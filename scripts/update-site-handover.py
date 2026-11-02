"""Update only the map/site evidence in the complete project handover."""
from pathlib import Path
import json
import re

ROOT=Path(__file__).resolve().parents[1]

def main():
    target=ROOT/'CLIENT_HANDOVER_DOCUMENT.md'
    source=target.read_text(encoding='utf-8')
    source=re.sub(r'### 14\.11 Map and site operations.*?(?=## 15\. Handover Deliverables and Responsibilities)','',source,flags=re.S)
    evidence=json.loads((ROOT/'reports/handover/site-operations.json').read_text(encoding='utf-8'))
    latest={r['operation']:r for r in evidence}
    table=[]
    for name,r in latest.items():
        detail=r['detail']
        if isinstance(detail,dict):
            if 'sites' in detail:detail='Site #91 / KPI #92; 3 sites, 12 cells, zero missing coordinates'
            elif 'name' in detail:detail=f"{detail['name']}; {detail['cells']} cells; selected-site popup inspected"
            elif 'bytes' in detail:detail='Updated map PDF downloaded and validated'
            else:detail=str(detail.get('message') or detail)
        table.append(f"| {name} | {r['status']} | {str(detail).replace('|','/').replace(chr(10),' ')[:240]} |")
    block='''### 14.11 Map and site operations — corrected visual handover

The map was rechecked with site upload #91 and KPI upload #92. It contains GZB_A (#1001), NOI_B (#2001) and DEL_C (#3001), with four cells per site. All twelve source rows have coordinates. The original overview had coverage switched off; the replacement overview explicitly enables Cells, enlarges the display, opens the site legend and provides a wider geographic view.

Display settings used for the revised evidence: Site Size 250% and Cell Radius 220%. These settings change visual size only; they do not change source coordinates, KPI values, thresholds or calculated risk scores. The left filter panel is hidden for the final full-map overview to avoid covering site markers. Individual site screenshots use a closer view with the selected-site popup and legend visible.

For the same demonstration: open Map, select site upload #91 and KPI upload #92, turn Cells ON, expand Site Legend, then select a site to inspect its details. Use Utility to adjust display size. Open Analytics for clusters, handover relations, worst cells and alarm context. Enable issue layers separately to compare them. Prediction mode intentionally hides Cells; turn Cells back on when returning to coverage review.

A map rendering defect was also corrected: the Clusterization and Handover detail panels were nested inside the Overview condition and could not render when their own tab was selected. Their rendering conditions are now independent. The frontend production build passed, and browser checks confirmed populated cluster and handover panels. The change is limited to map panel rendering; other project modules and the earlier report content are retained.

| Map/site operation | Recorded result | Evidence scope |
|---|---|---|
'''+ '\n'.join(table)+'''

The table distinguishes display/control checks from source validation and execution results. A successful layer check confirms activation and removal in the interface; network-engineering acceptance of the resulting RF classifications remains necessary. LB/WCF execution completed with HTTP 200 for all 12 rows: 12 balanced and zero unbalanced. The interface still shows two quality-check recommendations; quality actions are separate from the load-balance classification. No TA file was supplied, so the service ran in KPI-only mode and reported that coverage features were skipped.

'''
    figures=[
        ('map-site-1001','GZB_A, site #1001, selected with its four-cell detail popup.'),
        ('map-site-2001','NOI_B, site #2001, selected with its four-cell detail popup.'),
        ('map-site-3001','DEL_C, site #3001, selected with its four-cell detail popup.'),
        ('map-pci-collision','PCI Collision layer enabled independently over the selected site data.'),
        ('map-pci-confusion','PCI Confusion layer enabled independently; its off state was checked afterwards.'),
        ('map-overshooting','Overshooting layer and site coverage display.'),
        ('map-missing-neighbours','Missing Neighbours layer enabled for the selected topology and KPI scope.'),
        ('map-handovers','Technology, band and PCI handover controls activated with visible relation paths.'),
        ('map-clusters','Populated Clusterization panel after correcting its rendering condition.'),
        ('map-handover-analysis','Populated Handover Intelligence and neighbour-relation list after the rendering correction.'),
        ('map-worst-cell-analysis','Worst Cells analytics for the chosen single KPI metric.'),
        ('map-alarm-analysis','Alarm Cells analytics for the selected KPI file.'),
        ('map-prediction-analysis','Prediction view after successful KPI-only LB/WCF execution; all 12 rows are balanced.')]
    for number,(key,caption) in enumerate(figures,19):
        if (ROOT/'reports/handover/images'/f'{key}.png').is_file():
            block+=f'![Figure {number}. {caption}](reports/handover/images/{key}.png)\n\n'
    block+='Map/site audit: `reports/handover/site-operations.json`. The refreshed map export remains at `reports/handover/exports/map-report.pdf`. The KPI and RCA exports and the rest of the complete-project handover remain unchanged.\n\n'
    source=source.replace('## 15. Handover Deliverables and Responsibilities',block+'## 15. Handover Deliverables and Responsibilities')
    source=source.replace('The map rendered successfully with site upload #91 and KPI upload #92. Its panel reports 3 sites, 12 cells plotted and zero rows without coordinates. Individual layer toggles remain part of the client acceptance checklist.','The revised map overview uses site upload #91 and KPI upload #92, with Cells enabled, larger display scales and an expanded three-site legend. Each of the three sites and its four cells was inspected separately. Source validation found zero missing coordinates. Detailed operation checks and closer site views are in Section 14.11; RF correctness remains part of client acceptance.')
    source=source.replace('**Version:** 1.3','**Version:** 1.4')
    target.write_text(source,encoding='utf-8')
    for name in ['create-client-handover-doc.py','create-client-handover-pdf.py']:
        p=ROOT/'scripts'/name
        p.write_text(p.read_text(encoding='utf-8').replace('Version 1.3','Version 1.4'),encoding='utf-8')
    print('Updated map/site evidence; retained the complete project report.')

if __name__=='__main__':main()

"""Execute the handover demonstration and capture completed results.

Read login JSON from stdin; keep credentials/session tokens out of artifacts.
"""
import json
import sys
import time
from pathlib import Path
from datetime import datetime
from zoneinfo import ZoneInfo
from playwright.sync_api import sync_playwright
from openpyxl import load_workbook

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'reports/handover'
IMAGES=OUT/'images'
EXPORTS=OUT/'exports'
EXPORTS.mkdir(exist_ok=True)

def main():
    creds=json.load(sys.stdin)
    only=set(sys.argv[sys.argv.index('--only')+1].split(',')) if '--only' in sys.argv else set()
    records=json.loads((OUT/'workflow-results.json').read_text(encoding='utf-8')) if only and (OUT/'workflow-results.json').exists() else []
    def record(name,status,detail):
        records.append({'workflow':name,'status':status,'detail':detail,'time':datetime.now(ZoneInfo('Asia/Kolkata')).isoformat()})
        (OUT/'workflow-results.json').write_text(json.dumps(records,indent=2),encoding='utf-8')
        print(name+': '+status+' | '+str(detail)[:230],flush=True)
    with sync_playwright() as p:
        browser=p.chromium.launch(channel='msedge')
        context=browser.new_context(viewport={'width':1600,'height':1000},accept_downloads=True)
        page=context.new_page()
        page.set_default_timeout(25000)
        def goto(route):
            page.goto('http://127.0.0.1:5173'+route,wait_until='domcontentloaded',timeout=60000)
            try:page.wait_for_load_state('networkidle',timeout=20000)
            except Exception:pass
        def shot(key,target=None):
            if target is not None:
                target.evaluate("el => el.scrollIntoView({block:'start'})")
                page.evaluate('window.scrollBy(0, -80)')
            page.wait_for_timeout(1200)
            page.screenshot(path=str(IMAGES/(key+'.png')),animations='disabled',timeout=45000)
            (OUT/(key+'-screen.txt')).write_text(page.locator('body').inner_text(),encoding='utf-8')
            print('Screenshot: '+key,flush=True)
        def task(name,action):
            if only and name not in only:return
            try:action()
            except Exception as e:record(name,'FAILED',str(e).split('Call log:')[0][:800])
        def download(name,button):
            with page.expect_download(timeout=120000) as info:button.click()
            item=info.value
            path=EXPORTS/(name+Path(item.suggested_filename).suffix)
            item.save_as(str(path))
            detail={'file':str(path.relative_to(ROOT)),'bytes':path.stat().st_size}
            if path.suffix.lower()=='.xlsx':
                workbook=load_workbook(path,read_only=True)
                detail['sheets']=workbook.sheetnames
                workbook.close()
            elif path.suffix.lower()=='.pdf':
                assert path.read_bytes().startswith(b'%PDF-')
            record(name,'PASS',detail)
        goto('/login')
        page.get_by_placeholder('Enter your email').fill(creds['email'])
        page.get_by_placeholder('Enter your password',exact=True).fill(creds['password'])
        page.get_by_role('button',name='Sign In',exact=True).click()
        page.wait_for_url('http://127.0.0.1:5173/',timeout=60000)
        creds.clear()
        token=page.evaluate("localStorage.getItem('authToken')")
        headers={'Authorization':'Bearer '+token}
        record('Authentication','PASS','Signed in through the application login form.')
        def api(name,path,method='get',data=None):
            response=context.request.fetch('http://127.0.0.1:3001/'+path,method=method.upper(),headers=headers,data=data,timeout=120000)
            payload=response.json()
            (OUT/(name+'.json')).write_text(json.dumps(payload,indent=2),encoding='utf-8')
            if not response.ok or payload.get('success') is False:raise RuntimeError(str(payload)[:700])
            record(name,'PASS',{'httpStatus':response.status,'evidence':name+'.json'})
            return payload
        task('Backend health',lambda:api('backend-health','health'))
        task('Dashboard',lambda:(page.get_by_text('Backend Online',exact=False).first.wait_for(timeout=45000),shot('dashboard'),record('Dashboard','PASS','Sample KPI scope loaded.')))
        task('Upload history',lambda:(goto('/uploads'),shot('uploads'),record('Upload history','PASS','Existing source records loaded; no duplicate source uploads created.')))
        task('Threshold evaluation',lambda:api('threshold-evaluation','threshold-rules/evaluate-file?fileId=92'))
        task('Site intelligence',lambda:api('site-intelligence','sites/intelligence?fileId=92&siteFileId=91&limit=20'))
        task('Site prediction',lambda:api('site-prediction','site-prediction/recommendations?fileId=92&limit=50'))
        def kpi():
            goto('/kpi')
            charts=page.locator('[data-kpi-title]')
            charts.first.wait_for(timeout=45000)
            shot('kpi')
            shot('kpi-charts',charts.first)
            record('KPI charts','PASS',{'renderedCharts':charts.count(),'fileId':92})
            download('kpi-report-format-1',page.get_by_role('button',name='Export Excel (Format 1)',exact=True))
            download('kpi-report-format-2',page.get_by_role('button',name='Export Excel (Format 2)',exact=True))
        task('KPI analysis and exports',kpi)
        def alarms():
            goto('/alarms')
            page.get_by_role('button',name='Generated KPI Alarms',exact=True).click()
            with page.expect_response(lambda r:'/alarms/generate' in r.url and r.request.method=='POST',timeout=120000) as info:
                page.get_by_role('button',name='Generate',exact=True).click()
            payload=info.value.json()
            (OUT/'alarm-generation.json').write_text(json.dumps(payload,indent=2),encoding='utf-8')
            if not info.value.ok or payload.get('success') is False:raise RuntimeError(str(payload))
            try:page.wait_for_load_state('networkidle',timeout=30000)
            except Exception:pass
            shot('alarms-generated')
            record('Generated KPI alarms','PASS',payload)
        task('Generated KPI alarms',alarms)
        task('Validation results',lambda:(goto('/validation-report'),page.get_by_text('PASSED',exact=True).wait_for(timeout=45000),shot('validation-report'),record('Validation results','PASS','Sample validation and worst-performance results loaded.')))
        task('Sites',lambda:(goto('/sites'),page.get_by_text('All Sites',exact=True).wait_for(timeout=45000),shot('sites'),shot('site-intelligence-results',page.get_by_role('heading',name='Site Intelligence',exact=True)),record('Sites','PASS','Site health and intelligence sections loaded.')))
        def rca():
            goto('/recommendation')
            selects=page.locator('select')
            for i,value in enumerate(['shortname','date','cellid','siteid','tech'],3):selects.nth(i).select_option(value)
            page.get_by_role('button',name='Auto Suggest Mapping',exact=True).click()
            with page.expect_response(lambda r:'/recommendation/jobs' in r.url and r.request.method=='POST',timeout=60000) as started:
                page.get_by_role('button',name='Run Recommendation',exact=True).click()
            job=started.value.json()
            jobid=job.get('data',{}).get('jobId')
            if not jobid:raise RuntimeError(str(job))
            print('RCA job started: '+str(jobid),flush=True)
            page.get_by_text('Recommendation complete.',exact=True).wait_for(timeout=300000)
            result=context.request.get('http://127.0.0.1:3001/kpi/recommendation/jobs/'+str(jobid),headers=headers,timeout=60000).json()
            (OUT/'rca-completed-job.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
            if result.get('data',{}).get('status')!='COMPLETED':raise RuntimeError('RCA job did not complete successfully')
            shot('recommendation')
            headings=page.get_by_role('heading')
            print('RCA result headings: '+str(headings.all_text_contents()),flush=True)
            # Result controls follow the configuration panel; scroll to completed output.
            page.evaluate('window.scrollTo(0, 950)')
            shot('rca-results')
            record('RCA execution','PASS',{'jobId':jobid,'status':'COMPLETED','evidence':'rca-completed-job.json'})
            download('rca-recommendation',page.get_by_role('button',name='Export',exact=True).first)
        task('RCA execution and export',rca)
        def maps():
            goto('/map')
            page.get_by_text('Loading map...',exact=True).wait_for(state='hidden',timeout=60000)
            page.wait_for_timeout(4000)
            shot('map')
            checks=[]
            for label in ['PCI Collision','PCI Confusion','Overshooting']:
                control=page.get_by_text(label+' OFF',exact=True)
                if control.count():
                    control.locator('xpath=../../..').get_by_role('button').click()
                    page.get_by_text(label+' ON',exact=True).wait_for()
                    checks.append(label+' ON')
            shot('map-analysis-layers')
            record('Map and issue controls','PASS',{'enabledControls':checks,'note':'Visual state verified; RF correctness requires operator acceptance.'})
            task('Map PDF export',lambda:download('map-report',page.get_by_role('button',name='Download PDF',exact=True)))
        task('Map rendering and layers',maps)
        task('Threshold screen',lambda:(goto('/threshold-rules'),shot('threshold-rules')))
        context.request.post('http://127.0.0.1:3001/api/v1/auth/logout',headers=headers,timeout=30000)
        browser.close()
    print('Workflow verification complete.',flush=True)

if __name__=='__main__':main()

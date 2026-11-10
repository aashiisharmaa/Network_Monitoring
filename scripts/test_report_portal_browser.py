import json, re
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT=Path(__file__).resolve().parents[1]/"reports"/"performance"
ROOT.mkdir(parents=True,exist_ok=True)
snapshot={"id":1,"status":"COMPLETED","dataDate":"2026-11-05","datasetVersion":"demo-version","freshness":"HISTORICAL","profileCurrent":True,"summary":{"dataStatus":"AVAILABLE","groups":1,"metrics":1}}
profile={"groupBy":["cellName"],"metrics":[{"key":"prb","aggregation":"MEAN","direction":"LOWER_IS_BETTER","unit":"PERCENT","relativeChangeEnabled":True}],"minimumBaselineDays":7,"minimumSamplesPerDay":1,"requireEqualSampleCounts":True}
item={"dimensions":{"cellName":"Demo Cell A","site":"Demo Site"},"metric":"prb","unit":"PERCENT","aggregation":"MEAN","direction":"LOWER_IS_BETTER","current":{"value":80,"status":"OK"},"previousDay":{"baselineValue":40,"difference":40,"trend":"DEGRADED","status":"ASSESSED"},"previousSevenDays":{"baselineValue":None,"difference":None,"daysAvailable":6,"trend":"UNASSESSED","status":"INSUFFICIENT_BASELINE"},"trend":[{"date":f"2026-11-{d:02d}","observation":None if d==2 else {"value":80 if d==5 else 40}} for d in range(1,6)]}
report={"id":1,"snapshotId":1,"fileId":500,"status":"PARTIAL","formats":["pdf","xlsx","json","configuration"],"summary":{"moduleStatuses":{"thresholds":"COMPLETED","alarmCandidates":"LIMITED","worstCells":"COMPLETED","prediction":"LIMITED"}}}
mail={"profileHash":"reviewed-v1","outboundEnabled":False,"profile":{"mode":"CAPTURE","recipients":["qa@example.com"],"attachments":["pdf"],"allowPartial":True,"enabled":True}}
deliveries=[{"id":1,"reportId":1,"mode":"CAPTURE","status":"CAPTURED","recipient":"qa@example.com","attempts":1,"message":"Local test email captured.","updatedAt":"2026-11-05T01:20:00"},{"id":2,"reportId":1,"mode":"SMTP","status":"UNKNOWN","recipient":"customer@example.com","attempts":1,"message":"Provider response was lost.","updatedAt":"2026-11-05T01:21:00"}]
nightly={"id":1,"fileId":500,"dataDate":"2026-11-04","stage":"WAITING_INPUT","status":"FAILED","attempts":1,"deadlineStatus":"MISSED","deadlineAt":"2026-11-04T20:30:00Z","cutoffAt":"2026-11-04T20:00:00Z","deliveryIds":[],"message":"Input cutoff reached before a stable daily file was accepted.","events":[{"event":"DEADLINE_MISSED","createdAt":"2026-11-04T20:31:00Z","details":"Synthetic deadline fixture"}]}
calls=[]
def route_handler(route):
    req=route.request; url=req.url; path=url.split('/api/v1/')[-1].split('?')[0]
    headers={"Access-Control-Allow-Origin":"http://localhost:5173","Access-Control-Allow-Headers":"Authorization, Content-Type","Access-Control-Allow-Methods":"GET,POST,PUT,OPTIONS"}
    if req.method=='OPTIONS':route.fulfill(status=204,headers=headers);return
    calls.append((req.method,path,req.post_data,req.headers.get('authorization')))
    if '/download/' in path or path.endswith('message.eml'):
        route.fulfill(status=200,body=b'%PDF synthetic browser download',content_type='application/pdf',headers=headers);return
    if req.method in ['POST','PUT']:
        if path.endswith('/reconcile'):
            payload=json.loads(req.post_data); assert payload['outcome']=='NOT_ACCEPTED'; data={**deliveries[1],"status":"FAILED","attemptHistory":[],"events":[]}
        elif path=='nightly/profile':
            payload=json.loads(req.post_data);assert payload['enabled']==False and payload['folder']=='';data={'profile':payload,'schedulerEnabled':False}
        elif path=='reports':
            payload=json.loads(req.post_data);assert 'rca' in payload['modules'] and payload['rcaConfig']['selectedKpis']==['PRB'];data={'id':1}
        elif path=='report-mail/deliveries':
            assert 'expectedProfileHash=reviewed-v1' in url;data={"capacityLimited":False,"deliveries":deliveries}
        else:data={"id":1,"profileHash":"saved","profile":profile}
    elif path=='report-datasets':data={"items":[{"id":500,"fileName":"Demo daily KPI dataset"},{"id":501,"fileName":"Empty KPI dataset"}],"total":2}
    elif 'fileId=501' in url:data={"status":"CONFIGURATION_REQUIRED"} if path.endswith('profile') else []
    elif path=='daily-analysis/profile':data={"profileHash":"daily-v1","profile":profile}
    elif path=='daily-analysis/snapshots':data=[snapshot]
    elif path.endswith('/comparisons'):data={"items":[item],"totalGroups":1,"groupsPerPage":10}
    elif path=='nightly/profile':data={'status':'CONFIGURATION_REQUIRED','schedulerEnabled':False,'stableSeconds':60}
    elif path=='nightly/runs':data=[nightly]
    elif path=='nightly/runs/1':data=nightly
    elif path=='reports':data=[report]
    elif path=='report-mail/profile':data=mail
    elif path=='report-mail/deliveries':data=deliveries
    elif path.startswith('report-mail/deliveries/'):
        row=deliveries[int(path.split('/')[2])-1];data={**row,"messageId":"<demo-report@example.invalid>","attemptHistory":[{"attemptNo":1,"status":row['status'],"startedAt":"2026-11-05T01:20:00","finishedAt":"2026-11-05T01:21:00","message":row['message']}],"events":[{"event":"REQUESTED","actorId":1,"createdAt":"2026-11-05T01:19:00","evidence":"Synthetic browser fixture"}]}
    elif path=='kpi/recommendation/presets':data={'presets':['Demo RCA']}
    elif path.startswith('kpi/recommendation/presets/'):
        data={'config':{'vendor':'nokia','column_mapping':{'Date':'date','Short name':'cellname'},'kpi_mapping':{'PRB':'prb'},'selected_kpis':['PRB']}}
    elif path=='kpi/dynamic/columns':
        route.fulfill(json={"success":True,"columns":[{"key":"prb","label":"PRB utilization","numericCount":100,"isDimension":False}]},headers=headers);return
    else:raise AssertionError('Unhandled '+path)
    route.fulfill(status=200,json={"success":True,"data":data},headers=headers)

with sync_playwright() as p:
    browser=p.chromium.launch(channel="chrome",headless=True)
    context=browser.new_context(viewport={"width":1440,"height":1100},accept_downloads=True)
    context.add_init_script("localStorage.setItem('authToken','browser-test-token');localStorage.setItem('user',JSON.stringify({id:1,email:'qa@example.com',role:'SUPER_ADMIN',companyId:10}));")
    context.route('http://localhost:3001/api/v1/**',route_handler)
    page=context.new_page();errors=[];page.on('pageerror',lambda error:errors.append(str(error)))
    page.goto('http://localhost:5173/reports');page.get_by_label('KPI dataset',exact=True).select_option('500')
    page.get_by_role('heading',name='Comparisons',exact=False).wait_for()
    page.get_by_text('INSUFFICIENT_BASELINE',exact=True).wait_for()
    page.screenshot(path=str(ROOT/'report-portal-analysis.png'),full_page=True)
    page.get_by_role('button',name='Comparison settings',exact=True).click();page.get_by_role('button',name='Save comparison settings',exact=True).wait_for()
    page.get_by_role('button',name='Save comparison settings',exact=True).click();page.get_by_role('status').filter(has_text='settings saved').wait_for()
    page.get_by_role('tab',name='Reports',exact=True).click()
    page.get_by_role('button',name='Load saved RCA presets',exact=True).click();page.get_by_role('status').filter(has_text='RCA presets loaded').wait_for()
    page.get_by_label('RCA report preset',exact=True).select_option('Demo RCA');page.get_by_text('RCA included',exact=False).wait_for()
    page.get_by_role('button',name='Generate report',exact=True).click();page.get_by_role('status').filter(has_text='Report generation requested').wait_for()
    with page.expect_download() as event:page.get_by_role('button',name='PDF',exact=True).click()
    assert event.value.suggested_filename=='common-report-1.pdf'
    page.get_by_role('button',name='Capture test email',exact=True).click();page.get_by_role('status').filter(has_text='Delivery requested').wait_for()
    page.screenshot(path=str(ROOT/'report-portal-reports.png'),full_page=True)
    page.get_by_role('tab',name='Email delivery',exact=True).click();page.get_by_role('button',name='Details',exact=True).nth(1).click()
    page.get_by_label('Reconciliation evidence').fill('Provider searched the exact Message-ID and confirmed no acceptance.')
    page.get_by_role('button',name='Record verified outcome').click();page.get_by_role('status').filter(has_text='reconciled').wait_for()
    page.screenshot(path=str(ROOT/'report-portal-delivery.png'),full_page=True)
    page.get_by_role('tab',name='Automation',exact=True).click();page.get_by_role('heading',name='Nightly workflow history').wait_for()
    page.get_by_role('button',name='Automation settings',exact=True).click();page.get_by_role('button',name='Save automation settings',exact=True).click();page.get_by_role('status').filter(has_text='Automation settings saved').wait_for()
    page.get_by_role('button',name='Run details',exact=True).click();page.get_by_text('Nightly run #1',exact=True).wait_for();page.get_by_text('Synthetic deadline fixture',exact=True).wait_for()
    page.screenshot(path=str(ROOT/'report-portal-automation.png'),full_page=True)
    page.set_viewport_size({"width":390,"height":844});page.screenshot(path=str(ROOT/'report-portal-mobile.png'),full_page=True)
    assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth + 1'), 'Mobile viewport overflow'
    assert not errors,errors
    page.set_viewport_size({"width":1440,"height":1000});page.get_by_label('KPI dataset',exact=True).select_option('501')
    page.get_by_text('Daily comparison settings are required.',exact=False).wait_for();assert page.get_by_role('button',name='Build daily analysis',exact=True).is_disabled()
    page.screenshot(path=str(ROOT/'report-portal-empty.png'),full_page=True)
    context.close()
    readonly=browser.new_context(viewport={"width":1440,"height":1000})
    readonly.add_init_script("localStorage.setItem('authToken','browser-test-token');localStorage.setItem('user',JSON.stringify({id:2,email:'client@example.com',role:'USER',companyId:10}));")
    readonly.route('http://localhost:3001/api/v1/**',route_handler);rp=readonly.new_page();rp.goto('http://localhost:5173/reports');rp.get_by_label('KPI dataset',exact=True).select_option('500');rp.get_by_role('heading',name='Comparisons',exact=False).wait_for()
    assert rp.get_by_role('button',name='Build daily analysis',exact=True).count()==0
    rp.get_by_role('tab',name='Reports',exact=True).click();assert rp.get_by_role('button',name='Generate report',exact=True).count()==0;assert rp.get_by_role('button',name='Capture test email',exact=True).count()==0
    rp.get_by_role('button',name='PDF',exact=True).wait_for();readonly.close();browser.close()
    assert all(call[3]=='Bearer browser-test-token' for call in calls)
    result={"browser_errors":errors,"checks":["analysis and missing baseline","settings save","authenticated binary download","reviewed recipient hash","RCA preset report generation","delivery reconciliation","mobile viewport","empty configuration","read-only client","nightly disabled draft and missed-deadline audit"],"mocked_api_calls":len(calls),"external_emails_sent":0}
    (ROOT/'report-portal-browser.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))

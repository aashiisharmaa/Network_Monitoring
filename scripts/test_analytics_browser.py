"""Production KPI/Worst Cell regression with 21,000 synthetic metric observations.
All APIs are intercepted; real browser chart code and analysis workers are exercised.
"""
import asyncio, json
from pathlib import Path
from urllib.parse import urlsplit, parse_qs
from playwright.async_api import async_playwright, expect

ROOT=Path(__file__).resolve().parents[1]
OUTPUT=ROOT/'reports/performance'
METRICS=[{'key':f'm{i}','label':f'CURRENT KPI {i:02d}'} for i in range(14)]
ROWS=[{'cellName':f'CELL_{i:04d}','date':'2026-11-09','site':'SITE_B','tech':'LTE','value':80+i%10} for i in range(1500)]
BAD_ROWS=[{'value':f'CELL_{i:04d}','meta':{'cellId':f'CELL_{i:04d}','shortName':f'CELL_{i:04d}'},'badDayCount':3,'degradedKpiUniqueCount':1,'degradedKpiEventCount':3,'degradedKpiNames':['CURRENT KPI 00'],'badDays':[{'date':date,'degradedKpiCount':1,'degradedKpis':[{'metricKey':'m0','metricLabel':'CURRENT KPI 00','average':50,'thresholdValue':95,'thresholdOperator':'>=','sampleCount':1}]} for date in ['2026-11-07','2026-11-08','2026-11-09']]} for i in range(120)]

async def main():
    state={'fail_batch':True,'batch_calls':0,'bad_calls':0,'delay_bad':False,'bad_requests':[]}
    async def api(route):
        request=route.request;parsed=urlsplit(request.url);path=parsed.path.lstrip('/');query=parse_qs(parsed.query);file=query.get('fileId',[''])[0]
        headers={'Access-Control-Allow-Origin':'http://localhost:4173','Access-Control-Allow-Headers':'Authorization, Content-Type','Access-Control-Allow-Methods':'GET,POST,OPTIONS'}
        if request.method=='OPTIONS':await route.fulfill(status=204,headers=headers);return
        body={'success':True}
        if path=='upload/uploads/history':body['data']=[{'id':101,'fileName':'Old KPI A','remarks':'[kpi]'},{'id':102,'fileName':'Current KPI B','remarks':'[kpi]'}]
        elif path=='kpi/dynamic/batch-data':
            state['batch_calls']+=1
            if file=='101':await asyncio.sleep(1)
            if file=='102' and state['fail_batch']:
                state['fail_batch']=False;await route.fulfill(status=503,json={'message':'Synthetic KPI data unavailable'},headers=headers);return
            body['metrics']=[{**metric,'label':metric['label'] if file=='102' else 'OLD KPI','data':ROWS} for metric in METRICS]
        elif path=='kpi/dynamic/columns':body.update({'columns':[{'key':'cellName','label':'Cell Name','isDimension':True}]+[{'key':metric['key'],'label':metric['label'],'numericCount':1500,'isDimension':False} for metric in METRICS],'selectedMetricKeys':[metric['key'] for metric in METRICS],'selectedFilterKeys':['cellName']})
        elif path=='kpi/dynamic/metrics':body['metrics']=METRICS
        elif path=='setting/getsettings':body['data']={**{metric['key']:95 for metric in METRICS},'thresholdOperators':{metric['key']:'>=' for metric in METRICS}}
        elif path=='kpi/dynamic/bad-days':
            state['bad_calls']+=1;state['bad_requests'].append(json.loads(request.post_data));
            if state['delay_bad']:await asyncio.sleep(1)
            body.update({'rows':BAD_ROWS,'totalComparedCells':120,'evaluatedMetricCount':14})
        elif path=='kpi/dynamic/data':body['data']=ROWS
        else:raise AssertionError('Unexpected '+path)
        try:await route.fulfill(json=body,headers=headers)
        except Exception:
            if not request.failure:raise

    async with async_playwright() as p:
        browser=await p.chromium.launch(channel='chrome',headless=True)
        context=await browser.new_context(viewport={'width':1440,'height':1100},accept_downloads=True)
        await context.add_init_script("localStorage.setItem('authToken','synthetic-token');localStorage.setItem('user',JSON.stringify({id:1,role:'SUPER_ADMIN',companyId:10}));if(!localStorage.getItem('globalSelectedKpiFileId:v1'))localStorage.setItem('globalSelectedKpiFileId:v1','101');")
        await context.add_init_script("const NativeWorker=window.Worker;window.__workerTerminations=0;window.Worker=class extends NativeWorker{terminate(){window.__workerTerminations++;return super.terminate();}};")
        await context.route('http://localhost:3001/**',api)
        page=await context.new_page();errors=[]
        page.on('pageerror',lambda error:(errors.append(str(error)),print('PAGE ERROR:',error,flush=True)))
        await page.goto('http://localhost:4173/kpi')
        source=page.locator('select').filter(has=page.locator('option[value="102"]')).first
        await source.select_option('102')
        await page.get_by_role('button',name='Retry KPI data',exact=True).click()
        await page.get_by_text('CURRENT KPI 00',exact=True).first.wait_for()
        assert await page.get_by_text('OLD KPI',exact=True).count()==0
        await page.get_by_role('navigation',name='KPI card pages').get_by_role('button',name='Next KPIs').click()
        await page.get_by_text('CURRENT KPI 12',exact=True).first.wait_for()
        expect_nav=page.get_by_role('navigation',name='KPI card pages')
        await expect(expect_nav.get_by_role('button',name='Next KPIs')).to_be_disabled()
        await expect_nav.get_by_role('button',name='Previous KPIs').click()
        search=page.get_by_placeholder('Search cell name...',exact=True)
        await search.wait_for()
        options=search.locator('../..')
        assert await options.get_by_role('checkbox').count()==101,'Unbounded filter DOM'
        await options.get_by_role('button',name='Next options',exact=True).click()
        assert await options.get_by_role('checkbox').count()==101
        await options.get_by_role('checkbox',name='Select All (1500)',exact=True).check()
        await options.get_by_text('1500 selected',exact=True).wait_for()
        await expect(options.get_by_role('checkbox',name='CELL_0100',exact=True)).to_be_checked()
        await options.get_by_role('checkbox',name='Select All (1500)',exact=True).uncheck()
        await search.fill('CELL_1499')
        await options.get_by_role('checkbox',name='Select All (1)',exact=True).check()
        await options.get_by_text('1 selected',exact=True).wait_for()
        async with page.expect_response(lambda response: '/kpi/dynamic/batch-data' in response.url):
            await page.get_by_role('button',name='Reload data',exact=True).click()
        await page.get_by_text('CURRENT KPI 00',exact=True).first.wait_for()
        await options.get_by_text('1 selected',exact=True).wait_for()
        await page.screenshot(path=str(OUTPUT/'analytics-kpi-desktop.png'),full_page=False)
        await page.set_viewport_size({'width':390,'height':844});await page.wait_for_timeout(350)
        await page.screenshot(path=str(OUTPUT/'analytics-kpi-mobile.png'),full_page=False)
        if not await page.evaluate('document.documentElement.scrollWidth<=innerWidth+1'):
            print('OVERFLOW',await page.evaluate("[...document.querySelectorAll('body *')].map(e=>({tag:e.tagName,cls:String(e.className),right:e.getBoundingClientRect().right,width:e.getBoundingClientRect().width})).filter(e=>e.right>innerWidth+1).sort((a,b)=>b.width-a.width).slice(0,12)"),flush=True)
        assert await page.evaluate('document.documentElement.scrollWidth<=innerWidth+1'),'KPI mobile overflow'
        await page.screenshot(path=str(OUTPUT/'analytics-kpi-mobile.png'),full_page=False)
        await page.set_viewport_size({'width':1440,'height':1100})
        batch_before=state['batch_calls']
        await page.locator('a[href="/worstcell"]').click()
        await page.get_by_role('heading',name='Worst Cell Analysis',exact=True).wait_for()
        dimension=page.locator('select').filter(has=page.locator('option[value="cellName"]')).first
        await dimension.select_option('cellName')
        await page.get_by_role('button',name='Cell Bad Days Format',exact=True).click()
        await page.locator('input[type="date"]').nth(0).fill('2026-11-07')
        await page.locator('input[type="date"]').nth(1).fill('2026-11-09')
        await page.wait_for_timeout(200)
        run=page.get_by_role('button',name='Refresh Worst Cell Analysis',exact=True).first
        await run.click()
        await page.get_by_text('Showing cells 1-50 of 120',exact=True).wait_for()
        assert state['batch_calls']==batch_before,'Backend Bad Days path downloaded the full batch'
        assert len(state['bad_requests'][-1]['thresholdContexts'])==14
        await page.get_by_role('button',name='Next cells',exact=True).click()
        await page.get_by_text('Showing cells 51-100 of 120',exact=True).wait_for()
        await page.get_by_role('button',name='Visualization',exact=True).click()
        await page.get_by_text('Showing cells 1-10 of 120',exact=True).wait_for()
        await page.get_by_placeholder('Search Cell ID / Short Name...',exact=True).fill('CELL_0119')
        await page.get_by_text('Showing cells 1-1 of 1',exact=True).wait_for()
        await page.get_by_role('button',name='Table',exact=True).click()
        await page.get_by_role('button',name='Current KPI Format',exact=True).click()
        await run.click()
        await page.get_by_role('navigation',name='CURRENT KPI 00 failed values').wait_for()
        await page.get_by_role('navigation',name='CURRENT KPI 00 failed values').get_by_role('button',name='Next failed values',exact=True).click()
        await page.screenshot(path=str(OUTPUT/'analytics-worst-desktop.png'),full_page=False)
        # Pending analysis cannot publish into a newly selected source.
        await page.get_by_role('button',name='Reload data',exact=True).click()
        await dimension.select_option('cellName')
        await page.get_by_role('button',name='Cell Bad Days Format',exact=True).click()
        state['delay_bad']=True
        await run.click()
        source=page.locator('select').filter(has=page.locator('option[value="101"]')).first
        terminated_before=await page.evaluate('window.__workerTerminations')
        await source.select_option('101')
        await page.wait_for_timeout(1300)
        assert await page.evaluate('window.__workerTerminations')>terminated_before,'Source change did not terminate obsolete worker work'
        assert await page.get_by_text('Showing cells 1-50 of 120',exact=True).count()==0,'Old analysis published into a new source'
        await page.set_viewport_size({'width':390,'height':844})
        await page.wait_for_timeout(350)
        assert await page.evaluate('document.documentElement.scrollWidth<=innerWidth+1'),'Worst Cell mobile overflow'
        await page.screenshot(path=str(OUTPUT/'analytics-worst-mobile.png'),full_page=True)
        assert not errors,errors
        (OUTPUT/'analytics-browser-checks.json').write_text(json.dumps({'checks':['failed batch retry','obsolete source ignored','paged KPI cards','100-option filter DOM','search/select matching values','Bad Days avoids batch download','50-row result pages','10-cell heatmap pages','heatmap search','real worker analysis','paged failure badges','source invalidates pending results'],'syntheticMetrics':14,'syntheticObservations':21000,'syntheticAffectedCells':120,'pageErrors':errors,'customerDatabaseWrites':0},indent=2))
        await browser.close()
    print('KPI and Worst Cell production browser checks passed')

asyncio.run(main())

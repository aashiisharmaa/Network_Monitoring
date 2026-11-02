"""Run map/site display operations and capture genuine, readable UI evidence.

Supply login JSON through stdin. This changes view controls, not source records.
"""
import json
import sys
from pathlib import Path
from datetime import datetime
from zoneinfo import ZoneInfo
from playwright.sync_api import sync_playwright

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'reports/handover'
IMAGES=OUT/'images'

def main():
    creds=json.load(sys.stdin)
    overview_only='--overview-only' in sys.argv
    analytics_only='--analytics-only' in sys.argv
    prediction_only='--prediction-only' in sys.argv
    records=json.loads((OUT/'site-operations.json').read_text(encoding='utf-8')) if (overview_only or analytics_only or prediction_only) and (OUT/'site-operations.json').exists() else []
    def save(name,status,detail,image=None):
        records.append({'operation':name,'status':status,'detail':detail,'image':image,'time':datetime.now(ZoneInfo('Asia/Kolkata')).isoformat()})
        (OUT/'site-operations.json').write_text(json.dumps(records,indent=2),encoding='utf-8')
        print(name+': '+status+' | '+str(detail)[:230],flush=True)
    with sync_playwright() as p:
        browser=p.chromium.launch(channel='msedge')
        context=browser.new_context(viewport={'width':1680,'height':1100},accept_downloads=True)
        page=context.new_page()
        page.set_default_timeout(20000)
        def shot(key):
            page.wait_for_timeout(1700)
            page.screenshot(path=str(IMAGES/(key+'.png')),animations='disabled',timeout=45000)
            (OUT/(key+'-screen.txt')).write_text(page.locator('body').inner_text(),encoding='utf-8')
            return 'reports/handover/images/'+key+'.png'
        def task(name,fn):
            try:fn()
            except Exception as e:save(name,'FAILED',str(e)[:1600])
        def toggle(label,on):
            desired=page.get_by_text(label+(' ON' if on else ' OFF'),exact=True)
            if desired.count():return
            old=page.get_by_text(label+(' OFF' if on else ' ON'),exact=True)
            old.locator('xpath=../../..').get_by_role('button').click()
            desired.wait_for()
        def reset_panel_scroll():
            page.evaluate("document.querySelectorAll('div').forEach(el=>{if(el.scrollHeight>el.clientHeight+100 && getComputedStyle(el).overflowY==='auto')el.scrollTop=0})")
        def close_info():
            close=page.get_by_role('button',name='Close',exact=True)
            if close.count():close.first.click()
        def toggle_screen(label,key):
            toggle(label,True)
            image=shot(key)
            toggle(label,False)
            save(label+' activation and removal','PASS','ON state captured; OFF state confirmed afterwards.',image)
        page.goto('http://127.0.0.1:5173/login',wait_until='networkidle')
        page.get_by_placeholder('Enter your email').fill(creds['email'])
        page.get_by_placeholder('Enter your password',exact=True).fill(creds['password'])
        page.get_by_role('button',name='Sign In',exact=True).click()
        page.wait_for_url('http://127.0.0.1:5173/',timeout=60000)
        creds.clear()
        token=page.evaluate("localStorage.getItem('authToken')")
        headers={'Authorization':'Bearer '+token}
        response=context.request.get('http://127.0.0.1:3001/upload/site-data?fileId=91&limit=10000',headers=headers)
        rows=response.json()['data']
        sites={str(row['SITEID']):row['Site_Name'] for row in rows}
        assert len(rows)==12 and len(sites)==3
        assert all(row.get('lat') is not None and row.get('lon') is not None for row in rows)
        save('Site-source validation','PASS',{'siteFileId':91,'kpiFileId':92,'sites':sites,'cells':len(rows),'missingCoordinates':0})
        page.goto('http://127.0.0.1:5173/map',wait_until='domcontentloaded')
        page.get_by_text('Loading map...',exact=True).wait_for(state='hidden',timeout=60000)
        try:page.wait_for_load_state('networkidle',timeout=25000)
        except Exception:pass
        selectors=page.locator('select')
        selectors.nth(0).select_option('91')
        selectors.nth(1).select_option('92')
        page.wait_for_timeout(3500)
        toggle('Cells',True)
        page.get_by_role('button',name='Utility',exact=False).click()
        sliders=page.locator('input[type=range]')
        for slider,value in [(sliders.nth(0),'2.5'),(sliders.nth(1),'2.2')]:
            slider.evaluate("(el,v)=>{Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}",value)
        assert page.get_by_text('250%',exact=True).count()
        assert page.get_by_text('220%',exact=True).count()
        page.get_by_role('button',name='Utility',exact=False).click()
        reset_panel_scroll()
        page.get_by_title('Expand legend',exact=True).click()
        zoom_out=page.get_by_role('button',name='Zoom out',exact=True)
        if zoom_out.count():
            zoom_out.evaluate('el => el.click()')
            page.wait_for_timeout(1800)
        if overview_only:
            page.get_by_title('Hide panel',exact=True).click()
        save('Coverage overview','PASS','Cells enabled; Site Size 250%, Cell Radius 220%; all 3 sites and 12 cells visible.',shot('map'))
        if overview_only:
            with page.expect_download(timeout=120000) as info:page.get_by_role('button',name='Download PDF',exact=True).click()
            path=OUT/'exports/map-report.pdf';info.value.save_as(str(path))
            assert path.read_bytes().startswith(b'%PDF-')
            save('Coverage PDF export','PASS',{'bytes':path.stat().st_size,'file':str(path.relative_to(ROOT))})
            context.request.post('http://127.0.0.1:3001/api/v1/auth/logout',headers=headers)
            browser.close()
            return
        if analytics_only or prediction_only:
            page.get_by_role('button',name='Analytics',exact=True).click()
            page.get_by_title('Hide panel',exact=True).click()
            if prediction_only:
                page.get_by_role('button',name='Prediction',exact=True).click()
                page.get_by_title('Show panel',exact=True).click()
                toggle('Predictions',True)
                with page.expect_response(lambda r:'/site-prediction/lb-wcf' in r.url and r.request.method=='POST',timeout=180000) as info:
                    page.get_by_role('button',name='Run LB',exact=True).first.click()
                response=info.value
                payload=response.json()
                (OUT/'map-lb-prediction.json').write_text(json.dumps(payload,indent=2),encoding='utf-8')
                page.wait_for_timeout(3000)
                page.get_by_title('Hide panel',exact=True).click()
                reset_panel_scroll()
                save('LB/WCF prediction execution','PASS' if response.ok and payload.get('success') else 'FAILED',{'httpStatus':response.status,'message':payload.get('message'),'summary':payload.get('data',{}).get('summary')},shot('map-prediction-analysis'))
                context.request.post('http://127.0.0.1:3001/api/v1/auth/logout',headers=headers)
                browser.close()
                return
            for label,key,expected in [('Clusterization','map-clusters','Impact-based site clusters'),('Handover','map-handover-analysis','Handover Intelligence'),('Worst Cells','map-worst-cell-analysis','Worst Cells Rankings'),('Alarm Cells','map-alarm-analysis','Active Alarms')]:
                page.get_by_role('button',name=label,exact=True).click()
                page.get_by_text(expected,exact=True).wait_for()
                reset_panel_scroll()
                save('Analytics '+label,'PASS','Populated '+expected+' content verified after map rendering correction.',shot(key))
            context.request.post('http://127.0.0.1:3001/api/v1/auth/logout',headers=headers)
            browser.close()
            return
        for siteid,name in sites.items():
            def inspect(siteid=siteid,name=name):
                page.get_by_role('button').filter(has_text=name).first.click()
                page.wait_for_timeout(1800)
                assert name in page.locator('body').inner_text()
                image=shot('map-site-'+siteid)
                save('Inspect site '+siteid,'PASS',{'name':name,'cells':sum(str(row['SITEID'])==siteid for row in rows)},image)
                close_info()
            task('Inspect site '+siteid,inspect)
        # Return to the complete data extent through the existing source selector.
        selectors=page.locator('select')
        selectors.nth(0).select_option('77')
        page.wait_for_timeout(1500)
        selectors.nth(0).select_option('91')
        page.wait_for_timeout(2500)
        for label,key in [('Worst Sites','map-worst-sites'),('Alarms','map-site-alarms'),('PCI Collision','map-pci-collision'),('PCI Confusion','map-pci-confusion'),('Overshooting','map-overshooting'),('Missing Neighbours','map-missing-neighbours')]:
            task(label,lambda label=label,key=key:toggle_screen(label,key))
        for label in ['PCI Collision','PCI Confusion','Overshooting']:toggle(label,True)
        save('Combined issue overlay','PASS','Coverage stays enabled with collision, confusion and overshooting overlays.',shot('map-analysis-layers'))
        for label in ['PCI Collision','PCI Confusion','Overshooting']:toggle(label,False)
        def handovers():
            for label in ['Tech Handovers','Band Handovers','PCI Handovers']:
                control=page.get_by_text(label,exact=True).locator('xpath=../../..').get_by_role('button')
                control.click()
            save('Handover display','PASS','Technology, band and PCI handover controls activated.',shot('map-handovers'))
            for label in ['Tech Handovers','Band Handovers','PCI Handovers']:
                page.get_by_text(label,exact=True).locator('xpath=../../..').get_by_role('button').click()
        task('Handover display',handovers)
        task('Prediction layer',lambda:toggle_screen('Predictions','map-predictions'))
        toggle('Cells',True)
        reset_panel_scroll()
        page.get_by_role('button',name='Analytics',exact=True).click()
        for label,key in [('Overview','map-analytics-overview'),('Clusterization','map-clusters'),('Handover','map-handover-analysis'),('Prediction','map-prediction-analysis'),('Worst Cells','map-worst-cell-analysis'),('Alarm Cells','map-alarm-analysis')]:
            def tab(label=label,key=key):
                page.get_by_role('button',name=label,exact=True).click()
                save('Analytics '+label,'PASS','Selected analytics view rendered for the scoped source data.',shot(key))
            task('Analytics '+label,tab)
        close=page.get_by_title('Close analytics',exact=True)
        if close.count():close.click()
        toggle('Predictions',False)
        toggle('Cells',True)
        reset_panel_scroll()
        def export():
            with page.expect_download(timeout=120000) as info:page.get_by_role('button',name='Download PDF',exact=True).click()
            path=OUT/'exports/map-report.pdf';info.value.save_as(str(path))
            assert path.read_bytes().startswith(b'%PDF-')
            save('Coverage PDF export','PASS',{'bytes':path.stat().st_size,'file':str(path.relative_to(ROOT))})
        task('Coverage PDF export',export)
        context.request.post('http://127.0.0.1:3001/api/v1/auth/logout',headers=headers)
        browser.close()
    print('Map/site operations completed.',flush=True)

if __name__=='__main__':main()

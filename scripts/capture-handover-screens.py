"""Capture authenticated UI evidence. Supply {email, password} JSON on stdin.

Credentials and session tokens are never written to the evidence files.
"""
import json
import sys
from pathlib import Path
from datetime import datetime
from zoneinfo import ZoneInfo
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'reports/handover'
IMAGES = OUT / 'images'

def main():
    credentials = json.load(sys.stdin)
    results = []
    with sync_playwright() as p:
        browser = p.chromium.launch(channel='msedge')
        context = browser.new_context(viewport={'width':1600,'height':1000},device_scale_factor=1)
        page = context.new_page()
        page.goto('http://127.0.0.1:5173/login', wait_until='networkidle')
        page.get_by_placeholder('Enter your email').fill(credentials['email'])
        page.get_by_placeholder('Enter your password', exact=True).fill(credentials['password'])
        page.get_by_role('button',name='Sign In',exact=True).click()
        page.wait_for_url('http://127.0.0.1:5173/',timeout=60000)
        credentials.clear()
        for key,route,title in [
            ('dashboard','/','Network dashboard'),
            ('uploads','/uploads','Upload management'),
            ('kpi','/kpi','KPI analysis'),
            ('sites','/sites','Site intelligence'),
            ('map','/map','Geographic map'),
            ('alarms','/alarms','Alarm monitoring'),
            ('threshold-rules','/threshold-rules','Threshold rules'),
            ('validation-report','/validation-report','Validation reports'),
            ('recommendation','/recommendation','RCA configuration'),
        ]:
            page.goto('http://127.0.0.1:5173'+route,wait_until='domcontentloaded',timeout=60000)
            try: page.wait_for_load_state('networkidle',timeout=55000)
            except Exception: pass
            page.wait_for_timeout(2000)
            if '/login' in page.url:
                raise RuntimeError('Authenticated session was lost; no protected screenshots saved.')
            # Scope screenshots to existing data using a local UI selection only.
            selections=[]
            for select in page.locator('select').all():
                try:
                    options=select.locator('option').evaluate_all('(items)=>items.map(o=>({value:o.value,label:o.textContent}))')
                    selections.append(options)
                except Exception: pass
            text=page.locator('body').inner_text()
            page.screenshot(path=str(IMAGES/(key+'.png')),full_page=False)
            results.append({'screen':key,'title':title,'route':route,'image':'reports/handover/images/'+key+'.png','visibleText':text[:10000],'selectOptions':selections,'capturedAt':datetime.now(ZoneInfo('Asia/Kolkata')).isoformat()})
            print('Captured '+title,flush=True)
        # Leave the user's accounts and application data unchanged; end this capture session.
        token=page.evaluate("localStorage.getItem('authToken')")
        if token:
            context.request.post('http://127.0.0.1:3001/api/v1/auth/logout',headers={'Authorization':'Bearer '+token})
        browser.close()
    (OUT/'screen-evidence.json').write_text(json.dumps(results,indent=2),encoding='utf-8')

if __name__=='__main__': main()

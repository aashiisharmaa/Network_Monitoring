"""Production-build UI regression with synthetic APIs and no customer data writes."""
import json, gzip, re
from pathlib import Path
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'reports/performance'
state = {'pending': True, 'posts': 0, 'polls': 0}
column_requests = []
assets = []

def mock_api(route):
    request = route.request
    path = request.url.split('localhost:3001/')[-1].split('?')[0]
    headers = {'Access-Control-Allow-Origin': 'http://localhost:4173', 'Access-Control-Allow-Headers': 'Authorization, Content-Type', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS'}
    if request.method == 'OPTIONS':
        route.fulfill(status=204, headers=headers); return
    if path == 'upload/uploads/history':
        data = [{'id': 500, 'fileName': 'Synthetic dataset A', 'remarks': '[kpi]'}, {'id': 501, 'fileName': 'Synthetic dataset B', 'remarks': '[kpi]'}]
    elif path == 'kpi/dynamic/columns':
        column_requests.append(request.url)
        route.fulfill(json={'columns': [{'key': 'prb', 'label': 'DL PRB Utilisation', 'isDimension': False, 'numericCount': 120}]}, headers=headers); return
    elif path == 'kpi/recommendation/presets':
        route.fulfill(json={'presets': []}, headers=headers); return
    elif path == 'kpi/recommendation/jobs' and request.method == 'POST':
        state['posts'] += 1
        if state['pending']:
            data = {'jobId': state['posts'], 'status': 'QUEUED', 'message': 'Synthetic pending analysis'}
        else:
            rows = [{'Short name': 'CURRENT_DATASET_B', 'Date': '2026-11-09', 'Severity': 'Major', 'Anomaly Score': 120-i, 'Primary Cause': 'Synthetic cause', 'Secondary Causes': f'Pattern {i}', 'Vendor Recommendation': 'Synthetic investigation only'} for i in range(120)]
            data = {'jobId': state['posts'], 'status': 'COMPLETED', 'result': {'anomaly_details': rows, 'summary': {'anomaly_count': 120}}}
    elif path.startswith('kpi/recommendation/jobs/'):
        state['polls'] += 1
        data = {'jobId': 1, 'status': 'PROCESSING', 'message': 'Synthetic pending analysis', 'progressPercent': 35}
    else:
        raise AssertionError('Unexpected API '+path)
    route.fulfill(json={'success': True, 'data': data}, headers=headers)

with sync_playwright() as p:
    browser = p.chromium.launch(channel='chrome', headless=True)
    context = browser.new_context(viewport={'width': 1440, 'height': 1050})
    context.add_init_script("localStorage.setItem('authToken','synthetic-token');localStorage.setItem('user',JSON.stringify({id:1,role:'SUPER_ADMIN',companyId:10}));if(!localStorage.getItem('globalSelectedKpiFileId:v1'))localStorage.setItem('globalSelectedKpiFileId:v1','500');")
    context.route('http://localhost:3001/**', mock_api)
    page = context.new_page(); errors=[]
    page.on('pageerror', lambda error: errors.append(str(error)))
    page.on('request', lambda request: assets.append(request.url) if '/assets/' in request.url else None)
    page.goto('http://localhost:4173/recommendation')
    run = page.get_by_role('button', name='Run Recommendation', exact=True)
    run.wait_for(); page.wait_for_timeout(500)
    assert column_requests and all('fileId=500' in url for url in column_requests)
    assert not any(re.search(r'/(MapPage|KpiView|WorstCell|RecommendationResultsPanel)-', url) for url in assets), assets
    run.click(); page.get_by_role('status').filter(has_text='Synthetic pending analysis').wait_for()
    source = page.locator('select').filter(has=page.locator('option[value="500"]'))
    source.select_option('501')
    expect(run).to_be_enabled()
    polls = state['polls']; page.wait_for_timeout(1800)
    assert state['polls'] == polls, 'Old dataset continued polling after source change'
    assert page.get_by_role('heading', name='Result Summary').count() == 0
    state['pending'] = False
    run.click(); page.get_by_role('heading', name='Result Summary').wait_for()
    table = page.locator('table').last
    expect(table.locator('tbody tr')).to_have_count(50)
    page.get_by_text('Showing 1-50 of 120 returned rows', exact=True).wait_for()
    page.get_by_role('button', name='Next rows', exact=True).click()
    page.get_by_text('Showing 51-100 of 120 returned rows', exact=True).wait_for()
    page.get_by_role('button', name='Next rows', exact=True).click()
    expect(table.locator('tbody tr')).to_have_count(20)
    expect(page.get_by_role('button', name='Next rows', exact=True)).to_be_disabled()
    page.get_by_role('checkbox', name='Show recommendations', exact=True).check()
    page.get_by_role('columnheader', name='Recommendation and implementation', exact=True).wait_for()
    assert any('/RecommendationResultsPanel-' in url for url in assets)
    page.screenshot(path=str(OUTPUT/'frontend-smooth-desktop.png'), full_page=True)
    page.wait_for_timeout(600)
    # Settings/results survive a reload, but never appear under a different source.
    page.reload(); page.get_by_role('heading', name='Result Summary').wait_for()
    source = page.locator('select').filter(has=page.locator('option[value="500"]'))
    source.select_option('500')
    expect(page.get_by_role('heading', name='Result Summary')).to_have_count(0)
    page.set_viewport_size({'width': 390, 'height': 844})
    page.screenshot(path=str(OUTPUT/'frontend-smooth-mobile.png'), full_page=True)
    assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth + 1'), 'Mobile page overflow'
    assert not errors, errors
    # A failed lazy chunk is contained to page content; navigation stays available.
    page.set_viewport_size({'width': 1440, 'height': 1050})
    page.route('**/assets/MapPage-*.js', lambda route: route.abort())
    page.locator('a[href="/map"]').click()
    page.get_by_role('heading', name='This page could not load', exact=True).wait_for()
    page.get_by_role('button', name='Reload page', exact=True).wait_for()
    page.locator('a[href="/recommendation"]').click()
    run.wait_for(); expect(page.get_by_role('heading', name='This page could not load')).to_have_count(0)
    (OUTPUT/'frontend-smooth-browser.json').write_text(json.dumps({'checks': ['lazy page assets', 'lazy RCA results', 'persisted initial dataset', 'cancel old polling', '50-row pagination with all returned rows accessible', 'recommendation toggle', 'persist/reload', 'hide stale results', 'mobile overflow', 'failed chunk recovery via sidebar'], 'customerDatabaseWrites': 0, 'unexpectedErrors': errors}, indent=2))
    browser.close()

dist=ROOT/'frontend/dist'; html=(dist/'index.html').read_text()
entry=re.search(r'src="\./(assets/[^\"]+\.js)"',html).group(1); content=(dist/entry).read_bytes()
before=json.loads((OUTPUT/'frontend-before.json').read_text())
after={'entry':entry, 'entryBytes':len(content), 'entryGzipBytes':len(gzip.compress(content)), 'entryReductionPercent':round((1-len(content)/before['entryBytes'])*100,1), 'note':'Entry bundle only; page/shared chunks load on demand. Not a measured navigation latency reduction.'}
(OUTPUT/'frontend-after.json').write_text(json.dumps(after,indent=2))
print('Production frontend browser checks passed. '+json.dumps(after))

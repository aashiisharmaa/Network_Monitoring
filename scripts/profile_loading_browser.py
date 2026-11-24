"""Reproducible production-browser sample using synthetic APIs with fixed latency.

No customer backend requests are made. This measures frontend scheduling and
transferred local assets, not real database/server or WAN performance.
"""
import argparse
import asyncio
import json
import statistics
from pathlib import Path
from time import perf_counter
from urllib.parse import urlsplit
from playwright.async_api import async_playwright, expect


async def sample(browser, base):
    context = await browser.new_context(viewport={"width": 1440, "height": 1000})
    await context.add_init_script("localStorage.setItem('authToken','synthetic-token');localStorage.setItem('user',JSON.stringify({id:1,role:'SUPER_ADMIN',companyId:10}));localStorage.setItem('globalSelectedKpiFileId:v1','500');")
    calls = []
    async def api(route):
        path = urlsplit(route.request.url).path.removeprefix('/api/v1').lstrip('/')
        call = {'path': path, 'started': perf_counter()}
        calls.append(call)
        await asyncio.sleep(.18)
        data = {}
        if path == 'health': data = {'status': 'UP'}
        elif path == 'upload/uploads/history': data = [{'id': 500, 'fileName': 'Synthetic KPI', 'remarks': '[kpi]', 'createdAt': '2026-11-18T01:00:00Z'}]
        elif path == 'companies': data = [{'id': 10, 'companyName': 'Synthetic company'}]
        elif path == 'dashboard/stats': data = {'totalCells': 120, 'totalSites': 40, 'totalBands': 3, 'totalTech': 2, 'totalSectors': 120}
        elif path == 'kpi/dynamic/summary': data = [{'key': 'rrcSuccessRate', 'label': 'RRC Setup SR (%)', 'statistics': {'average': 90, 'count': 120}}]
        elif path == 'setting/getsettings': data = {'rrcSuccessRate': 95, 'thresholdOperators': {'rrcSuccessRate': '>='}}
        elif 'threshold' in path: data = []
        payload = {'success': True, 'metrics': data} if path == 'kpi/dynamic/summary' else {'success': True, 'data': data}
        await route.fulfill(json=payload, headers={'Access-Control-Allow-Origin': base, 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS'})
        call['finished'] = perf_counter()
    await context.route('http://localhost:3001/**', api)
    page = await context.new_page()
    errors = []
    page.on('pageerror', lambda error: errors.append(str(error)))
    started = perf_counter()
    await page.goto(base + '/')
    await expect(page.locator('.portal-inventory-card').first.locator('strong')).to_have_text('120')
    await expect(page.get_by_text('RRC Setup SR (%)', exact=True)).to_be_visible()
    await page.wait_for_load_state('networkidle')
    initial = round((perf_counter() - started) * 1000)
    initial_calls = len(calls)
    resources = await page.evaluate("performance.getEntriesByType('resource').filter(x => x.initiatorType === 'script' || x.name.endsWith('.js')).map(x => ({name:x.name.split('/').pop(),bytes:x.decodedBodySize}))")
    started = perf_counter()
    await page.get_by_role('button', name='Refresh', exact=True).click()
    await expect(page.get_by_role('button', name='Refresh', exact=True)).to_be_enabled()
    refresh = round((perf_counter() - started) * 1000)
    refreshed = calls[initial_calls:]
    api_span = round((max(c.get('finished', perf_counter()) for c in refreshed) - min(c['started'] for c in refreshed)) * 1000)
    assert not errors, errors
    await context.close()
    return {'initialIncludingNetworkIdleMs': initial, 'refreshMs': refresh, 'initialApiCalls': initial_calls,
            'refreshApiCalls': len(calls) - initial_calls, 'refreshApiSpanMs': api_span,
            'refreshTimeline': [{'path': c['path'], 'startMs': round((c['started']-started)*1000), 'endMs': round((c.get('finished',perf_counter())-started)*1000)} for c in refreshed],
            'scriptBytes': sum(r['bytes'] for r in resources), 'scripts': resources}


async def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--base', default='http://localhost:4173')
    cli.add_argument('--output', required=True, type=Path)
    args = cli.parse_args()
    async with async_playwright() as p:
        browser = await p.chromium.launch(channel='chrome', headless=True)
        samples = [await sample(browser, args.base.rstrip('/')) for _ in range(3)]
        await browser.close()
    result = {'syntheticApiLatencyMs': 180, 'samples': samples,
              'medians': {k: statistics.median(x[k] for x in samples) for k in ['initialIncludingNetworkIdleMs', 'refreshMs', 'refreshApiSpanMs', 'initialApiCalls', 'refreshApiCalls', 'scriptBytes']}}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result['medians']))


if __name__ == '__main__':
    asyncio.run(main())

"""Prove the real production basemap remains usable with data/renderer downloads held.
Uses the configured Google browser key, synthetic site APIs, and records no URLs/keys.
"""
import asyncio,json,re
from pathlib import Path
from urllib.parse import urlsplit
from playwright.async_api import async_playwright,expect

ROOT=Path(__file__).resolve().parents[1]

async def main():
    data_gate=asyncio.Event();renderer_gate=asyncio.Event();renderer_requested=asyncio.Event()
    errors=[];provider=[]
    async with async_playwright() as p:
        browser=await p.chromium.launch(channel='chrome',headless=True,args=['--enable-unsafe-swiftshader'])
        context=await browser.new_context(viewport={'width':1440,'height':1000})
        await context.add_init_script("localStorage.setItem('authToken','synthetic-token');localStorage.setItem('user',JSON.stringify({id:1,role:'SUPER_ADMIN',companyId:10}));")
        async def api(route):
            request=route.request;path=urlsplit(request.url).path.lstrip('/')
            headers={'Access-Control-Allow-Origin':'http://localhost:4173','Access-Control-Allow-Headers':'Authorization, Content-Type','Access-Control-Allow-Methods':'GET,OPTIONS'}
            if request.method=='OPTIONS':await route.fulfill(status=204,headers=headers);return
            if path=='upload/uploads/history':data=[{'id':100,'fileName':'Synthetic startup site','remarks':'[site]'}]
            elif path=='upload/site-data':
                await data_gate.wait()
                await route.fulfill(json={'success':True,'data':[{'SITEID':'STARTUP_DEMO','Site_Name':'STARTUP_DEMO','Cell_Name':'STARTUP_CELL','lat':28.6,'lon':77.2,'Band':'1800','Technology':'LTE','AZIMUTH':0}],'pagination':{'totalCount':1,'hasNextPage':False}},headers=headers);return
            else:data={'data':[]}
            await route.fulfill(json={'success':True,'data':data},headers=headers)
        async def renderer(route):
            renderer_requested.set();await renderer_gate.wait();await route.continue_()
        await context.route('http://localhost:3001/**',api)
        await context.route('**/assets/deckRenderer-*.js',renderer)
        page=await context.new_page()
        page.on('pageerror',lambda error:errors.append(type(error).__name__))
        def console(message):
            match=re.search(r'Google Maps JavaScript API error:\s*(\w+)',message.text)
            if match:provider.append(match.group(1))
        page.on('console',console)
        await page.goto('http://localhost:4173/map')
        await page.locator('.gm-style').first.wait_for()
        await page.get_by_text('Loading site data...',exact=True).wait_for()
        await asyncio.wait_for(renderer_requested.wait(),10)
        assert not data_gate.is_set() and not renderer_gate.is_set()
        await page.get_by_role('button',name='Zoom in',exact=True).click()
        notice=await page.get_by_test_id('map-loading-state').bounding_box()
        assert notice['width']<400 and notice['height']<150
        await page.screenshot(path=str(ROOT/'reports/performance/map-basemap-before-data.png'),full_page=False)
        data_gate.set()
        await page.get_by_text('Loading site layers...',exact=True).wait_for()
        renderer_gate.set()
        await expect(page.get_by_test_id('map-loading-state')).to_have_count(0,timeout=30000)
        await expect(page.get_by_role('button',name='Download PDF',exact=True)).to_be_enabled()
        card=page.get_by_text('Plotted',exact=True).locator('../..')
        await expect(card.get_by_text('1',exact=True)).to_be_visible()
        assert not errors and not provider,(errors,provider)
        await page.screenshot(path=str(ROOT/'reports/performance/map-progressive-ready.png'),full_page=False)
        route_chunk=next((ROOT/'frontend/dist/assets').glob('MapPage-*.js'))
        renderer_chunk=next((ROOT/'frontend/dist/assets').glob('deckRenderer-*.js'))
        report={'realGoogleSdk':True,'basemapTilesBeforeSiteData':True,'cameraUsableWhileRendererBlocked':True,'noFullScreenMask':True,'siteLayersAfterDeferredDownload':True,'mapRouteBytes':route_chunk.stat().st_size,'deferredRendererBytes':renderer_chunk.stat().st_size,'previousMapRouteBytes':1097854,'providerErrors':provider,'runtimeErrors':errors,'customerDatabaseWrites':0,'note':'Critical route bytes and ordering proof, not a controlled end-to-end latency comparison.'}
        (ROOT/'reports/performance/map-startup-production.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
        await browser.close()

asyncio.run(main())

"""Verify readiness ordering with independently delayed Maps SDK, data and tiles."""
import asyncio, json
from pathlib import Path
from urllib.parse import urlsplit, parse_qs
from playwright.async_api import async_playwright, expect
from test_map_browser import MAP_SDK, DECK_SDK, LAYERS_SDK

OUTPUT=Path(__file__).resolve().parents[1]/'reports/performance'

async def scenario(browser,hold_sdk=False,hold_data=False,hold_renderer=False):
    gate=asyncio.Event()
    if not hold_data:gate.set()
    renderer_gate=asyncio.Event()
    if not hold_renderer:renderer_gate.set()
    context=await browser.new_context(viewport={'width':1440,'height':1000})
    await context.add_init_script("localStorage.setItem('authToken','synthetic-token');localStorage.setItem('user',JSON.stringify({id:1,role:'SUPER_ADMIN',companyId:10}));window.__holdSdk="+json.dumps(hold_sdk)+";window.__holdTiles=true;")
    async def api(route):
        request=route.request;url=urlsplit(request.url);path=url.path.lstrip('/');query=parse_qs(url.query)
        headers={'Access-Control-Allow-Origin':'http://localhost:5173','Access-Control-Allow-Headers':'Authorization, Content-Type','Access-Control-Allow-Methods':'GET,OPTIONS'}
        if request.method=='OPTIONS':await route.fulfill(status=204,headers=headers);return
        if path=='upload/uploads/history':data=[{'id':100,'fileName':'Synthetic sites A','remarks':'[site]'},{'id':101,'fileName':'Synthetic sites B','remarks':'[site]'}]
        elif path=='upload/site-data':
            await gate.wait()
            file=query.get('fileId',['100'])[0]
            data=[{'SITEID':'SITE_'+file,'Site_Name':'SITE_'+file,'Cell_Name':'CELL_'+file,'lat':28.6 if file=='100' else 28.8,'lon':77.2,'Technology':'LTE','Band':'1800','PCI':'10','AZIMUTH':0}]
            await route.fulfill(json={'success':True,'data':data,'pagination':{'totalCount':1,'hasNextPage':False}},headers=headers);return
        else:data={'data':[]}
        await route.fulfill(json={'success':True,'data':data},headers=headers)
    await context.route('http://localhost:3001/**',api)
    await context.route('**/node_modules/.vite/deps/@react-google-maps_api.js*',lambda route:route.fulfill(body=MAP_SDK,content_type='application/javascript'))
    async def renderer(route):
        await renderer_gate.wait()
        body=DECK_SDK if '@deck__gl_google-maps' in route.request.url else LAYERS_SDK
        await route.fulfill(body=body,content_type='application/javascript')
    await context.route('**/node_modules/.vite/deps/@deck__gl_google-maps.js*',renderer)
    await context.route('**/node_modules/.vite/deps/@deck__gl_layers.js*',renderer)
    page=await context.new_page();errors=[];page.on('pageerror',lambda error:errors.append(str(error)))
    await page.clock.install()
    await page.goto('http://localhost:5173/map')
    await expect(page.get_by_label('Site Upload File',exact=True)).to_have_value('100')
    await expect(page.get_by_test_id('map-loading-state')).to_be_visible()
    await expect(page.get_by_role('button',name='Download PDF',exact=True)).to_be_disabled()
    if hold_sdk:assert await page.get_by_test_id('map-canvas').count()==0
    else:await page.get_by_test_id('map-canvas').wait_for()
    return context,page,gate,renderer_gate,errors

async def main():
    checks=[]
    async with async_playwright() as p:
        browser=await p.chromium.launch(channel='chrome',headless=True)
        # Data first, SDK second, basemap tiles last.
        context,page,gate,renderer_gate,errors=await scenario(browser,hold_sdk=True)
        await page.get_by_text('Site data fetched. Preparing map and site layers...',exact=True).wait_for()
        assert not await page.evaluate('Boolean(window.__mapLayers?.length)')
        await page.evaluate('window.__releaseSdk()')
        await page.get_by_test_id('map-canvas').wait_for()
        assert not await page.evaluate('Boolean(window.__mapLayers?.length)')
        await expect(page.get_by_test_id('map-loading-state')).to_be_visible()
        await page.screenshot(path=str(OUTPUT/'map-waiting-for-tiles.png'),full_page=True)
        await page.evaluate('window.__releaseTiles()')
        await expect(page.get_by_test_id('map-loading-state')).to_have_count(0)
        await expect(page.get_by_role('button',name='Download PDF',exact=True)).to_be_enabled()
        assert await page.evaluate("window.__mapLayers.find(layer=>layer.id==='deck-sites').props.data[0].SITEID")=='SITE_100'
        checks.append('SDK can load independently; overlays wait for initial basemap paint')
        # Changing upload replaces layer data, not the basemap instance.
        instances=await page.evaluate('window.__mapInstances')
        gate.clear()
        await page.evaluate('window.__oldTiles=window.__releaseTiles')
        await page.get_by_label('Site Upload File',exact=True).select_option('101')
        await page.wait_for_function('!window.__mapLayers?.length')
        await page.evaluate('window.__oldTiles()')
        await expect(page.get_by_test_id('map-loading-state')).to_be_visible()
        assert not await page.evaluate('Boolean(window.__mapLayers?.length)')
        assert await page.evaluate('window.__mapInstances')==instances
        gate.set()
        await expect(page.get_by_test_id('map-loading-state')).to_have_count(0)
        assert await page.evaluate("window.__mapLayers.find(layer=>layer.id==='deck-sites').props.data[0].SITEID")=='SITE_101'
        assert not errors,errors
        checks.append('source change keeps the same basemap and replaces only current layer data')
        await context.close()
        # SDK first, data second; map mounts at the correct source viewport.
        context,page,gate,renderer_gate,errors=await scenario(browser,hold_data=True)
        await page.get_by_test_id('map-canvas').wait_for()
        instances=await page.evaluate('window.__mapInstances')
        await page.evaluate('window.__releaseTiles()')
        assert not await page.evaluate('Boolean(window.__mapLayers?.length)')
        gate.set()
        await expect(page.get_by_test_id('map-loading-state')).to_have_count(0)
        assert await page.evaluate('window.__mapInstances')==instances
        checks.append('basemap starts before slow site data; data does not remount it')
        assert not errors,errors
        await context.close()
        # A slow WebGL download cannot block basemap creation or camera controls.
        context,page,gate,renderer_gate,errors=await scenario(browser,hold_renderer=True)
        await page.evaluate('window.__releaseTiles();window.__testMap.setZoom(9)')
        assert await page.evaluate('window.__testMap.getZoom()')==9
        notice=await page.get_by_test_id('map-loading-state').bounding_box()
        assert notice['height']<150 and notice['width']<400,'A blocking loading cover returned'
        await page.clock.fast_forward(31000)
        await page.get_by_text('Site layers are taking longer than expected. Reload the map to retry.',exact=True).first.wait_for()
        assert await page.get_by_test_id('map-canvas').count()==1
        renderer_gate.set()
        await expect(page.get_by_test_id('map-loading-state')).to_have_count(0)
        checks.append('slow deferred renderer leaves basemap and camera usable; no full-screen mask')
        assert not errors,errors
        await context.close()
        # A stalled SDK offers recovery instead of an endless success-looking page.
        context,page,gate,renderer_gate,errors=await scenario(browser,hold_sdk=True)
        await page.get_by_text('Site data fetched. Preparing map and site layers...',exact=True).wait_for()
        await page.clock.fast_forward(31000)
        await page.get_by_text('Basemap is taking longer than expected.',exact=True).wait_for()
        await expect(page.get_by_role('button',name='Reload map',exact=True).first).to_be_enabled()
        await page.evaluate('window.__failSdk()')
        await page.get_by_text('Basemap could not load. Check your connection and reload.',exact=True).wait_for()
        assert not errors,errors
        checks.append('timeout and SDK error expose reload recovery')
        await context.close();await browser.close()
    (OUTPUT/'map-loading-checks.json').write_text(json.dumps({'checks':checks,'googleRequests':0,'customerDatabaseWrites':0},indent=2))
    print('Map loading order browser checks passed')

asyncio.run(main())

"""Read-only Google/deck integration smoke with synthetic site API responses.
Uses the application's configured browser Maps key; never records it or request URLs.
"""
import asyncio, json, re
from pathlib import Path
from playwright.async_api import async_playwright

async def main():
    errors=[];provider=[]
    async def api(route):
        request=route.request;path=request.url.split('localhost:3001/')[-1].split('?')[0]
        headers={'Access-Control-Allow-Origin':'http://localhost:5173','Access-Control-Allow-Headers':'Authorization, Content-Type','Access-Control-Allow-Methods':'GET,OPTIONS'}
        if request.method=='OPTIONS': await route.fulfill(status=204,headers=headers);return
        if path=='upload/uploads/history': data=[{'id':100,'fileName':'Synthetic SDK smoke site','remarks':'[site]'}]
        elif path=='upload/site-data':
            await route.fulfill(json={'success':True,'data':[{'SITEID':'SDK_DEMO','Site_Name':'SDK_DEMO','Cell_Name':'SDK_DEMO_CELL','lat':28.6,'lon':77.2,'Band':'1800','Technology':'LTE','PCI':'10','AZIMUTH':0}],'pagination':{'totalCount':1,'hasNextPage':False}},headers=headers);return
        else:data={'data':[]}
        await route.fulfill(json={'success':True,'data':data},headers=headers)
    async with async_playwright() as p:
        browser=await p.chromium.launch(channel='chrome',headless=True,args=['--enable-unsafe-swiftshader'])
        context=await browser.new_context(viewport={'width':1440,'height':1000})
        await context.add_init_script("localStorage.setItem('authToken','synthetic-token');localStorage.setItem('user',JSON.stringify({id:1,role:'SUPER_ADMIN',companyId:10}));")
        await context.route('http://localhost:3001/**',api)
        page=await context.new_page()
        # Record only classification, never URLs, tokens, source rows or provider keys.
        page.on('pageerror',lambda error:errors.append(type(error).__name__))
        def console(message):
            found=re.search(r'Google Maps JavaScript API error:\s*(\w+)',message.text)
            if found:provider.append(found.group(1))
        page.on('console',console)
        await page.goto('http://localhost:5173/map')
        await page.wait_for_timeout(12000)
        result={'googleSdkAvailable':await page.evaluate('Boolean(window.google?.maps?.Map)'), 'googleMapContainers':await page.locator('.gm-style').count(),'canvases':await page.locator('canvas').count(),'loadingOverlayDismissed':await page.get_by_test_id('map-loading-state').count()==0,'exportEnabledAfterMapReady':await page.get_by_role('button',name='Download PDF',exact=True).is_enabled(),'providerErrors':provider,'runtimeErrorTypes':errors,'syntheticSiteRows':1,'customerDatabaseWrites':0,'note':'A headless browser cannot verify tile availability for every deployment or validate customer analysis.'}
        result['passed']=result['googleSdkAvailable'] and result['googleMapContainers']>0 and result['canvases']>0 and result['loadingOverlayDismissed'] and result['exportEnabledAfterMapReady'] and not provider and not errors
        target=Path(__file__).resolve().parents[1]/'reports/performance/map-live-sdk-smoke.json';target.write_text(json.dumps(result,indent=2));print(json.dumps(result))
        await browser.close()
        if not result['passed']:raise SystemExit(1)

asyncio.run(main())

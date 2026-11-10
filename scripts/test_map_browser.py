"""Map interactions with synthetic API fixtures and instrumented map SDK adapters.
No Google tiles, real API credentials, customer jobs or external services are used.
"""
import asyncio, json
from pathlib import Path
from urllib.parse import urlsplit, parse_qs
from playwright.async_api import async_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'reports/performance'

MAP_SDK = r'''
import React from '/node_modules/.vite/deps/react.js';
const {useEffect,useState}=React;
class InfoWindow {setContent(x){this.content=x}setPosition(x){}open(map){this.map=map}close(){this.map=null}getMap(){return this.map}}
class Bounds {constructor(){this.points=[]}extend(x){this.points.push(x);return this}isEmpty(){return !this.points.length}}
class Native {constructor(){window.__nativeLines=(window.__nativeLines||0)+1}setMap(){}addListener(){return {remove(){}}}setIcon(){}setZIndex(){}}
window.google={maps:{InfoWindow,LatLngBounds:Bounds,Size:class{},Point:class{},Polyline:Native,Marker:Native,Polygon:Native,SymbolPath:{CIRCLE:'CIRCLE'},event:{addListenerOnce:(map,name,fn)=>map.addListener(name,fn),removeListener:listener=>listener.remove()}}};
export function useJsApiLoader(){const [isLoaded,setLoaded]=useState(!window.__holdSdk);const [loadError,setError]=useState(null);useEffect(()=>{window.__releaseSdk=()=>setLoaded(true);window.__failSdk=()=>setError(new Error('Synthetic SDK failure'))},[]);return {isLoaded,loadError}}
export function GoogleMap(props){useEffect(()=>{const listeners=new Map();const map={zoom:11,addListener(name,fn){if(!listeners.has(name))listeners.set(name,new Set());listeners.get(name).add(fn);return {remove:()=>listeners.get(name).delete(fn)}},getZoom(){return this.zoom},setZoom(x){this.zoom=x;for(const fn of listeners.get('zoom_changed')||[])fn()},fitBounds(){this.fits=(this.fits||0)+1},panTo(){},listenerCount(){return [...listeners.values()].reduce((n,set)=>n+set.size,0)}};window.__testMap=map;window.__mapInstances=(window.__mapInstances||0)+1;props.onLoad(map);window.__releaseTiles=()=>props.onTilesLoaded?.();const timer=window.__holdTiles?null:setTimeout(()=>props.onTilesLoaded?.(),0);return ()=>{clearTimeout(timer);props.onUnmount(map)}},[]);return React.createElement('div',{'data-testid':'map-canvas',style:{width:'100%',height:'100%'}})}
'''
DECK_SDK = '''export class GoogleMapsOverlay {constructor(){window.__overlayFinalized=false}setMap(map){}setProps(props){window.__mapLayers=props.layers||[];window.__overlayUpdates=(window.__overlayUpdates||0)+1}finalize(){window.__overlayFinalized=true;window.__mapLayers=[]}}'''
LAYERS_SDK = '''class Layer {constructor(props){this.props=props;this.id=props.id}}export const LineLayer=Layer,PolygonLayer=Layer,ScatterplotLayer=Layer,TextLayer=Layer;'''

def site(name, lat, lon, band='1800', tech='LTE', pci='10'):
    return {'SITEID':name,'Site_Name':name,'Cell_Name':name+'_CELL','Cell_ID':name+'_CELL','lat':lat,'lon':lon,'Band':band,'Technology':tech,'PCI':pci,'AZIMUTH':0}

async def main():
    state={'fail_site':True,'pages':[],'calls':[]}
    async def api(route):
        request=route.request; parsed=urlsplit(request.url); path=parsed.path.lstrip('/'); query=parse_qs(parsed.query)
        headers={'Access-Control-Allow-Origin':'http://localhost:5173','Access-Control-Allow-Headers':'Authorization, Content-Type','Access-Control-Allow-Methods':'GET,POST,OPTIONS'}
        if request.method=='OPTIONS': await route.fulfill(status=204,headers=headers);return
        state['calls'].append(path)
        file=query.get('fileId',[''])[0]
        if path=='upload/uploads/history': data=[{'id':100,'fileName':'Synthetic sites A','remarks':'[site]'},{'id':101,'fileName':'Synthetic sites B','remarks':'[site]'},{'id':200,'fileName':'Synthetic KPI A','remarks':'[kpi]'},{'id':201,'fileName':'Synthetic KPI B','remarks':'[kpi]'}]
        elif path=='upload/site-data':
            page=int(query.get('page',['1'])[0]);state['pages'].append((file,page))
            if file=='101' and state['fail_site']:
                await route.fulfill(json={'success':False,'message':'Synthetic source unavailable'},headers=headers);return
            rows=([site('SITE_A',28.6,77.2),site('INVALID',95,77)] if page==1 else [site('SITE_B',28.61,77.21,'3500','NR')]) if file=='100' else [site('SITE_C',28.7,77.3)]
            await route.fulfill(json={'success':True,'data':rows,'pagination':{'totalCount':3 if file=='100' else 1,'hasNextPage':file=='100' and page==1}},headers=headers);return
        elif path=='kpi/dynamic/metrics':
            if file=='200': await asyncio.sleep(.5)
            await route.fulfill(json={'success':True,'metrics':[{'key':'prb' if file=='200' else 'throughput','label':'OLD_METRIC' if file=='200' else 'CURRENT_METRIC'}]},headers=headers);return
        elif path=='sites/summary':
            if file=='200': await asyncio.sleep(1)
            data={'data':[{'site':'STALE_SITE' if file=='200' else 'SITE_A','status':'GOOD','cellCount':1}],'statusCounts':{'GOOD':1}}
        elif path=='sites/intelligence': data={'data':[]}
        elif path=='sites/details': data={'site':'SITE_A','cells':[]}
        elif path=='site-prediction/recommendations':
            await asyncio.sleep(3 if file=='201' else .8)
            data={'data':[{'site':'SITE_A','cellName':'SITE_A_CELL','actionCode':'QUALITY_CHECK','score':90,'action':'AUTO_OLD','reason':'Synthetic'}]}
        elif path=='site-prediction/lb-wcf': data={'records':[],'summary':{'rows':1,'unbalanced_count':0,'balanced_count':1},'warnings':[]}
        elif path=='alarms/summary': data={'total':0}
        elif path=='alarms': data={'items':[]}
        elif path=='kpi/worst-cells': data={'data':[]}
        else: raise AssertionError('Unexpected '+path)
        try: await route.fulfill(json={'success':True,'data':data},headers=headers)
        except Exception:
            if not request.failure: raise

    async with async_playwright() as p:
        browser=await p.chromium.launch(channel='chrome',headless=True)
        context=await browser.new_context(viewport={'width':1440,'height':1000},accept_downloads=True)
        await context.add_init_script("localStorage.setItem('authToken','synthetic-token');localStorage.setItem('user',JSON.stringify({id:1,role:'SUPER_ADMIN',companyId:10}));")
        await context.route('http://localhost:3001/**',api)
        await context.route('**/node_modules/.vite/deps/@react-google-maps_api.js*',lambda route:route.fulfill(body=MAP_SDK,content_type='application/javascript'))
        await context.route('**/node_modules/.vite/deps/@deck__gl_google-maps.js*',lambda route:route.fulfill(body=DECK_SDK,content_type='application/javascript'))
        await context.route('**/node_modules/.vite/deps/@deck__gl_layers.js*',lambda route:route.fulfill(body=LAYERS_SDK,content_type='application/javascript'))
        page=await context.new_page();errors=[]
        page.on('console',lambda message:print('CONSOLE:',message.text[:1600],flush=True) if message.type=='error' else None)
        page.on('pageerror',lambda error:(errors.append(str(error)),print('PAGE ERROR:',error,flush=True)))
        await page.goto('http://localhost:5173/map')
        await page.wait_for_timeout(2000)
        if not await page.evaluate("Boolean(window.__mapLayers?.find(l=>l.id==='deck-sites'))"):
            print('MAP DIAGNOSTIC',json.dumps({'errors':errors,'calls':state['calls'],'body':(await page.locator('body').inner_text())[:1800]}),flush=True)
            await page.screenshot(path=str(OUTPUT/'map-debug.png'),full_page=True)
        await page.wait_for_function("window.__mapLayers?.find(l=>l.id==='deck-sites')?.props.data.length===2")
        assert ('100',2) in state['pages']
        await page.get_by_label('KPI Upload File',exact=True).first.select_option('201')
        await page.get_by_label('Worst Cell KPI Metric',exact=True).first.select_option('throughput')
        await page.get_by_role('switch',name='Predictions OFF',exact=True).click()
        await page.get_by_role('button',name='Run LB',exact=True).first.click()
        await page.get_by_text('LB prediction completed. No unbalanced/actionable cells found.',exact=False).first.wait_for()
        await page.get_by_role('button',name='Close analytics',exact=True).click()
        await page.wait_for_timeout(3200)
        assert await page.get_by_label('Worst Cell KPI Metric',exact=True).first.input_value()=='throughput'
        assert await page.get_by_text('OLD_METRIC',exact=True).count()==0
        assert not await page.evaluate("window.__mapLayers.find(l=>l.id==='deck-predictions')?.props.data.length"),'Automatic predictions overwrote the newer manual LB result'
        await page.get_by_role('switch',name='Predictions ON',exact=True).click()
        # Base sites survive overlay enable/disable; only one renderer draws handovers.
        await page.get_by_role('switch',name='Tech Handovers',exact=True).click()
        await page.wait_for_function("window.__mapLayers.some(l=>l.id==='deck-handover-relations')")
        assert await page.evaluate('window.__nativeLines||0')==0
        await page.get_by_role('switch',name='Tech Handovers',exact=True).click()
        await page.wait_for_function("!window.__mapLayers.some(l=>l.id==='deck-handover-relations') && window.__mapLayers.some(l=>l.id==='deck-sites')")
        await page.get_by_role('switch',name='Cells OFF',exact=True).click()
        await page.wait_for_function("window.__mapLayers.some(l=>l.id==='deck-cells')")
        before=await page.evaluate("window.__mapLayers.find(l=>l.id==='deck-cells').props.getPolygon(window.__mapLayers.find(l=>l.id==='deck-cells').props.data[0])")
        await page.evaluate('window.__testMap.setZoom(15)');await page.wait_for_timeout(250)
        after=await page.evaluate("window.__mapLayers.find(l=>l.id==='deck-cells').props.getPolygon(window.__mapLayers.find(l=>l.id==='deck-cells').props.data[0])")
        assert before!=after,'Zoom listener did not update cell geometry'
        await page.get_by_role('button',name='Utility',exact=True).click()
        site_radius=await page.evaluate("window.__mapLayers.find(l=>l.id==='deck-sites').props.getRadius(window.__mapLayers.find(l=>l.id==='deck-sites').props.data[0])")
        await page.get_by_role('button',name='Increase site size',exact=True).click()
        new_radius=await page.evaluate("window.__mapLayers.find(l=>l.id==='deck-sites').props.getRadius(window.__mapLayers.find(l=>l.id==='deck-sites').props.data[0])")
        assert new_radius>site_radius
        assert await page.get_by_role('slider',name='Cell Radius',exact=True).input_value()=='1'
        await page.get_by_role('button',name='Utility',exact=True).click()
        # PDF uses the real jsPDF exporter against synthetic data.
        async with page.expect_download() as download:
            await page.get_by_role('button',name='Download PDF',exact=True).click()
        artifact=await download.value;target=OUTPUT/'map-synthetic-export.pdf';await artifact.save_as(str(target))
        assert target.read_bytes().startswith(b'%PDF') and target.stat().st_size>1000
        await page.get_by_label('Site Upload File',exact=True).select_option('101')
        await page.get_by_role('button',name='Retry site data',exact=True).wait_for()
        state['fail_site']=False
        await page.get_by_role('button',name='Retry site data',exact=True).click()
        await page.wait_for_function("window.__mapLayers.find(l=>l.id==='deck-sites')?.props.data[0]?.SITEID==='SITE_C'")
        await page.screenshot(path=str(OUTPUT/'map-fixed-desktop.png'),full_page=True)
        await page.set_viewport_size({'width':390,'height':844})
        assert await page.evaluate('document.documentElement.scrollWidth<=innerWidth+1'),'Mobile horizontal overflow'
        assert await page.get_by_label('Site Upload File',exact=True).evaluate('(element)=>element.getBoundingClientRect().right<=innerWidth'),'Mobile source controls are clipped'
        await page.get_by_role('button',name='Utility',exact=True).click()
        await page.screenshot(path=str(OUTPUT/'map-utility-mobile.png'),full_page=True)
        assert await page.get_by_role('slider',name='Site Size',exact=True).evaluate('(element)=>element.getBoundingClientRect().left>=0 && element.getBoundingClientRect().right<=innerWidth'),'Mobile utility controls are clipped'
        await page.get_by_role('button',name='Utility',exact=True).click()
        await page.screenshot(path=str(OUTPUT/'map-fixed-mobile.png'),full_page=True)
        await page.set_viewport_size({'width':1440,'height':1000})
        await page.locator('a[href="/recommendation"]').click()
        await page.wait_for_function('window.__overlayFinalized===true')
        assert await page.evaluate('window.__testMap.listenerCount()')==0
        assert not errors,errors
        (OUTPUT/'map-browser-checks.json').write_text(json.dumps({'checks':['all site pages','coordinate validation','obsolete metric responses ignored','newer manual LB result wins over pending automatic response','overlay toggle preserves sites','single handover renderer','zoom geometry update','independent size controls','real PDF exporter','source retry','mobile overflow','overlay and listener cleanup'],'sdk':'instrumented Google Maps/deck adapters; external basemap not exercised','pageErrors':errors,'customerDatabaseWrites':0},indent=2))
        await browser.close()
    print('Map browser checks passed')

if __name__=='__main__':
    asyncio.run(main())

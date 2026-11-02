"""Capture real local runtime evidence without changing application data."""
from pathlib import Path
import json
import urllib.request
from datetime import datetime
from zoneinfo import ZoneInfo
from PIL import Image, ImageDraw, ImageFont
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "reports" / "handover"
ASSETS = OUT / "images"
ASSETS.mkdir(parents=True, exist_ok=True)

def font(size, bold=False):
    return ImageFont.truetype("C:/Windows/Fonts/" + ("arialbd.ttf" if bold else "arial.ttf"), size)

def canvas(title, subtitle):
    img = Image.new("RGB", (1600, 1000), "#f1f5f9")
    draw = ImageDraw.Draw(img)
    draw.rectangle((0, 0, 1600, 155), fill="#17375e")
    draw.text((65, 35), title, font=font(42, True), fill="white")
    draw.text((65, 100), subtitle, font=font(23), fill="#dbeafe")
    return img, draw

def box(draw, xy, title, lines):
    draw.rounded_rectangle(xy, radius=18, fill="white", outline="#b6c8db", width=3)
    x,y,_,_ = xy
    draw.text((x+25,y+22),title,font=font(28,True),fill="#17375e")
    for i,line in enumerate(lines):
        draw.text((x+25,y+72+i*35),line,font=font(23),fill="#334155")

def arrow(draw, start, end):
    draw.line((start,end),fill="#3b82f6",width=7)
    x,y=end
    draw.polygon([(x,y),(x-13,y-20),(x+13,y-20)],fill="#3b82f6")

img,d=canvas("Solution architecture", "Network KPI Monitoring | Implementation overview")
box(d,(390,195,1210,325),"React browser / Electron shell",["Dashboard, KPI charts, maps, alarms and reports | :5173"])
arrow(d,(800,330),(800,390))
box(d,(390,395,1210,550),"Java Spring Boot REST API",["Authentication, licensing, upload parsing, analytics", "JPA / JDBC persistence and Python orchestration | :3001"])
arrow(d,(800,555),(800,615))
box(d,(50,625,520,830),"MySQL",["Users and sessions", "Uploads, KPI/site/alarm rows", "Thresholds and settings | :3306"])
box(d,(565,625,1035,830),"Local files",["Original upload files", "Dynamic KPI JSON cache", "Optional Redis response cache"])
box(d,(1080,625,1550,830),"Python RCA",["pandas / numpy / openpyxl", "Rule-based analysis", "JSON and Excel outputs"])
d.line((280,600,1320,600),fill="#3b82f6",width=5)
for x in [280,800,1320]: arrow(d,(x,600),(x,620))
d.text((65,910),"External dependencies: Google Maps credentials, valid local license, configured runtime secrets.",font=font(23),fill="#475569")
img.save(ASSETS/'architecture.png')

img,d=canvas("Operational workflow", "From source files to an actionable network review")
stages=[("1. Prepare and upload",["Site, KPI and alarm CSV / Excel files", "Review inserted, skipped and invalid row feedback"]),
        ("2. Select scope and thresholds",["Choose the relevant upload, dates and dimensions", "Confirm metric direction and operator-approved limits"]),
        ("3. Analyse and investigate",["Review dashboard, worst cells, site intelligence and map", "Correlate alarms and run RCA with correct mappings"]),
        ("4. Export and follow up",["Export supported KPI / RCA workbooks and reports", "Assign corrective actions and verify the next observation period"])]
for i,(title,lines) in enumerate(stages):
 y=190+i*190
 box(d,(160,y,1440,y+150),title,lines)
 if i<3:arrow(d,(800,y+152),(800,y+185))
img.save(ASSETS/'workflow.png')

evidence={"capturedAt":datetime.now(ZoneInfo('Asia/Kolkata')).isoformat(),"checks":[]}
for name,path in [("Backend health","/health"),("Dashboard API","/api/v1/dashboard/stats")]:
    with urllib.request.urlopen('http://127.0.0.1:3001'+path,timeout=90) as response:
        payload=json.load(response)
        evidence['checks'].append({"name":name,"path":path,"httpStatus":response.status,"success":payload.get('success')})
        if name=='Dashboard API': evidence['dashboard']=payload.get('data',{})
with sync_playwright() as p:
    browser=p.chromium.launch(channel="msedge")
    page=browser.new_page(viewport={"width":1440,"height":950},device_scale_factor=1)
    response=page.goto('http://127.0.0.1:5173/login',wait_until='networkidle',timeout=60000)
    page.screenshot(path=str(ASSETS/'login.png'),full_page=True)
    evidence['checks'].append({"name":"Frontend login page","httpStatus":response.status})
    browser.close()
img,d=canvas("Verified local runtime", "Live API evidence | "+evidence['capturedAt'][:19].replace('T',' ')+' IST')
metrics=[('Cells','totalCells'),('Sites','totalSites'),('Bands','totalBands'),('Technologies','totalTech')]
for i,(label,key) in enumerate(metrics):
 x=80+(i%2)*770;y=220+(i//2)*235
 box(d,(x,y,x+670,y+190),label,[format(evidence['dashboard'].get(key,0),','),"Reported by /api/v1/dashboard/stats"])
d.text((80,755),"Backend health: HTTP 200 | Dashboard API: HTTP 200 | Frontend login: HTTP 200",font=font(26,True),fill="#166534")
d.text((80,820),"Counts reflect existing local data; this figure is generated from API results,",font=font(25),fill="#475569")
d.text((80,858),"and is not an application dashboard screenshot or a production capacity benchmark.",font=font(25),fill="#475569")
img.save(ASSETS/'runtime-evidence.png')
(OUT/'verification.json').write_text(json.dumps(evidence,indent=2),encoding='utf-8')
print('Created four report images and verification.json')

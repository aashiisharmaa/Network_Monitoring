"""Existing /alarms summary-tab checks with synthetic intercepted APIs; no DB/email access."""
import io
import json
import os
from pathlib import Path
from urllib.parse import urlsplit, parse_qs
import openpyxl
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "reports/performance/project-review-2026-11-18"
BASE = os.environ.get("PORTAL_TEST_BASE_URL", "http://127.0.0.1:5173").rstrip("/")
OUTPUT.mkdir(parents=True, exist_ok=True)
calls = []
state = {"fail": False, "large": False, "operational": False, "snapshot": {"id": None, "revision": 0, "circles": []}, "conflict": False}
group = {"zone": "West", "circleId": 10, "circle": "Synthetic Circle", "technology": "4G", "observedRecords": 4,
         "includedRecords": 3, "critical": 1, "major": 2, "minor": 0, "serviceAffectingRecords": 2,
         "knownDownSites": 1, "unmappedCatalogue": 1, "unmappedSites": 1,
         "downSiteAge": {"under12Hours": 1, "from12To48Hours": 0, "above48Under72Hours": 0, "atLeast72Hours": 0}}
summary = {"contractVersion": "alarm-summary-v1", "zones": [{**group, "circleId": None, "circle": None}],
           "circles": [group], "observedRecords": 4, "includedRecords": 3, "knownDownSites": 1,
           "unmappedCatalogue": 1, "pendingOperationalInputs": ["MS2 pending 2G/4G", "LR pending status/count"],
           "notes": ["Missing observations do not confirm clearance."]}
output = io.BytesIO()
book = openpyxl.Workbook()
book.active.append(["Synthetic report"])
book.save(output)


def intercept(route):
    request = route.request
    url = urlsplit(request.url)
    if url.port != 3001:
        route.continue_()
        return
    headers = {"Access-Control-Allow-Origin": BASE, "Access-Control-Allow-Headers": "Authorization, Content-Type",
               "Access-Control-Allow-Methods": "GET,POST,OPTIONS"}
    if request.method == "OPTIONS":
        route.fulfill(status=204, headers=headers)
        return
    calls.append({"method": request.method, "path": url.path, "query": parse_qs(url.query), "authorization": request.headers.get("authorization")})
    data = {}
    if url.path.endswith("/companies"):
        assert request.headers.get("authorization") == "Bearer synthetic-token"
        data = [{"id": 100, "companyName": "Synthetic A"}, {"id": 200, "companyName": "Synthetic B"}]
    elif "/alarm-config/" in url.path and url.path.endswith("/circles"):
        data = [{"id": 10, "name": "Synthetic Circle", "active": True}]
    elif "/alarm-workflow/" in url.path:
        assert request.headers.get("authorization") == "Bearer synthetic-token"
        if url.path.endswith("/operational-inputs") and request.method == "POST":
            body = json.loads(request.post_data)
            if state["conflict"]:
                assert body["expectedRevision"] == 1 and body["expectedBaseSnapshotId"] == 901
                route.fulfill(status=409, json={"message": "Changed elsewhere; reload before saving"}, headers=headers)
                return
            assert body["expectedRevision"] == 0 and body["expectedBaseSnapshotId"] is None
            item = body["circles"][0]
            assert item["ms2Pending"] == 0 and item["lrPending"] is None
            assert item["siteUp"] == 8 and item["siteInventoryTotal"] == 10
            assert item["sites"][0]["state"] == "UP" and item["sites"][0]["recoveryReason"] == "Power restored"
            state["snapshot"] = {"id": 901, "effectiveAt": body["effectiveAt"], "revision": 1,
                "sourceReference": body["sourceReference"], "checksum": "synthetic", "createdBy": 1,
                "circles": [{"circleName": "Synthetic Circle", "zoneName": "West", "input": item}]}
            route.fulfill(status=201, json={"success": True, "data": state["snapshot"]}, headers=headers)
            return
        if url.path.endswith("/operational-inputs/history"):
            route.fulfill(json={"success": True, "data": [{"id": 901, "revision": 1, "effective_at": "2026-11-17T00:00:00", "source_reference": "Synthetic NOC register", "created_by": 1}]}, headers=headers)
            return
        if "/report/" in url.path:
            assert parse_qs(url.query).get("circleId") == ["10"]
            assert parse_qs(url.query).get("asOf") == ["2026-11-17T00:00:00Z"]
            route.fulfill(body=output.getvalue(), content_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", headers=headers)
            return
        if state["fail"]:
            state["fail"] = False
            route.fulfill(status=503, json={"message": "Synthetic source unavailable"}, headers=headers)
            return
        empty = "/companies/200/" in url.path
        data = {"asOf": "2026-11-17T00:00:00Z", "configuredActiveFeeds": 2,
                "sources": [] if empty else [{"id": 1, "feed_id": 20, "snapshot_at": "2026-11-16T23:00:00"}],
                "reportSummary": {**summary, "zones": [], "circles": [], "observedRecords": 0, "includedRecords": 0,
                                  "knownDownSites": 0, "unmappedCatalogue": 0} if empty else summary}
        data["operationalInputs"] = {"enabled": state["operational"], "snapshot": {"id": None, "revision": 0, "circles": []} if empty else state["snapshot"]}
        if state["large"] and not empty:
            data["reportSummary"] = {**summary, "circles": [dict(group, circleId=1000+i, circle=f"Circle {i:02}") for i in range(31)]}
    elif url.path.endswith("/history"):
        data = []
    elif url.path.endswith("/health"):
        data = {"status": "UP"}
    route.fulfill(json={"success": True, "data": data}, headers=headers)


with sync_playwright() as p:
    browser = p.chromium.launch(channel="chrome", headless=True)
    context = browser.new_context(viewport={"width": 1440, "height": 1000}, accept_downloads=True)
    context.add_init_script("localStorage.setItem('authToken','synthetic-token');localStorage.setItem('user',JSON.stringify({id:1,role:'SUPER_ADMIN',email:'qa@example.invalid'}));")
    context.route("**/*", intercept)
    page = context.new_page()
    errors = []
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.goto(BASE + "/alarms")
    expect(page.get_by_role("heading", name="Alarm Monitoring", exact=True)).to_be_visible()
    expect(page.get_by_role("link", name="Alarm Operations", exact=True)).to_have_count(0)
    page.get_by_role("button", name="National & Circle Summary", exact=True).click()
    region = page.get_by_role("region", name="National and circle alarm summaries")
    region.get_by_role("combobox", name="Company", exact=True).select_option("100")
    expect(region.get_by_role("heading", name="Circle summary", exact=True)).to_be_visible()
    expect(region.get_by_text("MS2 pending 2G/4G", exact=True)).to_be_visible()
    expect(region.get_by_text("1 configured source(s) have no completed snapshot", exact=False)).to_be_visible()
    region.get_by_role("combobox", name="Report scope", exact=True).select_option("10")
    with page.expect_download() as download:
        region.get_by_role("button", name="Excel report", exact=True).click()
    assert download.value.suggested_filename == "alarm-circle-10-2026-11-17.xlsx"
    expect(page.locator(".portal-main")).to_have_css("margin-left", "256px")
    page.screenshot(path=str(OUTPUT / "alarm-summary-desktop.png"), full_page=False)
    state["fail"] = True
    region.get_by_role("button", name="Refresh summary", exact=True).click()
    expect(region.get_by_role("alert")).to_contain_text("Synthetic source unavailable")
    region.get_by_role("button", name="Refresh summary", exact=True).click()
    expect(region.get_by_role("heading", name="Circle summary", exact=True)).to_be_visible()
    region.get_by_role("combobox", name="Company", exact=True).select_option("200")
    expect(region.get_by_text("No completed OSS snapshots", exact=False)).to_be_visible()
    expect(region.get_by_role("heading", name="Circle summary", exact=True)).to_have_count(0)
    region.get_by_role("combobox", name="Company", exact=True).select_option("100")
    expect(region.get_by_role("heading", name="Circle summary", exact=True)).to_be_visible()
    state["large"] = True
    region.get_by_role("button", name="Refresh summary", exact=True).click()
    paging = region.get_by_role("navigation", name="Circle summary pages")
    expect(paging).to_be_visible()
    expect(region.locator("table").last.locator("tbody tr")).to_have_count(25)
    paging.get_by_role("button", name="Next", exact=True).click()
    expect(region.locator("table").last.locator("tbody tr")).to_have_count(6)
    state["large"] = False
    region.get_by_role("button", name="Refresh summary", exact=True).click()
    expect(region.get_by_role("heading", name="Circle summary", exact=True)).to_be_visible()
    page.set_viewport_size({"width": 390, "height": 844})
    expect(region.get_by_role("button", name="Excel report", exact=True)).to_be_visible()
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth + 2"), "Page overflows on mobile"
    page.screenshot(path=str(OUTPUT / "alarm-summary-mobile.png"), full_page=False)
    state["operational"] = True
    region.get_by_role("button", name="Refresh summary", exact=True).click()
    ops = region.get_by_role("region", name="Reported operational inputs")
    ops.get_by_text("Capture or correct operational inputs", exact=True).click()
    ops.get_by_role("combobox", name="Operational circle").select_option("10")
    ops.get_by_role("spinbutton", name="MS2 pending", exact=True).fill("0")
    ops.get_by_role("spinbutton", name="Site inventory total", exact=True).fill("10")
    ops.get_by_role("spinbutton", name="Reported site up", exact=True).fill("8")
    ops.get_by_role("textbox", name="Operational source reference", exact=True).fill("Synthetic NOC register")
    ops.get_by_role("checkbox", name="Add or correct a reported site update").check()
    ops.get_by_role("textbox", name="Reported site code").fill("SITE-1")
    ops.get_by_role("combobox", name="Reported site state").select_option("UP")
    ops.get_by_role("textbox", name="Recovery reason", exact=True).fill("Power restored")
    ops.get_by_role("button", name="Save operational revision", exact=True).click()
    expect(ops.get_by_text("Snapshot #901", exact=False)).to_be_visible()
    expect(ops.locator("table").first.locator("tbody tr").first.locator("td").nth(2)).to_have_text("0")
    expect(ops.locator("table").first.locator("tbody tr").first.locator("td").nth(3)).to_have_text("\u2014")
    ops.get_by_text("Reported site updates (1)", exact=True).click()
    expect(ops.get_by_role("cell", name="Power restored", exact=True)).to_be_visible()
    ops.get_by_role("button", name="Load revision history", exact=True).click()
    expect(ops.locator("li").filter(has_text="#901 \u00b7 revision 1")).to_be_visible()
    ops.get_by_text("Capture or correct operational inputs", exact=True).click()
    ops.get_by_role("spinbutton", name="MS2 pending", exact=True).fill("3")
    ops.get_by_role("textbox", name="Operational source reference", exact=True).fill("Correction")
    state["conflict"] = True
    ops.get_by_role("button", name="Save operational revision", exact=True).click()
    expect(ops.get_by_role("alert")).to_contain_text("Changed elsewhere")
    state["conflict"] = False
    page.screenshot(path=str(OUTPUT / "operational-inputs-mobile.png"), full_page=False)
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth + 2"), "Operational form overflows on mobile"
    assert not errors, errors
    context.close()
    scoped = browser.new_context(viewport={"width": 1200, "height": 900})
    scoped.add_init_script("localStorage.setItem('authToken','synthetic-token');localStorage.setItem('user',JSON.stringify({id:2,role:'USER',companyId:100}));")
    scoped.route("**/*", intercept)
    before = len(calls)
    page = scoped.new_page()
    page.goto(BASE + "/alarms")
    page.get_by_role("button", name="National & Circle Summary", exact=True).click()
    expect(page.get_by_role("heading", name="Circle summary", exact=True)).to_be_visible()
    assert not any(call["path"].endswith("/companies") for call in calls[before:]), "Company user must not discover other companies"
    expect(page.get_by_text("Snapshot #901", exact=False)).to_be_visible()
    expect(page.get_by_text("Capture or correct operational inputs", exact=True)).to_have_count(0)
    assert not any(call["method"] == "POST" for call in calls[before:]), "Read-only user cannot capture operational inputs"
    scoped.close()
    browser.close()
    (OUTPUT / "browser-checks.json").write_text(json.dumps({"passed": True, "errors": errors, "calls": calls}, indent=2))
    print("PASS: existing Alarms tab, tenant scope, report download, failure/retry, stale-state clearing, operational capture/revisions/zero/unknown/site updates, read-only scope and mobile layout")

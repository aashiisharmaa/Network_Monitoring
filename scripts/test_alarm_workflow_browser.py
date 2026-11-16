"""Synthetic browser checks. All API calls are intercepted; no customer DB/mail is used."""
import json
import os
from pathlib import Path
from urllib.parse import urlsplit
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "reports/performance"
BASE = os.environ.get("PORTAL_TEST_BASE_URL", "http://localhost:4173").rstrip("/")
OUTPUT.mkdir(parents=True, exist_ok=True)
calls = []
zones = [{"id": 1, "companyId": 100, "code": "WEST", "name": "West", "active": True, "version": 1}]
circles = [{"id": 10, "companyId": 100, "zoneId": 1, "code": "MU", "name": "Mumbai", "active": True, "version": 1}]
aliases = [{"id": 1, "circleId": 10, "alias": "MU", "canonical": True}]
feeds = [{"id": 20, "companyId": 100, "sourceKey": "SYNTHETIC", "name": "Synthetic Nokia 4G", "oem": "NOKIA", "technology": "4G", "fileFormat": "CSV", "sheet": None, "sourceTimezone": "Asia/Kolkata", "dumpType": "SNAPSHOT", "circleId": 10, "active": True, "version": 1}]
runs = [{"id": 1, "feed_id": 20, "snapshot_at": "2026-11-15T05:00:00", "status": "SUCCEEDED", "attempts": 1, "message": "Imported atomically", "result_json": json.dumps({"distinctAlarms": 65, "newAlarms": 4, "continuingAlarms": 61, "notObservedAgain": 2, "previousRunId": 9})}]
settings = {"version": 0, "enabled": False, "freshnessMinutes": 60, "reports": [], "escalations": [], "watches": []}
evidence = [{"id": 5, "identity_key": "a" * 64, "circle_id": 10, "circle_name": "Mumbai", "zone_name": "West", "site_code": "TEST-SITE", "oem": "NOKIA", "technology": "4G", "resource_name": "SYNTHETIC-NODE", "alarm_code": "100", "alarm_text": "Synthetic transport alarm", "source_severity": "Major", "classification": "CRITICAL", "included": True, "raised_at": "2026-11-15T04:00:00"}]


def api(route):
    global settings
    req = route.request
    path = urlsplit(req.url).path
    headers = {"Access-Control-Allow-Origin": BASE, "Access-Control-Allow-Headers": "Authorization, Content-Type", "Access-Control-Allow-Methods": "GET,POST,PUT,DELETE,OPTIONS"}
    if req.method == "OPTIONS":
        route.fulfill(status=204, headers=headers)
        return
    calls.append((req.method, path, req.headers.get("authorization")))
    data = {}
    if path.endswith("/health") or path == "/health":
        data = {"status": "UP"}
    elif path.endswith("/companies"):
        data = [{"id": 100, "companyName": "Synthetic customer"}]
    elif "/alarm-config/" in path:
        suffix = path.split("/companies/100/")[-1]
        if suffix == "zones": data = zones
        elif suffix == "circles": data = circles
        elif suffix == "circle-aliases":
            if req.method == "POST":
                item = json.loads(req.post_data)
                item.update({"id": 2, "canonical": False})
                aliases.append(item)
            data = aliases
        elif suffix == "feeds": data = feeds
        elif suffix == "feeds/20" and req.method == "PUT":
            feeds[0].update(json.loads(req.post_data))
            feeds[0]["version"] += 1
            data = feeds[0]
    elif "/alarm-workflow/" in path:
        assert req.headers.get("authorization") == "Bearer synthetic-token"
        assert "/companies/100/" in path, "Unexpected tenant request"
        suffix = path.split("/companies/100/")[-1]
        if suffix == "imports": data = runs
        elif suffix == "dashboard": data = {"asOf": "2026-11-16T00:00:00Z", "sources": [{"id": 1, "feed_id": 20, "snapshot_at": "2026-11-15T05:00:00"}], "circles": [{"circle_id": 10, "circle_name": "Mumbai", "zone_name": "West", "observed_alarms": 65, "critical": 12, "major": 20, "minor": 30, "down_sites": 2, "unmapped_catalogue": 3}], "warning": "Synthetic source snapshots; missing records are not confirmed cleared."}
        elif suffix == "status": data = {"workflowEnabled": True, "inputRootConfigured": False, "smtpEnabled": False, "folderStatus": []}
        elif suffix == "automation":
            if req.method == "PUT":
                incoming = json.loads(req.post_data)
                assert incoming["version"] == settings["version"]
                settings = {**incoming, "version": incoming["version"] + 1}
            data = settings
        elif suffix == "catalogue": data = []
        elif suffix == "deliveries": data = []
        elif suffix.endswith("/observations"): data = evidence
        elif suffix.endswith("/history"): data = {"snapshots": [{"run_id": 1, "snapshot_at": "2026-11-15T05:00:00", "classification": "CRITICAL"}], "operatorEvents": [], "warning": "Operator actions are distinct from OSS state."}
        elif suffix == "feeds/20/imports":
            assert "snapshotAt" in req.post_data and "file" in req.post_data
            data = runs[0]
        elif "/report/" in suffix:
            route.fulfill(status=200, headers=headers, content_type="text/csv", body="Synthetic export only")
            return
    route.fulfill(status=200, headers=headers, content_type="application/json", body=json.dumps({"success": True, "data": data}))


with sync_playwright() as p:
    browser = p.chromium.launch(channel="chrome", headless=True)
    context = browser.new_context(viewport={"width": 1440, "height": 1000}, accept_downloads=True)
    context.add_init_script("localStorage.setItem('authToken','synthetic-token');localStorage.setItem('user',JSON.stringify({id:9,role:'ADMIN',companyId:100,email:'qa@example.invalid'}));")
    context.route("http://localhost:3001/**", api)
    page = context.new_page()
    errors = []
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.goto(BASE + "/alarm-operations")
    expect(page.get_by_role("heading", name="Alarm operations", exact=True)).to_be_visible()
    expect(page.get_by_role("heading", name="National & circle overview", exact=True)).to_be_visible()
    expect(page.locator(".oss-metrics")).to_have_css("display", "grid")
    assert page.locator(".oss-metrics").bounding_box()["x"] >= 256, "Main content is hidden under the fixed sidebar"
    page.screenshot(path=str(OUTPUT / "oss-alarm-operations-desktop.png"), full_page=True)
    page.get_by_role("button", name="Imports & evidence", exact=True).click()
    expect(page.get_by_role("heading", name="Alarm evidence", exact=True)).to_be_visible()
    expect(page.get_by_text("SYNTHETIC-NODE", exact=True)).to_be_visible()
    page.get_by_role("button", name="History", exact=True).click()
    expect(page.get_by_role("dialog")).to_be_visible()
    expect(page.get_by_role("heading", name="Source observations", exact=True)).to_be_visible()
    page.keyboard.press("Escape")
    expect(page.get_by_role("dialog")).not_to_be_visible()
    with page.expect_download() as download:
        page.get_by_role("button", name="CSV", exact=True).click()
    assert download.value.suggested_filename == "alarm-report-1.csv"
    page.get_by_label("Source profile").select_option("20")
    page.get_by_label("Snapshot time (UTC)").fill("2026-11-15T05:00")
    page.get_by_label("Raw alarm file").set_input_files({"name": "alarms.csv", "mimeType": "text/csv", "buffer": b"synthetic fixture"})
    page.get_by_role("button", name="Validate & queue", exact=True).click()
    expect(page.get_by_role("status").filter(has_text="Snapshot queued")).to_be_visible()
    page.screenshot(path=str(OUTPUT / "oss-alarm-evidence-desktop.png"), full_page=True)
    page.get_by_role("button", name="Configuration", exact=True).click()
    alias_form = page.locator("form").filter(has=page.get_by_role("heading", name="3. Map a circle alias"))
    alias_form.get_by_label("Canonical circle").select_option("10")
    alias_form.get_by_label("Exact source alias").fill("SYNTHETIC-ALIAS")
    alias_form.get_by_role("button", name="Save", exact=True).click()
    expect(page.locator(".oss-chips")).to_contain_text("SYNTHETIC-ALIAS")
    page.get_by_role("button", name="Automation & mail", exact=True).click()
    expect(page.get_by_role("heading", name="Automation controls", exact=True)).to_be_visible()
    page.get_by_label("Enable configured automation").check()
    page.get_by_role("button", name="Save controls", exact=True).click()
    expect(page.get_by_role("button", name="Save controls", exact=True)).to_be_enabled()
    expect(page.get_by_label("Enable configured automation")).to_be_checked()
    route_form = page.locator("form").filter(has=page.get_by_role("heading", name="Report recipients", exact=True))
    route_form.get_by_label("Route / policy key").fill("preview")
    route_form.get_by_label("Recipient emails").fill("qa@example.invalid")
    route_form.get_by_label("Delivery mode").select_option("CAPTURE")
    route_form.get_by_role("button", name="Save", exact=True).click()
    expect(page.get_by_text("reports: preview", exact=True)).to_be_visible()
    assert settings["reports"][0]["mode"] == "CAPTURE"
    page.set_viewport_size({"width": 390, "height": 844})
    page.get_by_role("button", name="Overview", exact=True).click()
    expect(page.locator(".oss-metrics")).to_be_visible()
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1"), "Page has horizontal overflow"
    page.screenshot(path=str(OUTPUT / "oss-alarm-operations-mobile.png"), full_page=True)
    assert not errors, errors
    context.close()
    reader = browser.new_context(viewport={"width": 1440, "height": 900})
    reader.add_init_script("localStorage.setItem('authToken','synthetic-token');localStorage.setItem('user',JSON.stringify({id:11,role:'USER',companyId:100,email:'reader@example.invalid'}));")
    reader.route("http://localhost:3001/**", api)
    page = reader.new_page()
    page.goto(BASE + "/alarm-operations")
    expect(page.get_by_role("heading", name="National & circle overview")).to_be_visible()
    page.get_by_role("button", name="Configuration", exact=True).click()
    expect(page.get_by_role("heading", name="1. Add a zone", exact=True)).to_have_count(0)
    page.get_by_role("button", name="Imports & evidence", exact=True).click()
    expect(page.get_by_role("button", name="Validate & queue", exact=True)).to_have_count(0)
    reader.close()
    browser.close()
print("Alarm workspace desktop/mobile, import, evidence, history, downloads, configuration, routing and read-only role checks passed.")

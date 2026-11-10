"""Browser regression with synthetic API responses; never starts real customer jobs."""
import json
from pathlib import Path
from playwright.sync_api import sync_playwright, expect

output = Path(__file__).resolve().parents[1] / "reports" / "performance"
output.mkdir(parents=True, exist_ok=True)
state = {"posts": 0, "polls": 0, "downloads": 0, "failure": False}
calls = []

def handle(route):
    request = route.request
    path = request.url.split("localhost:3001/")[-1].split("?")[0]
    headers = {"Access-Control-Allow-Origin": "http://localhost:5173", "Access-Control-Allow-Headers": "Authorization, Content-Type", "Access-Control-Allow-Methods": "GET,POST,OPTIONS"}
    if request.method == "OPTIONS":
        route.fulfill(status=204, headers=headers)
        return
    calls.append((request.method, path))
    if path == "upload/uploads/history":
        data = [{"id": 500, "fileName": "Synthetic RCA KPI", "remarks": "[kpi]"}]
    elif path == "kpi/dynamic/columns":
        route.fulfill(json={"success": True, "columns": [{"key": "prb", "label": "DL PRB Utilisation", "numericCount": 100, "isDimension": False}]}, headers=headers)
        return
    elif path == "kpi/recommendation/presets":
        route.fulfill(json={"success": True, "presets": []}, headers=headers)
        return
    elif path.startswith("api/v1/rca-exports"):
        assert request.headers.get("authorization") == "Bearer browser-test-token"
        if request.method == "POST":
            state["posts"] += 1
            data = {"id": 1, "status": "QUEUED" if state["posts"] != 2 else "COMPLETED", "progressPercent": 5, "message": "Checking captured analysis"}
        elif path.endswith("/download"):
            state["downloads"] += 1
            route.fulfill(body=b"synthetic workbook download", content_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", headers=headers)
            return
        else:
            state["polls"] += 1
            failed = state["failure"]
            complete = state["polls"] > 1
            data = {"id": 1, "status": "FAILED" if failed else "COMPLETED" if complete else "PROCESSING", "progressPercent": 100 if complete else 65, "message": "Synthetic export failure" if failed else "Writing Excel sheets"}
    else:
        raise AssertionError("Unexpected request: " + path)
    route.fulfill(json={"success": True, "data": data}, headers=headers)

with sync_playwright() as p:
    browser = p.chromium.launch(channel="chrome", headless=True)
    context = browser.new_context(viewport={"width": 1440, "height": 1100}, accept_downloads=True)
    context.add_init_script("localStorage.setItem('authToken','browser-test-token');localStorage.setItem('user',JSON.stringify({id:1,role:'SUPER_ADMIN',companyId:10}));localStorage.setItem('globalSelectedKpiFileId:v1','500');")
    context.route("http://localhost:3001/**", handle)
    page = context.new_page()
    errors = []
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.goto("http://localhost:5173/recommendation")
    page.get_by_role("option", name="Synthetic RCA KPI", exact=True).wait_for(state="attached")
    page.wait_for_timeout(500)
    page.get_by_role("button", name="Export", exact=True).click()
    expect(page.get_by_role("button", name="Run Recommendation", exact=True)).to_be_disabled()
    page.get_by_role("status").filter(has_text="65%").wait_for()
    page.screenshot(path=str(output / "rca-export-progress.png"), full_page=True)
    with page.expect_download() as event:
        page.get_by_role("status").filter(has_text="Export downloaded.").wait_for()
    assert event.value.suggested_filename == "rca_recommendation_500_1.xlsx"
    polls = state["polls"]
    with page.expect_download():
        page.get_by_role("button", name="Export", exact=True).click()
    expect(page.get_by_role("button", name="Export", exact=True)).to_be_enabled()
    assert state["polls"] == polls, "Completed workbook should download immediately"
    state["failure"] = True
    page.get_by_role("button", name="Export", exact=True).click()
    page.get_by_text("Synthetic export failure", exact=True).wait_for()
    expect(page.get_by_role("button", name="Run Recommendation", exact=True)).to_be_enabled()
    expect(page.get_by_role("button", name="Export", exact=True)).to_be_enabled()
    assert state["downloads"] == 2
    assert not any(path in ("kpi/recommendation/export", "kpi/recommendation/run") for _, path in calls)
    assert not errors, errors
    page.screenshot(path=str(output / "rca-export-failure.png"), full_page=True)
    (output / "rca-export-browser-checks.json").write_text(json.dumps({"checks": ["authenticated async jobs", "progress", "run disabled during export", "download", "cached download", "failed job releases buttons", "no legacy rerun"], "pageErrors": errors, "customerDatabaseWrites": 0}, indent=2))
    browser.close()
print("RCA export browser checks passed")

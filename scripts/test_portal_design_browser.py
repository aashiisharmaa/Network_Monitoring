"""Shared navigation and dashboard UX checks using synthetic, intercepted APIs."""
import json
import os
from pathlib import Path
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "reports/performance/project-review-2026-11-18"
OUTPUT.mkdir(parents=True, exist_ok=True)
calls = []
BASE_URL = os.environ.get("PORTAL_TEST_BASE_URL", "http://localhost:4173").rstrip("/")

def api(route):
    request = route.request
    path = request.url.split("localhost:3001/")[-1].split("?")[0]
    path = path.removeprefix("api/v1/")
    headers = {"Access-Control-Allow-Origin": BASE_URL, "Access-Control-Allow-Headers": "Authorization, Content-Type", "Access-Control-Allow-Methods": "GET,POST,OPTIONS"}
    if request.method == "OPTIONS":
        route.fulfill(status=204, headers=headers)
        return
    calls.append(path)
    data = {}
    if path == "health": data = {"status": "UP", "timestamp": "2026-11-10T08:00:00Z"}
    elif path == "upload/uploads/history": data = [{"id": 500, "fileName": "Synthetic daily KPI", "remarks": "[kpi]", "createdAt": "2026-11-10T01:00:00Z"}]
    elif path == "dashboard/stats": data = {"totalCells": 120, "totalSites": 40, "totalBands": 3, "totalTech": 2, "totalSectors": 120}
    elif path == "dashboard/stats/performance": data = {"averages": {"rrcSuccessRate": 90}, "peaks": {}}
    elif path == "setting/getsettings": data = {"RRC_Establishment_Success_Rate": 95, "rrcSuccessRate": 95, "thresholdOperators": {"rrcSuccessRate": ">="}}
    elif path == "kpi/dynamic/summary":
        route.fulfill(json={"success": True, "metrics": [{"key": "rrcSuccessRate", "label": "RRC Setup SR (%)", "statistics": {"average": 90, "count": 120}}]}, headers=headers)
        return
    elif "threshold" in path: data = []
    elif "compan" in path: data = [{"id": 10, "companyName": "Synthetic company"}]
    route.fulfill(json={"success": True, "data": data}, headers=headers)

with sync_playwright() as p:
    browser = p.chromium.launch(channel="chrome", headless=True)
    context = browser.new_context(viewport={"width": 1440, "height": 1000})
    context.add_init_script("localStorage.setItem('authToken','synthetic-token');localStorage.setItem('user',JSON.stringify({id:1,role:'SUPER_ADMIN',companyId:10,email:'qa@example.com'}));localStorage.setItem('globalSelectedKpiFileId:v1','500');")
    context.route("http://localhost:3001/**", api)
    page = context.new_page()
    errors = []
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.goto(BASE_URL + "/")
    expect(page.get_by_role("heading", name="Network Dashboard", exact=True)).to_be_visible()
    expect(page.get_by_role("heading", name="Performance Metrics", exact=True)).to_be_visible()
    expect(page.locator(".portal-heading-inner")).to_have_css("display", "flex")
    expect(page.locator(".portal-inventory-grid")).to_have_css("display", "grid")
    assert page.locator(".portal-inventory-card").count() >= 5, "Inventory cards are missing"
    expect(page.get_by_text("Service online", exact=True)).to_be_visible()
    expect(page.get_by_role("link", name="Daily Reports", exact=True)).to_have_attribute("href", "/reports")
    expect(page.get_by_role("link", name="Worst Cell Analysis", exact=True)).to_be_visible()
    page.screenshot(path=str(OUTPUT / "portal-design-dashboard-desktop.png"))
    page.keyboard.press("Control+k")
    search = page.get_by_role("combobox", name="Search pages", exact=True)
    expect(search).to_be_focused()
    page.keyboard.press("ArrowDown")
    expect(page.get_by_role("listbox", name="Pages", exact=True).get_by_role("option").nth(1)).to_have_attribute("aria-selected", "true")
    search.fill("not-a-page-123")
    expect(page.get_by_text("No matching pages", exact=True)).to_be_visible()
    page.get_by_role("button", name="Clear search", exact=True).click()
    expect(search).to_be_focused()
    search.fill("daily reports")
    expect(page.get_by_role("listbox", name="Pages", exact=True).get_by_role("option")).to_have_count(1)
    page.screenshot(path=str(OUTPUT / "portal-quick-navigation-desktop.png"))
    search.fill("Dashboard")
    page.keyboard.press("Enter")
    expect(page.get_by_role("dialog")).to_have_count(0)
    expect(page).to_have_title("Dashboard | Network Monitor")
    page.get_by_role("button", name="Collapse sidebar", exact=True).click()
    expect(page.get_by_role("button", name="Expand sidebar", exact=True)).to_be_visible()
    page.reload()
    expect(page.get_by_role("button", name="Expand sidebar", exact=True)).to_be_visible()
    expect(page.get_by_role("heading", name="Network Dashboard", exact=True)).to_be_visible()
    expect(page.get_by_role("heading", name="Performance Metrics", exact=True)).to_be_visible()
    page.set_viewport_size({"width": 390, "height": 844})
    page.wait_for_timeout(250)
    expect(page.get_by_role("button", name="Open navigation", exact=True)).to_be_visible()
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1"), "Dashboard mobile overflow"
    page.screenshot(path=str(OUTPUT / "portal-design-dashboard-mobile.png"))
    page.get_by_role("button", name="Open navigation", exact=True).click()
    expect(page.get_by_role("link", name="Daily Reports", exact=True)).to_be_visible()
    page.keyboard.press("Escape")
    expect(page.get_by_role("button", name="Open navigation", exact=True)).to_be_focused()
    page.get_by_role("button", name="Find a page", exact=True).click()
    search = page.get_by_role("combobox", name="Search pages", exact=True)
    expect(search).to_be_focused()
    search.fill("map")
    expect(page.get_by_role("listbox", name="Pages", exact=True).get_by_role("option")).to_have_count(1)
    page.screenshot(path=str(OUTPUT / "portal-quick-navigation-mobile.png"))
    assert page.get_by_role("dialog").evaluate("element => element.getBoundingClientRect().right <= innerWidth"), "Search modal overflows mobile viewport"
    page.keyboard.press("Escape")
    expect(page.get_by_role("button", name="Find a page", exact=True)).to_be_focused()
    assert not errors, errors
    # Client navigation retains only permitted routes and useful names.
    client = browser.new_context(viewport={"width": 1280, "height": 900})
    client.add_init_script("localStorage.setItem('authToken','synthetic-token');localStorage.setItem('user',JSON.stringify({id:2,role:'USER',companyId:10}));")
    client.route("http://localhost:3001/**", api)
    client_page = client.new_page()
    client_page.goto(BASE_URL + "/")
    expect(client_page.get_by_role("link", name="Daily Reports", exact=True)).to_be_visible()
    expect(client_page.get_by_role("link", name="Admin Management", exact=True)).to_have_count(0)
    # Worst-cell analysis is a shared analysis route in the current application;
    # administrative account management remains super-admin only.
    expect(client_page.get_by_role("link", name="Worst Cell Analysis", exact=True)).to_be_visible()
    client_page.keyboard.press("Control+k")
    client_page.get_by_role("combobox", name="Search pages", exact=True).fill("Admin Management")
    expect(client_page.get_by_role("listbox", name="Pages", exact=True).get_by_role("option")).to_have_count(0)
    browser.close()
    (OUTPUT / "portal-design-checks.json").write_text(json.dumps({"checks": ["dashboard performance, inventory and report navigation", "desktop grouped navigation", "persisted sidebar preference", "mobile layout without horizontal overflow", "mobile Escape and focus restoration", "client role navigation", "Ctrl+K page search and keyboard selection", "empty search recovery", "mobile search modal and focus restoration", "role-filtered search results", "route document title"], "runtimeErrors": errors, "customerDatabaseWrites": 0}, indent=2))
    print("Portal design dashboard and navigation checks passed")

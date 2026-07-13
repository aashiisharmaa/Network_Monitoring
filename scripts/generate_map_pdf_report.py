from __future__ import annotations

import math
import re
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pandas as pd
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4, landscape
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import inch
from reportlab.platypus import (
    Image,
    KeepTogether,
    PageBreak,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)


ROOT = Path(__file__).resolve().parents[1]
SAMPLE_DATA = ROOT / "sample-data"
REPORT_DIR = ROOT / "reports"
ASSET_DIR = REPORT_DIR / "map_report_assets"
PHYSICAL_SITE_DB = Path(r"C:\Users\Vinfocom\Downloads\Physical_sitedb_Rw.csv")
KPI_FILE = SAMPLE_DATA / "physical_sitedb_generated_kpi.csv"
TA_FILE = SAMPLE_DATA / "physical_sitedb_generated_ta.csv"
LB_OUTPUT = SAMPLE_DATA / "physical_sitedb_generated_lb_output.xlsx"
PDF_OUT = REPORT_DIR / "Coverage_Map_and_LB_WCF_Prediction_Report.pdf"

SECTOR_COLORS = {
    "A": "#3B82F6",
    "B": "#10B981",
    "C": "#F97316",
    "D": "#EC4899",
}
BAND_COLORS = {
    "L01": "#2563EB",
    "L03": "#10B981",
    "L08": "#F97316",
}
ACTION_COLORS = {
    "LOAD_BALANCE": "#2563EB",
    "QUALITY_CHECK": "#DC2626",
    "CAPACITY_REVIEW": "#7C3AED",
    "COVERAGE_CHECK": "#D97706",
    "OBSERVE": "#059669",
}
DISTANCE_COLUMNS = [
    "Till 0.08 Km",
    "0.08Km-0.23Km",
    "0.23Km-0.63Km",
    "0.63Km-1.01Km",
    "1.01Km-1.64Km",
    "1.64Km-2.42Km",
    "2.42Km-3.36Km",
    "3.36Km-4.45Km",
    "4.45Km-5.7Km",
    "5.7Km-7.18Km",
    "7.18Km-8.74Km",
    "8.74Km-10.5Km",
    "10.5Km-12.5Km",
    "12.5Km-14.6Km",
    "14.6Km-16.9Km",
]


def parse_cell(cell: str) -> tuple[str, str, str, str]:
    match = re.match(r"^(.+?)_([A-Za-z])(\d{1,3})(?:_?([A-Za-z0-9]+))?$", str(cell or ""))
    if not match:
        return "", "", "", ""
    site, tech_code, band_code, sector = match.groups()
    tech_code = tech_code.upper()
    tech = {"L": "LTE", "N": "NR", "G": "GSM", "U": "UMTS", "W": "WCDMA"}.get(tech_code, tech_code)
    return site, tech, f"{tech_code}{int(band_code):02d}", sector[-1:].upper() if sector else ""


def classify_lb_row(row: pd.Series) -> str:
    recs = f"{row.get('ML_Recommendations', '')} {row.get('Recommendations', '')}".lower()
    coverage = str(row.get("coverage_category", "")).lower()
    near = float(row.get("%coverage_upto_2km", 100) or 100)
    far = float(row.get("%coverage_6km_plus", 0) or 0)
    hosr = float(row.get("Inter_freq_HOSR%", 100) or 100)
    rrc_sr = float(row.get("RRC_SR%", 100) or 100)
    cqi = float(row.get("CQI Avg", 15) or 15)
    availability = float(row.get("Cell Availability %", 100) or 100)
    prb = float(row.get("PRB DL Utilization %", 0) or 0)
    traffic = float(row.get("Total Traffic(GB)", 0) or 0)
    users = float(row.get("Avg_number_of_RRC_users", 0) or 0)
    unbalanced = "not" in str(row.get("Band_Unbalanced", "")).lower()

    if hosr < 95 or rrc_sr < 98 or cqi < 9 or availability < 99 or any(
        token in recs for token in ["quality", "hosr", "rrc", "drop", "availability", "hysteresis", "threshxhigh"]
    ):
        return "QUALITY_CHECK"
    if "under" in coverage or "over" in coverage or near < 50 or far > 25 or any(
        token in recs for token in ["coverage", "antenna", "tilt", "threshxlow", "a5threshold1"]
    ):
        return "COVERAGE_CHECK"
    if unbalanced or prb >= 85 or any(token in recs for token in ["prb", "scheduler", "loadbalancing", "offload"]):
        return "LOAD_BALANCE"
    if traffic >= 1.8 or users >= 32 or any(token in recs for token in ["capacity", "traffic", "user"]):
        return "CAPACITY_REVIEW"
    return "OBSERVE"


def make_site_dataframe() -> pd.DataFrame:
    site_df = pd.read_csv(PHYSICAL_SITE_DB)
    parsed = site_df["4G Cell Name"].apply(parse_cell)
    site_df["Parsed Site"] = parsed.apply(lambda item: item[0])
    site_df["Technology"] = parsed.apply(lambda item: item[1])
    site_df["Cell Band"] = parsed.apply(lambda item: item[2])
    site_df["Sector"] = parsed.apply(lambda item: item[3])
    site_df["Site"] = site_df["4G Site Name"].fillna(site_df["Parsed Site"])
    site_df["Latitude"] = pd.to_numeric(site_df["Latitude"], errors="coerce")
    site_df["Longitude"] = pd.to_numeric(site_df["Longitude"], errors="coerce")
    site_df["Azimuth"] = pd.to_numeric(site_df["Azimuth"], errors="coerce").fillna(0)
    return site_df.dropna(subset=["Latitude", "Longitude"])


def draw_sector_triangle(ax, lon: float, lat: float, azimuth: float, sector: str, scale: float = 0.0022) -> None:
    angle = math.radians(90 - azimuth)
    spread = math.radians(30)
    points = [(lon, lat)]
    for offset in (-spread, spread):
        points.append((lon + math.cos(angle + offset) * scale, lat + math.sin(angle + offset) * scale))
    color = SECTOR_COLORS.get(sector, "#64748B")
    polygon = plt.Polygon(points, closed=True, facecolor=color, edgecolor=color, alpha=0.28, linewidth=0.8)
    ax.add_patch(polygon)
    ax.scatter([lon], [lat], s=8, color=color, edgecolors="white", linewidths=0.4, zorder=3)


def build_charts(site_df: pd.DataFrame, kpi_df: pd.DataFrame, ta_df: pd.DataFrame, lb_df: pd.DataFrame) -> dict[str, Path]:
    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    chart_paths: dict[str, Path] = {}

    plt.style.use("seaborn-v0_8-whitegrid")

    fig, ax = plt.subplots(figsize=(11, 7))
    for sector, group in site_df.groupby("Sector"):
        ax.scatter(
            group["Longitude"],
            group["Latitude"],
            s=9,
            label=f"Sector {sector}",
            color=SECTOR_COLORS.get(sector, "#64748B"),
            alpha=0.72,
        )
    ax.set_title("Physical Site DB: plotted cells by sector", fontsize=15, weight="bold")
    ax.set_xlabel("Longitude")
    ax.set_ylabel("Latitude")
    ax.legend(ncol=4, fontsize=8)
    ax.text(
        0.01,
        0.01,
        f"{site_df['Site'].nunique():,} sites | {len(site_df):,} plotted cells",
        transform=ax.transAxes,
        fontsize=9,
        bbox=dict(facecolor="white", alpha=0.85, edgecolor="#CBD5E1"),
    )
    fig.tight_layout()
    path = ASSET_DIR / "site_scatter_by_sector.png"
    fig.savefig(path, dpi=180)
    plt.close(fig)
    chart_paths["site_scatter"] = path

    sample_sites = site_df.drop_duplicates("Site").head(18)["Site"].tolist()
    sample_cells = site_df[site_df["Site"].isin(sample_sites)].head(80)
    fig, ax = plt.subplots(figsize=(11, 7))
    for _, row in sample_cells.iterrows():
        draw_sector_triangle(ax, row["Longitude"], row["Latitude"], row["Azimuth"], row["Sector"])
    ax.set_title("Sector fan preview: azimuth-based triangles", fontsize=15, weight="bold")
    ax.set_xlabel("Longitude")
    ax.set_ylabel("Latitude")
    ax.set_aspect("equal", adjustable="box")
    legend_handles = [
        plt.Line2D([0], [0], marker="s", color="w", label=f"Sector {sector}", markerfacecolor=color, markersize=10)
        for sector, color in SECTOR_COLORS.items()
    ]
    ax.legend(handles=legend_handles, ncol=4, fontsize=8)
    fig.tight_layout()
    path = ASSET_DIR / "sector_fan_preview.png"
    fig.savefig(path, dpi=180)
    plt.close(fig)
    chart_paths["sector_fans"] = path

    fig, ax = plt.subplots(figsize=(10, 5))
    action_counts = lb_df["Prediction Type"].value_counts().reindex(ACTION_COLORS.keys()).dropna()
    ax.bar(
        [item.replace("_", " ").title() for item in action_counts.index],
        action_counts.values,
        color=[ACTION_COLORS[item] for item in action_counts.index],
    )
    ax.set_title("LB/WCF prediction distribution", fontsize=14, weight="bold")
    ax.set_ylabel("Rows")
    ax.tick_params(axis="x", rotation=20)
    for index, value in enumerate(action_counts.values):
        ax.text(index, value, f"{int(value):,}", ha="center", va="bottom", fontsize=9, weight="bold")
    fig.tight_layout()
    path = ASSET_DIR / "prediction_distribution.png"
    fig.savefig(path, dpi=180)
    plt.close(fig)
    chart_paths["prediction_distribution"] = path

    band_counts = kpi_df["Band"].value_counts().sort_index()
    fig, ax = plt.subplots(figsize=(9, 5))
    ax.pie(
        band_counts.values,
        labels=[f"{band} ({count:,})" for band, count in band_counts.items()],
        colors=[BAND_COLORS.get(band, "#64748B") for band in band_counts.index],
        startangle=90,
        autopct="%1.1f%%",
        textprops={"fontsize": 9},
    )
    ax.set_title("KPI rows by EARFCN-derived band", fontsize=14, weight="bold")
    fig.tight_layout()
    path = ASSET_DIR / "band_distribution.png"
    fig.savefig(path, dpi=180)
    plt.close(fig)
    chart_paths["band_distribution"] = path

    coverage_summary = ta_df.groupby("Band")[DISTANCE_COLUMNS].mean()
    near = coverage_summary[DISTANCE_COLUMNS[:5]].sum(axis=1)
    mid = coverage_summary[DISTANCE_COLUMNS[5:9]].sum(axis=1)
    far = coverage_summary[DISTANCE_COLUMNS[9:]].sum(axis=1)
    fig, ax = plt.subplots(figsize=(10, 5))
    ax.bar(coverage_summary.index, near, label="Near <= 1.64km", color="#2563EB")
    ax.bar(coverage_summary.index, mid, bottom=near, label="Mid 1.64-5.7km", color="#10B981")
    ax.bar(coverage_summary.index, far, bottom=near + mid, label="Far > 5.7km", color="#F97316")
    ax.set_ylim(0, 100)
    ax.set_ylabel("Average TA distribution %")
    ax.set_title("TA distance profile by band", fontsize=14, weight="bold")
    ax.legend(fontsize=8)
    fig.tight_layout()
    path = ASSET_DIR / "ta_coverage_profile.png"
    fig.savefig(path, dpi=180)
    plt.close(fig)
    chart_paths["ta_profile"] = path

    return chart_paths


def table(data, widths=None, header=True) -> Table:
    item = Table(data, colWidths=widths, repeatRows=1 if header else 0)
    item.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#0F172A") if header else colors.white),
                ("TEXTCOLOR", (0, 0), (-1, 0), colors.white if header else colors.HexColor("#0F172A")),
                ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
                ("FONTSIZE", (0, 0), (-1, -1), 8),
                ("GRID", (0, 0), (-1, -1), 0.25, colors.HexColor("#CBD5E1")),
                ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F8FAFC")]),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("LEFTPADDING", (0, 0), (-1, -1), 6),
                ("RIGHTPADDING", (0, 0), (-1, -1), 6),
                ("TOPPADDING", (0, 0), (-1, -1), 5),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
            ]
        )
    )
    return item


def page_footer(canvas, doc):
    canvas.saveState()
    canvas.setFont("Helvetica", 8)
    canvas.setFillColor(colors.HexColor("#64748B"))
    canvas.drawString(0.55 * inch, 0.38 * inch, "Coverage Map and LB/WCF Prediction Report")
    canvas.drawRightString(A4[0] - 0.55 * inch, 0.38 * inch, f"Page {doc.page}")
    canvas.restoreState()


def build_pdf() -> Path:
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    site_df = make_site_dataframe()
    kpi_df = pd.read_csv(KPI_FILE)
    ta_df = pd.read_csv(TA_FILE)
    lb_df = pd.read_excel(LB_OUTPUT)
    lb_df["Prediction Type"] = lb_df.apply(classify_lb_row, axis=1)

    charts = build_charts(site_df, kpi_df, ta_df, lb_df)

    styles = getSampleStyleSheet()
    styles.add(ParagraphStyle(name="TitleCenter", parent=styles["Title"], alignment=TA_CENTER, textColor=colors.HexColor("#0F172A")))
    styles.add(ParagraphStyle(name="Section", parent=styles["Heading2"], textColor=colors.HexColor("#1D4ED8"), spaceBefore=12, spaceAfter=6))
    styles.add(ParagraphStyle(name="BodyTight", parent=styles["BodyText"], leading=13, spaceAfter=6, alignment=TA_LEFT))
    styles.add(ParagraphStyle(name="Small", parent=styles["BodyText"], fontSize=8, leading=10, textColor=colors.HexColor("#475569")))

    doc = SimpleDocTemplate(
        str(PDF_OUT),
        pagesize=A4,
        rightMargin=0.55 * inch,
        leftMargin=0.55 * inch,
        topMargin=0.55 * inch,
        bottomMargin=0.65 * inch,
        title="Coverage Map and LB/WCF Prediction Report",
    )

    story = []
    story.append(Paragraph("Coverage Map and LB/WCF Prediction Report", styles["TitleCenter"]))
    story.append(Paragraph("Network Monitoring Map Module", styles["Heading3"]))
    story.append(
        Paragraph(
            "This report documents the map features implemented in the application and summarizes the plotted physical site, sector, KPI, TA, and LB/WCF prediction data.",
            styles["BodyTight"],
        )
    )
    story.append(Spacer(1, 0.12 * inch))

    summary_data = [
        ["Metric", "Value"],
        ["Source physical site rows", f"{len(site_df):,}"],
        ["Unique plotted sites", f"{site_df['Site'].nunique():,}"],
        ["KPI rows generated", f"{len(kpi_df):,}"],
        ["TA rows generated", f"{len(ta_df):,}"],
        ["Bands", ", ".join(sorted(kpi_df["Band"].dropna().astype(str).unique()))],
        ["Sectors", ", ".join(sorted(kpi_df["Sector"].dropna().astype(str).unique()))],
        ["LB/WCF output rows", f"{len(lb_df):,}"],
        ["Not balanced rows", f"{(lb_df['Band_Unbalanced'].astype(str).str.contains('Not', case=False, na=False)).sum():,}"],
        ["Balanced rows", f"{(lb_df['Band_Unbalanced'].astype(str).str.contains('Balanced', case=False, na=False) & ~lb_df['Band_Unbalanced'].astype(str).str.contains('Not', case=False, na=False)).sum():,}"],
    ]
    story.append(table(summary_data, widths=[2.45 * inch, 4.5 * inch]))

    story.append(Paragraph("Implemented Map Features", styles["Section"]))
    feature_rows = [
        ["Feature", "What it does"],
        ["Filter panel", "Separates site upload and KPI upload files, with filters for technology, band, and PCI."],
        ["Map layers", "Independent toggles for cells, worst cells/sites, prediction cells, and alarm cells."],
        ["Floating legends", "Dynamic map legends for sectors, prediction types, worst cells, alarms, and cell layer counts."],
        ["Site legend", "Floating draggable/resizable site legend with collapsed/dropdown behavior and click-to-highlight site selection."],
        ["Utility controls", "Controls for sector triangle size and site/cell radius scaling."],
        ["Analytics drawer tabs", "Overview, Prediction, Worst Cells, and Alarm Cells tabs for cleaner analytics navigation."],
        ["Prediction on map", "LB/WCF predictions run from the map and render as colored markers by recommendation type."],
        ["Worst cell map view", "Worst-cell markers appear only when the worst layer is enabled."],
    ]
    story.append(table(feature_rows, widths=[1.65 * inch, 5.3 * inch]))

    story.append(Paragraph("Upload and Prediction Flow", styles["Section"]))
    flow_rows = [
        ["Step", "File / Action", "Purpose"],
        ["1", "Upload physical site DB as Site Data", "Plots real cell locations, sectors, azimuth triangles, PCI, band, and technology."],
        ["2", "Upload physical_sitedb_generated_kpi.csv as KPI Data", "Provides KPI records for worst cells, prediction, alarms, and LB/WCF."],
        ["3", "Optionally select physical_sitedb_generated_ta.csv in LB/WCF", "Adds TA distance features for coverage and overlap analysis."],
        ["4", "Run LB/WCF on Map", "Generates balanced/not-balanced rows and recommendation categories."],
        ["5", "Turn Prediction layer ON", "Displays colored prediction markers and dynamic legend counts."],
    ]
    story.append(table(flow_rows, widths=[0.45 * inch, 2.55 * inch, 3.95 * inch]))

    story.append(PageBreak())
    story.append(Paragraph("Map Plot: Sites and Sectors", styles["Section"]))
    story.append(Paragraph("The following plot uses the physical site database latitude/longitude and colors each cell by parsed sector.", styles["BodyTight"]))
    story.append(Image(str(charts["site_scatter"]), width=7.0 * inch, height=4.45 * inch))
    story.append(Spacer(1, 0.12 * inch))
    story.append(Paragraph("Sector fan preview uses azimuth to draw sector triangles similar to the application map layer.", styles["BodyTight"]))
    story.append(Image(str(charts["sector_fans"]), width=7.0 * inch, height=4.45 * inch))

    story.append(PageBreak())
    story.append(Paragraph("KPI, TA, and Prediction Summary", styles["Section"]))
    story.append(Image(str(charts["band_distribution"]), width=6.7 * inch, height=3.75 * inch))
    story.append(Image(str(charts["ta_profile"]), width=6.9 * inch, height=3.45 * inch))
    story.append(Paragraph("Prediction categories are inferred from the LB/WCF output using PRB, traffic/users, quality KPI, and coverage/TA evidence.", styles["Small"]))
    story.append(Image(str(charts["prediction_distribution"]), width=6.9 * inch, height=3.5 * inch))

    story.append(PageBreak())
    story.append(Paragraph("Top Prediction Evidence", styles["Section"]))
    top = (
        lb_df.sort_values(["ML_Probability", "PRB DL Utilization %"], ascending=False)
        .head(18)
        .copy()
    )
    top_rows = [["Site", "Cell", "Band", "Sector", "Type", "Balance", "PRB", "DL THP", "ML Prob"]]
    for _, row in top.iterrows():
        top_rows.append(
            [
                str(row.get("Site", ""))[:16],
                str(row.get("Cell", ""))[:20],
                str(row.get("Band", "")),
                str(row.get("Sector_x", "")),
                str(row.get("Prediction Type", "")).replace("_", " "),
                str(row.get("Band_Unbalanced", "")),
                f"{float(row.get('PRB DL Utilization %', 0) or 0):.1f}",
                f"{float(row.get('DL_throughput', 0) or 0):.1f}",
                f"{float(row.get('ML_Probability', 0) or 0):.2f}",
            ]
        )
    story.append(table(top_rows, widths=[0.8 * inch, 1.25 * inch, 0.45 * inch, 0.45 * inch, 1.0 * inch, 1.0 * inch, 0.55 * inch, 0.6 * inch, 0.55 * inch]))

    story.append(Paragraph("Notes and Limitations", styles["Section"]))
    notes = [
        "The generated KPI and TA files are derived from the physical site database, with synthetic KPI values created for testing LB/WCF behavior.",
        "TA is optional. Without TA, coverage and distance-based predictions are limited because the backend cannot compute distance buckets or coverage overlap.",
        "The current handover support is basic: Inter-frequency HOSR and mobility-threshold recommendations are included, but a full source-target neighbor handover module requires additional handover counter data.",
        "For map performance, the UI can count all predictions while rendering only the highest-priority prediction markers on the map.",
    ]
    for note in notes:
        story.append(Paragraph(f"- {note}", styles["BodyTight"]))

    doc.build(story, onFirstPage=page_footer, onLaterPages=page_footer)
    return PDF_OUT


if __name__ == "__main__":
    output = build_pdf()
    print(output)

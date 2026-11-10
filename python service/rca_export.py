"""Full scored-frame cache and single-pass streaming workbook generation (no RCA rerun)."""
import gzip
import json
from datetime import datetime
from pathlib import Path
from openpyxl import Workbook
from openpyxl.cell import WriteOnlyCell
from openpyxl.styles import Font, PatternFill, Alignment

MAX_BUNDLE_BYTES = 256 * 1024 * 1024
TABLES = [("Anomaly_Details", "anomaly_details"), ("All_Data_Scored", "all_scored"),
          ("Root_Cause_Summary", "summary_root"), ("Top_Impacted_Cells", "summary_cells"),
          ("Date_Trend", "summary_dates"), ("Rules_Thresholds", "rules_df")]


def write_bundle(path, captured, metadata):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with gzip.open(path, "wt", encoding="utf-8", compresslevel=1) as out:
        def write(text):
            nonlocal count
            count += len(text.encode("utf-8"))
            if count > MAX_BUNDLE_BYTES:
                raise ValueError("Full RCA export data exceeds the configured 256 MiB cache limit.")
            out.write(text)
        write('{"version":1,"metadata":')
        write(json.dumps(metadata, default=str, ensure_ascii=False))
        write(',"periods":[')
        for index, (period, frames) in enumerate(captured):
            if index:
                write(',')
            write('{"period":' + json.dumps(period) + ',"tables":{')
            for table_index, (_, key) in enumerate(TABLES):
                if table_index:
                    write(',')
                write(json.dumps(key) + ':')
                write(frames[key].to_json(orient="split", date_format="iso", double_precision=15))
            write('}}')
        write(']}')


def read_bundle(path):
    with gzip.open(path, "rb") as stream:
        raw = stream.read(MAX_BUNDLE_BYTES + 1)
    if len(raw) > MAX_BUNDLE_BYTES:
        raise ValueError("RCA export data exceeds its decompressed size limit.")
    bundle = json.loads(raw)
    if bundle.get("version") != 1 or not bundle.get("periods"):
        raise ValueError("Unsupported or empty RCA analysis cache.")
    return bundle


def write_workbook(bundle, path, recommendation, parameter_guide):
    """Write all cached scored rows, not the UI's response-limited anomaly list."""
    book = Workbook(write_only=True)
    header_font = Font(bold=True, color="FFFFFF")
    header_fill = PatternFill("solid", fgColor="142B43")
    wrap_alignment = Alignment(vertical="top", wrap_text=True)
    metadata = bundle["metadata"]
    vendor = metadata.get("vendor", "nokia")
    memo = {}

    def append(sheet, values, header=False, columns=None):
        cells = []
        for index, value in enumerate(values):
            if isinstance(value, (dict, list)):
                value = json.dumps(value, ensure_ascii=False, default=str)
            cell = WriteOnlyCell(sheet, value=value)
            # Labels, cell IDs and source text must never become spreadsheet formulas.
            if isinstance(value, str):
                if len(value) > 32767:
                    raise ValueError("An RCA export cell exceeds Excel's text limit.")
                if columns and columns[index] == "Date":
                    try:
                        cell.value = datetime.fromisoformat(value.replace("Z", "+00:00")).replace(tzinfo=None)
                        cell.number_format = "yyyy-mm-dd"
                    except ValueError:
                        cell.data_type = "s"
                else:
                    cell.data_type = "s"
            if header:
                cell.font, cell.fill = header_font, header_fill
                cell.alignment = wrap_alignment
            elif isinstance(value, str) and ("\n" in value or len(value) > 100):
                cell.alignment = wrap_alignment
            cells.append(cell)
        sheet.append(cells)

    def sheet(name, columns):
        target = book.create_sheet(name[:31])
        target.freeze_panes = "A2"
        target.sheet_view.showGridLines = False
        from openpyxl.utils import get_column_letter
        for index, column in enumerate(columns, 1):
            target.column_dimensions[get_column_letter(index)].width = 58 if any(word in str(column) for word in ("Recommendation", "Explanation", "Evidence", "Parameters", "Notes", "Note")) else 22
        append(target, columns, header=True)
        return target

    try:
        manifest = sheet("Export_Info", ["Setting", "Value"])
        for key, value in metadata.items():
            append(manifest, [key, value])
        append(manifest, ["Scope", "All analyzed rows after the configured input row limit/sampling and date filters; UI detail limits do not truncate scored sheets."])
        append(manifest, ["Analysis reuse", "Built from captured scored frames; RCA is not executed while rendering this workbook."])
        for period in bundle["periods"]:
            prefix = period["period"] + "_" if len(bundle["periods"]) > 1 else ""
            for title, key in TABLES:
                table = period["tables"][key]
                columns = table["columns"]
                extra = ["RCA Explanation", "Vendor Recommendation", "Recommended Parameters", "Recommended Values", "Implementation Notes"] if key == "anomaly_details" else []
                headers = columns + [key for key in extra if key not in columns]
                target = sheet(prefix + title, headers)
                for values in table["data"]:
                    if extra:
                        row = dict(zip(columns, values))
                        identity = tuple(str(row.get(key)) for key in ("Primary Cause", "Secondary Causes", "Evidence", "Top KPI Drivers", "Nokia Recommendation"))
                        rec = memo.get(identity)
                        if rec is None:
                            rec = recommendation(vendor, row)
                            if len(memo) < 4096:
                                memo[identity] = rec
                        values = [row.get(column, rec.get(column)) for column in headers]
                    append(target, values, columns=headers)
                from openpyxl.utils import get_column_letter
                target.auto_filter.ref = f"A1:{get_column_letter(len(headers))}{len(table['data']) + 1}"
        guide = parameter_guide() if vendor == "nokia" else []
        if guide:
            columns = list(guide[0])
            target = sheet("Parameter_Guide", columns)
            for row in guide:
                append(target, [row.get(column) for column in columns])
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        book.save(path)
    finally:
        book.close()

from pathlib import Path
import re

from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_TABLE_ALIGNMENT, WD_CELL_VERTICAL_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "CLIENT_HANDOVER_DOCUMENT.md"
OUTPUT = ROOT / "CLIENT_HANDOVER_DOCUMENT.docx"


def set_cell_shading(cell, fill):
    properties = cell._tc.get_or_add_tcPr()
    shading = OxmlElement("w:shd")
    shading.set(qn("w:fill"), fill)
    properties.append(shading)


def set_cell_text(cell, value, bold=False):
    cell.text = ""
    paragraph = cell.paragraphs[0]
    run = paragraph.add_run(value)
    run.bold = bold
    run.font.size = Pt(9)
    cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER


def add_table(document, rows):
    usable = []
    for row in rows:
        cells = [part.strip() for part in row.strip().strip("|").split("|")]
        if cells and not all(re.fullmatch(r":?-{3,}:?", cell) for cell in cells):
            usable.append(cells)
    if not usable:
        return
    columns = max(len(row) for row in usable)
    table = document.add_table(rows=len(usable), cols=columns)
    table.style = "Table Grid"
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    for row_index, row in enumerate(usable):
        if row_index == 0:
            repeat = OxmlElement("w:tblHeader")
            table.rows[row_index]._tr.get_or_add_trPr().append(repeat)
        for col_index in range(columns):
            value = row[col_index] if col_index < len(row) else ""
            set_cell_text(table.cell(row_index, col_index), value, row_index == 0)
            if row_index == 0:
                set_cell_shading(table.cell(row_index, col_index), "D9EAF7")
    document.add_paragraph()


def add_code(document, lines):
    paragraph = document.add_paragraph()
    paragraph.style = "No Spacing"
    paragraph.paragraph_format.left_indent = Inches(0.25)
    for index, line in enumerate(lines):
        run = paragraph.add_run(line)
        run.font.name = "Consolas"
        run.font.size = Pt(8)
        if index < len(lines) - 1:
            run.add_break()


def add_body(document, line):
    line = re.sub(r"\*\*(.*?)\*\*", r"\1", line)
    line = re.sub(r"`([^`]*)`", r"\1", line)
    if line.startswith("- "):
        document.add_paragraph(line[2:], style="List Bullet")
    elif re.match(r"^\d+\. ", line):
        document.add_paragraph(re.sub(r"^\d+\. ", "", line), style="List Number")
    else:
        document.add_paragraph(line)


def convert():
    source_lines = SOURCE.read_text(encoding="utf-8").splitlines()
    document = Document()
    section = document.sections[0]
    section.top_margin = Inches(0.65)
    section.bottom_margin = Inches(0.65)
    section.left_margin = Inches(0.75)
    section.right_margin = Inches(0.75)

    styles = document.styles
    styles["Normal"].font.name = "Aptos"
    styles["Normal"].font.size = Pt(10)
    styles["Normal"].paragraph_format.space_after = Pt(6)
    styles["Title"].font.name = "Aptos Display"
    styles["Title"].font.color.rgb = RGBColor(23, 55, 94)
    for style_name in ("Heading 1", "Heading 2", "Heading 3"):
        styles[style_name].font.name = "Aptos Display"
        styles[style_name].font.color.rgb = RGBColor(23, 55, 94)

    footer = section.footer.paragraphs[0]
    footer.alignment = WD_ALIGN_PARAGRAPH.CENTER
    footer_run = footer.add_run("Network KPI Monitoring | Client Handover Document")
    footer_run.font.size = Pt(8)
    footer_run.font.color.rgb = RGBColor(100, 100, 100)
    footer.add_run(" | Page ")
    field = OxmlElement("w:fldSimple")
    field.set(qn("w:instr"), "PAGE")
    footer._p.append(field)

    document.add_paragraph("NETWORK KPI MONITORING", style="Subtitle")
    document.add_paragraph("Client & Management\nHandover Report", style="Title")
    document.add_paragraph("Features • Operations • Technical Reference • Visual Evidence", style="Subtitle")
    document.add_paragraph("Prepared: 29 October 2026 | Version 1.4")
    document.add_paragraph("Submission copy — acceptance and sign-off pending")
    document.add_paragraph("Prepared for: Client representatives, project managers and operations teams")
    document.add_paragraph("This report describes the delivered implementation, operating procedures and verification evidence. Formal acceptance remains with the receiving team.")
    document.add_page_break()
    document.add_heading("Document contents", level=1)
    for source_line in source_lines:
        if source_line.startswith("## "):
            document.add_paragraph(source_line[3:], style="Normal")
    document.add_page_break()

    index = 0
    first_title = True
    while index < len(source_lines):
        line = source_lines[index].strip()
        if not line or line == "---":
            index += 1
            continue
        if line.startswith("!["):
            match = re.fullmatch(r"!\[(.*?)\]\((.*?)\)", line)
            if not match:
                raise ValueError(f"Invalid image syntax: {line}")
            caption, relative_path = match.groups()
            picture_path = (SOURCE.parent / relative_path).resolve()
            if not picture_path.is_file():
                raise FileNotFoundError(f"Missing report image: {picture_path}")
            document.add_picture(str(picture_path), width=Inches(6.65))
            document.paragraphs[-1].alignment = WD_ALIGN_PARAGRAPH.CENTER
            document.add_paragraph(caption, style="Caption")
        elif line.startswith("# "):
            paragraph = document.add_paragraph(style="Title" if first_title else "Heading 1")
            paragraph.add_run(line[2:].strip())
            first_title = False
        elif line.startswith("## "):
            document.add_heading(line[3:].strip(), level=1)
        elif line.startswith("### "):
            document.add_heading(line[4:].strip(), level=2)
        elif line.startswith("#### "):
            document.add_heading(line[5:].strip(), level=3)
        elif line.startswith("```"):
            code_lines = []
            index += 1
            while index < len(source_lines) and not source_lines[index].strip().startswith("```"):
                code_lines.append(source_lines[index])
                index += 1
            add_code(document, code_lines)
        elif line.startswith("|"):
            table_rows = []
            while index < len(source_lines) and source_lines[index].strip().startswith("|"):
                table_rows.append(source_lines[index])
                index += 1
            add_table(document, table_rows)
            continue
        else:
            add_body(document, line)
        index += 1

    document.core_properties.title = "Network KPI Monitoring Platform - Client Handover"
    document.core_properties.subject = "Technical implementation, operations, and calculation guide"
    document.core_properties.author = "Network KPI Monitoring"
    document.save(OUTPUT)
    print(OUTPUT)


if __name__ == "__main__":
    convert()

"""Render the handover Markdown and its images as a standalone submission PDF."""
from pathlib import Path
import re
from html import escape
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, PageBreak, Table, TableStyle, Image, Preformatted, KeepTogether
from reportlab.lib import colors
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.utils import ImageReader
from reportlab.lib.pagesizes import A4

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'CLIENT_HANDOVER_DOCUMENT.pdf'
styles = getSampleStyleSheet()
styles.add(ParagraphStyle(name='Body', fontName='Helvetica',fontSize=9,leading=13,spaceAfter=7))
styles.add(ParagraphStyle(name='SmallCell',fontName='Helvetica',fontSize=7.5,leading=10))
styles.add(ParagraphStyle(name='FigureCaption',fontName='Helvetica-Oblique',fontSize=8,leading=11,spaceAfter=12,textColor=colors.HexColor('#475569')))
styles['Title'].textColor=colors.HexColor('#17375e')
for name in ['Heading1','Heading2','Heading3']:
    styles[name].textColor=colors.HexColor('#17375e')
    styles[name].spaceBefore=14
styles['Code'].fontSize=6.5
styles['Code'].leading=9

def inline(value):
    value=escape(value)
    value=re.sub(r'\*\*(.*?)\*\*',r'<b>\1</b>',value)
    value=re.sub(r'`([^`]+)`',r'<font face="Courier">\1</font>',value)
    return value

def footer(canvas,doc):
    canvas.setStrokeColor(colors.HexColor('#cbd5e1'))
    canvas.line(42,40,A4[0]-42,40)
    canvas.setFont('Helvetica',8)
    canvas.setFillColor(colors.HexColor('#64748b'))
    canvas.drawString(42,27,'Network KPI Monitoring | Client & Management Handover')
    canvas.drawRightString(A4[0]-42,27,f'Page {doc.page}')

def convert():
    lines=(ROOT/'CLIENT_HANDOVER_DOCUMENT.md').read_text(encoding='utf-8').splitlines()
    story=[Spacer(1,90),Paragraph('NETWORK KPI MONITORING',styles['Heading2']),Spacer(1,20),
           Paragraph('Client &amp; Management<br/>Handover Report',styles['Title']),Spacer(1,20),
           Paragraph('Features | Operations | Technical Reference | Visual Evidence',styles['Body']),
           Spacer(1,25),Paragraph('Prepared: 29 October 2026 | Version 1.4',styles['Body']),
           Paragraph('Submission copy - acceptance and sign-off pending',styles['Body']),Spacer(1,25),
           Paragraph('Prepared for client representatives, project managers and operations teams.',styles['Body']),
           Paragraph('This report describes the delivered implementation, operating procedures and verification evidence. Formal acceptance remains with the receiving team.',styles['Body']),PageBreak(),
           Paragraph('Document contents',styles['Heading1'])]
    for line in lines:
        if line.startswith('## '):story.append(Paragraph(inline(line[3:]),styles['Body']))
    story.append(PageBreak())
    i=0
    while i<len(lines):
        line=lines[i].strip()
        if not line or line=='---':i+=1;continue
        if line.startswith('```'):
            block=[];i+=1
            while i<len(lines) and not lines[i].strip().startswith('```'):
                block.append(lines[i]);i+=1
            # Wrap long commands so the reference remains within the printable area.
            import textwrap
            wrapped=[]
            for row in block:wrapped.extend(textwrap.wrap(row,110,replace_whitespace=False,drop_whitespace=False) or [''])
            story.extend([Preformatted('\n'.join(wrapped),styles['Code']),Spacer(1,8)])
        elif line.startswith('|'):
            rows=[]
            while i<len(lines) and lines[i].strip().startswith('|'):
                cells=[v.strip() for v in lines[i].strip().strip('|').split('|')]
                if not all(re.fullmatch(r':?-{3,}:?',v) for v in cells):rows.append(cells)
                i+=1
            count=max(map(len,rows))
            data=[[Paragraph(inline(value),styles['SmallCell']) for value in row+['']*(count-len(row))] for row in rows]
            table=Table(data,colWidths=[(A4[0]-84)/count]*count,repeatRows=1,hAlign='LEFT')
            table.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),colors.HexColor('#d9eaf7')),('GRID',(0,0),(-1,-1),.4,colors.HexColor('#cbd5e1')),('VALIGN',(0,0),(-1,-1),'TOP'),('LEFTPADDING',(0,0),(-1,-1),6),('RIGHTPADDING',(0,0),(-1,-1),6),('TOPPADDING',(0,0),(-1,-1),6),('BOTTOMPADDING',(0,0),(-1,-1),6)]))
            story.extend([table,Spacer(1,10)]);continue
        elif line.startswith('!['):
            match=re.fullmatch(r'!\[(.*?)\]\((.*?)\)',line)
            caption,path=match.groups();path=ROOT/path
            w,h=ImageReader(str(path)).getSize();width=A4[0]-84
            story.append(KeepTogether([Image(str(path),width=width,height=width*h/w),Paragraph(inline(caption),styles['FigureCaption'])]))
        elif line.startswith('#'):
            level=len(line)-len(line.lstrip('#'))
            story.append(Paragraph(inline(line[level:].strip()),styles['Title' if level==1 else f'Heading{min(level-1,3)}']))
        else:
            if line.startswith('- '):line='• '+line[2:]
            story.append(Paragraph(inline(line),styles['Body']))
        i+=1
    SimpleDocTemplate(str(OUTPUT),pagesize=A4,rightMargin=42,leftMargin=42,topMargin=42,bottomMargin=55,title='Network KPI Monitoring - Client and Management Handover',author='Network KPI Monitoring').build(story,onFirstPage=footer,onLaterPages=footer)
    print(OUTPUT)

if __name__=='__main__':convert()

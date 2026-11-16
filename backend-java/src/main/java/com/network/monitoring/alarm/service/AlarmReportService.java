package com.network.monitoring.alarm.service;

import com.network.monitoring.alarm.exception.AlarmConfigurationException;
import com.network.monitoring.alarm.security.AlarmTenantAccess;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.network.monitoring.alarm.dto.AlarmModels.*;
import static com.network.monitoring.alarm.support.AlarmValues.*;

@Service
public class AlarmReportService {
    private final AlarmWorkflowService workflow;
    private final AlarmTenantAccess access;
    private final JdbcTemplate jdbc;
    private final java.util.concurrent.Semaphore downloadSlot=new java.util.concurrent.Semaphore(1);
    public AlarmReportService(AlarmWorkflowService workflow, AlarmTenantAccess access, JdbcTemplate jdbc) {
        this.workflow=workflow; this.access=access; this.jdbc=jdbc;
    }
    public byte[] download(long company,long run,Long circle,String format) {
        access.require(company,false);
        circle(company,circle);
        return guarded(()->format.equals("pdf") ? pdf(company,run,circle,null) : csv(company,run,circle,null));
    }
    List<Map<String,Object>> rows(long company,long run,Long circle,Set<String> keys) {
        workflow.completed(company,run);
        String filter=circle==null?"":" AND circle_id=?";
        Object[] args=circle==null?new Object[]{company,run}:new Object[]{company,run,circle};
        var rows=jdbc.queryForList("SELECT *,JSON_UNQUOTE(JSON_EXTRACT(row_json,'$.supplementaryInformation')) supplementary_information FROM alarm_observations WHERE company_id=? AND run_id=?"+filter+" ORDER BY circle_name,classification,resource_name LIMIT 5001",args);
        if(rows.size()>5000) throw new IllegalStateException("Observation budget exceeded");
        return keys==null?rows:rows.stream().filter(row->keys.contains(string(row,"identity_key"))).toList();
    }
    public byte[] csv(long company,long run,Long circle,Set<String> keys) {
        var info=workflow.completed(company,run); Instant asOf=jdbcInstant(info.get("snapshot_at"));
        StringBuilder result=new StringBuilder("\uFEFF");
        result.append("Import ID,Feed ID,Snapshot UTC,Circle,Zone,Site,OEM,Technology,Resource,Alarm Code,Alarm Text,Source Severity,Customer Classification,Included,Service Affecting,Site Down,Raised UTC,Age Hours,Identity,Supplementary Information\r\n");
        for(var row:rows(company,run,circle,keys)) {
            List<Object> cells=Arrays.asList(run,info.get("feed_id"),asOf,string(row,"circle_name"),string(row,"zone_name"),string(row,"site_code"),string(row,"oem"),string(row,"technology"),string(row,"resource_name"),string(row,"alarm_code"),string(row,"alarm_text"),string(row,"source_severity"),string(row,"classification"),row.get("included"),row.get("service_affecting"),row.get("site_down"),jdbcInstant(row.get("raised_at")),String.format(Locale.ROOT,"%.2f",Duration.between(jdbcInstant(row.get("raised_at")),asOf).toSeconds()/3600.0),string(row,"identity_key"));
            result.append(String.join(",",cells.stream().map(AlarmReportService::cell).toList())).append(',').append(cell(row.get("supplementary_information"))).append("\r\n");
        }
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }
    public static String cell(Object value) {
        String text=value==null?"UNMAPPED":value.toString();
        String leading=text.stripLeading();
        if(!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0))>=0) text="'"+text;
        return "\""+text.replace("\"","\"\"")+"\"";
    }
    public byte[] pdf(long company,long run,Long circle,Set<String> keys) {
        var info=workflow.completed(company,run); var rows=rows(company,run,circle,keys);
        long unmapped=rows.stream().filter(r->string(r,"classification").equals("UNMAPPED")).count();
        List<String> lines=new ArrayList<>();
        lines.add("ALARM MONITORING | SNAPSHOT REPORT");
        lines.add("Company "+company+" | Feed "+info.get("feed_id")+" | Import "+run);
        lines.add("Snapshot UTC: "+jdbcInstant(info.get("snapshot_at")));
        lines.add("Scope: "+(circle==null?"All circles in this source snapshot":"Circle ID "+circle));
        lines.add("Observed alarms: "+rows.size()+" | Unmapped catalogue: "+unmapped);
        for(String severity:List.of("CRITICAL","MAJOR","MINOR"))
            lines.add(severity+" (included): "+rows.stream().filter(r->bool(r,"included") && severity.equals(r.get("classification"))).count());
        lines.add("Known down sites (included catalogue rules only): "+rows.stream().filter(r->bool(r,"included") && bool(r,"site_down") && r.get("site_code")!=null).map(r->r.get("circle_id")+"|"+r.get("site_code")).distinct().count());
        lines.add("Missing records are NOT confirmed cleared. Source severity is not customer classification.");
        if(circle==null) {
            lines.add("SOURCE COMPARISON (whole feed; not a circle subtotal)");
            lines.add(Objects.toString(info.get("result_json"),"No baseline available"));
        }
        lines.add("ALARM DETAILS | First "+Math.min(100,rows.size())+" of "+rows.size()+" records");
        lines.add("Full UTF-8 details and age in hours are included in the companion CSV export.");
        for(var row:rows.stream().limit(100).toList()) {
            lines.add(string(row,"circle_name")+" | "+string(row,"site_code")+" | "+string(row,"classification")+" | "+string(row,"oem")+" "+string(row,"technology"));
            String details=string(row,"resource_name")+" | "+string(row,"alarm_code")+" | "+string(row,"alarm_text");
            lines.add(details.length()>220?details.substring(0,217)+"...":details);
        }
        lines.add("Data quality: site mapping, customer inclusion and pending operational inputs must be reviewed.");
        lines.add("MS2/LR/site recovery reasons are not inferred from incomplete source fields.");
        return textPdf(lines);
    }
    public List<Long> sourceIds(long company,String at) {
        var sources=workflow.sourceSnapshots(company,instant(at));
        return sources.stream().map(source->number(source,"id")).toList();
    }
    public byte[] nationalDownload(long company,String at,Long circle,String format) {
        access.require(company,false);var ids=sourceIds(company,at);
        circle(company,circle);
        if(ids.isEmpty()) throw com.network.monitoring.alarm.exception.AlarmConfigurationException.conflict("No completed source snapshots exist at the requested report time.");
        return guarded(()->format.equals("pdf")?cohortPdf(company,ids,circle):cohortCsv(company,ids,circle));
    }
    private byte[] guarded(java.util.function.Supplier<byte[]> render) {
        if(!downloadSlot.tryAcquire()) throw new com.network.monitoring.alarm.exception.AlarmConfigurationException(503,"ALARM_REPORT_BUSY","Another alarm export is running; retry shortly.");
        try{return render.get();}finally{downloadSlot.release();}
    }
    private void circle(long company,Long circle) {
        if(circle!=null && jdbc.queryForObject("SELECT COUNT(*) FROM alarm_circles WHERE company_id=? AND id=?",Integer.class,company,circle)!=1) throw com.network.monitoring.alarm.exception.AlarmConfigurationException.missing();
    }
    public byte[] cohortCsv(long company,List<Long> runs,Long circle) {
        if(runs.size()>200) throw new IllegalStateException("Source budget exceeded");
        try(var output=new ByteArrayOutputStream()) {
            int rows=0;
            for(long run:runs) {
                rows+=jdbc.queryForObject("SELECT COUNT(*) FROM alarm_observations WHERE company_id=? AND run_id=?",Integer.class,company,run);
                if(rows>50000) throw new IllegalStateException("National export exceeds 50000 records; narrow the scope");
                byte[] bytes=csv(company,run,circle,null);
                int start=0;
                if(output.size()>0) {while(start<bytes.length && bytes[start]!='\n')start++;start++;}
                if(output.size()+bytes.length-start>20*1024*1024) throw new IllegalStateException("National export exceeds the 20 MiB artifact budget");
                output.write(bytes,start,bytes.length-start);
            }
            return output.toByteArray();
        }catch(IOException error){throw new IllegalStateException(error);}
    }
    public byte[] cohortPdf(long company,List<Long> runs,Long circle) {
        List<String> lines=new ArrayList<>();lines.add("NATIONAL / CIRCLE ALARM REPORT");
        lines.add("Company "+company+" | Scope: "+(circle==null?"All circles":"Circle "+circle));
        lines.add("Source cohort is pinned by immutable import IDs; timestamps may differ between feeds.");
        Map<String,long[]> totals=new TreeMap<>();Set<String> downSites=new HashSet<>();
        int count=0;
        for(long run:runs) {
            var source=workflow.completed(company,run);
            lines.add("Feed "+source.get("feed_id")+" | Import "+run+" | "+jdbcInstant(source.get("snapshot_at")));
            for(var row:rows(company,run,circle,null)) {
                if(++count>50000) throw new IllegalStateException("National summary exceeds 50000 records");
                String key=string(row,"zone_name")+" / "+string(row,"circle_name")+" / "+string(row,"technology");
                long[] group=totals.computeIfAbsent(key,ignored->new long[5]);group[0]++;
                if(string(row,"classification").equals("UNMAPPED"))group[4]++;
                if(bool(row,"included")) {
                    switch(string(row,"classification")){case "CRITICAL"->group[1]++;case "MAJOR"->group[2]++;case "MINOR"->group[3]++;default->{}}
                    if(bool(row,"site_down")&&row.get("site_code")!=null)downSites.add(row.get("circle_id")+"|"+row.get("site_code"));
                }
            }
        }
        lines.add("Observed records (deduplicated within each source): "+count+" | Known down sites: "+downSites.size());
        lines.add("CIRCLE / TECHNOLOGY SUMMARY | Observed / Critical / Major / Minor / Unmapped");
        totals.forEach((name,value)->lines.add(name+" | "+value[0]+" / "+value[1]+" / "+value[2]+" / "+value[3]+" / "+value[4]));
        lines.add("The companion CSV includes complete alarm evidence, source IDs, source timestamps and age.");
        lines.add("Different feeds may overlap. Record totals are not certified unique national incidents.");
        lines.add("Missing observations do not prove clearance. Stale/missing feeds require review.");
        lines.add("Customer MS2/LR operational inputs and recovery reasons are not inferred.");
        return textPdf(lines);
    }
    private byte[] textPdf(List<String> lines) {
        try(var document=new PDDocument();var output=new ByteArrayOutputStream()) {
            var font=new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            List<String> wrapped=new ArrayList<>();
            for(String line:lines) {
                String safe=line.replaceAll("[^\\x20-\\x7E]","?");
                while(safe.length()>110) {wrapped.add(safe.substring(0,110));safe=safe.substring(110);}
                wrapped.add(safe);
            }
            for(int offset=0;offset<wrapped.size();offset+=48) {
                var page=new PDPage(PDRectangle.A4);document.addPage(page);
                try(var content=new PDPageContentStream(document,page)) {
                    content.setNonStrokingColor(0.07f,0.16f,0.29f);content.addRect(0,790,595,52);content.fill();
                    content.beginText();content.setFont(font,13);content.setNonStrokingColor(1f,1f,1f);content.newLineAtOffset(36,810);content.showText("NETWORK MONITOR | ALARM OPERATIONS");content.endText();
                    content.beginText();content.setFont(font,8);content.setNonStrokingColor(0.12f,0.17f,0.24f);content.setLeading(14);content.newLineAtOffset(36,760);
                    for(String line:wrapped.subList(offset,Math.min(offset+48,wrapped.size()))) {content.showText(line);content.newLine();}
                    content.endText();content.beginText();content.setFont(font,8);content.newLineAtOffset(36,30);content.showText("Source snapshot report | Page "+document.getNumberOfPages()+" | Unicode source text: see CSV");content.endText();
                }
            }
            document.save(output);return output.toByteArray();
        } catch(IOException e) {throw new IllegalStateException("Cannot render alarm PDF",e);}
    }
}

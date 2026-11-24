package com.network.monitoring.alarm.service;

import com.network.monitoring.alarm.exception.AlarmConfigurationException;
import com.network.monitoring.alarm.security.AlarmTenantAccess;
import com.network.monitoring.alarm.dto.AlarmReport;
import com.network.monitoring.alarm.dto.AlarmOperational;
import com.network.monitoring.alarm.reporting.AlarmOperationalContext;
import com.network.monitoring.alarm.reporting.AlarmWorkbookRenderer;
import com.network.monitoring.alarm.repository.AlarmReportRepository;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;
import static com.network.monitoring.alarm.dto.AlarmModels.*;
import static com.network.monitoring.alarm.support.AlarmValues.*;

@Service
public class AlarmReportService {
    private final AlarmWorkflowService workflow;
    private final AlarmTenantAccess access;
    private final AlarmReportRepository repository;
    private final AlarmOperationalService operational;
    private final java.util.concurrent.Semaphore downloadSlot=new java.util.concurrent.Semaphore(1);
    public AlarmReportService(AlarmWorkflowService workflow, AlarmTenantAccess access, AlarmReportRepository repository, AlarmOperationalService operational) {
        this.workflow=workflow; this.access=access; this.repository=repository;this.operational=operational;
    }
    public byte[] download(long company,long run,Long circle,String format) {
        access.require(company,false);
        circle(company,circle);
        return guarded(()->switch(format) {
            case "pdf" -> pdf(company,run,circle,null,operational.at(company,jdbcInstant(completed(company,run).get("snapshot_at"))));
            case "xlsx" -> workbook(company,List.of(run),circle,operational.at(company,jdbcInstant(completed(company,run).get("snapshot_at"))));
            case "csv" -> csv(company,run,circle,null);
            default -> throw AlarmConfigurationException.invalid("Report format must be csv, pdf or xlsx.");
        });
    }
    List<Map<String,Object>> rows(long company,long run,Long circle,Set<String> keys) {
        completed(company,run);
        var rows=repository.observations(company,run,circle,false);
        if(rows.size()>5000) throw new IllegalStateException("Observation budget exceeded");
        return keys==null?rows:rows.stream().filter(row->keys.contains(string(row,"identity_key"))).toList();
    }
    private Map<String,Object> completed(long company,long run) {
        var source=repository.source(company,run).orElseThrow(AlarmConfigurationException::missing);
        if(!"SUCCEEDED".equals(source.get("status"))) throw AlarmConfigurationException.conflict("The import has not completed successfully.");
        return source;
    }
    public byte[] csv(long company,long run,Long circle,Set<String> keys) {
        var info=completed(company,run); Instant asOf=jdbcInstant(info.get("snapshot_at"));
        StringBuilder result=new StringBuilder("\uFEFF");
        result.append("Import ID,Feed ID,Snapshot UTC,Circle,Zone,Site,OEM,Technology,Resource,Alarm Code,Alarm Text,Source Severity,Customer Classification,Included,Service Affecting,Site Down,Raised UTC,Age Hours,Identity,Supplementary Information,Source Remarks,L2 Remarks,Circle Update\r\n");
        for(var row:rows(company,run,circle,keys)) {
            List<Object> cells=Arrays.asList(run,info.get("feed_id"),asOf,string(row,"circle_name"),string(row,"zone_name"),string(row,"site_code"),string(row,"oem"),string(row,"technology"),string(row,"resource_name"),string(row,"alarm_code"),string(row,"alarm_text"),string(row,"source_severity"),string(row,"classification"),row.get("included"),row.get("service_affecting"),row.get("site_down"),jdbcInstant(row.get("raised_at")),String.format(Locale.ROOT,"%.2f",Duration.between(jdbcInstant(row.get("raised_at")),asOf).toSeconds()/3600.0),string(row,"identity_key"));
            result.append(String.join(",",cells.stream().map(AlarmReportService::cell).toList())).append(',').append(cell(row.get("supplementary_information"))).append(',').append(cell(string(row,"source_remarks"))).append(',').append(cell(string(row,"l2_remarks"))).append(',').append(cell(string(row,"circle_update"))).append("\r\n");
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
        return pdf(company,run,circle,keys,AlarmOperational.Snapshot.none());
    }
    private byte[] pdf(long company,long run,Long circle,Set<String> keys,AlarmOperational.Snapshot inputs) {
        var info=completed(company,run); var rows=rows(company,run,circle,keys);
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
        operationalLines(lines,AlarmOperationalContext.scope(inputs,circle));
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
        return guarded(()->switch(format) {
            case "pdf" -> cohortPdf(company,ids,circle,operational.at(company,instant(at)));
            case "xlsx" -> workbook(company,ids,circle,operational.at(company,instant(at)));
            case "csv" -> cohortCsv(company,ids,circle);
            default -> throw AlarmConfigurationException.invalid("Report format must be csv, pdf or xlsx.");
        });
    }

    public Map<String,Object> dashboard(long company,String at) {
        access.require(company,false);
        return guarded(()->{
        Map<String,Object> result=new LinkedHashMap<>(workflow.dashboardInternal(company,at));
        @SuppressWarnings("unchecked") var sources=(List<Map<String,Object>>)result.get("sources");
        var ids=sources.stream().map(source->number(source,"id")).toList();
        var inputs=operational.at(company,instant(at));
        result.put("operationalInputs",Map.of("enabled",operational.enabled(),"snapshot",inputs));
        result.put("reportSummary",AlarmOperationalContext.summary(AlarmSummaryCalculator.summarize(loadSources(company,ids,null,true)),inputs));
        return result;
        });
    }

    private List<AlarmReport.Source> loadSources(long company,List<Long> runs,Long circle,boolean summaryOnly) {
        if(runs.size()>200 || new HashSet<>(runs).size()!=runs.size())
            throw AlarmConfigurationException.invalid("Supply at most 200 distinct source snapshots.");
        if(runs.isEmpty()) return List.of();
        Map<Long,Map<String,Object>> metadata=new HashMap<>();
        for(var row:repository.sources(company,runs)) metadata.put(number(row,"id"),row);
        for(long run:runs) {
            var info=metadata.get(run);
            if(info==null) throw AlarmConfigurationException.missing();
            if(!"SUCCEEDED".equals(info.get("status"))) throw AlarmConfigurationException.conflict("The import has not completed successfully.");
        }
        var observations=repository.observations(company,runs,circle,summaryOnly);
        if(observations.size()>50000) throw AlarmConfigurationException.invalid("Summary exceeds 50000 observations; narrow the report scope.");
        Map<Long,List<Map<String,Object>>> byRun=new HashMap<>();
        for(var row:observations) {
            var evidence=byRun.computeIfAbsent(number(row,"run_id"),ignored->new ArrayList<>());
            evidence.add(row);
            if(evidence.size()>5000) throw new IllegalStateException("Observation budget exceeded");
        }
        List<AlarmReport.Source> sources=new ArrayList<>();
        for(long run:runs) {
            var info=metadata.get(run);
            var evidence=byRun.getOrDefault(run,List.of());
            sources.add(new AlarmReport.Source(run,number(info,"feed_id"),jdbcInstant(info.get("snapshot_at")),evidence));
        }
        return List.copyOf(sources);
    }

    private byte[] workbook(long company,List<Long> runs,Long circle,AlarmOperational.Snapshot inputs) {
        var sources=loadSources(company,runs,circle,false);
        var scoped=AlarmOperationalContext.scope(inputs,circle);
        return AlarmWorkbookRenderer.render(sources,AlarmOperationalContext.summary(AlarmSummaryCalculator.summarize(sources),scoped),scoped);
    }
    private <T> T guarded(java.util.function.Supplier<T> render) {
        if(!downloadSlot.tryAcquire()) throw new com.network.monitoring.alarm.exception.AlarmConfigurationException(503,"ALARM_REPORT_BUSY","Another alarm report or summary is running; retry shortly.");
        try{return render.get();}finally{downloadSlot.release();}
    }
    private void circle(long company,Long circle) {
        if(circle!=null && !repository.circleExists(company,circle)) throw com.network.monitoring.alarm.exception.AlarmConfigurationException.missing();
    }
    public byte[] cohortCsv(long company,List<Long> runs,Long circle) {
        if(runs.size()>200) throw new IllegalStateException("Source budget exceeded");
        try(var output=new ByteArrayOutputStream()) {
            int rows=0;
            for(long run:runs) {
                rows+=repository.count(company,run,circle);
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
        return cohortPdf(company,runs,circle,AlarmOperational.Snapshot.none());
    }
    public byte[] cohortPdf(long company,List<Long> runs,Long circle,Long operationalSnapshotId) {
        return cohortPdf(company,runs,circle,operational.pinned(company,operationalSnapshotId));
    }
    byte[] scopedPdf(long company,long run,Long circle,Set<String> keys,Long operationalSnapshotId) {
        return pdf(company,run,circle,keys,operational.pinned(company,operationalSnapshotId));
    }
    Long operationalSnapshotId(long company,String at) {return operational.at(company,instant(at)).id();}
    private byte[] cohortPdf(long company,List<Long> runs,Long circle,AlarmOperational.Snapshot inputs) {
        var sources=loadSources(company,runs,circle,true);
        var scoped=AlarmOperationalContext.scope(inputs,circle);
        var summary=AlarmOperationalContext.summary(AlarmSummaryCalculator.summarize(sources),scoped);
        List<String> lines=new ArrayList<>();
        lines.add("NATIONAL / CIRCLE ALARM REPORT");
        lines.add("Company "+company+" | Scope: "+(circle==null?"All circles":"Circle "+circle));
        for(var source:sources) lines.add("Feed "+source.feedId()+" | Import "+source.runId()+" | "+source.snapshotAt());
        lines.add("Observed records: "+summary.observedRecords()+" | Included: "+summary.includedRecords()+" | Known down sites: "+summary.knownDownSites());
        lines.add("ZONE / TECHNOLOGY SUMMARY");
        for(var group:summary.zones()) summaryLines(lines,group);
        lines.add("CIRCLE / TECHNOLOGY SUMMARY");
        for(var group:summary.circles()) summaryLines(lines,group);
        lines.add("PENDING CUSTOMER OPERATIONAL INPUTS (not zero)");
        lines.addAll(summary.pendingOperationalInputs());
        lines.addAll(summary.notes());
        operationalLines(lines,scoped);
        lines.add("Companion CSV contains complete evidence; Excel also includes structured summaries and pending inputs.");
        return textPdf(lines);
    }
    private void operationalLines(List<String> lines,AlarmOperational.Snapshot snapshot) {
        if(snapshot.id()==null) return;
        lines.add("CUSTOMER-REPORTED OPERATIONAL INPUTS | Snapshot "+snapshot.id()+" | Revision "+snapshot.revision());
        lines.add("Effective UTC: "+snapshot.effectiveAt()+" | Source: "+snapshot.sourceReference()+" | Payload SHA256: "+snapshot.checksum());
        for(var context:snapshot.circles()) {
            var input=context.input();
            lines.add(context.zoneName()+" / "+context.circleName()+" / "+input.technology());
            lines.add("MS2 pending "+display(input.ms2Pending())+" | LR pending "+display(input.lrPending())+" | Inventory "+display(input.siteInventoryTotal())+" | Reported site up "+display(input.siteUp()));
            lines.add("GPS "+display(input.gpsAlarms())+" | VSWR "+display(input.vswrAlarms())+" | Owner "+display(input.circleOwner())+" | Major reason "+display(input.majorReason()));
            for(var site:input.sites()) lines.add("Site "+site.siteCode()+" | Reported "+site.state()+" at "+site.observedAt()+" | Recovery reason "+display(site.recoveryReason()));
        }
        lines.add("Reported site states and counts do not clear OSS alarms. Unknown inputs are not zero.");
    }
    private String display(Object value){return value==null?"NOT SUPPLIED":value.toString();}
    private void summaryLines(List<String> lines,com.network.monitoring.alarm.dto.AlarmReport.Group group) {
        lines.add(group.zone()+" / "+(group.circle()==null?"All circles":group.circle())+" / "+group.technology());
        lines.add("Observed "+group.observedRecords()+" | Included "+group.includedRecords()+" | Critical "+group.critical()+" | Major "+group.major()+" | Minor "+group.minor());
        lines.add("Service-affecting records "+group.serviceAffectingRecords()+" | Known down sites "+group.knownDownSites()+" | Unmapped catalogue "+group.unmappedCatalogue()+" | Unmapped sites "+group.unmappedSites());
        var age=group.downSiteAge();
        lines.add("Down-site age: <12h "+age.under12Hours()+" | 12h-48h "+age.from12To48Hours()+" | >48h and <72h "+age.above48Under72Hours()+" | >=72h "+age.atLeast72Hours());
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

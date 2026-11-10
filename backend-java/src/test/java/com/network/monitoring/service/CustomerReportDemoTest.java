package com.network.monitoring.service;

import com.network.monitoring.entity.KpiThresholdRule;
import com.network.monitoring.repository.KpiThresholdRuleRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import static com.network.monitoring.service.DailyKpiCalculator.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A reproducible, substantial demo through the real calculator, RCA engine and report renderer. */
class CustomerReportDemoTest {
    @TempDir Path directory;
    @Test @EnabledIfEnvironmentVariable(named="KPI_RCA_PYTHON",matches=".+")
    void generatesCustomerReadableReportWithRealAnalysisOfClearlySyntheticObservations() throws Exception {
        LocalDate date=LocalDate.of(2026,11,5);
        String[] keys={"availability","rrcsetup","erabsetup","dcr","prb","throughput","packetloss","mvpuschsinr"};
        String[] labels={"MV_Radio NW Availability","RRC Setup Success Rate","MV_ERAB Setup Success Rate","MV_PS Drop Call Rate %","DL PRB Utilisation","MV_DL User Throughput_Mbps","MV_Packet Loss DL","MV_PUSCH SINR"};
        boolean[] higher={true,true,true,false,false,true,false,true};
        double[] baseline={99.9,99.6,99.4,.3,50,18,.01,12};
        double[][] current={{99.8,94,95,1.1,55,8,.02,10},{99.9,99.1,99,.5,94,2,.02,8},{99.7,98.5,98.7,3,60,1,.7,-3},{88,90,92,4.5,20,.5,1,7},{100,99.85,99.8,.1,38,25,.005,16},{99.9,99.6,99.4,.3,50,18,.01,12}};
        List<Map<String,Object>> observations=new ArrayList<>();
        for(int offset=7;offset>=0;offset--)for(int hour=0;hour<24;hour++)for(int cell=0;cell<6;cell++) {
            Map<String,Object> metrics=new LinkedHashMap<>();
            for(int metric=0;metric<keys.length;metric++) {
                double value=offset==0?current[cell][metric]:baseline[metric]+(metric==4?cell:0);
                double variation=Math.sin(hour*Math.PI/12)*(metric==4||metric==5||metric==7?.25:.001);
                value+=variation;if(metric!=5&&metric!=7)value=Math.max(0,Math.min(100,value));metrics.put(keys[metric],value);
            }
            observations.add(Map.of("date",date.minusDays(offset).toString(),"cellName","DEMO_CELL_"+(cell+1),"site","DEMO_SITE_"+(cell/2+1),"tech","LTE","band","1800","dimensions",Map.of("hour",String.format("%02d",hour)),"metrics",metrics));
        }
        List<Metric> metrics=new ArrayList<>();List<KpiThresholdRule> rules=new ArrayList<>();Map<String,String> mapping=new LinkedHashMap<>();
        double[][] limits={{90,95,98,99},{95,97,98,99},{95,97,98,99},{3,2,1,.8},{95,90,80,70},{1,3,5,8},{1,.5,.1,.05},{0,3,5,8}};
        for(int i=0;i<keys.length;i++) {
            metrics.add(new Metric(keys[i],Aggregation.MEAN,higher[i]?Direction.HIGHER_IS_BETTER:Direction.LOWER_IS_BETTER,i==5?"MBPS":i==7?"DB":"PERCENT",null,null,null,true));mapping.put(labels[i],keys[i]);
            var rule=new KpiThresholdRule();rule.setId((long)i+1);rule.setMetricName(keys[i]);rule.setTechnology("LTE");rule.setDirection(higher[i]?"LOWER_IS_BAD":"HIGHER_IS_BAD");rule.setCriticalValue(limits[i][0]);rule.setMajorValue(limits[i][1]);rule.setMinorValue(limits[i][2]);rule.setWarningValue(limits[i][3]);rules.add(rule);
        }
        Profile profile=new Profile(List.of("cellName","site","tech"),metrics,7,24,true).validated();var built=build(observations,profile,date,1000);
        Map<String,List<GroupDay>> groups=new TreeMap<>();built.days().forEach(day->groups.computeIfAbsent(day.groupHash(),ignored->new ArrayList<>()).add(day));List<Map<String,Object>> comparisons=new ArrayList<>();
        for(var days:groups.values())for(var metric:profile.metrics()) { Map<LocalDate,Value> values=new HashMap<>();days.forEach(day->values.put(day.date(),day.metrics().get(metric.key())));comparisons.add(compare(days.get(0).dimensions(),metric,values,date,profile)); }
        var source=new LinkedHashMap<String,Object>(Map.of("records",observations));String fingerprint=HexFormat.of().formatHex(RecommendationCacheKey.digest().digest(CommonReportModules.JSON.writeValueAsBytes(source)));
        var input=new DailyAnalysisService.ReportInput(9001,9000,"demo-source.json",fingerprint,"demo-synthetic-8days-v1","demo-profile-v1",date,profile,Map.of("demo",true,"dataStatus","AVAILABLE","groups",6,"currentGroups",6,"metrics",8,"inputRecords",observations.size(),"groupDays",48));
        var repository=mock(KpiThresholdRuleRepository.class);when(repository.findAllByEnabledTrue()).thenReturn(rules);var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
        var thresholds=new KpiThresholdRuleService(repository,null,jdbc);var alarms=new KpiAlarmService(jdbc,null,thresholds);
        var rca=new RecommendationService(new KpiCacheService(directory.resolve("cache").toString()),null,null);
        Path workspace=Path.of(System.getProperty("user.dir")).getParent();ReflectionTestUtils.setField(rca,"pythonServicePath",workspace.resolve("python service").toString());ReflectionTestUtils.setField(rca,"pythonBin",System.getenv("KPI_RCA_PYTHON"));
        var prediction=new SitePredictionService(null,null,null,null,jdbc,task->{});
        var modules=new CommonReportModules(repository,thresholds,alarms,rca,prediction);
        var options=new CommonReportModules.Options(null,Map.of("vendor","nokia","columnMapping",Map.of("Short name","cellname","Date","date","Cell I'd","cellname","Site I'd","site","Tech","tech"),"kpiMapping",mapping,"selectedKpis",List.of(labels)));
        var config=modules.capture(input,options);var results=modules.run(input,new KpiCacheService.Snapshot(source,fingerprint),config,50000,200,500);
        assertEquals("LIMITED",CommonReportRenderer.map(results.get("rca")).get("status"),results.get("rca").toString());
        Path output=directory.resolve("customer-report");var summary=new CommonReportRenderer().render(output,input,results,sink->comparisons.forEach(sink),10000);assertEquals(48L,summary.get("comparisonRows"));
        Files.writeString(output.resolve("configuration.json"),CommonReportModules.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(config));
        try(var pdf=Loader.loadPDF(output.resolve("report.pdf").toFile())) {
            String contents=new PDFTextStripper().getText(pdf);assertTrue(contents.contains("DEMONSTRATION"));assertTrue(contents.contains("Priority KPI comparison"));assertTrue(contents.contains("Suspected cause"));assertTrue(contents.contains("Recommended investigation / action"));assertFalse(contents.contains("{cellName="));assertFalse(contents.contains("Top KPI drivers:"));assertTrue(pdf.getNumberOfPages()>=6);
            String preview=System.getenv("KPI_REPORT_PREVIEW_DIR");
            if(preview!=null) {
                Path target=Path.of(preview);for(String ext:List.of("pdf","xlsx","json"))Files.copy(output.resolve("report."+ext),target.resolve("proper-common-report-demo."+ext),StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(target.resolve("proper-report-demo-configuration.json"),CommonReportModules.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(config));
                var renderer=new PDFRenderer(pdf);ImageIO.write(renderer.renderImageWithDPI(0,110),"PNG",target.resolve("proper-report-first-page.png").toFile());
                BufferedImage contact=new BufferedImage(800,Math.ceilDiv(pdf.getNumberOfPages(),4)*290,BufferedImage.TYPE_INT_RGB);var graphics=contact.createGraphics();graphics.setColor(java.awt.Color.WHITE);graphics.fillRect(0,0,contact.getWidth(),contact.getHeight());
                for(int page=0;page<pdf.getNumberOfPages();page++)graphics.drawImage(renderer.renderImageWithDPI(page,30),page%4*200,page/4*290,200,283,null);graphics.dispose();ImageIO.write(contact,"PNG",target.resolve("proper-report-page-overview.png").toFile());
                Files.writeString(target.resolve("proper-report-demo-checks.json"),CommonReportModules.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("synthetic",true,"observations",observations.size(),"cells",6,"sites",3,"metrics",8,"comparisonRows",comparisons.size(),"pages",pdf.getNumberOfPages(),"rcaEngine","REAL_NOKIA_RULE_ENGINE","customerDatabaseWrites",0)));
            }
        }
        verify(repository,never()).save(org.mockito.ArgumentMatchers.any());
    }
}

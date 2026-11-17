package com.network.monitoring.alarm.service;

import com.network.monitoring.alarm.dto.AlarmReport;
import com.network.monitoring.alarm.reporting.AlarmWorkbookRenderer;
import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AlarmSummaryCalculatorTest {
    private static final Instant AT=Instant.parse("2026-11-01T12:00:00Z");
    private Map<String,Object> alarm(String site,String rat,long ageSeconds,boolean included) {
        Map<String,Object> row=new HashMap<>();
        row.put("circle_id",10L);row.put("circle_name","Circle A");row.put("zone_name","West");
        row.put("technology",rat);row.put("site_code",site);row.put("classification","MAJOR");
        row.put("included",included);row.put("site_down",true);row.put("service_affecting",true);
        row.put("raised_at",LocalDateTime.ofInstant(AT.minusSeconds(ageSeconds),ZoneOffset.UTC));
        row.put("alarm_text","=HYPERLINK(\"unsafe\")");row.put("supplementary_information","Unicode evidence: परीक्षण");
        return row;
    }
    @Test void distinctMappedSitesUseExactFormulaBoundariesAndDoNotTurnUnknownsIntoZeroInputs() {
        List<Map<String,Object>> rows=new ArrayList<>();
        long[] ages={12*3600-1,12*3600,48*3600,48*3600+1,72*3600-1,72*3600};
        for(int i=0;i<ages.length;i++)rows.add(alarm("S"+i,"4G",ages[i],true));
        rows.add(alarm("S0","4G",3600,true)); // Same mapped site, another source record.
        rows.add(alarm("excluded","4G",1,false));
        var unknown=alarm(null,"4G",1,false);unknown.put("classification","UNMAPPED");unknown.put("included",null);rows.add(unknown);
        var summary=AlarmSummaryCalculator.summarize(List.of(new AlarmReport.Source(1,20,AT,rows)));
        assertEquals(9,summary.observedRecords());assertEquals(7,summary.includedRecords());assertEquals(6,summary.knownDownSites());
        var group=summary.circles().get(0);
        assertEquals(new AlarmReport.AgeBands(1,2,2,1),group.downSiteAge());
        assertEquals(7,group.serviceAffectingRecords());assertEquals(1,group.unmappedCatalogue());assertEquals(1,group.unmappedSites());
        assertTrue(summary.pendingOperationalInputs().contains("MS2 pending 2G/4G"));
    }
    @Test void siteIdentityIncludesCircleAndRatAndAgeRemainsAnchoredToSourceTime() {
        var a=alarm("S","4G",3600,true);var b=alarm("S","2G",3600,true);
        var c=alarm("S","4G",3600,true);c.put("circle_id",11L);c.put("circle_name","Circle B");
        var summary=AlarmSummaryCalculator.summarize(List.of(new AlarmReport.Source(1,20,AT,List.of(a,b,c)),
                new AlarmReport.Source(2,21,AT,List.of(a))));
        assertEquals(4,summary.observedRecords());assertEquals(3,summary.knownDownSites());assertEquals(3,summary.circles().size());
        assertThrows(IllegalStateException.class,()->AlarmSummaryCalculator.summarize(
                List.of(new AlarmReport.Source(3,22,AT.minusSeconds(7200),List.of(a)))));
    }
    @Test void workbookContainsStructuredSheetsCompleteTextAndBlankOperationalInputs() throws Exception {
        var rows=List.of(alarm("S","4G",3600,true));var sources=List.of(new AlarmReport.Source(1,20,AT,rows));
        byte[] bytes=AlarmWorkbookRenderer.render(sources,AlarmSummaryCalculator.summarize(sources));
        try(var book=WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertEquals(5,book.getNumberOfSheets());
            assertEquals(1,book.getSheet("Circle Summary").getRow(1).getCell(9).getNumericCellValue());
            assertEquals(CellType.BLANK,book.getSheet("Circle Summary").getRow(1).getCell(16).getCellType());
            assertEquals(CellType.STRING,book.getSheet("Alarm Evidence").getRow(1).getCell(9).getCellType());
            assertEquals("=HYPERLINK(\"unsafe\")",book.getSheet("Alarm Evidence").getRow(1).getCell(9).getStringCellValue());
            assertEquals("Unicode evidence: परीक्षण",book.getSheet("Alarm Evidence").getRow(1).getCell(17).getStringCellValue());
            assertTrue(book.getSheet("Pending Inputs").getRow(1).getCell(1).getStringCellValue().contains("Not supplied"));
        }
    }
    @Test void workbookRetainsEveryEvidenceRowAfterStreamingWindowFlushes() throws Exception {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int i=0;i<205;i++) rows.add(alarm("SITE-"+i,"4G",i,true));
        var sources=List.of(new AlarmReport.Source(1,20,AT,rows));
        try(var book=WorkbookFactory.create(new ByteArrayInputStream(AlarmWorkbookRenderer.render(sources,AlarmSummaryCalculator.summarize(sources))))) {
            var evidence=book.getSheet("Alarm Evidence");
            assertEquals(206,evidence.getPhysicalNumberOfRows());
            assertEquals("SITE-204",evidence.getRow(205).getCell(6).getStringCellValue());
        }
    }
}

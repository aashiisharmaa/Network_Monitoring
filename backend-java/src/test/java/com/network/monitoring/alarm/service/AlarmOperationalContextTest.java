package com.network.monitoring.alarm.service;

import com.network.monitoring.alarm.dto.AlarmOperational.*;
import com.network.monitoring.alarm.dto.AlarmReport.*;
import com.network.monitoring.alarm.reporting.*;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AlarmOperationalContextTest {
    private Group group(Long id,String circle){return new Group("West",id,circle,"4G",1,1,1,0,0,1,1,0,0,new AgeBands(1,0,0,0));}
    private final Group a=group(10L,"Circle A"),b=group(11L,"Circle B"),zone=group(null,null);
    private final Summary summary=new Summary("alarm-summary-v1",List.of(zone),List.of(a,b),2,2,0,2,List.of(),List.of());
    private CircleContext entry(long circle,Integer ms2,Integer lr){return new CircleContext("Circle "+circle,"West",new CircleInput(circle,"4G",ms2,lr,10,8,0,null,"Zone owner","=HYPERLINK(\"unsafe\")","Reason",List.of()));}
    private Snapshot snapshot(List<CircleContext> circles){return new Snapshot(13L,"2026-11-01T04:00:00Z",2,12L,"Register","a".repeat(64),9,"2026-11-18T00:00:00Z",circles,12L);}

    @Test void zoneTotalsRequireAllRepresentedCirclesAndPreserveUnknownVersusZero() {
        var partial=snapshot(List.of(entry(10,0,null)));
        assertNull(AlarmOperationalContext.values(partial,zone,summary).ms2Pending());
        assertEquals(0L,AlarmOperationalContext.values(partial,a,summary).ms2Pending());
        var complete=snapshot(List.of(entry(10,0,null),entry(11,5,2)));
        var totals=AlarmOperationalContext.values(complete,zone,summary);
        assertEquals(5L,totals.ms2Pending());assertNull(totals.lrPending());assertEquals("Zone owner",totals.zoneOwner());assertNull(totals.circleOwner());
        assertTrue(AlarmOperationalContext.summary(summary,complete).pendingOperationalInputs().contains("LR pending status/count"));
        assertFalse(AlarmOperationalContext.summary(summary,complete).pendingOperationalInputs().contains("MS2 pending 2G/4G"));
    }
    @Test void circleScopeDoesNotLeakOtherOperationalRowsAndWorkbookUsesTypedText() throws Exception {
        var scoped=AlarmOperationalContext.scope(snapshot(List.of(entry(10,0,null),entry(11,5,2))),10L);
        assertEquals(1,scoped.circles().size());assertEquals(12L,scoped.basedOnId());assertEquals(13L,scoped.id());
        var scopedSummary=new Summary("alarm-summary-v1",List.of(zone),List.of(a),1,1,0,1,List.of(),List.of());
        try(var book=WorkbookFactory.create(new ByteArrayInputStream(AlarmWorkbookRenderer.render(List.of(),scopedSummary,scoped)))) {
            assertEquals(2,book.getSheet("Operational Inputs").getPhysicalNumberOfRows());
            var row=book.getSheet("Circle Summary").getRow(1);
            assertEquals(0,row.getCell(16).getNumericCellValue());assertEquals(CellType.BLANK,row.getCell(17).getCellType());
            assertEquals(CellType.STRING,row.getCell(18).getCellType());assertEquals("=HYPERLINK(\"unsafe\")",row.getCell(18).getStringCellValue());
        }
    }
    @Test void changedZoneMappingDoesNotAttachNewZoneOwnerOrCountsToHistoricalSourceZone() {
        var input=entry(10,7,2);
        var moved=snapshot(List.of(new CircleContext(input.circleName(),"East",input.input())));
        assertNull(AlarmOperationalContext.values(moved,a,summary).ms2Pending());
        assertNull(AlarmOperationalContext.values(moved,zone,summary).zoneOwner());
        assertTrue(AlarmOperationalContext.summary(summary,moved).pendingOperationalInputs().contains("MS2 pending 2G/4G"));
    }
}

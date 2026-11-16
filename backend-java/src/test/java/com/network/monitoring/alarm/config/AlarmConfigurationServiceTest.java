package com.network.monitoring.alarm.config;

import org.junit.jupiter.api.Test;
import java.util.Optional;
import static com.network.monitoring.alarm.config.AlarmConfiguration.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AlarmConfigurationServiceTest {
    final AlarmTenantAccess access = mock(AlarmTenantAccess.class);
    final AlarmConfigurationRepository repository = mock(AlarmConfigurationRepository.class);
    final AlarmConfigurationService service = new AlarmConfigurationService(access, repository);

    @Test void deniedAccessNeverTouchesConfigurationRepository() {
        when(access.require(200, false)).thenThrow(new AlarmConfigurationException(403, "DENIED", "Denied"));
        assertThrows(AlarmConfigurationException.class, () -> service.feeds(200, 0, 100));
        assertThrows(AlarmConfigurationException.class, () -> service.resolveCircle(200, "MU"));
        verifyNoInteractions(repository);
    }
    @Test void listsRejectUnboundedQueries() {
        assertThrows(AlarmConfigurationException.class, () -> service.zones(100, 0, 201));
        assertThrows(AlarmConfigurationException.class, () -> service.circles(100, -1, 100));
        verifyNoInteractions(repository);
    }
    @Test void unknownAliasesStayUnmappedAndInactiveParentsCannotResolve() {
        assertEquals("UNMAPPED", service.resolveCircle(100, "mu").status());
        when(repository.alias(100, "MU")).thenReturn(Optional.of(new Alias(1, 100, 4, "MU", false)));
        when(repository.circle(100, 4)).thenReturn(Optional.of(new Circle(4, 100, 2, "MUM", "Mumbai", true, 1)));
        when(repository.zone(100, 2)).thenReturn(Optional.of(new Zone(2, 100, "WEST", "West", false, 1)));
        assertEquals("INACTIVE", service.resolveCircle(100, " MU ").status());
    }
    @Test void feedValidationRequiresAnExplicitImportContract() {
        for (FeedCreate request : new FeedCreate[]{
                feed("Nokia", "5G", "CSV", null, "UTC", "SNAPSHOT"),
                feed("Unknown", "4G", "CSV", null, "UTC", "SNAPSHOT"),
                feed("Nokia", "4G", "XLSX", null, "UTC", "SNAPSHOT"),
                feed("Nokia", "4G", "CSV", "Alarm Dump", "UTC", "SNAPSHOT"),
                feed("Nokia", "4G", "CSV", null, "bad-zone", "SNAPSHOT"),
                feed("Nokia", "4G", "CSV", null, "UTC", "UNKNOWN")}) {
            assertEquals(400, assertThrows(AlarmConfigurationException.class, () -> service.createFeed(100, request)).status());
        }
        verify(repository, never()).createFeed(anyLong(), anyString(), any(), anyLong());
    }
    static FeedCreate feed(String oem, String rat, String format, String sheet, String zone, String type) {
        return new FeedCreate("TEST-FEED", "Test feed", oem, rat, format, sheet, zone, type, null);
    }
}

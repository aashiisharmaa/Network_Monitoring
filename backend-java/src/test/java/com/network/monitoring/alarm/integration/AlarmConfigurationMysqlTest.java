package com.network.monitoring.alarm.integration;

import com.network.monitoring.alarm.exception.AlarmConfigurationException;
import com.network.monitoring.alarm.repository.AlarmConfigurationRepository;
import com.network.monitoring.alarm.security.AlarmTenantAccess;
import com.network.monitoring.alarm.service.AlarmConfigurationService;
import com.network.monitoring.identity.entity.Company;
import com.network.monitoring.identity.entity.User;
import com.network.monitoring.identity.repository.CompanyRepository;
import com.network.monitoring.identity.repository.UserRepository;
import com.network.monitoring.identity.service.RoleAuthorizationService;
import java.sql.Connection;
import java.util.Optional;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static com.network.monitoring.alarm.dto.AlarmConfiguration.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses only a disposable local database, never the application's datasource. */
@EnabledIfEnvironmentVariable(named = "ALARM_CONFIG_TEST_DB_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AlarmConfigurationMysqlTest {
    DriverManagerDataSource datasource;
    JdbcTemplate jdbc;
    AlarmConfigurationRepository repository;
    AlarmTenantAccess access;
    AlarmConfigurationService service;

    @BeforeAll void migrateCleanDatabase() throws Exception {
        String url = System.getenv("ALARM_CONFIG_TEST_DB_URL");
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:13316/alarm_config_test(?:\\?.*)?"))
            throw new IllegalStateException("Tests require the disposable localhost:13316/alarm_config_test database.");
        datasource = new DriverManagerDataSource(url, "root", "");
        jdbc = new JdbcTemplate(datasource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS companies (id BIGINT NOT NULL PRIMARY KEY, company_name VARCHAR(255) NOT NULL, status VARCHAR(32) NOT NULL) ENGINE=InnoDB");
        jdbc.update("INSERT IGNORE INTO companies VALUES (100,'Synthetic tenant A','ACTIVE'),(200,'Synthetic tenant B','ACTIVE'),(300,'Existing company preserved','ACTIVE')");
        migrate();
        repository = new AlarmConfigurationRepository(jdbc);
        var users = mock(UserRepository.class);
        var companies = mock(CompanyRepository.class);
        var authorization = mock(RoleAuthorizationService.class);
        var user = new User(); user.setId(9L); user.setRole("SUPER_ADMIN");
        when(authorization.currentUserId()).thenReturn(9L);
        when(users.findById(9L)).thenReturn(Optional.of(user));
        for (long id : new long[]{100, 200}) {
            var company = new Company(); company.setId(id);
            when(companies.findById(id)).thenReturn(Optional.of(company));
        }
        access = new AlarmTenantAccess(authorization, users, companies);
        service = transactional(repository);
    }

    @BeforeEach void clearOnlyDisposableTestConfiguration() {
        for (String table : new String[]{"alarm_escalation_decisions","alarm_operator_events","alarm_delivery_outbox","alarm_observations","alarm_ingestion_runs","alarm_automation_settings","alarm_site_objects","alarm_catalog_rules"}) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",Integer.class,table) > 0) jdbc.update("DELETE FROM " + table);
        }
        for (String table : new String[]{"alarm_source_feed_profiles", "alarm_circle_aliases", "alarm_circles", "alarm_zones"})
            jdbc.update("DELETE FROM " + table);
    }

    @Test void migrationReapplicationPreservesLegacyAndNewData() throws Exception {
        var zone = zone(100);
        migrate();
        assertEquals("Existing company preserved", jdbc.queryForObject("SELECT company_name FROM companies WHERE id=300", String.class));
        assertEquals(zone, service.zones(100, 0, 100).get(0));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('alarm_zones','alarm_circles','alarm_circle_aliases','alarm_source_feed_profiles')", Integer.class));
    }

    @Test void aliasesResolveOnlyWithinTenantAndShareCanonicalNamespace() {
        var a = service.createCircle(100, new CircleCreate(zone(100).id(), "MUM", "Mumbai A"));
        var b = service.createCircle(200, new CircleCreate(zone(200).id(), "MUM", "Mumbai B"));
        service.createAlias(100, new AliasCreate(a.id(), "mu"));
        service.createAlias(200, new AliasCreate(b.id(), "MU"));
        assertEquals(a.id(), service.resolveCircle(100, " mu ").circle().id());
        assertEquals(b.id(), service.resolveCircle(200, "MU").circle().id());
        assertEquals("UNMAPPED", service.resolveCircle(100, "unknown").status());
        assertEquals(409, assertThrows(AlarmConfigurationException.class,
                () -> service.createCircle(100, new CircleCreate(a.zoneId(), "MU", "Ambiguous"))).status());
        assertEquals(409, assertThrows(AlarmConfigurationException.class,
                () -> service.createAlias(100, new AliasCreate(a.id(), "MUM"))).status());
        var canonical = service.aliases(100, 0, 100).stream().filter(Alias::canonical).findFirst().orElseThrow();
        assertEquals(409, assertThrows(AlarmConfigurationException.class, () -> service.deleteAlias(100, canonical.id())).status());
        var alias = service.aliases(100, 0, 100).stream().filter(x -> !x.canonical()).findFirst().orElseThrow();
        service.deleteAlias(100, alias.id());
        assertEquals("UNMAPPED", service.resolveCircle(100, "MU").status());
        assertEquals("MAPPED", service.resolveCircle(200, "MU").status());
    }

    @Test void foreignTenantResourceIdsAreRejectedByServiceAndDatabaseConstraints() {
        var foreignZone = zone(200);
        var foreignCircle = service.createCircle(200, new CircleCreate(foreignZone.id(), "MU", "Foreign"));
        assertEquals(404, assertThrows(AlarmConfigurationException.class,
                () -> service.createCircle(100, new CircleCreate(foreignZone.id(), "MU", "Forbidden"))).status());
        assertEquals(404, assertThrows(AlarmConfigurationException.class,
                () -> service.createAlias(100, new AliasCreate(foreignCircle.id(), "OTHER"))).status());
        assertEquals(404, assertThrows(AlarmConfigurationException.class,
                () -> service.createFeed(100, feed(foreignCircle.id()))).status());
        assertThrows(DataIntegrityViolationException.class, () -> repository.createCircle(100, foreignZone.id(), "MU", "Bad reference", 9));
        assertThrows(DataIntegrityViolationException.class, () -> repository.createAlias(100, foreignCircle.id(), "MU", false, 9));
        assertThrows(DataIntegrityViolationException.class, () -> repository.createFeed(100, "FOREIGN-FEED",
                new FeedUpdate("Bad reference", "NOKIA", "4G", "CSV", null, "UTC", "SNAPSHOT", foreignCircle.id(), false, 1), 9));
        assertTrue(service.circles(100, 0, 100).isEmpty());
    }

    @Test void optimisticVersionsAndInactiveParentsPreventSilentConfigurationChanges() {
        var zone = zone(100);
        var circle = service.createCircle(100, new CircleCreate(zone.id(), "MU", "Mumbai"));
        var changed = service.updateZone(100, zone.id(), new ZoneUpdate("Updated", false, 1));
        assertEquals(2, changed.version());
        assertEquals("INACTIVE", service.resolveCircle(100, "MU").status());
        assertEquals(409, assertThrows(AlarmConfigurationException.class,
                () -> service.updateZone(100, zone.id(), new ZoneUpdate("Stale", true, 1))).status());
        assertEquals(409, assertThrows(AlarmConfigurationException.class,
                () -> service.createFeed(100, feed(circle.id()))).status());
        assertEquals(9L, jdbc.queryForObject("SELECT updated_by FROM alarm_zones WHERE id=?", Long.class, zone.id()));
        assertTrue(service.zones(100, zone.id(), 1).isEmpty());
        assertTrue(service.zones(200, 0, 100).isEmpty());
    }

    @Test void feedProfilePersistsExplicitContractStartsInactiveAndCannotBeReadAcrossTenant() {
        var circle = service.createCircle(100, new CircleCreate(zone(100).id(), "MU", "Mumbai"));
        var feed = service.createFeed(100, feed(circle.id()));
        assertEquals("TEST-FEED", feed.sourceKey());
        assertEquals("ERICSSON", feed.oem());
        assertEquals("Alarm Dump", feed.sheet());
        assertFalse(feed.active());
        assertEquals(404, assertThrows(AlarmConfigurationException.class, () -> service.feed(200, feed.id())).status());
        var update = new FeedUpdate("Changed", "MIXED", "2G", "CSV", null, "UTC", "DELTA", null, true, 1);
        var next = service.updateFeed(100, feed.id(), update);
        assertEquals(2, next.version()); assertTrue(next.active()); assertNull(next.sheet());
        assertEquals("MIXED", next.oem()); assertEquals("DELTA", next.dumpType());
        assertEquals(409, assertThrows(AlarmConfigurationException.class, () -> service.updateFeed(100, feed.id(), update)).status());
        assertTrue(service.feeds(200, 0, 100).isEmpty());
    }

    @Test void failedCanonicalAliasInsertRollsBackItsCircleInRealTransaction() {
        var zone = zone(100);
        var spy = spy(repository);
        doThrow(new DataIntegrityViolationException("Injected alias failure")).when(spy)
                .createAlias(eq(100L), anyLong(), eq("MU"), eq(true), eq(9L));
        var serviceWithFailure = transactional(spy);
        assertThrows(DataIntegrityViolationException.class,
                () -> serviceWithFailure.createCircle(100, new CircleCreate(zone.id(), "MU", "Mumbai")));
        assertTrue(service.circles(100, 0, 100).isEmpty());
        assertTrue(service.aliases(100, 0, 100).isEmpty());
    }

    @Test void simultaneousAliasAssignmentsHaveExactlyOneWinner() throws Exception {
        var zone = zone(100);
        var a = service.createCircle(100, new CircleCreate(zone.id(), "A", "Circle A"));
        var b = service.createCircle(100, new CircleCreate(zone.id(), "B", "Circle B"));
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> assign(start, a.id()));
            var second = executor.submit(() -> assign(start, b.id()));
            start.countDown();
            assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM alarm_circle_aliases WHERE company_id=100 AND alias_key='SHARED'", Long.class));
        } finally { executor.shutdownNow(); }
    }

    private int assign(CountDownLatch start, long circle) throws InterruptedException {
        start.await();
        try { service.createAlias(100, new AliasCreate(circle, "SHARED")); return 1; }
        catch (AlarmConfigurationException e) { assertEquals(409, e.status()); return 0; }
    }
    private Zone zone(long company) { return service.createZone(company, new ZoneCreate("WEST", "Synthetic West")); }
    private FeedCreate feed(Long circle) {
        return new FeedCreate("test-feed", "Synthetic source", "E///", "4G", "XLSX", "Alarm Dump", "Asia/Kolkata", "SNAPSHOT", circle);
    }
    private void migrate() throws Exception {
        try (Connection connection = datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("db/migrations/008_alarm_master_data.sql"));
        }
    }
    private AlarmConfigurationService transactional(AlarmConfigurationRepository repository) {
        var proxy = new ProxyFactory(new AlarmConfigurationService(access, repository));
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(datasource), new AnnotationTransactionAttributeSource()));
        return (AlarmConfigurationService) proxy.getProxy();
    }
}

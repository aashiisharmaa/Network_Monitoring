package com.network.monitoring.architecture;

import jakarta.persistence.Entity;
import java.nio.file.Path;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import com.network.monitoring.kpi.service.KpiDynamicDataService;
import com.network.monitoring.spatial.service.SitePredictionService;
import static org.mockito.Mockito.*;

/** Opt-in bootstrap for disposable test schemas only; never uses app datasource settings. */
class IsolatedProjectDatabaseTest {
    @Test
    @EnabledIfSystemProperty(named="project.fixture.bootstrap",matches="true")
    void createDisposableKpiSchemaFromCurrentEntitiesAndMigrations() throws Exception {
        String url=System.getenv("KPI_TEST_DB_URL");
        if(url==null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:13316/kpi_project_test(?:\\?.*)?"))
            throw new IllegalStateException("Fixture only accepts disposable localhost:13316/kpi_project_test.");
        var scanner=new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        try(var registry=new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url",url)
                .applySetting("hibernate.connection.username","root")
                .applySetting("hibernate.connection.password","")
                .applySetting("hibernate.hbm2ddl.auto","create-only")
                .applySetting("hibernate.globally_quoted_identifiers",true)
                .applySetting("hibernate.globally_quoted_identifiers_skip_column_definitions",true)
                .applySetting("hibernate.dialect","org.hibernate.dialect.MySQLDialect")
                .applySetting("hibernate.hbm2ddl.halt_on_error",true).build()) {
            var sources=new MetadataSources(registry);
            for(var entity:scanner.findCandidateComponents("com.network.monitoring"))
                sources.addAnnotatedClass(Class.forName(entity.getBeanClassName()));
            try(var factory=sources.buildMetadata().buildSessionFactory()) { /* Create entity schema, no customer rows. */ }
        }
        var ds=new DriverManagerDataSource(url,"root","");var jdbc=new JdbcTemplate(ds);
        new KpiDynamicDataService(jdbc);
        var predictions=mock(SitePredictionService.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(predictions,"jdbcTemplate",jdbc);
        ReflectionTestUtils.invokeMethod(predictions,"ensurePredictionTable");
        for(int n=1;n<=7;n++) {
            final String prefix=String.format("%03d_",n);
            try(var files=java.nio.file.Files.list(Path.of("db/migrations"))) {
                Path file=files.filter(p->p.getFileName().toString().startsWith(prefix)).findFirst().orElseThrow();
                try(var connection=ds.getConnection()) { ScriptUtils.executeSqlScript(connection,new FileSystemResource(file)); }
            }
        }
    }
}

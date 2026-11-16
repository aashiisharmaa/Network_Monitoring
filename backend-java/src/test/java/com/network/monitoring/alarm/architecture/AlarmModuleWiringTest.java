package com.network.monitoring.alarm.architecture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.monitoring.alarm.api.AlarmWorkflowController;
import com.network.monitoring.alarm.scheduling.AlarmWorkflowWorker;
import com.network.monitoring.alarm.service.AlarmConfigurationService;
import com.network.monitoring.alarm.service.AlarmMailDeliveryService;
import com.network.monitoring.identity.repository.CompanyRepository;
import com.network.monitoring.identity.repository.UserRepository;
import com.network.monitoring.identity.service.RoleAuthorizationService;
import com.network.monitoring.platform.mail.ReportMailTransport;
import com.network.monitoring.platform.mail.config.ReportMailSettings;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

/** Catches missing/ambiguous beans and circular dependencies after moving feature packages. */
class AlarmModuleWiringTest {
    @Test void wholeFeatureWiresWithoutDatabaseOrOutboundSideEffects() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ReportMailTransport transport = mock(ReportMailTransport.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
            context.registerBean(RoleAuthorizationService.class, () -> mock(RoleAuthorizationService.class));
            context.registerBean(UserRepository.class, () -> mock(UserRepository.class));
            context.registerBean(CompanyRepository.class, () -> mock(CompanyRepository.class));
            context.registerBean(ReportMailSettings.class, ReportMailSettings::new);
            context.registerBean(ReportMailTransport.class, () -> transport);
            context.registerBean("alarmWorkflowExecutor", ThreadPoolTaskExecutor.class, () -> {
                var executor = new ThreadPoolTaskExecutor();
                executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(1);
                return executor;
            });
            // Do not enable @Scheduled processing in a wiring-only test.
            context.scan("com.network.monitoring.alarm");
            context.refresh();
            assertNotNull(context.getBean(AlarmWorkflowController.class));
            assertNotNull(context.getBean(AlarmWorkflowWorker.class));
            assertNotNull(context.getBean(AlarmConfigurationService.class));
            assertNotNull(context.getBean(AlarmMailDeliveryService.class));
            verify(jdbc).afterPropertiesSet(); // Spring's normal InitializingBean callback.
            verifyNoMoreInteractions(jdbc);
            verifyNoInteractions(transport);
        }
    }
}

package com.network.monitoring.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class BackgroundJobConfig {
    @Bean("uploadJobExecutor")
    public ThreadPoolTaskExecutor uploadJobExecutor(
            @Value("${app.jobs.upload.workers:1}") int workers,
            @Value("${app.jobs.upload.queue-capacity:4}") int capacity) {
        return bounded("upload-job-", workers, capacity);
    }

    @Bean("rcaJobExecutor")
    public ThreadPoolTaskExecutor rcaJobExecutor(
            @Value("${app.jobs.rca.workers:1}") int workers,
            @Value("${app.jobs.rca.queue-capacity:4}") int capacity) {
        return bounded("rca-job-", workers, capacity);
    }

    @Bean("predictionWarmupExecutor")
    public ThreadPoolTaskExecutor predictionWarmupExecutor(
            @Value("${app.jobs.warmup.workers:1}") int workers,
            @Value("${app.jobs.warmup.queue-capacity:2}") int capacity) {
        return bounded("prediction-warmup-", workers, capacity);
    }

    @Bean("dailyAnalysisExecutor")
    public ThreadPoolTaskExecutor dailyAnalysisExecutor(
            @Value("${app.jobs.analysis.workers:1}") int workers,
            @Value("${app.jobs.analysis.queue-capacity:4}") int capacity) {
        return bounded("daily-analysis-", workers, capacity);
    }

    @Bean("commonReportExecutor")
    public ThreadPoolTaskExecutor commonReportExecutor(
            @Value("${app.jobs.report.workers:1}") int workers,
            @Value("${app.jobs.report.queue-capacity:2}") int capacity) {
        return bounded("common-report-", workers, capacity);
    }

    @Bean("reportMailExecutor")
    public ThreadPoolTaskExecutor reportMailExecutor(
            @Value("${app.jobs.mail.workers:1}") int workers,
            @Value("${app.jobs.mail.queue-capacity:4}") int capacity) {
        return bounded("report-mail-", workers, capacity);
    }

    @Bean("nightlyExecutor") public ThreadPoolTaskExecutor nightlyExecutor() {
        return bounded("nightly-workflow-", 1, 1);
    }
    @Bean("rcaExportExecutor") public ThreadPoolTaskExecutor rcaExportExecutor(){return bounded("rca-export-",1,2);}

    static ThreadPoolTaskExecutor bounded(String prefix, int workers, int capacity) {
        if (workers < 1 || capacity < 0) {
            throw new IllegalArgumentException("Background workers must be positive and queue capacity nonnegative.");
        }
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(capacity);
        executor.setThreadNamePrefix(prefix);
        executor.setTaskDecorator(DelegatingSecurityContextRunnable::new);
        // Never execute rejected work on an HTTP request thread or silently discard a job.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}

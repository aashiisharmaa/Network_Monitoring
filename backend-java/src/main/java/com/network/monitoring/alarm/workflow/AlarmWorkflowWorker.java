package com.network.monitoring.alarm.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class AlarmWorkflowWorker {
    private static final Logger log = LoggerFactory.getLogger(AlarmWorkflowWorker.class);
    private final AlarmWorkflowService workflow;
    private final ThreadPoolTaskExecutor executor;
    private final AlarmAutomationService automation;

    public AlarmWorkflowWorker(AlarmWorkflowService workflow, @Qualifier("alarmWorkflowExecutor") ThreadPoolTaskExecutor executor, AlarmAutomationService automation) {
        this.workflow = workflow; this.executor = executor; this.automation=automation;
    }
    @Scheduled(fixedDelayString = "${app.alarm-workflow.tick-millis:3000}")
    public void tick() {
        if (!workflow.enabled() || executor.getActiveCount() > 0 || executor.getThreadPoolExecutor().getQueue().remainingCapacity() == 0) return;
        try {
            executor.execute(() -> {
                phase("folders",automation::pollSources);
                phase("imports",()->{for(long id:workflow.pending()) workflow.process(id);});
                phase("delivery planning",automation::plan);
                phase("delivery",automation::deliverNext);
            });
        } catch (org.springframework.core.task.TaskRejectedException rejected) {
            log.debug("Alarm executor full; durable queued imports remain available for the next tick");
        } catch (Exception error) {
            log.error("Alarm workflow poll failed; verify migration 009 and database connectivity", error);
        }
    }
    private void phase(String name,Runnable task) {try{task.run();}catch(Exception error){log.error("Alarm workflow {} failed; inspect configuration and database health",name,error);}}
}

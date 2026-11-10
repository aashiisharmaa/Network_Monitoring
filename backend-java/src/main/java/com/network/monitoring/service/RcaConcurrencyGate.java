package com.network.monitoring.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
public class RcaConcurrencyGate {
    private final Semaphore permits;
    private final int waitSeconds;
    public RcaConcurrencyGate(@Value("${app.jobs.rca.workers:1}") int permits,
                               @Value("${app.jobs.rca.permit-wait-seconds:30}") int waitSeconds) {
        if (permits < 1 || waitSeconds < 0) throw new IllegalArgumentException("Invalid RCA concurrency limits.");
        this.permits = new Semaphore(permits, true); this.waitSeconds = waitSeconds;
    }
    PythonProcessRunner.Output execute(List<String> args, Path cwd, long timeout) throws IOException {
        try {
            if (!permits.tryAcquire(waitSeconds, TimeUnit.SECONDS)) throw new IOException("RCA engine is busy. Please retry after current work completes.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new IOException("RCA engine wait interrupted.", ex);
        }
        try { return PythonProcessRunner.execute(args, cwd, timeout); }
        finally { permits.release(); }
    }
}

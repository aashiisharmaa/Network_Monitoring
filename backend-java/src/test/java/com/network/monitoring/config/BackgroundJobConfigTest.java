package com.network.monitoring.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BackgroundJobConfigTest {
    @Test
    void boundedQueueRejectsWithoutCallerExecutionAndSeparatePoolsRemainAvailable() throws Exception {
        var upload = BackgroundJobConfig.bounded("test-upload-", 1, 1);
        var rca = BackgroundJobConfig.bounded("test-rca-", 1, 1);
        upload.initialize();
        rca.initialize();
        var running = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var queued = new CountDownLatch(1);
        var independent = new CountDownLatch(1);
        try {
            upload.execute(() -> {
                running.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            });
            assertTrue(running.await(2, TimeUnit.SECONDS));
            upload.execute(queued::countDown);
            assertThrows(RejectedExecutionException.class, () -> upload.execute(() -> fail("Rejected work ran")));
            assertEquals(1, upload.getPoolSize());
            assertEquals(1, upload.getQueueSize());
            rca.execute(independent::countDown);
            assertTrue(independent.await(2, TimeUnit.SECONDS));
            release.countDown();
            assertTrue(queued.await(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            upload.shutdown();
            rca.shutdown();
        }
    }

    @Test
    void invalidLimitsFailInsteadOfSilentlyCreatingUnboundedWork() {
        assertThrows(IllegalArgumentException.class, () -> BackgroundJobConfig.bounded("test-", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> BackgroundJobConfig.bounded("test-", 1, -1));
    }

    @Test
    void workerPreservesSubmittingIdentityAndDoesNotLeakItToTheNextTask() throws Exception {
        var executor = BackgroundJobConfig.bounded("identity-test-", 1, 1);
        executor.initialize();
        try {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(42L, null, List.of()));
            var first = executor.submit(() -> SecurityContextHolder.getContext().getAuthentication().getPrincipal());
            SecurityContextHolder.clearContext();
            assertEquals(42L, first.get(2, TimeUnit.SECONDS));
            var second = executor.submit(() -> SecurityContextHolder.getContext().getAuthentication());
            assertNull(second.get(2, TimeUnit.SECONDS));
        } finally {
            SecurityContextHolder.clearContext();
            executor.shutdown();
        }
    }
}

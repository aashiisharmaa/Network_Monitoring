package com.network.monitoring.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UploadJobServiceTest {
    @TempDir Path directory;

    @Test
    void rejectedSubmissionFailsPersistedJobAndKeepsStagedFileForSafeRetry() throws Exception {
        var fixture = new Fixture(directory);
        fixture.reject = true;
        var ex = assertThrows(JobCapacityException.class, () -> fixture.start(file()));
        assertEquals(123L, ex.getJobId());
        assertEquals("FAILED", fixture.job.get("status"));
        assertTrue(fixture.results.get(fixture.results.size() - 1).contains("\"retrySafe\":true"));
        assertTrue(Files.exists(Path.of((String) fixture.job.get("filePath"))));
    }

    @Test
    void stagingFailureCannotLeaveAQueuedJob() {
        var fixture = new Fixture(directory);
        var broken = new MockMultipartFile("file", "data.csv", "text/csv", new byte[]{1}) {
            @Override public InputStream getInputStream() throws IOException { throw new IOException("staging failed"); }
        };
        assertThrows(IOException.class, () -> fixture.start(broken));
        assertEquals("FAILED", fixture.job.get("status"));
        verifyNoInteractions(fixture.worker);
    }

    @Test
    void duplicateWorkerClaimDoesNotWriteRows() {
        var fixture = new Fixture(directory);
        fixture.workerClaim = false;
        fixture.service.processKpiUploadJob(123L, directory.resolve("unused.csv"), "data.csv", "text/csv",
                null, null, "append", null, null, false);
        verifyNoInteractions(fixture.upload);
    }

    @Test
    void interruptedOrPartiallyProcessedUploadCannotBeBlindlyRetried() {
        var fixture = new Fixture(directory);
        fixture.job.put("result", Map.of("retrySafe", false));
        assertThrows(IllegalArgumentException.class, () -> fixture.service.retryJob(123L));
        verifyNoInteractions(fixture.worker);
    }

    @Test
    void retryUsesConditionalClaimAndRejectionReturnsToFailed() throws Exception {
        var fixture = new Fixture(directory);
        Path file = Files.writeString(directory.resolve("daily.csv"), "a,b\n1,2");
        fixture.job.put("filePath", file.toString());
        fixture.retryClaim = false;
        assertThrows(IllegalArgumentException.class, () -> fixture.service.retryJob(123L));
        verifyNoInteractions(fixture.worker);
        fixture.retryClaim = true;
        fixture.reject = true;
        assertThrows(JobCapacityException.class, () -> fixture.service.retryJob(123L));
        assertEquals("FAILED", fixture.job.get("status"));
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "daily.csv", "text/csv", "a,b\n1,2".getBytes());
    }

    private static class Fixture {
        boolean reject;
        boolean workerClaim = true;
        boolean retryClaim = true;
        final Map<String, Object> job = new HashMap<>(Map.of("jobId", 123L, "status", "FAILED", "result", Map.of("retrySafe", true)));
        final List<String> results = new ArrayList<>();
        final UploadService upload = mock(UploadService.class);
        final UploadJobService worker;
        final UploadJobService service;

        @SuppressWarnings("unchecked")
        Fixture(Path directory) {
            worker = mock(UploadJobService.class, invocation -> {
                if (reject && invocation.getMethod().getName().equals("processKpiUploadJob")) {
                    throw new RejectedExecutionException("full");
                }
                return RETURNS_DEFAULTS.answer(invocation);
            });
            var jdbc = mock(JdbcTemplate.class, invocation -> {
                Object[] args = invocation.getArguments();
                if (invocation.getMethod().getName().equals("query")) return List.of(job);
                if (invocation.getMethod().getName().equals("update")) {
                    if (args[0] instanceof PreparedStatementCreator) {
                        ((KeyHolder) args[1]).getKeyList().add(Map.of("id", 123L));
                        job.put("status", "QUEUED");
                        return 1;
                    }
                    String sql = (String) args[0];
                    if (sql.contains("AND `status` = 'QUEUED'") && !workerClaim) return 0;
                    if (sql.contains("AND `status` = 'FAILED'") && !retryClaim) return 0;
                    if (sql.contains("SET `filePath`")) job.put("filePath", args[1]);
                    else {
                        job.put("status", args[1]);
                        if (sql.contains("`resultJson` = ?")) results.add((String) args[4]);
                    }
                    return 1;
                }
                return RETURNS_DEFAULTS.answer(invocation);
            });
            ObjectProvider<UploadJobService> provider = mock(ObjectProvider.class);
            when(provider.getObject()).thenReturn(worker);
            service = new UploadJobService(jdbc, upload, provider, directory.toString(), 30);
        }

        void start(MockMultipartFile file) throws IOException {
            service.startKpiUploadJob(file, null, null, "append", null, null, false);
        }
    }
}

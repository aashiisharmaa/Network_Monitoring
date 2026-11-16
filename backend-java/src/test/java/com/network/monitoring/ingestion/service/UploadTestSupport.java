package com.network.monitoring.ingestion.service;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;

/** Test-only access to the post-commit hook; no need to widen production visibility. */
public final class UploadTestSupport {
    private UploadTestSupport() {}
    public static void suppressAfterCommit(UploadService uploads) {
        doNothing().when(uploads).afterDailyCommit(anyLong());
    }
}

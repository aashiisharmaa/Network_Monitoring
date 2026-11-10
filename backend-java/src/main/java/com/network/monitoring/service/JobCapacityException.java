package com.network.monitoring.service;

/** The persistent job has already been marked failed before this reaches the controller. */
public class JobCapacityException extends RuntimeException {
    private final Long jobId;

    public JobCapacityException(Long jobId, String message, Throwable cause) {
        super(message, cause);
        this.jobId = jobId;
    }

    public Long getJobId() { return jobId; }
}

package com.mhi3.updater.operation;

import java.time.Instant;
import java.util.UUID;

public class OperationContext {
    private final String operationId = UUID.randomUUID().toString();
    private final Instant startedAt = Instant.now();
    private Instant finishedAt;
    private boolean canceled;

    public String operationId() { return operationId; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
    public boolean canceled() { return canceled; }

    public void markFinished(boolean canceled) {
        this.canceled = canceled;
        this.finishedAt = Instant.now();
    }
}

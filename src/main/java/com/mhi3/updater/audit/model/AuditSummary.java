package com.mhi3.updater.audit.model;

public class AuditSummary {
    public int eventCount;
    public int warningsCount;
    public int errorsCount;
    public int filesScanned;
    public int filesRead;
    public int filesChanged;
    public int filesWritten;
    public int filesSkipped;
    public int filesFailed;
    public int backupsCreated;
    public int restoresPerformed;
    public int checksumResolvedCount;
    public int checksumUnresolvedCount;
    public int writeSuccessCount;
    public int writeFailureCount;
}

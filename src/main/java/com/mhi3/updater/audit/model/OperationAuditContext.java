package com.mhi3.updater.audit.model;

import com.mhi3.updater.model.AppSettings;

import java.time.Instant;

public class OperationAuditContext {
    public String operationId;
    public String sessionId;
    public Instant startedAt;
    public Instant finishedAt;
    public boolean canceled;
    public String workflow;
    public boolean applyMode;
    public ReportLevel reportLevel = ReportLevel.NORMAL;
    public AppSettings settings;
}

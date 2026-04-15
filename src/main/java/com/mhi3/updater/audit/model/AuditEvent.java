package com.mhi3.updater.audit.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class AuditEvent {
    public String eventId = UUID.randomUUID().toString();
    public String operationId;
    public String sessionId;
    public Instant timestamp = Instant.now();
    public String stage = "";
    public ActionType actionType;
    public ActionStatus status;
    public String message = "";
    public String targetFile;
    public String sourceFile;
    public String tempFile;
    public String backupFile;
    public Long durationMs;
    public String exceptionType;
    public String exceptionMessage;
    public String warning;
    public ReportLevel eventLevel = ReportLevel.NORMAL;
    public Map<String, Object> details = new LinkedHashMap<>();
}

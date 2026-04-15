package com.mhi3.updater.audit.model;

import java.util.ArrayList;
import java.util.List;

public class AuditReport {
    public OperationAuditContext context = new OperationAuditContext();
    public AuditSummary summary = new AuditSummary();
    public final List<AuditEvent> events = new ArrayList<>();
    public final List<ValueChange> valueChanges = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public final List<String> errors = new ArrayList<>();
}

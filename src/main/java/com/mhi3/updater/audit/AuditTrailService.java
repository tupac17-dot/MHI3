package com.mhi3.updater.audit;

import com.mhi3.updater.audit.model.*;
import com.mhi3.updater.model.AppSettings;
import com.mhi3.updater.model.ChangeItem;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

public class AuditTrailService {
    private final AuditReport report = new AuditReport();

    public void startOperation(String operationId,
            String sessionId,
            String workflow,
            AppSettings settings,
            boolean applyMode,
            ReportLevel reportLevel) {
        report.context.operationId = operationId;
        report.context.sessionId = sessionId;
        report.context.startedAt = Instant.now();
        report.context.workflow = workflow;
        report.context.settings = settings;
        report.context.applyMode = applyMode;
        report.context.reportLevel = reportLevel;
    }

    public AuditEvent event(ActionType actionType,
            ActionStatus status,
            String message,
            ReportLevel level,
            Consumer<AuditEvent> enricher) {
        AuditEvent event = new AuditEvent();
        event.operationId = report.context.operationId;
        event.sessionId = report.context.sessionId;
        event.actionType = actionType;
        event.status = status;
        event.message = message;
        event.eventLevel = level;
        if (enricher != null) {
            enricher.accept(event);
        }
        report.events.add(event);
        updateSummary(event);
        return event;
    }

    public AuditEvent event(ActionType actionType, ActionStatus status, String message) {
        return event(actionType, status, message, ReportLevel.NORMAL, null);
    }

    public void warning(String warning, Consumer<AuditEvent> enricher) {
        report.warnings.add(warning);
        event(ActionType.WARNING, ActionStatus.SKIPPED, warning, ReportLevel.NORMAL, enricher);
    }

    public void error(String error, Exception e, Consumer<AuditEvent> enricher) {
        report.errors.add(error);
        event(ActionType.ERROR, ActionStatus.FAILED, error, ReportLevel.NORMAL, evt -> {
            if (e != null) {
                evt.exceptionType = e.getClass().getName();
                evt.exceptionMessage = e.getMessage();
            }
            if (enricher != null) {
                enricher.accept(evt);
            }
        });
    }

    public void addValueChanges(List<ChangeItem> changes, String reason, String category) {
        for (ChangeItem c : changes) {
            ValueChange valueChange = new ValueChange();
            valueChange.file = c.file() == null ? "" : c.file().toString();
            valueChange.field = c.jsonField();
            valueChange.oldValue = c.oldValue();
            valueChange.newValue = c.newValue();
            valueChange.changed = !String.valueOf(c.oldValue()).equals(String.valueOf(c.newValue()));
            valueChange.reason = reason;
            valueChange.category = category;
            report.valueChanges.add(valueChange);

            event(ActionType.VALUE_CHANGE,
                    valueChange.changed ? ActionStatus.SUCCESS : ActionStatus.SKIPPED,
                    "Value change tracked: " + valueChange.field,
                    ReportLevel.NORMAL,
                    evt -> {
                        evt.targetFile = valueChange.file;
                        evt.details.put("field", valueChange.field);
                        evt.details.put("oldValue", valueChange.oldValue);
                        evt.details.put("newValue", valueChange.newValue);
                        evt.details.put("changed", valueChange.changed);
                        evt.details.put("reason", valueChange.reason);
                        evt.details.put("category", valueChange.category);
                    });
        }
    }

    public void finishOperation(boolean canceled) {
        report.context.canceled = canceled;
        report.context.finishedAt = Instant.now();
    }

    public AuditReport report() {
        report.summary.eventCount = report.events.size();
        return report;
    }

    private void updateSummary(AuditEvent event) {
        AuditSummary summary = report.summary;
        if (event.actionType == ActionType.WARNING) {
            summary.warningsCount++;
        }
        if (event.actionType == ActionType.ERROR || event.status == ActionStatus.FAILED) {
            summary.errorsCount++;
        }

        switch (event.actionType) {
            case FILE_DISCOVERED -> summary.filesScanned++;
            case FILE_READ -> {
                if (event.status == ActionStatus.SUCCESS) {
                    summary.filesRead++;
                } else if (event.status == ActionStatus.FAILED) {
                    summary.filesFailed++;
                } else if (event.status == ActionStatus.SKIPPED) {
                    summary.filesSkipped++;
                }
            }
            case FILE_WRITE -> {
                if (event.status == ActionStatus.SUCCESS) {
                    summary.filesWritten++;
                    summary.writeSuccessCount++;
                } else if (event.status == ActionStatus.FAILED) {
                    summary.writeFailureCount++;
                } else if (event.status == ActionStatus.SKIPPED) {
                    summary.filesSkipped++;
                }
            }
            case BACKUP_CREATE -> {
                if (event.status == ActionStatus.SUCCESS) {
                    summary.backupsCreated++;
                }
            }
            case RESTORE -> {
                if (event.status == ActionStatus.SUCCESS) {
                    summary.restoresPerformed++;
                }
            }
            case CHECKSUM_RESOLUTION -> {
                Object unresolved = event.details.get("unresolved");
                if (Boolean.TRUE.equals(unresolved)) {
                    summary.checksumUnresolvedCount++;
                } else if (event.status == ActionStatus.SUCCESS) {
                    summary.checksumResolvedCount++;
                }
            }
            case VALUE_CHANGE -> {
                if (event.status == ActionStatus.SUCCESS) {
                    summary.filesChanged++;
                }
            }
            default -> {
            }
        }
    }
}

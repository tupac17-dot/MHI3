package com.mhi3.updater.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mhi3.updater.audit.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public class AuditReportService {
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public void export(Path out, AuditReport report, ReportLevel level) throws IOException {
        String name = out.getFileName().toString().toLowerCase();
        if (name.endsWith(".jsonl")) {
            exportJsonl(out, report, level);
        } else if (name.endsWith(".json")) {
            exportJson(out, report, level);
        } else if (name.endsWith(".csv")) {
            exportCsv(out, report, level);
        } else if (name.endsWith(".html")) {
            exportHtml(out, report, level);
        } else {
            exportTxt(out, report, level);
        }
    }

    private void exportJson(Path out, AuditReport report, ReportLevel level) throws IOException {
        mapper.writeValue(out.toFile(), filtered(report, level));
    }

    private void exportJsonl(Path out, AuditReport report, ReportLevel level) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (AuditEvent event : report.events) {
            if (!level.includes(event.eventLevel)) {
                continue;
            }
            sb.append(mapper.writeValueAsString(event)).append("\n");
        }
        Files.writeString(out, sb.toString());
    }

    private void exportCsv(Path out, AuditReport report, ReportLevel level) throws IOException {
        StringBuilder sb = new StringBuilder(
                "timestamp,operationId,actionType,status,message,targetFile,durationMs,exceptionType,warning\n");
        for (AuditEvent e : report.events) {
            if (!level.includes(e.eventLevel)) {
                continue;
            }
            sb.append(esc(String.valueOf(e.timestamp))).append(',')
                    .append(esc(e.operationId)).append(',')
                    .append(esc(e.actionType == null ? "" : e.actionType.name())).append(',')
                    .append(esc(e.status == null ? "" : e.status.name())).append(',')
                    .append(esc(e.message)).append(',')
                    .append(esc(e.targetFile)).append(',')
                    .append(esc(e.durationMs == null ? "" : String.valueOf(e.durationMs))).append(',')
                    .append(esc(e.exceptionType)).append(',')
                    .append(esc(e.warning)).append('\n');
        }
        Files.writeString(out, sb.toString());
    }

    private void exportTxt(Path out, AuditReport report, ReportLevel level) throws IOException {
        AuditReport filtered = filtered(report, level);
        StringBuilder sb = new StringBuilder();
        sb.append("Operation summary\n")
                .append("Operation ID: ").append(filtered.context.operationId).append("\n")
                .append("Workflow: ").append(filtered.context.workflow).append("\n")
                .append("Started: ").append(filtered.context.startedAt).append("\n")
                .append("Finished: ").append(filtered.context.finishedAt).append("\n")
                .append("Canceled: ").append(filtered.context.canceled).append("\n\n")
                .append("Events: ").append(filtered.summary.eventCount).append("\n")
                .append("Warnings: ").append(filtered.summary.warningsCount).append("\n")
                .append("Errors: ").append(filtered.summary.errorsCount).append("\n")
                .append("Files read: ").append(filtered.summary.filesRead).append("\n")
                .append("Files written: ").append(filtered.summary.filesWritten).append("\n")
                .append("Files skipped: ").append(filtered.summary.filesSkipped).append("\n\n")
                .append("Timeline of events\n");

        for (AuditEvent event : filtered.events) {
            sb.append(event.timestamp).append(" | ")
                    .append(event.actionType).append(" | ")
                    .append(event.status).append(" | ")
                    .append(event.message).append("\n");
        }

        sb.append("\nBefore/after changes table\n");
        for (ValueChange c : filtered.valueChanges) {
            sb.append(c.file).append(" :: ").append(c.field).append(" :: ")
                    .append(c.oldValue).append(" -> ").append(c.newValue)
                    .append(" (changed=").append(c.changed).append(")\n");
        }

        Files.writeString(out, sb.toString());
    }

    private void exportHtml(Path out, AuditReport report, ReportLevel level) throws IOException {
        AuditReport filtered = filtered(report, level);
        StringBuilder sb = new StringBuilder("<html><body><h2>Operation summary</h2>")
                .append("<p><b>Operation ID:</b> ").append(filtered.context.operationId).append("</p>")
                .append("<p><b>Settings used:</b> ").append(filtered.context.settings == null ? "" : filtered.context.settings.updateMode).append("</p>")
                .append("<h3>Files scanned</h3><p>").append(filtered.summary.filesScanned).append("</p>")
                .append("<h3>Files changed</h3><p>").append(filtered.summary.filesChanged).append("</p>")
                .append("<h3>Files skipped</h3><p>").append(filtered.summary.filesSkipped).append("</p>")
                .append("<h3>Write operations</h3><p>Writes: ").append(filtered.summary.filesWritten)
                .append(", failures: ").append(filtered.summary.writeFailureCount).append("</p>")
                .append("<h3>Checksum resolution results</h3><p>Resolved: ")
                .append(filtered.summary.checksumResolvedCount).append(", unresolved: ")
                .append(filtered.summary.checksumUnresolvedCount).append("</p>")
                .append("<h3>Unresolved checksum targets</h3><ul>");

        filtered.events.stream()
                .filter(e -> e.actionType == ActionType.CHECKSUM_RESOLUTION && Boolean.TRUE.equals(e.details.get("unresolved")))
                .forEach(e -> sb.append("<li>").append(e.details.getOrDefault("token", "")).append(" - ")
                        .append(e.message).append("</li>"));

        sb.append("</ul><h3>Errors and warnings</h3><ul>");
        filtered.errors.forEach(err -> sb.append("<li>ERROR: ").append(err).append("</li>"));
        filtered.warnings.forEach(warn -> sb.append("<li>WARN: ").append(warn).append("</li>"));
        sb.append("</ul><h3>Before/after changes table</h3><table border='1'><tr><th>File</th><th>Field</th><th>Old</th><th>New</th></tr>");

        for (ValueChange change : filtered.valueChanges) {
            sb.append("<tr><td>").append(change.file).append("</td><td>")
                    .append(change.field).append("</td><td>")
                    .append(change.oldValue).append("</td><td>")
                    .append(change.newValue).append("</td></tr>");
        }

        sb.append("</table><h3>Backup/restore activity</h3><p>Backups: ")
                .append(filtered.summary.backupsCreated)
                .append(", restores: ").append(filtered.summary.restoresPerformed)
                .append("</p><h3>Timeline of events</h3><ul>");

        for (AuditEvent event : filtered.events) {
            sb.append("<li>").append(event.timestamp).append(" :: ")
                    .append(event.actionType).append(" :: ")
                    .append(event.status).append(" :: ")
                    .append(event.message).append("</li>");
        }

        sb.append("</ul></body></html>");
        Files.writeString(out, sb.toString());
    }

    private AuditReport filtered(AuditReport report, ReportLevel level) {
        AuditReport out = new AuditReport();
        out.context = report.context;
        out.summary = report.summary;
        out.errors.addAll(report.errors);
        out.warnings.addAll(report.warnings);
        out.events.addAll(report.events.stream().filter(e -> level.includes(e.eventLevel)).collect(Collectors.toList()));

        if (level == ReportLevel.BASIC) {
            out.valueChanges.addAll(report.valueChanges.stream().filter(v -> v.changed).collect(Collectors.toList()));
        } else {
            out.valueChanges.addAll(report.valueChanges);
        }
        out.summary.eventCount = out.events.size();
        return out;
    }

    private String esc(String v) {
        if (v == null) {
            return "\"\"";
        }
        return '"' + v.replace("\"", "\"\"") + '"';
    }
}

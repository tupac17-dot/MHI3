package com.mhi3.updater.report;

import com.mhi3.updater.audit.AuditReportService;
import com.mhi3.updater.audit.model.AuditReport;
import com.mhi3.updater.audit.model.ReportLevel;

import java.io.IOException;
import java.nio.file.Path;

public class ReportService {
    private final AuditReportService auditReportService = new AuditReportService();

    public void export(Path out, AuditReport report, ReportLevel level) throws IOException {
        auditReportService.export(out, report, level);
    }
}

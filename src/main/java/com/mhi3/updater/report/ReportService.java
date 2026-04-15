package com.mhi3.updater.report;

import com.mhi3.updater.model.ChangeItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ReportService {
    public void exportCsv(Path out, List<ChangeItem> changes) throws IOException {
        StringBuilder sb = new StringBuilder("file,field,oldValue,newValue,checksumImpacted\n");
        for (ChangeItem c : changes) {
            sb.append(esc(c.file().toString())).append(',')
                    .append(esc(c.jsonField())).append(',')
                    .append(esc(c.oldValue())).append(',')
                    .append(esc(c.newValue())).append(',')
                    .append(c.checksumImpacted()).append('\n');
        }
        Files.writeString(out, sb.toString());
    }

    public void exportTxt(Path out, String summary, List<ChangeItem> changes) throws IOException {
        StringBuilder sb = new StringBuilder(summary).append("\n\n");
        changes.forEach(c -> sb.append(c.file()).append(" :: ").append(c.jsonField()).append(" :: ")
                .append(c.oldValue()).append(" -> ").append(c.newValue()).append("\n"));
        Files.writeString(out, sb.toString());
    }

    public void exportHtml(Path out, String summary, List<ChangeItem> changes) throws IOException {
        StringBuilder sb = new StringBuilder("<html><body><h2>Update Report</h2><pre>")
                .append(summary).append("</pre><table border='1'><tr><th>File</th><th>Field</th><th>Old</th><th>New</th></tr>");
        for (ChangeItem c : changes) {
            sb.append("<tr><td>").append(c.file()).append("</td><td>").append(c.jsonField())
                    .append("</td><td>").append(c.oldValue()).append("</td><td>").append(c.newValue()).append("</td></tr>");
        }
        sb.append("</table></body></html>");
        Files.writeString(out, sb.toString());
    }

    private String esc(String v) {
        if (v == null) return "";
        return '"' + v.replace("\"", "\"\"") + '"';
    }
}

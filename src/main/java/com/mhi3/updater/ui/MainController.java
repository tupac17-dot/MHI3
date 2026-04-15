package com.mhi3.updater.ui;

import com.mhi3.updater.audit.AuditTrailService;
import com.mhi3.updater.audit.model.*;
import com.mhi3.updater.backup.BackupHistoryService;
import com.mhi3.updater.backup.FileBackupService;
import com.mhi3.updater.backup.model.BackupMetadata;
import com.mhi3.updater.backup.model.BackupSession;
import com.mhi3.updater.backup.model.RestoreRequest;
import com.mhi3.updater.model.*;
import com.mhi3.updater.operation.CancellationToken;
import com.mhi3.updater.report.ReportService;
import com.mhi3.updater.scanner.FolderScannerService;
import com.mhi3.updater.updater.UpdateCoordinator;
import com.mhi3.updater.util.VersionTransformService;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

public class MainController {
    private final Stage stage;
    private final Preferences prefs = Preferences.userNodeForPackage(MainController.class);

    private final TextField rootFolderField = new TextField();
    private final TextField targetVersionField = new TextField("P4368");
    private final Label muLabel = new Label("-");
    private final Label wildcardLabel = new Label("-");
    private final CheckBox recursiveCb = new CheckBox("Scan subfolders");
    private final CheckBox updateMnfCb = new CheckBox("Update .mnf");
    private final CheckBox updateCksCb = new CheckBox("Update .mnf.cks");
    private final CheckBox recalcCb = new CheckBox("Recalculate checksums");
    private final CheckBox backupCb = new CheckBox("Create backups");
    private final CheckBox previewCb = new CheckBox("Preview only");
    private final CheckBox appendTrainCb = new CheckBox("Append SupportedTrains if missing");
    private final CheckBox replaceLatestTrainCb = new CheckBox("Replace only latest SupportedTrains wildcard");
    private final ComboBox<ReportLevel> reportLevelCombo = new ComboBox<>();
    private final CheckBox exportDiagnosticCb = new CheckBox("Export full diagnostic report");
    private final Label operationIdLabel = new Label("Operation: -");
    private final Label auditStatsLabel = new Label("Events: 0 | Warnings: 0 | Errors: 0 | Read: 0 | Written: 0 | Skipped: 0");
    private final ComboBox<UpdateMode> modeCombo = new ComboBox<>();
    private final ProgressBar progress = new ProgressBar(0);
    private final Label summaryLabel = new Label("Ready");
    private final TextArea logArea = new TextArea();
    private final TreeView<String> treeView = new TreeView<>();
    private final TableView<ChangeItem> changeTable = new TableView<>();
    private final TableView<FileRecord> fileTable = new TableView<>();
    private final TableView<ChecksumResolutionRow> checksumResolutionTable = new TableView<>();

    private final Button scanBtn = new Button("Scan");
    private final Button previewBtn = new Button("Preview Changes");
    private final Button applyBtn = new Button("Apply Changes");
    private final Button restoreBtn = new Button("Restore Backup");
    private final Button exportBtn = new Button("Export Report");
    private final Button stopBtn = new Button("Stop");

    private final FolderScannerService scanner = new FolderScannerService();
    private final VersionTransformService versionTransform = new VersionTransformService();
    private final UpdateCoordinator coordinator = new UpdateCoordinator();
    private final ReportService reportService = new ReportService();
    private final BackupHistoryService backupHistoryService = new BackupHistoryService();
    private final FileBackupService fileBackupService = new FileBackupService();

    private final AtomicReference<Task<?>> activeTask = new AtomicReference<>();
    private final Map<String, Path> manualMappings = new HashMap<>();

    private ScanResult lastScan;
    private UpdateCoordinator.UpdateResult lastResult;
    private AuditReport lastScanAudit;
    private final List<AuditReport> auditHistory = new ArrayList<>();

    public MainController(Stage stage) {
        this.stage = stage;
    }

    public Parent buildUi() {
        rootFolderField.setPrefWidth(520);
        rootFolderField.setText(prefs.get("last.root", ""));
        recursiveCb.setSelected(true);
        updateMnfCb.setSelected(true);
        updateCksCb.setSelected(false);
        recalcCb.setSelected(false);
        backupCb.setSelected(true);
        previewCb.setSelected(false);
        appendTrainCb.setSelected(true);
        replaceLatestTrainCb.setSelected(false);
        modeCombo.getItems().setAll(UpdateMode.values());
        reportLevelCombo.getItems().setAll(ReportLevel.values());
        reportLevelCombo.setValue(ReportLevel.NORMAL);
        exportDiagnosticCb.setSelected(true);
        modeCombo.setValue(UpdateMode.APPEND_SUPPORTED_TRAINS_ONLY);
        modeCombo.setOnAction(e -> syncUiForMode(modeCombo.getValue()));
        syncUiForMode(modeCombo.getValue());

        Button browse = new Button("Browse");
        browse.setOnAction(e -> chooseFolder());
        targetVersionField.textProperty().addListener((obs, o, n) -> deriveVersionLabels());
        deriveVersionLabels();

        HBox row1 = new HBox(10, new Label("Root folder:"), rootFolderField, browse, new Label("Target version:"),
                targetVersionField);
        HBox row2 = new HBox(20, new Label("MUVersion:"), muLabel, new Label("Wildcard:"), wildcardLabel);
        HBox row3 = new HBox(10, new Label("Mode:"), modeCombo, new Label("Report level:"), reportLevelCombo, exportDiagnosticCb);
        FlowPane options = new FlowPane(10, 8, recursiveCb, updateMnfCb, updateCksCb, recalcCb, backupCb, previewCb,
                appendTrainCb, replaceLatestTrainCb);

        stopBtn.setDisable(true);
        restoreBtn.setDisable(true);
        scanBtn.setOnAction(e -> runScan());
        previewBtn.setOnAction(e -> runUpdate(false));
        applyBtn.setOnAction(e -> runUpdate(true));
        restoreBtn.setOnAction(e -> openRestoreDialog());
        exportBtn.setOnAction(e -> exportReport());
        stopBtn.setOnAction(e -> stopActiveTask());

        HBox buttons = new HBox(10, scanBtn, previewBtn, applyBtn, restoreBtn, exportBtn, stopBtn);

        setupTables();
        SplitPane center = new SplitPane(
                titled("Folder tree", treeView),
                titled("Matched files", fileTable),
                titled("Detected old/new changes", changeTable),
                titled("Checksum resolution", checksumResolutionTable));
        center.setDividerPositions(0.2, 0.42, 0.72, 0.95);

        Button mapUnresolvedBtn = new Button("Map unresolved checksums");
        mapUnresolvedBtn.setOnAction(e -> mapUnresolvedChecksums());

        logArea.setPrefRowCount(8);
        VBox root = new VBox(10, row1, row2, row3, options, buttons, mapUnresolvedBtn, progress, summaryLabel, operationIdLabel, auditStatsLabel, center,
                titled("Log / unresolved checksum targets", logArea));
        root.setPadding(new Insets(10));
        return root;
    }

    private void setupTables() {
        TableColumn<FileRecord, String> rel = new TableColumn<>("Relative Path");
        rel.setCellValueFactory(v -> new SimpleStringProperty(
                v.getValue().relativePath() == null ? "" : v.getValue().relativePath().toString()));

        TableColumn<FileRecord, String> ext = new TableColumn<>("Ext");
        ext.setCellValueFactory(v -> new SimpleStringProperty(
                v.getValue().extension() == null ? "" : v.getValue().extension()));

        fileTable.getColumns().setAll(rel, ext);

        TableColumn<ChangeItem, String> f = new TableColumn<>("File");
        f.setCellValueFactory(v -> new SimpleStringProperty(
                v.getValue().file() == null ? "" : v.getValue().file().toString()));

        TableColumn<ChangeItem, String> field = new TableColumn<>("JSON field");
        field.setCellValueFactory(v -> new SimpleStringProperty(
                v.getValue().jsonField() == null ? "" : v.getValue().jsonField()));

        TableColumn<ChangeItem, String> old = new TableColumn<>("Old value");
        old.setCellValueFactory(v -> new SimpleStringProperty(
                v.getValue().oldValue() == null ? "" : v.getValue().oldValue()));

        TableColumn<ChangeItem, String> nw = new TableColumn<>("New value");
        nw.setCellValueFactory(v -> new SimpleStringProperty(
                v.getValue().newValue() == null ? "" : v.getValue().newValue()));

        TableColumn<ChangeItem, String> chks = new TableColumn<>("Checksum impacted");
        chks.setCellValueFactory(v -> new SimpleStringProperty(
                Boolean.toString(v.getValue().checksumImpacted())));

        changeTable.getColumns().setAll(f, field, old, nw, chks);

        TableColumn<ChecksumResolutionRow, String> target = new TableColumn<>("Checksum target");
        target.setCellValueFactory(v -> new SimpleStringProperty(
                v.getValue().checksumTarget() == null ? "" : v.getValue().checksumTarget()));

        TableColumn<ChecksumResolutionRow, String> resolved = new TableColumn<>("Resolved file");
        resolved.setCellValueFactory(v -> new SimpleStringProperty(
                v.getValue().resolvedFile() == null ? "" : v.getValue().resolvedFile()));

        TableColumn<ChecksumResolutionRow, String> type = new TableColumn<>("Resolution type");
        type.setCellValueFactory(v -> new SimpleStringProperty(
                v.getValue().resolutionType() == null ? "" : v.getValue().resolutionType().name()));

        TableColumn<ChecksumResolutionRow, String> conf = new TableColumn<>("Confidence");
        conf.setCellValueFactory(v -> new SimpleStringProperty(
                String.format("%.2f", v.getValue().confidence())));

        TableColumn<ChecksumResolutionRow, String> action = new TableColumn<>("Needs user action");
        action.setCellValueFactory(v -> new SimpleStringProperty(
                Boolean.toString(v.getValue().needsUserAction())));

        TableColumn<ChecksumResolutionRow, String> note = new TableColumn<>("Note");
        note.setCellValueFactory(v -> new SimpleStringProperty(
                v.getValue().note() == null ? "" : v.getValue().note()));

        checksumResolutionTable.getColumns().setAll(target, resolved, type, conf, action, note);
    }

    private TitledPane titled(String title, javafx.scene.Node node) {
        TitledPane t = new TitledPane(title, node);
        t.setCollapsible(false);
        return t;
    }

    private void chooseFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        if (!rootFolderField.getText().isBlank())
            chooser.setInitialDirectory(Path.of(rootFolderField.getText()).toFile());
        var selected = chooser.showDialog(stage);
        if (selected != null) {
            rootFolderField.setText(selected.getAbsolutePath());
            prefs.put("last.root", selected.getAbsolutePath());
            updateRestoreButtonState();
        }
    }

    private void deriveVersionLabels() {
        try {
            VersionInfo info = versionTransform.derive(targetVersionField.getText());
            muLabel.setText(info.muVersion());
            wildcardLabel.setText(info.wildcardVersion());
        } catch (Exception e) {
            muLabel.setText("invalid");
            wildcardLabel.setText("invalid");
        }
    }

    private AppSettings readSettings() {
        AppSettings s = new AppSettings();
        s.scanSubfolders = recursiveCb.isSelected();
        s.updateMnf = updateMnfCb.isSelected();
        s.updateCks = updateCksCb.isSelected();
        s.recalcChecksums = recalcCb.isSelected();
        s.backup = backupCb.isSelected();
        s.previewOnly = previewCb.isSelected();
        s.appendTrainIfMissing = appendTrainCb.isSelected();
        s.replaceLatestTrainWildcard = replaceLatestTrainCb.isSelected();
        s.updateMode = modeCombo.getValue();
        return s;
    }

    private void syncUiForMode(UpdateMode mode) {
        boolean appendOnlyMode = mode == UpdateMode.APPEND_SUPPORTED_TRAINS_ONLY;

        if (appendOnlyMode) {
            updateMnfCb.setSelected(true);
            appendTrainCb.setSelected(true);
            replaceLatestTrainCb.setSelected(false);
            updateCksCb.setSelected(false);
            recalcCb.setSelected(false);
        }

        updateCksCb.setDisable(appendOnlyMode);
        recalcCb.setDisable(appendOnlyMode);
        appendTrainCb.setDisable(appendOnlyMode);
    }

    private void runScan() {
        Path root = Path.of(rootFolderField.getText());
        AppSettings s = readSettings();
        Task<ScanResult> t = new Task<>() {
            @Override
            protected ScanResult call() throws Exception {
                updateProgress(0.1, 1);
                AuditTrailService audit = new AuditTrailService();
                String opId = java.util.UUID.randomUUID().toString();
                audit.startOperation(opId, null, "SCAN", s, false, reportLevelCombo.getValue());
                var res = scanner.scan(root, s.scanSubfolders, this::isCancelled, audit);
                audit.finishOperation(this.isCancelled());
                lastScanAudit = audit.report();
                lastScanAudit.context.operationId = opId;
                updateProgress(1, 1);
                return res;
            }
        };
        bindAndRun(t, res -> {
            lastScan = res;
            fileTable.setItems(FXCollections.observableArrayList(res.getAllFiles()));
            treeView.setRoot(buildTree(root, res.getAllFiles()));
            summaryLabel.setText("Scanned files: " + res.getAllFiles().size() + ", manifests: "
                    + res.getManifestFiles().size() + ", checksum manifests: " + res.getChecksumFiles().size());
            auditHistory.add(lastScanAudit);
            refreshAuditStats(lastScanAudit);
            log("Scan complete.");
        });
    }

    private TreeItem<String> buildTree(Path root, List<FileRecord> files) {
        TreeItem<String> r = new TreeItem<>(root.toString());
        r.setExpanded(true);
        for (FileRecord fr : files)
            r.getChildren().add(new TreeItem<>(fr.relativePath().toString()));
        return r;
    }

    private void runUpdate(boolean apply) {
        if (lastScan == null) {
            runScan();
            return;
        }

        VersionInfo info = versionTransform.derive(targetVersionField.getText());
        AppSettings settings = readSettings();

        // Apply button must always write changes
        settings.previewOnly = !apply;

        Task<UpdateCoordinator.UpdateResult> t = new Task<>() {
            @Override
            protected UpdateCoordinator.UpdateResult call() {
                updateProgress(0.2, 1);
                var r = coordinator.process(
                        Path.of(rootFolderField.getText()),
                        lastScan,
                        info,
                        settings,
                        apply,
                        this::isCancelled,
                        manualMappings,
                        reportLevelCombo.getValue());
                updateProgress(1, 1);
                return r;
            }
        };

        bindAndRun(t, res -> {
            lastResult = res;
            changeTable.setItems(FXCollections.observableArrayList(res.changes));
            checksumResolutionTable.setItems(FXCollections.observableArrayList(res.resolutionRows));
            summaryLabel.setText(res.summary());
            if (res.auditReport != null) {
                auditHistory.add(res.auditReport);
                refreshAuditStats(res.auditReport);
            }

            if (!res.unresolvedChecksumTargets.isEmpty()) {
                log("Unresolved checksum targets: " + String.join(", ", res.unresolvedChecksumTargets));
            }
            if (!res.errors.isEmpty()) {
                log("Errors: " + String.join(" | ", res.errors));
            }
            if (!res.writeLogs.isEmpty()) {
                for (String line : res.writeLogs) {
                    log(line);
                }
            }

            log((apply ? "Apply" : "Preview") + " completed.");
            if (res.auditReport != null) {
                log("Latest operation id: " + res.auditReport.context.operationId);
            }

            if (!res.backupSessionId.isBlank()) {
                log("Backup session created: " + res.backupSessionId);
            }

            updateRestoreButtonState();
        });
    }

    private <T> void bindAndRun(Task<T> task, java.util.function.Consumer<T> onSuccess) {
        Task<?> prior = activeTask.get();
        if (prior != null && prior.isRunning()) {
            log("Another operation is running.");
            return;
        }

        setOperationRunning(true);
        activeTask.set(task);
        progress.progressProperty().unbind();
        progress.progressProperty().bind(task.progressProperty());
        task.setOnSucceeded(e -> {
            setOperationRunning(false);
            activeTask.set(null);
            onSuccess.accept(task.getValue());
        });
        task.setOnCancelled(e -> {
            setOperationRunning(false);
            activeTask.set(null);
            log("Operation canceled.");
        });
        task.setOnFailed(e -> {
            setOperationRunning(false);
            activeTask.set(null);
            log("Task failed: " + task.getException().getMessage());
        });
        Thread thread = new Thread(task, "mhi3-worker");
        thread.setDaemon(true);
        thread.start();
    }

    private void setOperationRunning(boolean running) {
        scanBtn.setDisable(running);
        previewBtn.setDisable(running);
        applyBtn.setDisable(running);
        exportBtn.setDisable(running);
        stopBtn.setDisable(!running);
        if (running)
            restoreBtn.setDisable(true);
        else
            updateRestoreButtonState();
    }

    private void stopActiveTask() {
        Task<?> t = activeTask.get();
        if (t != null && t.isRunning()) {
            t.cancel();
            log("Cancel requested by user.");
        }
    }

    private void exportReport() {
        if (auditHistory.isEmpty()) {
            log("No report data yet.");
            return;
        }

        List<String> operationChoices = auditHistory.stream()
                .map(r -> r.context.operationId + " | " + r.context.workflow + " | started=" + r.context.startedAt)
                .toList();
        ChoiceDialog<String> opDialog = new ChoiceDialog<>(operationChoices.get(operationChoices.size() - 1), operationChoices);
        opDialog.setHeaderText("Select operation report to export");
        Optional<String> selectedOperation = opDialog.showAndWait();
        if (selectedOperation.isEmpty()) {
            return;
        }
        String selectedOpId = selectedOperation.get().split(" \\| ")[0];
        AuditReport selectedReport = auditHistory.stream()
                .filter(r -> Objects.equals(r.context.operationId, selectedOpId))
                .findFirst()
                .orElse(auditHistory.get(auditHistory.size() - 1));

        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName("mhi3-report-" + selectedOpId.substring(0, Math.min(8, selectedOpId.length())));
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("JSON", "*.json"),
                new FileChooser.ExtensionFilter("JSONL", "*.jsonl"),
                new FileChooser.ExtensionFilter("CSV", "*.csv"),
                new FileChooser.ExtensionFilter("TXT", "*.txt"),
                new FileChooser.ExtensionFilter("HTML", "*.html"));
        var file = chooser.showSaveDialog(stage);
        if (file == null)
            return;

        Task<Void> t = new Task<>() {
            @Override
            protected Void call() throws Exception {
                ReportLevel exportLevel = exportDiagnosticCb.isSelected() ? ReportLevel.DIAGNOSTIC : ReportLevel.BASIC;
                reportService.export(file.toPath(), selectedReport, exportLevel);
                return null;
            }
        };
        bindAndRun(t, v -> log("Report exported: " + file));
    }

    private void refreshAuditStats(AuditReport report) {
        if (report == null) {
            return;
        }
        operationIdLabel.setText("Latest operation id: " + report.context.operationId);
        auditStatsLabel.setText("Events: " + report.summary.eventCount
                + " | Warnings: " + report.summary.warningsCount
                + " | Errors: " + report.summary.errorsCount
                + " | Read: " + report.summary.filesRead
                + " | Written: " + report.summary.filesWritten
                + " | Skipped: " + report.summary.filesSkipped);
    }

    private void mapUnresolvedChecksums() {
        if (lastResult == null || lastScan == null) {
            log("No unresolved checksum items to map.");
            return;
        }
        List<String> unresolved = lastResult.resolutionRows.stream()
                .filter(r -> r.needsUserAction() && (r.resolvedFile() == null || r.resolvedFile().isBlank()))
                .map(ChecksumResolutionRow::checksumTarget)
                .distinct().toList();
        if (unresolved.isEmpty()) {
            log("No unresolved checksum targets.");
            return;
        }
        List<String> fileChoices = lastScan.getAllFiles().stream().map(fr -> fr.absolutePath().toString()).sorted()
                .toList();
        for (String token : unresolved) {
            ChoiceDialog<String> dialog = new ChoiceDialog<>(fileChoices.get(0), fileChoices);
            dialog.setHeaderText("Map checksum target: " + token);
            dialog.setContentText("Select file:");
            Optional<String> selected = dialog.showAndWait();
            selected.ifPresent(sel -> manualMappings.put(token, Path.of(sel)));
        }
        log("Manual mappings updated for current session: " + manualMappings.keySet());
        if (lastResult != null && lastResult.auditReport != null) {
            AuditTrailService audit = new AuditTrailService();
            audit.startOperation(lastResult.auditReport.context.operationId, null, "MANUAL_MAPPING", readSettings(), false, reportLevelCombo.getValue());
            audit.event(ActionType.MANUAL_CHECKSUM_MAPPING, ActionStatus.SUCCESS, "Manual checksum mapping updated", ReportLevel.NORMAL, evt -> {
                evt.details.put("mappingCount", manualMappings.size());
                evt.details.put("tokens", new ArrayList<>(manualMappings.keySet()));
            });
            audit.finishOperation(false);
            AuditReport mappingReport = audit.report();
            auditHistory.add(mappingReport);
            refreshAuditStats(mappingReport);
        }
    }

    private void openRestoreDialog() {
        Path root = Path.of(rootFolderField.getText());
        try {
            List<BackupSession> sessions = backupHistoryService.list(root);
            if (sessions.isEmpty()) {
                log("No backup sessions found.");
                restoreBtn.setDisable(true);
                return;
            }

            List<String> sessionChoices = sessions.stream()
                    .map(s -> s.sessionId + " | op=" + s.operationId + " | files=" + s.entries.size()).toList();
            ChoiceDialog<String> sessionDialog = new ChoiceDialog<>(sessionChoices.get(0), sessionChoices);
            sessionDialog.setHeaderText("Select backup session");
            Optional<String> selectedSession = sessionDialog.showAndWait();
            if (selectedSession.isEmpty())
                return;
            String selectedId = selectedSession.get().split(" \\|")[0];
            BackupSession session = sessions.stream().filter(s -> s.sessionId.equals(selectedId)).findFirst()
                    .orElse(null);
            if (session == null)
                return;

            List<String> modes = List.of("Restore selected file", "Restore all from last run",
                    "Restore all from selected session");
            ChoiceDialog<String> modeDialog = new ChoiceDialog<>(modes.get(0), modes);
            modeDialog.setHeaderText("Restore mode");
            Optional<String> selectedMode = modeDialog.showAndWait();
            if (selectedMode.isEmpty())
                return;

            List<BackupMetadata> toRestore;
            if (selectedMode.get().equals("Restore selected file")) {
                List<String> files = session.entries.stream().map(e -> e.originalFile() + " <= " + e.backupFile())
                        .toList();
                ChoiceDialog<String> fileDialog = new ChoiceDialog<>(files.get(0), files);
                fileDialog.setHeaderText("Select file to restore");
                Optional<String> selectedFile = fileDialog.showAndWait();
                if (selectedFile.isEmpty())
                    return;
                String fileLine = selectedFile.get();
                toRestore = session.entries.stream().filter(e -> fileLine.startsWith(e.originalFile().toString()))
                        .limit(1).collect(Collectors.toList());
            } else if (selectedMode.get().equals("Restore all from last run")) {
                toRestore = sessions.get(0).entries;
            } else {
                toRestore = session.entries;
            }

            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setHeaderText("Confirm restore");
            confirm.setContentText("Files to restore: " + toRestore.size() + "\nThese files will be overwritten.");
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK)
                return;

            AuditTrailService restoreAudit = new AuditTrailService();
            String restoreOp = java.util.UUID.randomUUID().toString();
            restoreAudit.startOperation(restoreOp, session.sessionId, "RESTORE", readSettings(), true, reportLevelCombo.getValue());
            restoreAudit.event(ActionType.RESTORE, ActionStatus.STARTED, "Restore started", ReportLevel.NORMAL, evt -> evt.details.put("fileCount", toRestore.size()));
            var restoreResult = fileBackupService.restore(new RestoreRequest(session.sessionId, toRestore));
            restoreAudit.event(ActionType.RESTORE, restoreResult.errors.isEmpty() ? ActionStatus.SUCCESS : ActionStatus.FAILED, "Restore finished", ReportLevel.NORMAL, evt -> evt.details.put("restored", restoreResult.restoredCount));
            restoreAudit.finishOperation(false);
            auditHistory.add(restoreAudit.report());
            refreshAuditStats(restoreAudit.report());
            if (lastResult == null)
                lastResult = new UpdateCoordinator.UpdateResult();
            lastResult.restoredFilesCount = restoreResult.restoredCount;
            String msg = "Restored files: " + restoreResult.restoredCount + ", errors: " + restoreResult.errors.size();
            if (!restoreResult.errors.isEmpty())
                log("Restore errors: " + String.join(" | ", restoreResult.errors));
            summaryLabel.setText(msg);
            log(msg);
            refreshAfterRestore();

            Alert done = new Alert(
                    restoreResult.errors.isEmpty() ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING);
            done.setHeaderText("Restore completed");
            done.setContentText(msg);
            done.showAndWait();
        } catch (Exception e) {
            log("Restore flow failed: " + e.getMessage());
        }
    }

    private void refreshAfterRestore() {
        if (rootFolderField.getText().isBlank())
            return;
        try {
            Path root = Path.of(rootFolderField.getText());
            var res = scanner.scan(root, recursiveCb.isSelected(), CancellationToken.NONE);
            lastScan = res;
            fileTable.setItems(FXCollections.observableArrayList(res.getAllFiles()));
            treeView.setRoot(buildTree(root, res.getAllFiles()));
            changeTable.getItems().clear();
        } catch (Exception e) {
            log("Refresh failed after restore: " + e.getMessage());
        }
    }

    private void updateRestoreButtonState() {
        if (rootFolderField.getText().isBlank()) {
            restoreBtn.setDisable(true);
            return;
        }
        try {
            List<BackupSession> sessions = backupHistoryService.list(Path.of(rootFolderField.getText()));
            restoreBtn.setDisable(sessions.isEmpty());
        } catch (Exception e) {
            restoreBtn.setDisable(true);
        }
    }

    private void log(String message) {
        Platform.runLater(() -> logArea.appendText(message + "\n"));
    }
}

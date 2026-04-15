package com.mhi3.updater.updater;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhi3.updater.audit.AuditTrailService;
import com.mhi3.updater.audit.model.*;
import com.mhi3.updater.backup.BackupHistoryService;
import com.mhi3.updater.backup.FileBackupService;
import com.mhi3.updater.backup.model.BackupMetadata;
import com.mhi3.updater.backup.model.BackupSession;
import com.mhi3.updater.checksum.ChecksumCalculator;
import com.mhi3.updater.checksum.ChecksumResolverService;
import com.mhi3.updater.checksum.model.ResolutionResult;
import com.mhi3.updater.checksum.model.ResolutionType;
import com.mhi3.updater.model.*;
import com.mhi3.updater.operation.CancellationToken;
import com.mhi3.updater.operation.OperationContext;
import com.mhi3.updater.parser.ChecksumManifestParser;
import com.mhi3.updater.parser.ManifestParser;
import com.mhi3.updater.scanner.FileIndexService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;

public class UpdateCoordinator {
    private final ManifestParser manifestParser = new ManifestParser();
    private final ChecksumManifestParser checksumParser = new ChecksumManifestParser();
    private final ChecksumResolverService resolverService = new ChecksumResolverService();
    private final ChecksumCalculator checksumCalculator = new ChecksumCalculator();
    private final FileBackupService backupService = new FileBackupService();
    private final BackupHistoryService backupHistoryService = new BackupHistoryService();

    public UpdateResult process(Path root,
            ScanResult scan,
            VersionInfo target,
            AppSettings settings,
            boolean apply,
            CancellationToken token,
            Map<String, Path> manualMappings,
            ReportLevel reportLevel) {
        OperationContext context = new OperationContext();
        UpdateResult result = new UpdateResult();
        result.operationId = context.operationId();
        result.startedAt = context.startedAt().toString();

        AuditTrailService audit = new AuditTrailService();
        audit.startOperation(context.operationId(), null, apply ? "APPLY" : "PREVIEW", settings, apply, reportLevel);
        audit.event(apply ? ActionType.APPLY : ActionType.PREVIEW,
                ActionStatus.STARTED,
                (apply ? "Apply" : "Preview") + " started",
                ReportLevel.NORMAL,
                evt -> evt.details.put("mode", settings.updateMode.name()));

        FileIndexService index = new FileIndexService();
        index.build(scan.getAllFiles());
        BackupSession backupSession = backupService.createSession(context.operationId());

        try {
            if (settings.updateMnf) {
                for (FileRecord fr : scan.getManifestFiles()) {
                    if (token.isCancelled()) {
                        audit.event(ActionType.CANCEL, ActionStatus.CANCELED, "Operation canceled by user", ReportLevel.NORMAL, null);
                        throw new CancellationException("Operation canceled by user");
                    }
                    long parseStart = System.nanoTime();
                    var parsed = manifestParser.parse(fr.absolutePath());
                    audit.event(ActionType.PARSE, ActionStatus.SUCCESS, "Manifest parsed", ReportLevel.NORMAL, evt -> {
                        evt.targetFile = fr.absolutePath().toString();
                        evt.durationMs = elapsedMs(parseStart);
                        evt.details.put("parser", "ManifestParser");
                        evt.details.put("encoding", "UTF-8");
                    });

                    var changes = manifestParser.applyVersion(fr.absolutePath(), parsed.root, target, settings);
                    audit.addValueChanges(changes, "Manifest mutation", "manifest");
                    if (changes.isEmpty()) {
                        audit.event(ActionType.SKIP, ActionStatus.SKIPPED,
                                "No manifest value changes detected",
                                ReportLevel.NORMAL,
                                evt -> evt.targetFile = fr.absolutePath().toString());
                    }
                    result.changes.addAll(changes);
                    maybeWriteUpdatedFile(fr.absolutePath(), parsed.toBytes(), apply, settings, backupSession, result,
                            "manifest", audit);
                }
            }

            if (settings.updateMode != UpdateMode.APPEND_SUPPORTED_TRAINS_ONLY && settings.updateCks) {
                for (FileRecord fr : scan.getChecksumFiles()) {
                    if (token.isCancelled()) {
                        audit.event(ActionType.CANCEL, ActionStatus.CANCELED, "Operation canceled by user", ReportLevel.NORMAL, null);
                        throw new CancellationException("Operation canceled by user");
                    }
                    long parseStart = System.nanoTime();
                    var parsed = checksumParser.parse(fr.absolutePath());
                    audit.event(ActionType.PARSE, ActionStatus.SUCCESS, "Checksum manifest parsed", ReportLevel.NORMAL, evt -> {
                        evt.targetFile = fr.absolutePath().toString();
                        evt.durationMs = elapsedMs(parseStart);
                        evt.details.put("parser", "ChecksumManifestParser");
                        evt.details.put("encoding", "UTF-8");
                    });

                    var changes = checksumParser.applyVersion(fr.absolutePath(), parsed.root, target);
                    if (settings.recalcChecksums) {
                        recalc(fr.absolutePath(), parsed.root, index, result, changes, token, manualMappings, audit);
                    } else {
                        audit.event(ActionType.CHECKSUM_RECALCULATION, ActionStatus.SKIPPED,
                                "Checksum recalculation skipped",
                                ReportLevel.NORMAL,
                                evt -> evt.details.put("reason", "settings.recalcChecksums=false"));
                    }

                    audit.addValueChanges(changes, "Checksum manifest mutation", "checksum");
                    result.changes.addAll(changes);
                    maybeWriteUpdatedFile(fr.absolutePath(), parsed.toBytes(), apply, settings, backupSession, result,
                            "checksum manifest", audit);
                }
            }

            if (settings.backup && apply && !backupSession.entries.isEmpty()) {
                backupHistoryService.persist(root, backupSession);
                result.backupSessionId = backupSession.sessionId;
                audit.event(ActionType.BACKUP_CREATE, ActionStatus.SUCCESS,
                        "Backup session persisted",
                        ReportLevel.NORMAL,
                        evt -> {
                            evt.details.put("sessionId", backupSession.sessionId);
                            evt.details.put("entries", backupSession.entries.size());
                        });
            }
        } catch (CancellationException e) {
            result.canceled = true;
            result.errors.add(e.getMessage());
        } catch (Exception e) {
            String fatal = "Unexpected update failure: " + formatException(e);
            result.errors.add(fatal);
            result.writeLogs.add("FATAL: update pipeline aborted: " + formatException(e));
            audit.error(fatal, e, null);
        }

        context.markFinished(result.canceled);
        result.finishedAt = context.finishedAt().toString();
        result.scannedFiles = scan.getAllFiles().size();
        result.manifestCount = scan.getManifestFiles().size();
        result.checksumCount = scan.getChecksumFiles().size();

        audit.event(apply ? ActionType.APPLY : ActionType.PREVIEW,
                result.canceled ? ActionStatus.CANCELED : ActionStatus.SUCCESS,
                (apply ? "Apply" : "Preview") + " finished",
                ReportLevel.NORMAL,
                evt -> evt.details.put("errors", result.errors.size()));
        audit.finishOperation(result.canceled);
        result.auditReport = audit.report();
        return result;
    }

    private void recalc(Path manifestFile,
            ObjectNode root,
            FileIndexService index,
            UpdateResult result,
            List<ChangeItem> changes,
            CancellationToken token,
            Map<String, Path> manualMappings,
            AuditTrailService audit) {
        String algorithm = root.path("ChecksumAlgorithm").asText("SHA1");
        JsonNode arr = root.get("PackageChecksumList");
        if (arr instanceof ArrayNode list) {
            for (int i = 0; i < list.size(); i++) {
                if (token.isCancelled()) {
                    audit.event(ActionType.CANCEL, ActionStatus.CANCELED, "Operation canceled during checksum phase", ReportLevel.NORMAL, null);
                    throw new CancellationException("Operation canceled during checksum phase");
                }
                JsonNode item = list.get(i);
                if (item instanceof ObjectNode obj && obj.has("PackageName")) {
                    String tokenName = obj.get("PackageName").asText();
                    long resolveStart = System.nanoTime();
                    ResolutionResult resolved = resolverService.resolveTarget(tokenName, index, manualMappings);
                    result.resolutionRows.add(new ChecksumResolutionRow(
                            tokenName,
                            resolved.resolvedFile() == null ? "" : resolved.resolvedFile().toString(),
                            resolved.resolutionType(),
                            resolved.confidence(),
                            resolved.needsUserAction(),
                            resolved.note()));
                    incrementResolutionCounts(result, resolved.resolutionType());

                    boolean unresolved = resolved.resolutionType() == ResolutionType.UNRESOLVED || resolved.resolvedFile() == null;
                    audit.event(ActionType.CHECKSUM_RESOLUTION,
                            unresolved ? ActionStatus.SKIPPED : ActionStatus.SUCCESS,
                            unresolved ? "Checksum target unresolved" : "Checksum target resolved",
                            ReportLevel.NORMAL,
                            evt -> {
                                evt.targetFile = manifestFile.toString();
                                evt.durationMs = elapsedMs(resolveStart);
                                evt.details.put("token", tokenName);
                                evt.details.put("resolvedFile", resolved.resolvedFile() == null ? "" : resolved.resolvedFile().toString());
                                evt.details.put("resolutionType", resolved.resolutionType().name());
                                evt.details.put("confidence", resolved.confidence());
                                evt.details.put("note", resolved.note());
                                evt.details.put("manualMappingsCount", manualMappings.size());
                                evt.details.put("unresolved", unresolved);
                            });

                    if (unresolved) {
                        result.unresolvedChecksumTargets.add(tokenName);
                        continue;
                    }
                    try {
                        long recalcStart = System.nanoTime();
                        String sum = checksumCalculator.calculate(resolved.resolvedFile(), algorithm);
                        String old = obj.path("CheckSum").asText("");
                        if (!sum.equalsIgnoreCase(old)) {
                            obj.put("CheckSum", sum);
                            result.checksumEntriesUpdated++;
                            changes.add(new ChangeItem(manifestFile, "PackageChecksumList[" + i + "].CheckSum", old,
                                    sum, true));
                        }
                        audit.event(ActionType.CHECKSUM_RECALCULATION, ActionStatus.SUCCESS,
                                "Checksum recalculated",
                                ReportLevel.DIAGNOSTIC,
                                evt -> {
                                    evt.targetFile = resolved.resolvedFile().toString();
                                    evt.durationMs = elapsedMs(recalcStart);
                                    evt.details.put("algorithm", algorithm);
                                    evt.details.put("token", tokenName);
                                    evt.details.put("checksumChanged", !sum.equalsIgnoreCase(old));
                                });
                    } catch (IOException e) {
                        String msg = "Checksum error for " + tokenName + ": " + e.getMessage();
                        result.errors.add(msg);
                        audit.error(msg, e, evt -> evt.targetFile = manifestFile.toString());
                    }
                }
            }
        }
    }

    private void incrementResolutionCounts(UpdateResult result, ResolutionType type) {
        switch (type) {
            case EXACT -> result.checksumExactCount++;
            case HEURISTIC -> result.checksumHeuristicCount++;
            case DERIVED -> result.checksumDerivedCount++;
            case UNRESOLVED -> result.checksumUnresolvedCount++;
        }
    }

    private void maybeWriteUpdatedFile(Path target,
            byte[] updatedBytes,
            boolean apply,
            AppSettings settings,
            BackupSession backupSession,
            UpdateResult result,
            String fileType,
            AuditTrailService audit) {
        long begin = System.nanoTime();
        try {
            byte[] originalBytes = Files.readAllBytes(target);
            String originalSerialized = new String(originalBytes, StandardCharsets.UTF_8);
            String updatedSerialized = new String(updatedBytes, StandardCharsets.UTF_8);
            boolean changed = !originalSerialized.equals(updatedSerialized);

            audit.event(ActionType.FILE_READ, ActionStatus.SUCCESS, "File read for write decision", ReportLevel.DIAGNOSTIC,
                    evt -> {
                        evt.targetFile = target.toString();
                        evt.durationMs = elapsedMs(begin);
                        evt.details.put("exists", Files.exists(target));
                        evt.details.put("fileSize", originalBytes.length);
                        evt.details.put("encoding", "UTF-8");
                        evt.details.put("parseSuccess", true);
                        evt.details.put("parserUsed", fileType);
                    });

            result.writeLogs.add("WRITE-CHECK [" + fileType + "] target=" + target
                    + ", apply=" + apply
                    + ", previewOnly=" + settings.previewOnly
                    + ", changed=" + changed
                    + ", originalLength=" + originalBytes.length
                    + ", updatedLength=" + updatedBytes.length);

            if (!apply) {
                audit.event(ActionType.FILE_WRITE, ActionStatus.SKIPPED, "Write skipped by preview request", ReportLevel.NORMAL,
                        evt -> fillWriteDetails(evt, target, null, null, originalBytes.length, updatedBytes.length, changed, true, false, false, false, false, false, false));
                result.writeLogs.add("SKIP-WRITE [" + fileType + "] reason=preview action requested.");
                return;
            }
            if (settings.previewOnly) {
                audit.event(ActionType.FILE_WRITE, ActionStatus.SKIPPED, "Write skipped by settings.previewOnly", ReportLevel.NORMAL,
                        evt -> fillWriteDetails(evt, target, null, null, originalBytes.length, updatedBytes.length, changed, true, false, false, false, false, false, false));
                result.writeLogs.add("SKIP-WRITE [" + fileType + "] reason=settings.previewOnly=true.");
                return;
            }
            if (!changed) {
                audit.event(ActionType.FILE_WRITE, ActionStatus.SKIPPED, "Write skipped because content unchanged", ReportLevel.NORMAL,
                        evt -> fillWriteDetails(evt, target, null, null, originalBytes.length, updatedBytes.length, false, false, true, false, false, false, false, false));
                result.writeLogs.add("SKIP-WRITE [" + fileType + "] reason=no serialized content change.");
                return;
            }

            BackupMetadata backupMetadata = null;
            if (settings.backup) {
                backupMetadata = backupService.backup(target, backupSession);
                BackupMetadata finalBackupMetadata = backupMetadata;
                audit.event(ActionType.BACKUP_CREATE, ActionStatus.SUCCESS, "Backup created", ReportLevel.NORMAL,
                        evt -> {
                            evt.targetFile = target.toString();
                            evt.backupFile = finalBackupMetadata.backupFile().toString();
                        });
            }
            result.writeLogs.add("BACKUP [" + fileType + "] target=" + target + ", created=" + (backupMetadata != null));

            WriteOperationDetails writeDetails = writeAtomically(target, updatedSerialized, result, fileType, audit);
            result.filesChanged++;
            result.writeLogs.add("WRITE-SUCCESS [" + fileType + "] target=" + target);
            BackupMetadata finalBackupMetadata1 = backupMetadata;
            audit.event(ActionType.FILE_WRITE, ActionStatus.SUCCESS, "Write completed", ReportLevel.NORMAL,
                    evt -> fillWriteDetails(evt,
                            target,
                            writeDetails.tempFile,
                            finalBackupMetadata1 == null ? null : finalBackupMetadata1.backupFile(),
                            originalBytes.length,
                            updatedBytes.length,
                            true,
                            false,
                            true,
                            true,
                            writeDetails.tempWriteSuccess,
                            writeDetails.moveSuccess,
                            writeDetails.atomicMoveUsed,
                            writeDetails.fallbackUsed));
        } catch (Exception e) {
            String message = "Write failed for " + target + ": " + formatException(e);
            result.errors.add(message);
            result.writeLogs.add("WRITE-FAIL [" + fileType + "] target=" + target + ", error=" + formatException(e));
            audit.error(message, e, evt -> evt.targetFile = target.toString());
        }
    }

    private void fillWriteDetails(AuditEvent evt,
            Path target,
            Path temp,
            Path backup,
            int originalLength,
            int updatedLength,
            boolean contentChanged,
            boolean previewOnly,
            boolean applyMode,
            boolean writeAttempted,
            boolean tempWriteSuccess,
            boolean moveSuccess,
            boolean atomicMoveUsed,
            boolean fallbackUsed) {
        evt.targetFile = target == null ? null : target.toString();
        evt.tempFile = temp == null ? null : temp.toString();
        evt.backupFile = backup == null ? null : backup.toString();
        evt.details.put("originalLength", originalLength);
        evt.details.put("updatedLength", updatedLength);
        evt.details.put("contentChanged", contentChanged);
        evt.details.put("previewOnly", previewOnly);
        evt.details.put("applyMode", applyMode);
        evt.details.put("writeAttempted", writeAttempted);
        evt.details.put("tempWriteSuccess", tempWriteSuccess);
        evt.details.put("moveReplaceSuccess", moveSuccess);
        evt.details.put("atomicMoveUsed", atomicMoveUsed);
        evt.details.put("fallbackUsed", fallbackUsed);
        evt.details.put("backupCreated", backup != null);
    }

    private WriteOperationDetails writeAtomically(Path target, String content, UpdateResult result, String fileType, AuditTrailService audit)
            throws IOException {
        WriteOperationDetails details = new WriteOperationDetails();
        Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        details.tempFile = temp;

        result.writeLogs.add("TEMP-WRITE-START [" + fileType + "] target=" + target + ", temp=" + temp);
        audit.event(ActionType.TEMP_FILE_WRITE, ActionStatus.STARTED, "Temp write started", ReportLevel.DIAGNOSTIC,
                evt -> {
                    evt.targetFile = target.toString();
                    evt.tempFile = temp.toString();
                });
        try {
            Files.writeString(temp, content);
            details.tempWriteSuccess = true;
            result.writeLogs.add("TEMP-WRITE-SUCCESS [" + fileType + "] temp=" + temp);
            audit.event(ActionType.TEMP_FILE_WRITE, ActionStatus.SUCCESS, "Temp write succeeded", ReportLevel.DIAGNOSTIC,
                    evt -> evt.tempFile = temp.toString());
            try {
                Files.move(temp, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                details.moveSuccess = true;
                details.atomicMoveUsed = true;
                result.writeLogs.add("MOVE-SUCCESS [" + fileType + "] mode=ATOMIC_MOVE target=" + target);
                audit.event(ActionType.FILE_REPLACE, ActionStatus.SUCCESS, "Atomic replace succeeded", ReportLevel.DIAGNOSTIC,
                        evt -> {
                            evt.targetFile = target.toString();
                            evt.tempFile = temp.toString();
                        });
            } catch (IOException atomicMoveEx) {
                details.fallbackUsed = true;
                result.writeLogs.add("MOVE-ATOMIC-FAILED [" + fileType + "] target=" + target
                        + ", error=" + formatException(atomicMoveEx)
                        + ", fallback=REPLACE_EXISTING");
                audit.event(ActionType.FILE_REPLACE, ActionStatus.SKIPPED, "Atomic move failed, fallback to replace", ReportLevel.DIAGNOSTIC,
                        evt -> {
                            evt.targetFile = target.toString();
                            evt.exceptionType = atomicMoveEx.getClass().getName();
                            evt.exceptionMessage = atomicMoveEx.getMessage();
                            evt.details.put("fallback", "REPLACE_EXISTING");
                        });
                Files.move(temp, target,
                        StandardCopyOption.REPLACE_EXISTING);
                details.moveSuccess = true;
                result.writeLogs.add("MOVE-SUCCESS [" + fileType + "] mode=REPLACE_EXISTING target=" + target);
                audit.event(ActionType.FILE_REPLACE, ActionStatus.SUCCESS, "Fallback replace succeeded", ReportLevel.DIAGNOSTIC,
                        evt -> evt.targetFile = target.toString());
            }
            return details;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private String formatException(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getName();
        }
        return e.getClass().getName() + ": " + message;
    }

    private long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }

    private static class WriteOperationDetails {
        private Path tempFile;
        private boolean tempWriteSuccess;
        private boolean moveSuccess;
        private boolean atomicMoveUsed;
        private boolean fallbackUsed;
    }

    public static class UpdateResult {
        public String operationId;
        public String startedAt;
        public String finishedAt;
        public boolean canceled;
        public String backupSessionId = "";
        public int scannedFiles;
        public int manifestCount;
        public int checksumCount;
        public int filesChanged;
        public int checksumEntriesUpdated;
        public int checksumExactCount;
        public int checksumDerivedCount;
        public int checksumHeuristicCount;
        public int checksumUnresolvedCount;
        public int restoredFilesCount;
        public final List<ChecksumResolutionRow> resolutionRows = new ArrayList<>();
        public final List<ChangeItem> changes = new ArrayList<>();
        public final List<String> unresolvedChecksumTargets = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
        public final List<String> writeLogs = new ArrayList<>();
        public AuditReport auditReport;

        public String summary() {
            return "opId=" + operationId +
                    ", started=" + startedAt +
                    ", finished=" + finishedAt +
                    ", canceled=" + canceled +
                    ", backupSession=" + backupSessionId +
                    ", scanned=" + scannedFiles +
                    ", manifests=" + manifestCount +
                    ", checksumManifests=" + checksumCount +
                    ", changedFiles=" + filesChanged +
                    ", checksumUpdated=" + checksumEntriesUpdated +
                    ", checksumExact=" + checksumExactCount +
                    ", checksumDerived=" + checksumDerivedCount +
                    ", checksumHeuristic=" + checksumHeuristicCount +
                    ", checksumUnresolved=" + checksumUnresolvedCount +
                    ", restoredFiles=" + restoredFilesCount +
                    ", errors=" + errors.size() +
                    ", writeLogs=" + writeLogs.size();
        }
    }
}

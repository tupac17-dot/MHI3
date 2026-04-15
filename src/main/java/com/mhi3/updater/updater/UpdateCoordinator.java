package com.mhi3.updater.updater;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhi3.updater.backup.BackupHistoryService;
import com.mhi3.updater.backup.FileBackupService;
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
            Map<String, Path> manualMappings) {
        OperationContext context = new OperationContext();
        UpdateResult result = new UpdateResult();
        result.operationId = context.operationId();
        result.startedAt = context.startedAt().toString();

        FileIndexService index = new FileIndexService();
        index.build(scan.getAllFiles());
        BackupSession backupSession = backupService.createSession(context.operationId());

        try {
            if (settings.updateMnf) {
                for (FileRecord fr : scan.getManifestFiles()) {
                    if (token.isCancelled())
                        throw new CancellationException("Operation canceled by user");
                    var parsed = manifestParser.parse(fr.absolutePath());
                    var changes = manifestParser.applyVersion(fr.absolutePath(), parsed.root, target, settings);
                    result.changes.addAll(changes);
                    if (apply && !settings.previewOnly && !changes.isEmpty()) {
                        if (settings.backup)
                            backupService.backup(fr.absolutePath(), backupSession);
                        atomicWrite(fr.absolutePath(), parsed.toBytes());
                        result.filesChanged++;
                    }
                }
            }

            if (settings.updateCks) {
                for (FileRecord fr : scan.getChecksumFiles()) {
                    if (token.isCancelled())
                        throw new CancellationException("Operation canceled by user");
                    var parsed = checksumParser.parse(fr.absolutePath());
                    var changes = checksumParser.applyVersion(fr.absolutePath(), parsed.root, target);
                    if (settings.recalcChecksums) {
                        recalc(fr.absolutePath(), parsed.root, index, result, changes, token, manualMappings);
                    }
                    result.changes.addAll(changes);
                    if (apply && !settings.previewOnly && !changes.isEmpty()) {
                        if (settings.backup)
                            backupService.backup(fr.absolutePath(), backupSession);
                        atomicWrite(fr.absolutePath(), parsed.toBytes());
                        result.filesChanged++;
                    }
                }
            }

            if (settings.backup && apply && !backupSession.entries.isEmpty()) {
                backupHistoryService.persist(root, backupSession);
                result.backupSessionId = backupSession.sessionId;
            }
        } catch (CancellationException e) {
            result.canceled = true;
            result.errors.add(e.getMessage());
        } catch (Exception e) {
            result.errors.add(e.getMessage());
        }

        context.markFinished(result.canceled);
        result.finishedAt = context.finishedAt().toString();
        result.scannedFiles = scan.getAllFiles().size();
        result.manifestCount = scan.getManifestFiles().size();
        result.checksumCount = scan.getChecksumFiles().size();
        return result;
    }

    private void recalc(Path manifestFile,
            ObjectNode root,
            FileIndexService index,
            UpdateResult result,
            List<ChangeItem> changes,
            CancellationToken token,
            Map<String, Path> manualMappings) {
        String algorithm = root.path("ChecksumAlgorithm").asText("SHA1");
        JsonNode arr = root.get("PackageChecksumList");
        if (arr instanceof ArrayNode list) {
            for (int i = 0; i < list.size(); i++) {
                if (token.isCancelled())
                    throw new CancellationException("Operation canceled during checksum phase");
                JsonNode item = list.get(i);
                if (item instanceof ObjectNode obj && obj.has("PackageName")) {
                    String tokenName = obj.get("PackageName").asText();
                    ResolutionResult resolved = resolverService.resolveTarget(tokenName, index, manualMappings);
                    result.resolutionRows.add(new ChecksumResolutionRow(
                            tokenName,
                            resolved.resolvedFile() == null ? "" : resolved.resolvedFile().toString(),
                            resolved.resolutionType(),
                            resolved.confidence(),
                            resolved.needsUserAction(),
                            resolved.note()));
                    incrementResolutionCounts(result, resolved.resolutionType());

                    if (resolved.resolutionType() == ResolutionType.UNRESOLVED || resolved.resolvedFile() == null) {
                        result.unresolvedChecksumTargets.add(tokenName);
                        continue;
                    }
                    try {
                        String sum = checksumCalculator.calculate(resolved.resolvedFile(), algorithm);
                        String old = obj.path("CheckSum").asText("");
                        if (!sum.equalsIgnoreCase(old)) {
                            obj.put("CheckSum", sum);
                            result.checksumEntriesUpdated++;
                            changes.add(new ChangeItem(manifestFile, "PackageChecksumList[" + i + "].CheckSum", old,
                                    sum, true));
                        }
                    } catch (IOException e) {
                        result.errors.add("Checksum error for " + tokenName + ": " + e.getMessage());
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

    private void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        Files.write(tmp, bytes, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
                    ", errors=" + errors.size();
        }
    }
}

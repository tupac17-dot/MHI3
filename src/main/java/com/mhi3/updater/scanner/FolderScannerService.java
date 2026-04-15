package com.mhi3.updater.scanner;

import com.mhi3.updater.audit.AuditTrailService;
import com.mhi3.updater.audit.model.ActionStatus;
import com.mhi3.updater.audit.model.ActionType;
import com.mhi3.updater.audit.model.ReportLevel;
import com.mhi3.updater.model.FileRecord;
import com.mhi3.updater.model.ScanResult;
import com.mhi3.updater.operation.CancellationToken;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class FolderScannerService {

    public ScanResult scan(Path root, boolean recursive) throws IOException {
        return scan(root, recursive, CancellationToken.NONE);
    }

    public ScanResult scan(Path root, boolean recursive, CancellationToken token) throws IOException {
        return scan(root, recursive, token, null);
    }

    public ScanResult scan(Path root, boolean recursive, CancellationToken token, AuditTrailService audit) throws IOException {
        ScanResult result = new ScanResult();
        AtomicBoolean cancelled = new AtomicBoolean(false);

        if (audit != null) {
            audit.event(ActionType.SCAN, ActionStatus.STARTED, "Scan started", ReportLevel.NORMAL, evt -> {
                evt.targetFile = root.toString();
                evt.details.put("recursive", recursive);
            });
        }

        int maxDepth = recursive ? Integer.MAX_VALUE : 1;

        Files.walkFileTree(root, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), maxDepth,
                new SimpleFileVisitor<>() {

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (token.isCancelled()) {
                            cancelled.set(true);
                            return FileVisitResult.TERMINATE;
                        }

                        if (!dir.equals(root) && shouldSkipDirectory(dir)) {
                            if (audit != null) {
                                audit.event(ActionType.SKIP, ActionStatus.SKIPPED, "Directory skipped", ReportLevel.DIAGNOSTIC, evt -> {
                                    evt.targetFile = dir.toString();
                                    evt.details.put("reason", "system directory");
                                });
                            }
                            return FileVisitResult.SKIP_SUBTREE;
                        }

                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                        if (token.isCancelled()) {
                            cancelled.set(true);
                            return FileVisitResult.TERMINATE;
                        }

                        if (!attrs.isRegularFile()) {
                            return FileVisitResult.CONTINUE;
                        }

                        try {
                            String fileName = path.getFileName().toString();
                            String ext = extension(fileName);

                            FileRecord record = new FileRecord(
                                    path,
                                    root.relativize(path),
                                    ext,
                                    Files.size(path));

                            result.getAllFiles().add(record);
                            if (audit != null) {
                                audit.event(ActionType.FILE_DISCOVERED, ActionStatus.SUCCESS, "File discovered", ReportLevel.DIAGNOSTIC, evt -> {
                                    evt.targetFile = path.toString();
                                    evt.details.put("exists", Files.exists(path));
                                    evt.details.put("size", record.size());
                                    evt.details.put("fileType", ext);
                                });
                            }

                            if (fileName.endsWith(".mnf")) {
                                result.getManifestFiles().add(record);
                            }

                            if (fileName.endsWith(".mnf.cks")) {
                                result.getChecksumFiles().add(record);
                            }
                        } catch (IOException ignored) {
                            if (audit != null) {
                                audit.event(ActionType.FILE_READ, ActionStatus.FAILED, "File metadata read failed", ReportLevel.NORMAL, evt -> {
                                    evt.targetFile = path.toString();
                                    evt.exceptionType = ignored.getClass().getName();
                                    evt.exceptionMessage = ignored.getMessage();
                                });
                            }
                        }

                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path path, IOException exc) {
                        if (token.isCancelled()) {
                            cancelled.set(true);
                            return FileVisitResult.TERMINATE;
                        }

                        if (exc instanceof AccessDeniedException) {
                            if (audit != null) {
                                audit.event(ActionType.FILE_READ, ActionStatus.SKIPPED, "Access denied file skipped", ReportLevel.NORMAL, evt -> {
                                    evt.targetFile = path.toString();
                                    evt.exceptionType = exc.getClass().getName();
                                    evt.exceptionMessage = exc.getMessage();
                                });
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        return FileVisitResult.CONTINUE;
                    }
                });

        if (cancelled.get()) {
            if (audit != null) {
                audit.event(ActionType.CANCEL, ActionStatus.CANCELED, "Scan canceled by user", ReportLevel.NORMAL, null);
            }
            throw new IOException("Scan canceled");
        }

        if (audit != null) {
            audit.event(ActionType.SCAN, ActionStatus.SUCCESS, "Scan completed", ReportLevel.NORMAL, evt -> {
                evt.targetFile = root.toString();
                evt.details.put("filesScanned", result.getAllFiles().size());
                evt.details.put("manifests", result.getManifestFiles().size());
                evt.details.put("checksumManifests", result.getChecksumFiles().size());
            });
        }

        return result;
    }

    private String extension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx < 0 ? "" : fileName.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private boolean shouldSkipDirectory(Path dir) {
        Path name = dir.getFileName();
        if (name == null) {
            return false;
        }

        String folder = name.toString().toLowerCase(Locale.ROOT);

        return folder.equals("system volume information")
                || folder.equals("$recycle.bin");
    }
}
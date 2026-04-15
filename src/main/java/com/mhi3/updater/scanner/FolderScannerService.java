package com.mhi3.updater.scanner;

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
        ScanResult result = new ScanResult();
        AtomicBoolean cancelled = new AtomicBoolean(false);

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

                            if (fileName.endsWith(".mnf")) {
                                result.getManifestFiles().add(record);
                            }

                            if (fileName.endsWith(".mnf.cks")) {
                                result.getChecksumFiles().add(record);
                            }
                        } catch (IOException ignored) {
                            // Skip unreadable file, continue scan
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
                            return FileVisitResult.CONTINUE;
                        }

                        return FileVisitResult.CONTINUE;
                    }
                });

        if (cancelled.get()) {
            throw new IOException("Scan canceled");
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
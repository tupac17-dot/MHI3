package com.mhi3.updater.scanner;

import com.mhi3.updater.model.FileRecord;
import com.mhi3.updater.model.ScanResult;
import com.mhi3.updater.operation.CancellationToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

public class FolderScannerService {
    public ScanResult scan(Path root, boolean recursive) throws IOException {
        return scan(root, recursive, CancellationToken.NONE);
    }

    public ScanResult scan(Path root, boolean recursive, CancellationToken token) throws IOException {
        ScanResult result = new ScanResult();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        try (Stream<Path> stream = recursive ? Files.walk(root) : Files.list(root)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                if (token.isCancelled()) {
                    cancelled.set(true);
                    return;
                }
                String ext = extension(path.getFileName().toString());
                try {
                    FileRecord record = new FileRecord(path, root.relativize(path), ext, Files.size(path));
                    result.getAllFiles().add(record);
                    if (path.getFileName().toString().endsWith(".mnf")) result.getManifestFiles().add(record);
                    if (path.getFileName().toString().endsWith(".mnf.cks")) result.getChecksumFiles().add(record);
                } catch (IOException ignored) {
                }
            });
        }
        if (cancelled.get()) {
            throw new IOException("Scan canceled");
        }
        return result;
    }

    private String extension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx < 0 ? "" : fileName.substring(idx + 1).toLowerCase();
    }
}

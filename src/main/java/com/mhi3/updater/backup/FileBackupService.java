package com.mhi3.updater.backup;

import com.mhi3.updater.backup.model.BackupMetadata;
import com.mhi3.updater.backup.model.BackupSession;
import com.mhi3.updater.backup.model.RestoreRequest;
import com.mhi3.updater.backup.model.RestoreResult;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

public class FileBackupService {
    public BackupSession createSession(String operationId) {
        BackupSession s = new BackupSession();
        s.sessionId = UUID.randomUUID().toString();
        s.operationId = operationId;
        s.startedAt = Instant.now().toString();
        return s;
    }

    public BackupMetadata backup(Path file, BackupSession session) throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Path backup = file.resolveSibling(file.getFileName() + ".bak." + stamp);
        Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        BackupMetadata md = new BackupMetadata(file, backup, stamp, session.operationId);
        session.entries.add(md);
        return md;
    }

    public RestoreResult restore(RestoreRequest request) {
        RestoreResult result = new RestoreResult();
        List<BackupMetadata> entries = request.entries();
        for (BackupMetadata b : entries) {
            try {
                Files.copy(b.backupFile(), b.originalFile(), StandardCopyOption.REPLACE_EXISTING);
                result.restoredCount++;
            } catch (Exception e) {
                result.errors.add("Failed to restore " + b.originalFile() + ": " + e.getMessage());
            }
        }
        return result;
    }
}

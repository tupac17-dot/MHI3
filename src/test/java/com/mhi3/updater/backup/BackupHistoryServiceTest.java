package com.mhi3.updater.backup;

import com.mhi3.updater.backup.model.BackupSession;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BackupHistoryServiceTest {
    @Test
    void persistsAndReadsBackupSessionMetadata() throws Exception {
        BackupHistoryService history = new BackupHistoryService();
        var root = Files.createTempDirectory("backup-history-root");
        BackupSession session = new BackupSession();
        session.sessionId = "session-123";
        session.operationId = "op-123";
        session.startedAt = "2026-01-01T00:00:00Z";

        history.persist(root, session);
        var loaded = history.list(root);
        assertFalse(loaded.isEmpty());
        assertEquals("session-123", loaded.get(0).sessionId);
    }
}

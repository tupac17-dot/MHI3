package com.mhi3.updater.backup;

import com.mhi3.updater.backup.model.RestoreRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileBackupServiceTest {
    @Test
    void restoreSingleFileFromBackup() throws Exception {
        FileBackupService service = new FileBackupService();
        var session = service.createSession("op-1");
        Path file = Files.createTempFile("restore-single", ".txt");
        Files.writeString(file, "original");
        var metadata = service.backup(file, session);
        Files.writeString(file, "changed");

        var result = service.restore(new RestoreRequest(session.sessionId, List.of(metadata)));
        assertEquals(1, result.restoredCount);
        assertEquals("original", Files.readString(file));
    }

    @Test
    void restoreEntireSession() throws Exception {
        FileBackupService service = new FileBackupService();
        var session = service.createSession("op-2");
        Path f1 = Files.createTempFile("restore-all-1", ".txt");
        Path f2 = Files.createTempFile("restore-all-2", ".txt");
        Files.writeString(f1, "a");
        Files.writeString(f2, "b");
        service.backup(f1, session);
        service.backup(f2, session);
        Files.writeString(f1, "x");
        Files.writeString(f2, "y");

        var result = service.restore(new RestoreRequest(session.sessionId, session.entries));
        assertEquals(2, result.restoredCount);
        assertEquals("a", Files.readString(f1));
        assertEquals("b", Files.readString(f2));
    }
}

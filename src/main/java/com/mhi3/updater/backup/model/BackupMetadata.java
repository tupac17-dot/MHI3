package com.mhi3.updater.backup.model;

import java.nio.file.Path;

public record BackupMetadata(Path originalFile, Path backupFile, String timestamp, String operationId) {
}

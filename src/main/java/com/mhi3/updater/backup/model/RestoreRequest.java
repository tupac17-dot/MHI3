package com.mhi3.updater.backup.model;

import java.util.List;

public record RestoreRequest(String sessionId, List<BackupMetadata> entries) {
}

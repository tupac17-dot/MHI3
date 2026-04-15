package com.mhi3.updater.backup.model;

import java.util.ArrayList;
import java.util.List;

public class BackupSession {
    public String sessionId;
    public String operationId;
    public String startedAt;
    public final List<BackupMetadata> entries = new ArrayList<>();
}

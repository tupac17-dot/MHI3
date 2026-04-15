package com.mhi3.updater.backup.model;

import java.util.ArrayList;
import java.util.List;

public class RestoreResult {
    public int restoredCount;
    public final List<String> errors = new ArrayList<>();
}

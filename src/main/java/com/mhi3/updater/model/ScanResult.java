package com.mhi3.updater.model;

import java.util.ArrayList;
import java.util.List;

public class ScanResult {
    private final List<FileRecord> allFiles = new ArrayList<>();
    private final List<FileRecord> manifestFiles = new ArrayList<>();
    private final List<FileRecord> checksumFiles = new ArrayList<>();

    public List<FileRecord> getAllFiles() { return allFiles; }
    public List<FileRecord> getManifestFiles() { return manifestFiles; }
    public List<FileRecord> getChecksumFiles() { return checksumFiles; }
}

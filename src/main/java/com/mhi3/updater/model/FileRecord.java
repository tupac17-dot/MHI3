package com.mhi3.updater.model;

import java.nio.file.Path;

public record FileRecord(Path absolutePath, Path relativePath, String extension, long size) {
    public String fileName() {
        return absolutePath.getFileName().toString();
    }
}

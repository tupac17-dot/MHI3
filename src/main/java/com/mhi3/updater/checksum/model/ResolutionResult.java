package com.mhi3.updater.checksum.model;

import java.nio.file.Path;

public record ResolutionResult(String checksumTarget,
                               Path resolvedFile,
                               ResolutionType resolutionType,
                               double confidence,
                               boolean needsUserAction,
                               String note) {
}

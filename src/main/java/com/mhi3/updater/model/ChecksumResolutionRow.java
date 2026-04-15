package com.mhi3.updater.model;

import com.mhi3.updater.checksum.model.ResolutionType;

public record ChecksumResolutionRow(String checksumTarget,
                                    String resolvedFile,
                                    ResolutionType resolutionType,
                                    double confidence,
                                    boolean needsUserAction,
                                    String note) {
}

package com.mhi3.updater.model;

import java.nio.file.Path;

public record ChangeItem(Path file, String jsonField, String oldValue, String newValue, boolean checksumImpacted) {
}

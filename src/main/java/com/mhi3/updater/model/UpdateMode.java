package com.mhi3.updater.model;

public enum UpdateMode {
    APPEND_SUPPORTED_TRAINS_ONLY("Append SupportedTrains entry only"),
    FULL_MANIFEST_VERSION_REPLACEMENT("Full manifest version replacement");

    private final String label;

    UpdateMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}

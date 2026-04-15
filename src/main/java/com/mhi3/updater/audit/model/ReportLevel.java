package com.mhi3.updater.audit.model;

public enum ReportLevel {
    BASIC,
    NORMAL,
    DIAGNOSTIC;

    public boolean includes(ReportLevel eventLevel) {
        return this.ordinal() >= eventLevel.ordinal();
    }
}

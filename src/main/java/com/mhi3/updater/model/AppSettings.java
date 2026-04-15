package com.mhi3.updater.model;

public class AppSettings {
    public boolean scanSubfolders = true;
    public boolean updateMnf = true;
    public boolean updateCks = false;
    public boolean recalcChecksums = false;
    public boolean backup = true;
    public boolean previewOnly = true;
    public boolean appendTrainIfMissing = true;
    public boolean replaceLatestTrainWildcard = false;
    public UpdateMode updateMode = UpdateMode.APPEND_SUPPORTED_TRAINS_ONLY;
}

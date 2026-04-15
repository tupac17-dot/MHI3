package com.mhi3.updater.operation;

import com.mhi3.updater.model.AppSettings;
import com.mhi3.updater.model.ScanResult;
import com.mhi3.updater.scanner.FolderScannerService;
import com.mhi3.updater.updater.UpdateCoordinator;
import com.mhi3.updater.util.VersionTransformService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellationBehaviorTest {
    @Test
    void cancelRecursiveScanMidRun() throws Exception {
        Path root = Files.createTempDirectory("cancel-scan");
        for (int i = 0; i < 100; i++) Files.writeString(root.resolve("f" + i + ".txt"), "x");

        AtomicInteger checks = new AtomicInteger();
        var token = (CancellationToken) () -> checks.incrementAndGet() > 10;

        FolderScannerService scanner = new FolderScannerService();
        boolean canceled = false;
        try {
            scanner.scan(root, true, token);
        } catch (Exception e) {
            canceled = e.getMessage().toLowerCase().contains("canceled");
        }
        assertTrue(canceled);
    }

    @Test
    void cancelPreviewAndApplyBeforeCompletion() {
        UpdateCoordinator coordinator = new UpdateCoordinator();
        ScanResult empty = new ScanResult();
        AppSettings settings = new AppSettings();
        settings.previewOnly = true;
        var token = (CancellationToken) () -> true;
        var result = coordinator.process(Path.of("."), empty, new VersionTransformService().derive("P4368"), settings, false, token, Map.of());
        assertTrue(result.canceled);

        settings.previewOnly = false;
        result = coordinator.process(Path.of("."), empty, new VersionTransformService().derive("P4368"), settings, true, token, Map.of());
        assertTrue(result.canceled);
    }
}

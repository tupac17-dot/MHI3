package com.mhi3.updater;

import com.mhi3.updater.scanner.FolderScannerService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FolderScannerServiceTest {
    @Test
    void indexesRecursiveFolderAndFindsMnfAndCks() throws Exception {
        var svc = new FolderScannerService();
        var result = svc.scan(Path.of("src/test/resources/sample-data"), true);
        assertEquals(2, result.getAllFiles().size());
        assertEquals(1, result.getManifestFiles().size());
        assertEquals(1, result.getChecksumFiles().size());
    }
}

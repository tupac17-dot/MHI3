package com.mhi3.updater.checksum;

import com.mhi3.updater.checksum.model.ResolutionType;
import com.mhi3.updater.model.FileRecord;
import com.mhi3.updater.scanner.FileIndexService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChecksumResolverServiceTest {
    @Test
    void classifiesExactVsHeuristicAndUnresolved() throws Exception {
        Path root = Files.createTempDirectory("idx-root");
        Path exact = Files.createFile(root.resolve("MHI3_ER_AU_P4368"));
        Path heuristic = Files.createFile(root.resolve("artifact-MHI3_ER_AU_P4368.zip"));

        FileIndexService index = new FileIndexService();
        index.build(List.of(
                new FileRecord(exact, root.relativize(exact), "", 0),
                new FileRecord(heuristic, root.relativize(heuristic), "zip", 0)
        ));

        ChecksumResolverService svc = new ChecksumResolverService();
        assertEquals(ResolutionType.EXACT, svc.resolveTarget("MHI3_ER_AU_P4368", index, Map.of()).resolutionType());
        assertEquals(ResolutionType.HEURISTIC, svc.resolveTarget("MHI3_ER_AU_P436", index, Map.of()).resolutionType());
        assertEquals(ResolutionType.UNRESOLVED, svc.resolveTarget("DOES_NOT_EXIST", index, Map.of()).resolutionType());
    }

    @Test
    void appliesManualMapping() throws Exception {
        Path root = Files.createTempDirectory("manual-map-root");
        Path mapped = Files.createFile(root.resolve("manual.bin"));
        FileIndexService index = new FileIndexService();
        index.build(List.of(new FileRecord(mapped, root.relativize(mapped), "bin", 0)));

        ChecksumResolverService svc = new ChecksumResolverService();
        var result = svc.resolveTarget("TARGET_TOKEN", index, Map.of("TARGET_TOKEN", mapped));
        assertEquals(ResolutionType.HEURISTIC, result.resolutionType());
        assertEquals(mapped, result.resolvedFile());
    }
}

package com.mhi3.updater;

import com.mhi3.updater.parser.ChecksumManifestParser;
import com.mhi3.updater.util.VersionTransformService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChecksumManifestParserTest {
    @Test
    void updatesChecksumManifestVersionedFields() throws Exception {
        Path file = Path.of("src/test/resources/sample-data/main.mnf.cks");
        ChecksumManifestParser parser = new ChecksumManifestParser();
        var parsed = parser.parse(file);
        parser.applyVersion(file, parsed.root, new VersionTransformService().derive("P4368"));
        assertEquals("4368", parsed.root.get("MUVersion").asText());
        assertEquals("MHI3_ER_AU_P4368.idx.json", parsed.root.get("Indexfile").get(0).get("FileName").asText());
        assertEquals("MHI3_ER_AU_P4368", parsed.root.get("PackageChecksumList").get(0).get("PackageName").asText());
    }
}

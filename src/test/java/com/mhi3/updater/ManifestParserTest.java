package com.mhi3.updater;

import com.mhi3.updater.model.AppSettings;
import com.mhi3.updater.parser.ManifestParser;
import com.mhi3.updater.util.VersionTransformService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManifestParserTest {
    @Test
    void updatesManifestFieldsAndWildcard() throws Exception {
        Path file = Path.of("src/test/resources/sample-data/main.mnf");
        ManifestParser parser = new ManifestParser();
        var parsed = parser.parse(file);
        var settings = new AppSettings();
        settings.replaceLatestTrainWildcard = true;
        var changes = parser.applyVersion(file, parsed.root, new VersionTransformService().derive("P4368"), settings);
        assertEquals("MHI3_ER_AU_P4368", parsed.root.get("PackageName").asText());
        assertEquals("4368", parsed.root.get("MUVersion").asText());
        assertEquals("M??3_??_AU_P436*", parsed.root.get("SupportedTrains").get(4).asText());
        assertEquals(true, changes.size() >= 3);
    }
}

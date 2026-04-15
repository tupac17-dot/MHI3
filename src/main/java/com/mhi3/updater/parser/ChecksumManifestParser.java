package com.mhi3.updater.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhi3.updater.model.ChangeItem;
import com.mhi3.updater.model.VersionInfo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class ChecksumManifestParser {
    private static final Pattern PACKAGE_TOKEN = Pattern.compile("P\\d{4}");
    private final ObjectMapper mapper = new ObjectMapper();

    public ManifestParser.ParsedJson parse(Path file) throws IOException {
        JsonNode node = mapper.readTree(file.toFile());
        if (!(node instanceof ObjectNode obj)) throw new IOException("Checksum manifest is not JSON object");
        return new ManifestParser.ParsedJson(obj, mapper);
    }

    public List<ChangeItem> applyVersion(Path file, ObjectNode root, VersionInfo target) {
        List<ChangeItem> changes = new ArrayList<>();
        updateDirect(root, "MUVersion", target.muVersion(), file, changes);

        JsonNode indexFiles = root.get("Indexfile");
        if (indexFiles instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode item = array.get(i);
                if (item instanceof ObjectNode obj && obj.has("FileName")) {
                    String old = obj.get("FileName").asText();
                    String nw = PACKAGE_TOKEN.matcher(old).replaceAll(target.normalizedVersion());
                    if (!old.equals(nw)) {
                        obj.put("FileName", nw);
                        changes.add(new ChangeItem(file, "Indexfile[" + i + "].FileName", old, nw, true));
                    }
                }
            }
        }

        JsonNode packs = root.get("PackageChecksumList");
        if (packs instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode item = array.get(i);
                if (item instanceof ObjectNode obj && obj.has("PackageName")) {
                    String old = obj.get("PackageName").asText();
                    String nw = PACKAGE_TOKEN.matcher(old).replaceAll(target.normalizedVersion());
                    if (!old.equals(nw)) {
                        obj.put("PackageName", nw);
                        changes.add(new ChangeItem(file, "PackageChecksumList[" + i + "].PackageName", old, nw, true));
                    }
                }
            }
        }
        return changes;
    }

    private void updateDirect(ObjectNode node, String field, String value, Path file, List<ChangeItem> changes) {
        if (node.has(field)) {
            String old = node.get(field).asText();
            if (!old.equals(value)) {
                node.put(field, value);
                changes.add(new ChangeItem(file, field, old, value, true));
            }
        }
    }
}

package com.mhi3.updater.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhi3.updater.model.AppSettings;
import com.mhi3.updater.model.ChangeItem;
import com.mhi3.updater.model.VersionInfo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class ManifestParser {
    private static final Pattern PACKAGE_TOKEN = Pattern.compile("P\\d{4}");
    private static final Pattern TRAIN_WILDCARD = Pattern.compile("P\\d{3}\\*");

    private final ObjectMapper mapper = new ObjectMapper();

    public ParsedJson parse(Path file) throws IOException {
        JsonNode node = mapper.readTree(file.toFile());
        if (!(node instanceof ObjectNode obj)) throw new IOException("Manifest is not JSON object");
        return new ParsedJson(obj, mapper);
    }

    public List<ChangeItem> applyVersion(Path file, ObjectNode root, VersionInfo target, AppSettings settings) {
        List<ChangeItem> changes = new ArrayList<>();
        updateString(root, "PackageName", target.normalizedVersion(), file, changes);
        updateString(root, "Release", target.normalizedVersion(), file, changes);
        updateString(root, "MUVersion", target.muVersion(), file, changes);

        JsonNode trains = root.get("SupportedTrains");
        if (trains instanceof ArrayNode array) {
            int latestIdx = -1;
            int bestVer = -1;
            for (int i = 0; i < array.size(); i++) {
                String v = array.get(i).asText();
                var m = TRAIN_WILDCARD.matcher(v);
                if (m.find()) {
                    int ver = Integer.parseInt(m.group().substring(1, 4));
                    if (ver > bestVer) {
                        bestVer = ver;
                        latestIdx = i;
                    }
                }
            }

            boolean replaced = false;
            if (settings.replaceLatestTrainWildcard && latestIdx >= 0) {
                String old = array.get(latestIdx).asText();
                String nw = TRAIN_WILDCARD.matcher(old).replaceAll(target.wildcardVersion());
                array.set(latestIdx, array.textNode(nw));
                changes.add(new ChangeItem(file, "SupportedTrains[" + latestIdx + "]", old, nw, false));
                replaced = true;
            }
            if (settings.appendTrainIfMissing && !containsWildcard(array, target.wildcardVersion())) {
                String prefix = array.size() > 0 ? array.get(0).asText().replaceAll("P\\d{3}\\*", target.wildcardVersion()) : "M??3_??_AU_" + target.wildcardVersion();
                array.add(prefix);
                changes.add(new ChangeItem(file, "SupportedTrains[+]", "", prefix, false));
                replaced = true;
            }
            if (!replaced) {
                for (int i = 0; i < array.size(); i++) {
                    String old = array.get(i).asText();
                    String nw = TRAIN_WILDCARD.matcher(old).replaceAll(target.wildcardVersion());
                    if (!old.equals(nw)) {
                        array.set(i, array.textNode(nw));
                        changes.add(new ChangeItem(file, "SupportedTrains[" + i + "]", old, nw, false));
                    }
                }
            }
        }
        return changes;
    }

    private boolean containsWildcard(ArrayNode arr, String wildcard) {
        for (JsonNode n : arr) if (n.asText().contains(wildcard)) return true;
        return false;
    }

    private void updateString(ObjectNode node, String field, String replacement, Path file, List<ChangeItem> changes) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual()) return;
        String old = v.asText();
        String nw = PACKAGE_TOKEN.matcher(old).replaceAll(replacement);
        if (field.equals("MUVersion")) nw = replacement;
        if (!old.equals(nw)) {
            node.put(field, nw);
            changes.add(new ChangeItem(file, field, old, nw, true));
        }
    }

    public static class ParsedJson {
        public final ObjectNode root;
        private final ObjectMapper mapper;

        public ParsedJson(ObjectNode root, ObjectMapper mapper) {
            this.root = root;
            this.mapper = mapper;
        }

        public byte[] toBytes() throws IOException {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        }
    }
}

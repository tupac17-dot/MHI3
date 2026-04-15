package com.mhi3.updater.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhi3.updater.model.AppSettings;
import com.mhi3.updater.model.ChangeItem;
import com.mhi3.updater.model.UpdateMode;
import com.mhi3.updater.model.VersionInfo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class ManifestParser {
    private static final Pattern PACKAGE_TOKEN = Pattern.compile("P\\d{4}");
    private static final Pattern TRAIN_WILDCARD = Pattern.compile("M..3_.._AU_P\\d{3}\\*");

    private final ObjectMapper mapper = new ObjectMapper();

    public ParsedJson parse(Path file) throws IOException {
        JsonNode node = mapper.readTree(file.toFile());
        if (!(node instanceof ObjectNode obj)) throw new IOException("Manifest is not JSON object");
        return new ParsedJson(obj, mapper);
    }

    public List<ChangeItem> applyVersion(Path file, ObjectNode root, VersionInfo target, AppSettings settings) {
        List<ChangeItem> changes = new ArrayList<>();
        if (settings.updateMode == UpdateMode.APPEND_SUPPORTED_TRAINS_ONLY) {
            appendSupportedTrainEntry(file, root, target.wildcardVersion(), changes);
            return changes;
        }

        updateString(root, "PackageName", target.normalizedVersion(), file, changes);
        updateString(root, "Release", target.normalizedVersion(), file, changes);
        updateString(root, "MUVersion", target.muVersion(), file, changes);

        JsonNode trains = root.get("SupportedTrains");
        if (trains instanceof ArrayNode array) {
            int latestIdx = -1;
            int bestVer = -1;
            for (int i = 0; i < array.size(); i++) {
                String v = array.get(i).asText();
                var m = Pattern.compile("P\\d{3}\\*").matcher(v);
                if (m.find()) {
                    int ver = Integer.parseInt(m.group().substring(1, 4));
                    if (ver > bestVer) {
                        bestVer = ver;
                        latestIdx = i;
                    }
                }
            }

            if (settings.replaceLatestTrainWildcard && latestIdx >= 0) {
                String old = array.get(latestIdx).asText();
                String nw = old.replaceAll("P\\d{3}\\*", target.wildcardVersion());
                array.set(latestIdx, array.textNode(nw));
                changes.add(new ChangeItem(file, "SupportedTrains[" + latestIdx + "]", old, nw, false));
            }
            if (settings.appendTrainIfMissing) {
                appendWildcardIfMissing(file, array, "SupportedTrains", target.wildcardVersion(), changes);
            }
        }
        return changes;
    }

    private void appendSupportedTrainEntry(Path file, JsonNode node, String wildcardVersion, List<ChangeItem> changes) {
        if (node instanceof ObjectNode obj) {
            obj.fields().forEachRemaining(field -> {
                JsonNode value = field.getValue();
                if (value instanceof ArrayNode array && isTrainWildcardArray(field.getKey(), array)) {
                    appendWildcardIfMissing(file, array, field.getKey(), wildcardVersion, changes);
                } else if (value.isObject() || value.isArray()) {
                    appendSupportedTrainEntry(file, value, wildcardVersion, changes);
                }
            });
            return;
        }

        if (node instanceof ArrayNode array) {
            for (JsonNode item : array) {
                if (item.isObject() || item.isArray()) {
                    appendSupportedTrainEntry(file, item, wildcardVersion, changes);
                }
            }
        }
    }

    private boolean isTrainWildcardArray(String fieldName, ArrayNode array) {
        if ("SupportedTrains".equals(fieldName)) {
            return true;
        }
        for (JsonNode item : array) {
            if (item.isTextual() && TRAIN_WILDCARD.matcher(item.asText()).matches()) {
                return true;
            }
        }
        return false;
    }

    private void appendWildcardIfMissing(Path file,
                                         ArrayNode array,
                                         String fieldName,
                                         String wildcardVersion,
                                         List<ChangeItem> changes) {
        String newEntry = "M??3_??_AU_" + wildcardVersion;
        if (containsEntry(array, newEntry)) {
            return;
        }

        array.add(newEntry);
        changes.add(new ChangeItem(file, fieldName + "[+]", "", newEntry, false));
    }

    private boolean containsEntry(ArrayNode arr, String value) {
        for (JsonNode n : arr) {
            if (value.equals(n.asText())) {
                return true;
            }
        }
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

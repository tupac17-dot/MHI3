package com.mhi3.updater.checksum;

import com.mhi3.updater.checksum.model.ResolutionResult;
import com.mhi3.updater.checksum.model.ResolutionType;
import com.mhi3.updater.scanner.FileIndexService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class ChecksumResolverService {

    private static final Set<String> LOGICAL_SUFFIXES = Set.of(
            "application", "bootloader", "voices", "files", "script",
            "common", "config", "data", "sr", "golden_package",
            "sxe_baselines", "au");

    private static final List<String> DERIVED_EXTENSIONS = List.of(
            ".idx.json", ".json", ".zip", ".bin", ".tar", ".tar.gz");

    public ResolutionResult resolveTarget(String token,
            FileIndexService indexService,
            Map<String, Path> manualMappings) {

        if (token == null || token.isBlank()) {
            return new ResolutionResult("", null, ResolutionType.UNRESOLVED, 0.0, true, "Empty checksum target");
        }

        String trimmed = token.trim();

        if (manualMappings.containsKey(trimmed)) {
            return new ResolutionResult(
                    trimmed,
                    manualMappings.get(trimmed),
                    ResolutionType.HEURISTIC,
                    1.0,
                    false,
                    "Manual mapping");
        }

        Optional<Path> exact = resolveExact(trimmed, indexService);
        if (exact.isPresent()) {
            return new ResolutionResult(
                    trimmed,
                    exact.get(),
                    ResolutionType.EXACT,
                    1.0,
                    false,
                    "Exact file name match");
        }

        for (String candidate : buildDerivedCandidates(trimmed)) {
            Optional<Path> derived = indexService.resolveByName(candidate);
            if (derived.isPresent()) {
                return new ResolutionResult(
                        trimmed,
                        derived.get(),
                        ResolutionType.DERIVED,
                        0.88,
                        false,
                        "Derived package-to-file candidate: " + candidate);
            }
        }

        for (String probe : buildHeuristicProbes(trimmed)) {
            Optional<Path> heuristic = indexService.resolveByPartial(probe);
            if (heuristic.isPresent()) {
                return new ResolutionResult(
                        trimmed,
                        heuristic.get(),
                        ResolutionType.HEURISTIC,
                        confidenceForProbe(trimmed, probe),
                        true,
                        "Heuristic partial match using probe: " + probe);
            }
        }

        if (looksLikeLogicalPackageToken(trimmed)) {
            return new ResolutionResult(
                    trimmed,
                    null,
                    ResolutionType.UNRESOLVED,
                    0.0,
                    true,
                    "Logical package token not present as a concrete file in the selected root");
        }

        return new ResolutionResult(
                trimmed,
                null,
                ResolutionType.UNRESOLVED,
                0.0,
                true,
                "No candidate found in selected root");
    }

    private Optional<Path> resolveExact(String token, FileIndexService indexService) {
        Optional<Path> exact = indexService.resolveByName(token);
        if (exact.isPresent()) {
            return exact;
        }

        String leaf = lastDotSegmentRemoved(token);
        if (!leaf.equals(token)) {
            exact = indexService.resolveByName(leaf);
            if (exact.isPresent()) {
                return exact;
            }
        }

        String root = firstDotSegment(token);
        if (!root.equals(token)) {
            exact = indexService.resolveByName(root);
            if (exact.isPresent()) {
                return exact;
            }
        }

        return Optional.empty();
    }

    private List<String> buildDerivedCandidates(String token) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();

        String strippedLogical = stripLogicalSuffixes(token);
        String firstSegment = firstDotSegment(token);
        String lastRemoved = lastDotSegmentRemoved(token);

        addWithExtensions(candidates, token);
        addWithExtensions(candidates, strippedLogical);
        addWithExtensions(candidates, lastRemoved);
        addWithExtensions(candidates, firstSegment);

        // Common manifest-related artifacts for package tokens like MHI3_ER_AU_P4368
        candidates.add(token + ".mnf");
        candidates.add(token + ".mnf.cks");
        candidates.add(token + ".idx.json");

        if (!strippedLogical.equals(token)) {
            candidates.add(strippedLogical + ".mnf");
            candidates.add(strippedLogical + ".mnf.cks");
            candidates.add(strippedLogical + ".idx.json");
        }

        candidates.remove(token); // exact already checked
        candidates.removeIf(String::isBlank);

        return new ArrayList<>(candidates);
    }

    private List<String> buildHeuristicProbes(String token) {
        LinkedHashSet<String> probes = new LinkedHashSet<>();

        String strippedLogical = stripLogicalSuffixes(token);
        String lastRemoved = lastDotSegmentRemoved(token);
        String firstSegment = firstDotSegment(token);

        probes.add(token);
        probes.add(strippedLogical);
        probes.add(lastRemoved);
        probes.add(firstSegment);

        // Prefix chain: DUC1H270.MSW.application -> DUC1H270.MSW -> DUC1H270
        String current = token;
        while (current.contains(".")) {
            current = lastDotSegmentRemoved(current);
            probes.add(current);
        }

        probes.removeIf(String::isBlank);

        return new ArrayList<>(probes);
    }

    private void addWithExtensions(Set<String> out, String base) {
        if (base == null || base.isBlank()) {
            return;
        }
        out.add(base);
        for (String ext : DERIVED_EXTENSIONS) {
            out.add(base + ext);
        }
    }

    private String stripLogicalSuffixes(String token) {
        String current = token;

        while (current.contains(".")) {
            String suffix = current.substring(current.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (!LOGICAL_SUFFIXES.contains(suffix)) {
                break;
            }
            current = current.substring(0, current.lastIndexOf('.'));
        }

        return current;
    }

    private String lastDotSegmentRemoved(String token) {
        int idx = token.lastIndexOf('.');
        return idx > 0 ? token.substring(0, idx) : token;
    }

    private String firstDotSegment(String token) {
        int idx = token.indexOf('.');
        return idx > 0 ? token.substring(0, idx) : token;
    }

    private boolean looksLikeLogicalPackageToken(String token) {
        String lower = token.toLowerCase(Locale.ROOT);

        if (token.contains("/") || token.contains("\\")) {
            return false;
        }

        if (lower.endsWith(".json")
                || lower.endsWith(".zip")
                || lower.endsWith(".bin")
                || lower.endsWith(".txt")
                || lower.endsWith(".xml")
                || lower.endsWith(".csv")
                || lower.endsWith(".html")
                || lower.endsWith(".idx")) {
            return false;
        }

        if (!token.contains(".")) {
            return true;
        }

        String suffix = token.substring(token.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return LOGICAL_SUFFIXES.contains(suffix);
    }

    private double confidenceForProbe(String token, String probe) {
        if (probe.equals(token)) {
            return 0.70;
        }
        if (probe.equals(stripLogicalSuffixes(token))) {
            return 0.65;
        }
        if (probe.equals(firstDotSegment(token))) {
            return 0.55;
        }
        return 0.50;
    }
}
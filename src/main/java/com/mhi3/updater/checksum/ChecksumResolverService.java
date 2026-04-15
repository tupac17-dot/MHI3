package com.mhi3.updater.checksum;

import com.mhi3.updater.checksum.model.ResolutionResult;
import com.mhi3.updater.checksum.model.ResolutionType;
import com.mhi3.updater.scanner.FileIndexService;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ChecksumResolverService {
    public ResolutionResult resolveTarget(String token, FileIndexService indexService, Map<String, Path> manualMappings) {
        if (manualMappings.containsKey(token)) {
            return new ResolutionResult(token, manualMappings.get(token), ResolutionType.HEURISTIC, 1.0, false, "Manual mapping");
        }

        Optional<Path> exact = indexService.resolveByName(token);
        if (exact.isPresent()) {
            return new ResolutionResult(token, exact.get(), ResolutionType.EXACT, 1.0, false, "Exact file name match");
        }

        List<String> derivedCandidates = List.of(token + ".idx.json", token + ".json", token + ".zip");
        for (String c : derivedCandidates) {
            Optional<Path> p = indexService.resolveByName(c);
            if (p.isPresent()) {
                return new ResolutionResult(token, p.get(), ResolutionType.DERIVED, 0.85, false, "Derived package-to-file candidate");
            }
        }

        Optional<Path> heuristic = indexService.resolveByPartial(token);
        if (heuristic.isPresent()) {
            return new ResolutionResult(token, heuristic.get(), ResolutionType.HEURISTIC, 0.60, true, "Partial name heuristic");
        }

        return new ResolutionResult(token, null, ResolutionType.UNRESOLVED, 0.0, true, "No candidate found");
    }
}

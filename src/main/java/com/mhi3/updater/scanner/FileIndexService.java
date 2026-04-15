package com.mhi3.updater.scanner;

import com.mhi3.updater.model.FileRecord;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class FileIndexService {
    private final Map<String, List<FileRecord>> byFileName = new HashMap<>();

    public void build(List<FileRecord> records) {
        byFileName.clear();
        records.forEach(r -> byFileName.computeIfAbsent(r.fileName(), k -> new ArrayList<>()).add(r));
    }

    public Optional<Path> resolveByName(String fileName) {
        return Optional.ofNullable(byFileName.get(fileName))
                .flatMap(list -> list.stream().findFirst())
                .map(FileRecord::absolutePath);
    }

    public Optional<Path> resolveByPartial(String token) {
        String lower = token.toLowerCase();
        return byFileName.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase().contains(lower))
                .findFirst()
                .flatMap(e -> e.getValue().stream().findFirst())
                .map(FileRecord::absolutePath);
    }

    public List<String> allKnownNames() {
        return byFileName.keySet().stream().sorted().collect(Collectors.toList());
    }
}

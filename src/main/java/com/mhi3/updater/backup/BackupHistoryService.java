package com.mhi3.updater.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mhi3.updater.backup.model.BackupSession;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class BackupHistoryService {
    private final ObjectMapper mapper = new ObjectMapper();

    public Path metadataDir(Path root) throws IOException {
        Path dir = root.resolve(".mhi3-backups").resolve("metadata");
        Files.createDirectories(dir);
        return dir;
    }

    public void persist(Path root, BackupSession session) throws IOException {
        Path file = metadataDir(root).resolve(session.sessionId + ".json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), session);
    }

    public List<BackupSession> list(Path root) throws IOException {
        Path dir = metadataDir(root);
        List<BackupSession> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : stream)
                out.add(mapper.readValue(p.toFile(), BackupSession.class));
        }
        out.sort(Comparator.comparing((BackupSession s) -> s.startedAt, Comparator.nullsLast(String::compareTo))
                .reversed());
        return out;
    }
}
package com.remoteterm.files;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;

public class FileHandler {

    private static final Logger log = LoggerFactory.getLogger(FileHandler.class);
    private static final Set<String> SENSITIVE_NAMES = Set.of(".env", ".git/config", ".ssh/id_rsa");
    private static final long MAX_READ_BYTES = 1_048_576; // 1MB

    private final Path workspaceRoot;

    public FileHandler(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    private Path resolveSafe(String userPath) throws SecurityException {
        Path resolved = workspaceRoot.resolve(userPath).normalize();
        Path rootNormalized = workspaceRoot.normalize();

        if (!resolved.startsWith(rootNormalized)) {
            throw new SecurityException("Path traversal denied: " + userPath);
        }

        // If path exists, also resolve symlinks
        if (Files.exists(resolved)) {
            try {
                Path real = resolved.toRealPath();
                Path rootReal = workspaceRoot.toRealPath();
                if (!real.startsWith(rootReal)) {
                    throw new SecurityException("Symlink escape denied: " + userPath);
                }
                return real;
            } catch (IOException e) {
                throw new SecurityException("Cannot resolve path: " + userPath, e);
            }
        }

        return resolved;
    }

    public List<Map<String, Object>> list(String path) {
        Path dir = resolveSafe(path.isEmpty() ? "." : path);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> {
                        try {
                            return !Files.isHidden(p);
                        } catch (IOException e) {
                            return true;
                        }
                    })
                    .sorted((a, b) -> {
                        boolean aDir = Files.isDirectory(a);
                        boolean bDir = Files.isDirectory(b);
                        if (aDir != bDir) return aDir ? -1 : 1;
                        return a.getFileName().toString().compareToIgnoreCase(
                                b.getFileName().toString());
                    })
                    .map(p -> {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("name", p.getFileName().toString());
                        boolean isDir = Files.isDirectory(p);
                        entry.put("isDir", isDir);
                        entry.put("size", isDir ? 0 : fileSize(p));
                        entry.put("mtime", lastModified(p));
                        return entry;
                    })
                    .toList();
        } catch (IOException e) {
            log.error("Error listing {}", dir, e);
            return List.of();
        }
    }

    public Map<String, Object> read(String path) {
        Path file = resolveSafe(path);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path);

        if (isSensitive(file)) {
            result.put("content", "[Sensitive file - read blocked]");
            result.put("truncated", false);
            return result;
        }
        try {
            long fileSize = Files.size(file);
            if (fileSize > MAX_READ_BYTES) {
                byte[] head = new byte[(int) MAX_READ_BYTES];
                try (var in = Files.newInputStream(file)) {
                    in.read(head);
                }
                result.put("content", new String(head) + "\n... [TRUNCATED: " +
                        (fileSize - MAX_READ_BYTES) + " more bytes]");
                result.put("truncated", true);
            } else {
                result.put("content", Files.readString(file));
                result.put("truncated", false);
            }
        } catch (IOException e) {
            result.put("content", null);
            result.put("truncated", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    public Map<String, Object> write(String path, String content) {
        Path file = resolveSafe(path);
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            result.put("ok", true);
        } catch (IOException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    public Map<String, Object> delete(String path) {
        Path target = resolveSafe(path);
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if (Files.isDirectory(target)) {
                try (Stream<Path> walk = Files.walk(target)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
                }
            } else {
                Files.delete(target);
            }
            result.put("ok", true);
        } catch (IOException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    public Map<String, Object> mkdir(String path) {
        Path dir = resolveSafe(path);
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Files.createDirectories(dir);
            result.put("ok", true);
        } catch (IOException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    private boolean isSensitive(Path file) {
        String s = file.toString();
        return SENSITIVE_NAMES.stream().anyMatch(s::endsWith);
    }

    private long fileSize(Path p) {
        try { return Files.size(p); } catch (IOException e) { return 0; }
    }

    private long lastModified(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis() / 1000; } catch (IOException e) { return 0; }
    }
}

package dev.deskflow;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

final class ProjectFiles {
    record Entry(Path relative, boolean directory, long bytes) {}

    static List<Entry> inspect(Path root, long cap) {
        var entries = new ArrayList<Entry>();
        long[] bytes = {0};
        try {
            Path canonical = root.toRealPath();
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                void add(Path path, BasicFileAttributes attrs) throws IOException {
                    if (entries.size() >= 10000 || attrs.isSymbolicLink()
                        || !path.toRealPath().startsWith(canonical)) {
                        throw new IOException("Unsafe tree");
                    }
                    if (!attrs.isDirectory() && !attrs.isRegularFile()) {
                        throw new IOException("Unsupported file");
                    }
                    long size = attrs.isDirectory() ? 0 : attrs.size();
                    bytes[0] = Math.addExact(bytes[0], size);
                    if (bytes[0] > cap) {
                        throw new IOException("Snapshot cap exceeded");
                    }
                    entries.add(new Entry(root.relativize(path), attrs.isDirectory(), size));
                }
                @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                    if (path.getFileName().toString().equalsIgnoreCase(".git")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    add(path, attrs);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                    if (!path.getFileName().toString().equalsIgnoreCase(".git")) {
                        add(path, attrs);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception error) {
            throw new IllegalArgumentException("Cannot safely inspect the instance tree");
        }
        entries.sort(Comparator.comparing(e -> e.relative().toString()));
        return entries;
    }

    static void copy(Path source, Path target, long cap) {
        var entries = inspect(source, cap);
        try {
            for (var entry : entries) {
                Path destination = target.resolve(entry.relative());
                if (entry.directory()) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(source.resolve(entry.relative()), destination,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
            inspect(target, cap);
        } catch (Exception error) {
            throw new IllegalArgumentException("Snapshot copy failed");
        }
    }

    static void digest(Path root, long cap, MessageDigest digest) {
        var entries = inspect(root, cap);
        long read = 0;
        try {
            for (var entry : entries) {
                frame(digest, entry.relative().toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                digest.update((byte)(entry.directory() ? 1 : 0));
                if (!entry.directory()) {
                    byte[] bytes;
                    try (var input = Files.newInputStream(root.resolve(entry.relative()), LinkOption.NOFOLLOW_LINKS)) {
                        int limit = Math.toIntExact(Math.min(Integer.MAX_VALUE - 1L, cap - read));
                        bytes = input.readNBytes(limit + 1);
                        if (bytes.length > limit) {
                            throw new IOException("Fingerprint cap exceeded");
                        }
                    }
                    read += bytes.length;
                    frame(digest, bytes);
                }
            }
        } catch (Exception error) {
            throw new IllegalArgumentException("Fingerprint unavailable");
        }
    }

    static void frame(MessageDigest digest, byte[] bytes) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());
        digest.update(bytes);
    }
}

package dev.deskflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class ProjectFilesTest {
    @TempDir Path directory;

    String hash(Path root) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        ProjectFiles.digest(root, 65536, digest);
        return HexFormat.of().formatHex(digest.digest());
    }
    @Test void fingerprintsFrameFileNamesAndContents() throws Exception {
        Path first = Files.createDirectory(directory.resolve("first"));
        Path second = Files.createDirectory(directory.resolve("second"));
        Files.writeString(first.resolve("a"), "bc");
        Files.writeString(second.resolve("ab"), "c");
        assertNotEquals(hash(first), hash(second));
    }
    @Test void emptyFoldersAreCopiedAndIncludedInTheFingerprint() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        String before = hash(source);
        Files.createDirectories(source.resolve("empty/nested"));
        assertNotEquals(before, hash(source));
        Path copy = directory.resolve("copy");
        ProjectFiles.copy(source, copy, 65536);
        assertTrue(Files.isDirectory(copy.resolve("empty/nested")));
        assertEquals(hash(source), hash(copy));
    }
    @Test void fileCapsAreEnforcedBeforeCopying() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("large"), "oversized");
        Path target = directory.resolve("target");
        assertThrows(IllegalArgumentException.class, () -> ProjectFiles.copy(source, target, 4));
        assertFalse(Files.exists(target));
    }
    @Test void metadataAliasesAndAlternateStreamsAreRejected() throws Exception {
        Path base = Files.createDirectory(directory.resolve("base"));
        Files.writeString(base.resolve(".git"), "private metadata");
        assertThrows(IllegalArgumentException.class, () -> ProjectManager.inside(base, ".GIT"));
        assertThrows(IllegalArgumentException.class, () -> ProjectManager.inside(base, ".git."));
        assertThrows(IllegalArgumentException.class, () -> ProjectManager.inside(base, "file:stream"));
    }
}

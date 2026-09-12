package dev.claudev.platform.windows.detect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GlobDirectoryScannerTest {

    @TempDir
    Path tempDir;

    @Test
    void matchesSubdirectoriesByGlob() throws IOException {
        Files.createDirectory(tempDir.resolve("erl-27.3.4.17"));
        Files.createDirectory(tempDir.resolve("erl-26.0"));
        Files.createDirectory(tempDir.resolve("not-erlang"));
        Files.createFile(tempDir.resolve("erl-not-a-dir"));

        List<Path> matches = new GlobDirectoryScanner().matchingSubdirectories(tempDir, "erl-*");

        assertThat(matches)
                .containsExactlyInAnyOrder(tempDir.resolve("erl-27.3.4.17"), tempDir.resolve("erl-26.0"));
    }

    @Test
    void returnsEmptyForMissingRoot() {
        List<Path> matches = new GlobDirectoryScanner()
                .matchingSubdirectories(tempDir.resolve("does-not-exist"), "*");

        assertThat(matches).isEmpty();
    }
}

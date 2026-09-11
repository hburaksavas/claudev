package dev.claudev.adapter.fepipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AtomicDeployExecutorTest {

    private final AtomicDeployExecutor executor = new AtomicDeployExecutor();

    @Test
    void firstDeployIsASimpleMove(@TempDir Path tempDir) throws Exception {
        Path staged = tempDir.resolve("staged");
        Files.createDirectories(staged);
        Files.writeString(staged.resolve("index.html"), "v1");

        Path deployDir = tempDir.resolve("live");

        executor.execute(Map.of("stagedDir", staged.toString(), "deployDir", deployDir.toString()));

        assertThat(deployDir.resolve("index.html")).exists();
        assertThat(Files.readString(deployDir.resolve("index.html"))).isEqualTo("v1");
        assertThat(staged).doesNotExist();
    }

    @Test
    void secondDeploySwapsAndRemovesTheOldOne(@TempDir Path tempDir) throws Exception {
        Path deployDir = tempDir.resolve("live");
        Files.createDirectories(deployDir);
        Files.writeString(deployDir.resolve("index.html"), "v1");

        Path staged = tempDir.resolve("staged");
        Files.createDirectories(staged);
        Files.writeString(staged.resolve("index.html"), "v2");

        executor.execute(Map.of("stagedDir", staged.toString(), "deployDir", deployDir.toString()));

        assertThat(Files.readString(deployDir.resolve("index.html"))).isEqualTo("v2");
        assertThat(staged).doesNotExist();

        // No lingering ".old-*" backup directory after a successful swap.
        try (var siblings = Files.list(tempDir)) {
            assertThat(siblings).noneMatch(p -> p.getFileName().toString().startsWith("live.old-"));
        }
    }
}

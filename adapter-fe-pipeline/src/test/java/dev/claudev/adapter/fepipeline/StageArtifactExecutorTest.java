package dev.claudev.adapter.fepipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StageArtifactExecutorTest {

    private final StageArtifactExecutor executor = new StageArtifactExecutor();

    @Test
    void copiesTheArtifactCreatingParentDirectories(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("target").resolve("app.jar");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "fake-jar-bytes");

        Path destination = tempDir.resolve("staging").resolve("nested").resolve("app.jar");

        executor.execute(Map.of("source", source.toString(), "destination", destination.toString()));

        assertThat(destination).exists();
        assertThat(Files.readString(destination)).isEqualTo("fake-jar-bytes");
    }

    @Test
    void failsClearlyWhenSourceIsMissing(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("nope.jar");
        Path destination = tempDir.resolve("staged.jar");

        assertThatThrownBy(() -> executor.execute(Map.of("source", missing.toString(), "destination", destination.toString())))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("does not exist");
    }
}

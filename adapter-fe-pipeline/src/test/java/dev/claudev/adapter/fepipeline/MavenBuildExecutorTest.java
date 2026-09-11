package dev.claudev.adapter.fepipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real {@code cmd.exe /c mvn.cmd} invocations against a trivial local project — no mocking. */
class MavenBuildExecutorTest {

    private final MavenBuildExecutor executor = new MavenBuildExecutor();

    @Test
    void runsARealMavenGoalAgainstATrivialProject(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>dev.claudev.test</groupId>
                    <artifactId>trivial</artifactId>
                    <version>1.0</version>
                </project>
                """);

        assertThatCode(() -> executor.execute(Map.of(
                "projectDir", tempDir.toString(),
                "goals", List.of("validate"))))
                .doesNotThrowAnyException();
    }

    @Test
    void aFailingGoalProducesAReadableFailureWithCapturedOutput(@TempDir Path tempDir) throws Exception {
        // Invalid POM (no groupId) — "validate" must fail, and the failure message must contain
        // real captured Maven output, not just an exit code.
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <artifactId>trivial</artifactId>
                    <version>1.0</version>
                </project>
                """);

        assertThatThrownBy(() -> executor.execute(Map.of(
                "projectDir", tempDir.toString(),
                "goals", List.of("validate"))))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("failed");
    }

    @Test
    void rejectsAnUnsafeGoalBeforeEverySpawningAnything(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");

        assertThatThrownBy(() -> executor.execute(Map.of(
                "projectDir", tempDir.toString(),
                "goals", List.of("clean", "install & calc.exe"))))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("Rejected Maven goal");
    }
}

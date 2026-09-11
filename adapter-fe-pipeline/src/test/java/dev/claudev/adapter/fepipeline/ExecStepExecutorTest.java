package dev.claudev.adapter.fepipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real spawns via WP1's launcher directly (no cmd.exe involved at all for this step type). */
class ExecStepExecutorTest {

    private final ExecStepExecutor executor = new ExecStepExecutor();

    @Test
    void runsARealAllowlistedExecutableAndSucceeds() {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        Path ping = Path.of(System.getenv("SystemRoot"), "System32", "ping.exe");

        assertThatCode(() -> executor.execute(Map.of(
                "executable", ping.toString(),
                "argv", List.of(ping.toString(), "-n", "1", "127.0.0.1"))))
                .doesNotThrowAnyException();
    }

    @Test
    void aNonZeroExitCodeFailsTheStep() {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        Path ping = Path.of(System.getenv("SystemRoot"), "System32", "ping.exe");

        // An address ping can't resolve/reach -> non-zero exit.
        assertThatThrownBy(() -> executor.execute(Map.of(
                "executable", ping.toString(),
                "argv", List.of(ping.toString(), "-n", "1", "-w", "100", "198.51.100.1"))))
                .isInstanceOf(StepExecutionException.class);
    }

    @Test
    void rejectsARelativeExecutablePath() {
        assertThatThrownBy(() -> executor.execute(Map.of(
                "executable", "ping.exe",
                "argv", List.of("ping.exe"))))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    void rejectsAnExecutableThatDoesNotExist(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("nope-" + UUID.randomUUID() + ".exe");

        assertThatThrownBy(() -> executor.execute(Map.of(
                "executable", missing.toString(),
                "argv", List.of(missing.toString()))))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void executableContainingASpaceInItsPathIsHandledCorrectly(@TempDir Path tempDir) throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        // The same argv-quoting concern GitStepsIntegrationTest exercises for Git, here for
        // ExecStep's own direct-spawn path (no cmd.exe involved at all for this step type).
        Path systemRoot = Path.of(System.getenv("SystemRoot"));
        Path realPing = systemRoot.resolve("System32").resolve("ping.exe");
        Path copiedPing = tempDir.resolve("ping with a space.exe");
        Files.copy(realPing, copiedPing);

        assertThatCode(() -> executor.execute(Map.of(
                "executable", copiedPing.toString(),
                "argv", List.of(copiedPing.toString(), "-n", "1", "127.0.0.1"))))
                .doesNotThrowAnyException();
    }
}

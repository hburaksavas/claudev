package dev.claudev.platform.windows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real (not mocked) verification of {@link LaunchSpec}'s stdout/stderr file redirection — new
 * Win32 surface ({@code CreateFileW}, inheritable {@code SECURITY_ATTRIBUTES},
 * {@code STARTF_USESTDHANDLES}) added specifically so a CLI-shaped step (Git, Maven, {@code
 * ExecStep}) gets valid output handles and a readable error message on failure.
 */
class WindowsProcessLauncherRedirectionTest {

    @Test
    void capturesStdoutAndStderrToSeparateFiles(@TempDir Path tempDir) throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        Path systemRoot = Path.of(System.getenv("SystemRoot"));
        Path cmdExe = systemRoot.resolve("System32").resolve("cmd.exe");
        Path stdoutFile = tempDir.resolve("stdout.txt");
        Path stderrFile = tempDir.resolve("stderr.txt");

        // A hardcoded, fully-controlled test string — safe to route through cmd.exe's own shell
        // syntax here (this is not the production Git/Maven spawn path, which never does this).
        LaunchSpec spec = new LaunchSpec(
                cmdExe,
                List.of(cmdExe.toString(), "/c", "echo stdout-marker & echo stderr-marker 1>&2"),
                null, Map.of("SystemRoot", systemRoot.toString()),
                "claudev-redirect-test-" + UUID.randomUUID(), true,
                Optional.of(stdoutFile), Optional.of(stderrFile));

        LaunchResult result = WindowsProcessLauncher.launch(spec);
        try {
            Optional<Integer> exitCode = ProcessExitWaiter.waitForExit(result.pid(), result.creationTime(), Duration.ofSeconds(10));
            assertThat(exitCode).contains(0);

            assertThat(Files.readString(stdoutFile)).contains("stdout-marker").doesNotContain("stderr-marker");
            assertThat(Files.readString(stderrFile)).contains("stderr-marker").doesNotContain("stdout-marker");
        } finally {
            result.job().terminate(1);
            result.job().close();
        }
    }

    @Test
    void unredirectedLaunchStillWorksExactlyAsBefore() {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        Path systemRoot = Path.of(System.getenv("SystemRoot"));
        Path pingExe = systemRoot.resolve("System32").resolve("ping.exe");

        // The pre-existing 6-arg constructor — must still compile and behave identically.
        LaunchSpec spec = new LaunchSpec(
                pingExe, List.of(pingExe.toString(), "-n", "1", "127.0.0.1"),
                null, Map.of("SystemRoot", systemRoot.toString()),
                "claudev-no-redirect-test-" + UUID.randomUUID(), true);

        LaunchResult result = WindowsProcessLauncher.launch(spec);
        try {
            Optional<Integer> exitCode = ProcessExitWaiter.waitForExit(result.pid(), result.creationTime(), Duration.ofSeconds(10));
            assertThat(exitCode).contains(0);
        } finally {
            result.job().terminate(1);
            result.job().close();
        }
    }
}

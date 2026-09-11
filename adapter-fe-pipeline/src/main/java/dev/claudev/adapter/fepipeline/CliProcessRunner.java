package dev.claudev.adapter.fepipeline;

import dev.claudev.platform.windows.LaunchResult;
import dev.claudev.platform.windows.LaunchSpec;
import dev.claudev.platform.windows.ProcessExitWaiter;
import dev.claudev.platform.windows.WindowsProcessLauncher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared spawn-wait-capture logic for every step that runs a real CLI tool to completion (Git,
 * Maven, {@code ExecStep}) — all go through WP1's {@link WindowsProcessLauncher}, argv as a list,
 * never a shell string. Output is captured to temp files (not just discarded) so a failed step's
 * exception message is actually readable, not just an exit code.
 */
final class CliProcessRunner {

    private CliProcessRunner() {
    }

    record Result(int exitCode, String stdout, String stderr) {
        boolean succeeded() {
            return exitCode == 0;
        }
    }

    static Result run(
            Path executable, List<String> argv, Path workingDirectory,
            Map<String, String> environment, String jobName, Duration timeout) throws StepExecutionException {
        Path stdoutFile;
        Path stderrFile;
        try {
            stdoutFile = Files.createTempFile("claudev-step-out-", ".log");
            stderrFile = Files.createTempFile("claudev-step-err-", ".log");
        } catch (IOException e) {
            throw new StepExecutionException("Failed to create temp output-capture files", e);
        }

        LaunchSpec spec = new LaunchSpec(
                executable, argv, workingDirectory, environment, jobName, true,
                Optional.of(stdoutFile), Optional.of(stderrFile));

        LaunchResult launch;
        try {
            launch = WindowsProcessLauncher.launch(spec);
        } catch (RuntimeException e) {
            deleteQuietly(stdoutFile);
            deleteQuietly(stderrFile);
            throw new StepExecutionException("Failed to spawn " + executable + ": " + e.getMessage(), e);
        }

        try {
            Optional<Integer> exitCode = ProcessExitWaiter.waitForExit(launch.pid(), launch.creationTime(), timeout);
            if (exitCode.isEmpty()) {
                launch.job().terminate(1);
                throw new StepExecutionException(
                        "Process did not exit within " + timeout + " (or its identity could not be "
                                + "re-verified): " + String.join(" ", argv));
            }
            return new Result(exitCode.get(), readQuietly(stdoutFile), readQuietly(stderrFile));
        } finally {
            launch.job().close();
            deleteQuietly(stdoutFile);
            deleteQuietly(stderrFile);
        }
    }

    private static String readQuietly(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "(could not read captured output: " + e.getMessage() + ")";
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort — a leftover temp file is not worth failing the step over
        }
    }
}

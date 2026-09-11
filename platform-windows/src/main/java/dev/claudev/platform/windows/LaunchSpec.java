package dev.claudev.platform.windows;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything {@link WindowsProcessLauncher} needs to spawn one supervised process.
 *
 * <p>{@code environment} is the *entire* environment the child receives — there is no merge with
 * the caller's own process environment (see {@link WindowsEnvironmentBlock}). Callers that want
 * the child to see standard variables (e.g. {@code SystemRoot}) must include them explicitly.
 *
 * <p>{@code stdoutFile}/{@code stderrFile}, if present, redirect the child's output to those files
 * (created/truncated) instead of the Windows null device. This exists for two reasons, not just
 * diagnostics: a CLI tool (git, Maven) that inherits no valid standard handles can behave oddly or
 * hang, and captured output is what makes a failed CLI step's error message readable at all rather
 * than just an exit code.
 *
 * <p><b>Known simplification</b>: enabling redirection sets {@code bInheritHandles=TRUE} for the
 * whole spawn (needed for the child to inherit the output file handles) rather than using
 * {@code STARTUPINFOEX}'s handle-list restriction to inherit only those two handles. In practice
 * this JVM does not mark other handles inheritable by default, so the residual risk of an
 * unrelated handle leaking to the child is low — but it is a real simplification, not a
 * bulletproof design, and is worth hardening via {@code PROC_THREAD_ATTRIBUTE_HANDLE_LIST} before
 * this path is used for anything more sensitive than git/Maven/ExecStep output capture.
 */
public record LaunchSpec(
        Path executable,
        List<String> argv,
        Path workingDirectory,
        Map<String, String> environment,
        String jobName,
        boolean suppressWindow,
        Optional<Path> stdoutFile,
        Optional<Path> stderrFile
) {
    public LaunchSpec {
        argv = List.copyOf(argv);
        environment = Map.copyOf(environment);
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("argv must contain at least the program name");
        }
    }

    /** Convenience for the common case: no output capture, output goes to the Windows null device. */
    public LaunchSpec(
            Path executable, List<String> argv, Path workingDirectory,
            Map<String, String> environment, String jobName, boolean suppressWindow) {
        this(executable, argv, workingDirectory, environment, jobName, suppressWindow, Optional.empty(), Optional.empty());
    }
}

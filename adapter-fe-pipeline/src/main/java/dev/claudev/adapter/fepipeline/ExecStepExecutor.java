package dev.claudev.adapter.fepipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * The one explicitly opt-in, "advanced/unsandboxed" escape hatch (docs/SECURITY.md): argv is a
 * plain list, never a shell string; the executable is spawned directly via WP1's
 * {@link CliProcessRunner} (real {@code CreateProcessW}, no {@code cmd.exe} involved — unlike
 * {@link MavenBuildExecutor}, so no cmd.exe-quoting concern applies here at all).
 *
 * <p><b>Partial implementation of the full security model</b>: this checks that {@code executable}
 * is an absolute path to a file that actually exists (closing the PATH-search-substitution class of
 * risk), but does not yet check it against a per-workspace-configured allow-list — that
 * configuration surface does not exist yet (it is a workspace-settings concept, not built). Treat
 * "any existing absolute path" as V1's allow-list until that surface exists; do not remove this
 * comment without replacing it with the real check.
 */
final class ExecStepExecutor implements StepExecutor {

    @Override
    public void execute(Map<String, Object> params) throws StepExecutionException {
        Path executable = Params.requirePath(params, "executable");
        if (!executable.isAbsolute()) {
            throw new StepExecutionException("ExecStep executable must be an absolute path: " + executable);
        }
        executable = executable.normalize();
        if (!Files.isRegularFile(executable)) {
            throw new StepExecutionException("ExecStep executable does not exist: " + executable);
        }

        java.util.List<String> argv = Params.requireStringList(params, "argv");
        Path workingDir = params.containsKey("workingDir")
                ? Params.requirePath(params, "workingDir")
                : executable.getParent();
        Map<String, String> extraEnv = Params.optionalStringMap(params, "env");
        int timeoutSeconds = Params.optionalInt(params, "timeoutSeconds", 300);

        Map<String, String> environment = new java.util.HashMap<>(CorporateNetworkEnvironment.baseEnvironment());
        environment.putAll(extraEnv);

        CliProcessRunner.Result result = CliProcessRunner.run(
                executable, argv, workingDir, environment,
                "claudev-exec-step-" + java.util.UUID.randomUUID(), Duration.ofSeconds(timeoutSeconds));

        if (!result.succeeded()) {
            throw new StepExecutionException(
                    "ExecStep " + executable + " failed (exit " + result.exitCode() + "): " + result.stderr());
        }
    }
}

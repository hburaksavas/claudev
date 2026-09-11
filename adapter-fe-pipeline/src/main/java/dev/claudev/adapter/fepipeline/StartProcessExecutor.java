package dev.claudev.adapter.fepipeline;

import dev.claudev.platform.windows.LaunchSpec;
import dev.claudev.platform.windows.WindowsProcessLauncher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Launches the deployed service and deliberately does not wait for it to exit — unlike the other
 * CLI-shaped steps, this one is meant to keep running after the step (and the pipeline) completes.
 *
 * <p>The returned {@code WindowsJobObject} handle is intentionally not closed or otherwise tracked
 * here — V1 has no `Instance`/`LaunchRecord` concept for an FE-pipeline-started service (that
 * model is for RabbitMQ/Redis instances), so there is nothing to persist it into yet. This is not a
 * leak in the sense that matters: the job (and the process in it) still dies with the app itself
 * via `KILL_ON_JOB_CLOSE` on app exit — the same "managed processes do not survive application
 * exit" rule (D6) — it just cannot be individually stopped again from Java once this method
 * returns. Reconnecting it to a real lifecycle (so it *can* be) is future work once an FE-deployed
 * service gets its own `Instance`-shaped tracking.
 */
final class StartProcessExecutor implements StepExecutor {

    @Override
    public void execute(Map<String, Object> params) throws StepExecutionException {
        Path executable = Params.requirePath(params, "executable").toAbsolutePath().normalize();
        List<String> extraArgs = params.containsKey("args") ? Params.requireStringList(params, "args") : List.of();
        Map<String, String> extraEnv = Params.optionalStringMap(params, "env");

        List<String> argv = new ArrayList<>();
        argv.add(executable.toString());
        argv.addAll(extraArgs);

        Map<String, String> environment = new java.util.HashMap<>(CorporateNetworkEnvironment.baseEnvironment());
        environment.putAll(extraEnv);

        LaunchSpec spec = new LaunchSpec(
                executable, argv, executable.getParent(), environment,
                "claudev-start-process-" + java.util.UUID.randomUUID(), true);

        try {
            WindowsProcessLauncher.launch(spec);
        } catch (RuntimeException e) {
            throw new StepExecutionException("Failed to start process " + executable + ": " + e.getMessage(), e);
        }
    }
}

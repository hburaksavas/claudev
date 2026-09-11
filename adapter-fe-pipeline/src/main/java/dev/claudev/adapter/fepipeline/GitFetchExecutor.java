package dev.claudev.adapter.fepipeline;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** {@code git fetch <remote>} — separate from fast-forwarding on purpose (docs/FE_PIPELINE_STEPS.md). */
final class GitFetchExecutor implements StepExecutor {

    @Override
    public void execute(Map<String, Object> params) throws StepExecutionException {
        Path repoDir = Params.requirePath(params, "repoDir").toAbsolutePath().normalize();
        String remote = Params.optionalString(params, "remote", "origin");

        Path git = ExecutableLocator.locate("git.exe", "CLAUDEV_GIT_EXE");
        CliProcessRunner.Result result = CliProcessRunner.run(
                git,
                List.of(git.toString(), "fetch", remote),
                repoDir,
                CorporateNetworkEnvironment.baseEnvironment(),
                "claudev-git-fetch",
                Duration.ofMinutes(5));

        if (!result.succeeded()) {
            throw new StepExecutionException("git fetch failed (exit " + result.exitCode() + "): " + result.stderr());
        }
    }
}

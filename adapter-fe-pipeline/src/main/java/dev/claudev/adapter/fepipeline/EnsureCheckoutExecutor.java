package dev.claudev.adapter.fepipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Clones {@code repositoryUrl} into {@code targetDir} if it isn't already a git working copy there; otherwise a no-op. */
final class EnsureCheckoutExecutor implements StepExecutor {

    @Override
    public void execute(Map<String, Object> params) throws StepExecutionException {
        String repositoryUrl = Params.requireString(params, "repositoryUrl");
        Path targetDir = Params.requirePath(params, "targetDir").toAbsolutePath().normalize();

        if (Files.isDirectory(targetDir.resolve(".git"))) {
            return;
        }

        Path parent = targetDir.getParent();
        if (parent == null) {
            throw new StepExecutionException("targetDir has no parent directory: " + targetDir);
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new StepExecutionException("Failed to create parent directory for checkout: " + parent, e);
        }

        Path git = ExecutableLocator.locate("git.exe", "CLAUDEV_GIT_EXE");
        CliProcessRunner.Result result = CliProcessRunner.run(
                git,
                List.of(git.toString(), "clone", repositoryUrl, targetDir.toString()),
                parent,
                CorporateNetworkEnvironment.baseEnvironment(),
                "claudev-git-clone",
                Duration.ofMinutes(10));

        if (!result.succeeded()) {
            throw new StepExecutionException("git clone failed (exit " + result.exitCode() + "): " + result.stderr());
        }
    }
}

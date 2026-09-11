package dev.claudev.adapter.fepipeline;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Fast-forwards the working branch onto {@code <remote>/<branch>}. A dirty worktree blocks
 * explicitly (checked before attempting anything — {@code git merge} can otherwise leave
 * uncommitted changes intact even against a policy that means to forbid it); a non-fast-forward
 * history blocks via {@code --ff-only}'s own refusal. Never auto-merges (docs/FE_PIPELINE_STEPS.md).
 */
final class GitFastForwardExecutor implements StepExecutor {

    @Override
    public void execute(Map<String, Object> params) throws StepExecutionException {
        Path repoDir = Params.requirePath(params, "repoDir").toAbsolutePath().normalize();
        String remote = Params.optionalString(params, "remote", "origin");
        String branch = Params.requireString(params, "branch");

        Path git = ExecutableLocator.locate("git.exe", "CLAUDEV_GIT_EXE");
        Map<String, String> env = CorporateNetworkEnvironment.baseEnvironment();

        CliProcessRunner.Result status = CliProcessRunner.run(
                git, List.of(git.toString(), "status", "--porcelain"),
                repoDir, env, "claudev-git-status", Duration.ofSeconds(30));
        if (!status.succeeded()) {
            throw new StepExecutionException("git status failed (exit " + status.exitCode() + "): " + status.stderr());
        }
        if (!status.stdout().isBlank()) {
            throw new StepExecutionException("Worktree is dirty, refusing to fast-forward: " + repoDir);
        }

        CliProcessRunner.Result merge = CliProcessRunner.run(
                git, List.of(git.toString(), "merge", "--ff-only", remote + "/" + branch),
                repoDir, env, "claudev-git-ff", Duration.ofMinutes(2));
        if (!merge.succeeded()) {
            throw new StepExecutionException(
                    "git merge --ff-only failed, worktree is not fast-forwardable onto "
                            + remote + "/" + branch + " (exit " + merge.exitCode() + "): " + merge.stderr());
        }
    }
}

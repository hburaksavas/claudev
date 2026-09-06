package dev.claudev.domain;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A declarative FE pipeline step, interpreted by the operation engine's fixed step-type registry
 * (docs/FE_PIPELINE_STEPS.md). {@code GitFetch}/{@code GitFastForward} are deliberately separate
 * (not a combined "GitPull") so a dirty or non-fast-forward worktree blocks explicitly rather than
 * silently merging. {@code ExecStep} is the sole escape hatch: argv is a plain list passed directly
 * to process creation, never a shell string, and the executable must resolve to an allow-listed
 * canonical path configured per workspace.
 */
public sealed interface StepDefinition {

    record EnsureCheckout(String repositoryUrl, Path targetDir) implements StepDefinition {}

    record GitFetch(Path repoDir, String remote) implements StepDefinition {}

    record GitFastForward(Path repoDir, String branch) implements StepDefinition {}

    record MavenBuild(Path projectDir, List<String> goals) implements StepDefinition {}

    record ConfigPatch(Path targetFile, Map<String, String> substitutions, String schemaId) implements StepDefinition {}

    record StageArtifact(Path source, Path destination) implements StepDefinition {}

    record AtomicDeploy(Path stagedDir, Path deployDir) implements StepDefinition {}

    record StartProcess(String executable, List<String> args, Map<String, String> env) implements StepDefinition {}

    record HealthCheck(String url, int timeoutSeconds) implements StepDefinition {}

    record ExecStep(
            Path allowlistedExecutable,
            List<String> argv,
            Path workingDir,
            Map<String, String> env,
            int timeoutSeconds
    ) implements StepDefinition {}
}

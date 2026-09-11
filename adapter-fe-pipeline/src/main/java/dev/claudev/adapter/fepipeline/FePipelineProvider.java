package dev.claudev.adapter.fepipeline;

import dev.claudev.provider.Ack;
import dev.claudev.provider.AdapterManifest;
import dev.claudev.provider.Capability;
import dev.claudev.provider.ProviderError;
import dev.claudev.provider.ProviderResult;
import dev.claudev.provider.pipeline.PipelineRunRequest;
import dev.claudev.provider.pipeline.ProjectPipelineProvider;
import dev.claudev.provider.pipeline.StepInvocation;

import java.util.List;
import java.util.Map;

/**
 * Interprets a fixed, versioned step-type registry (docs/FE_PIPELINE_STEPS.md) — never compiled
 * per-project adapter code. Steps run in order and stop at the first failure; a pipeline is a
 * simple ordered list, not a general DAG (that's {@code operation-engine}'s job one level up, when
 * a "run this pipeline" node wraps a single call to {@link #execute} as its action).
 */
public final class FePipelineProvider implements ProjectPipelineProvider {

    private static final Map<String, StepExecutor> REGISTRY = Map.of(
            "EnsureCheckout", new EnsureCheckoutExecutor(),
            "GitFetch", new GitFetchExecutor(),
            "GitFastForward", new GitFastForwardExecutor(),
            "MavenBuild", new MavenBuildExecutor(),
            "ConfigPatch", new ConfigPatchExecutor(),
            "StageArtifact", new StageArtifactExecutor(),
            "AtomicDeploy", new AtomicDeployExecutor(),
            "StartProcess", new StartProcessExecutor(),
            "HealthCheck", new HealthCheckExecutor(),
            "ExecStep", new ExecStepExecutor());

    @Override
    public AdapterManifest manifest() {
        return new AdapterManifest(
                "adapter-fe-pipeline", "0.1.0",
                List.of(new Capability("pipeline", "fe"), new Capability("steps", String.join(",", REGISTRY.keySet()))),
                "{}");
    }

    @Override
    public ProviderResult<Ack> execute(PipelineRunRequest request) {
        for (StepInvocation invocation : request.steps()) {
            StepExecutor executor = REGISTRY.get(invocation.stepType());
            if (executor == null) {
                return ProviderResult.err(new ProviderError.InvalidConfig(
                        "Unknown step type: " + invocation.stepType()));
            }
            try {
                executor.execute(invocation.params());
            } catch (StepExecutionException e) {
                return ProviderResult.err(new ProviderError.Underlying(
                        "STEP_FAILED",
                        invocation.stepType() + ": " + e.getMessage()));
            } catch (RuntimeException e) {
                return ProviderResult.err(new ProviderError.Underlying(
                        "STEP_FAILED_UNEXPECTED",
                        invocation.stepType() + ": " + e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
        return ProviderResult.ok(Ack.INSTANCE);
    }
}

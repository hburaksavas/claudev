package dev.claudev.adapter.fepipeline;

import dev.claudev.provider.Ack;
import dev.claudev.provider.AdapterManifest;
import dev.claudev.provider.Capability;
import dev.claudev.provider.ProviderError;
import dev.claudev.provider.ProviderResult;
import dev.claudev.provider.pipeline.PipelineRunRequest;
import dev.claudev.provider.pipeline.ProjectPipelineProvider;

import java.util.List;

/**
 * Milestone M2 backlog item — see docs/FE_PIPELINE_STEPS.md for the step catalog
 * ({@code EnsureCheckout}, {@code GitFetch}, {@code GitFastForward}, {@code MavenBuild},
 * {@code ConfigPatch}, {@code StageArtifact}, {@code AtomicDeploy}, {@code StartProcess},
 * {@code HealthCheck}, {@code ExecStep}) and the argv-array/no-shell-string discipline every step
 * (including the Git steps) must follow. Deliberately not implemented yet.
 */
public final class FePipelineProvider implements ProjectPipelineProvider {

    @Override
    public AdapterManifest manifest() {
        return new AdapterManifest(
                "adapter-fe-pipeline", "0.1.0",
                List.of(new Capability("pipeline", "fe")),
                "{}");
    }

    @Override
    public ProviderResult<Ack> execute(PipelineRunRequest request) {
        return ProviderResult.err(new ProviderError.Underlying(
                "NOT_IMPLEMENTED", "adapter-fe-pipeline is M2 backlog, not yet built"));
    }
}

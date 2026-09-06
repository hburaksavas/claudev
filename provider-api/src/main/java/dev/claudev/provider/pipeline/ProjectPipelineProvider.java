package dev.claudev.provider.pipeline;

import dev.claudev.provider.Ack;
import dev.claudev.provider.AdapterManifest;
import dev.claudev.provider.ProviderResult;

/**
 * Runs a declarative FE pipeline. Steps cross this boundary as data ({@link StepInvocation}), not
 * as domain-core's {@code StepDefinition} sealed types directly — the engine maps between the two,
 * keeping the wire shape stable even as the domain-side step catalog grows (D2).
 */
public interface ProjectPipelineProvider {

    AdapterManifest manifest();

    ProviderResult<Ack> execute(PipelineRunRequest request);
}

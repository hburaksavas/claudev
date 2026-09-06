package dev.claudev.provider.pipeline;

import java.util.List;

public record PipelineRunRequest(String pipelineId, List<StepInvocation> steps) {
    public PipelineRunRequest {
        steps = List.copyOf(steps);
    }
}

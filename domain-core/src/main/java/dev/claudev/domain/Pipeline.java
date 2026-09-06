package dev.claudev.domain;

import java.util.List;

public record Pipeline(PipelineId id, WorkspaceId workspaceId, List<StepDefinition> steps) {
    public Pipeline {
        steps = List.copyOf(steps);
    }
}

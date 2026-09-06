package dev.claudev.domain;

import java.time.Instant;
import java.util.List;

public record Workspace(
        WorkspaceId id,
        String name,
        Instant createdAt,
        OwnerSid ownerSid,
        List<InstanceId> instanceIds,
        List<PipelineId> pipelineIds
) {
    public Workspace {
        instanceIds = List.copyOf(instanceIds);
        pipelineIds = List.copyOf(pipelineIds);
    }
}

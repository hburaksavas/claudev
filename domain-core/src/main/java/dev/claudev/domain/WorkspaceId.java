package dev.claudev.domain;

import java.util.UUID;

public record WorkspaceId(UUID value) {
    public static WorkspaceId newId() {
        return new WorkspaceId(UUID.randomUUID());
    }
}

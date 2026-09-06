package dev.claudev.domain;

import java.util.UUID;

public record PipelineId(UUID value) {
    public static PipelineId newId() {
        return new PipelineId(UUID.randomUUID());
    }
}

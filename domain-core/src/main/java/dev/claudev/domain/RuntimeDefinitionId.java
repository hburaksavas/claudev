package dev.claudev.domain;

import java.util.UUID;

public record RuntimeDefinitionId(UUID value) {
    public static RuntimeDefinitionId newId() {
        return new RuntimeDefinitionId(UUID.randomUUID());
    }
}

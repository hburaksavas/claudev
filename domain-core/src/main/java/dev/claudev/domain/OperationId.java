package dev.claudev.domain;

import java.util.UUID;

public record OperationId(UUID value) {
    public static OperationId newId() {
        return new OperationId(UUID.randomUUID());
    }
}

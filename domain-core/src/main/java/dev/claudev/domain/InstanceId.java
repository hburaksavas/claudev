package dev.claudev.domain;

import java.util.UUID;

public record InstanceId(UUID value) {
    public static InstanceId newId() {
        return new InstanceId(UUID.randomUUID());
    }
}

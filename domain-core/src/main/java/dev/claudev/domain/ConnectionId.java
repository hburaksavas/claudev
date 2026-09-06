package dev.claudev.domain;

import java.util.UUID;

public record ConnectionId(UUID value) {
    public static ConnectionId newId() {
        return new ConnectionId(UUID.randomUUID());
    }
}

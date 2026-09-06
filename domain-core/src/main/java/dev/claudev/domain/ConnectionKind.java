package dev.claudev.domain;

public sealed interface ConnectionKind {
    record Local(InstanceId instanceId) implements ConnectionKind {}

    record Remote(String host, int port, boolean tls) implements ConnectionKind {}
}

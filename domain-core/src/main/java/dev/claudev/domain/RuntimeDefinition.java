package dev.claudev.domain;

public record RuntimeDefinition(
        RuntimeDefinitionId id,
        RuntimeKind kind,
        RuntimeSource source,
        int configSchemaVersion
) {}

package dev.claudev.provider.connection;

/** A single-key, already-typed-and-confirmed edit (GET/SET/DEL/EXPIRE/... on one exact key). */
public record MutationRequest(String connectionId, String operation, String key, String value) {}

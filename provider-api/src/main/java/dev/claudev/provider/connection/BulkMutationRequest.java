package dev.claudev.provider.connection;

/** A bulk/glob/flush-class operation, which requires the preview+token round trip before commit. */
public record BulkMutationRequest(String connectionId, String operation, String keyPattern) {}

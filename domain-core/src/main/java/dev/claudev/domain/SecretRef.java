package dev.claudev.domain;

import java.util.UUID;

/**
 * Never the secret itself — only an opaque handle a {@code SecretStore} adapter can resolve at
 * execution time. This is the only form a secret may take in SQLite, logs, or an immutable
 * operation plan (docs/SECURITY.md).
 */
public record SecretRef(UUID id, String storeAdapter, String opaqueHandle) {}

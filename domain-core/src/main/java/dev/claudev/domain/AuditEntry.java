package dev.claudev.domain;

import java.time.Instant;
import java.util.UUID;

/** Append-only. A destructive action writes its "intent" entry before executing, not just after. */
public record AuditEntry(
        UUID id,
        String actor,
        String action,
        String target,
        Instant timestamp,
        String result,
        String detail
) {}

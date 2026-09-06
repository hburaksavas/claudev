package dev.claudev.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record Operation(
        OperationId id,
        String kind,
        List<String> targets,
        OperationDag dag,
        OperationStatus status,
        List<OperationEvent> events,
        Instant startedAt,
        Optional<Instant> endedAt,
        UUID cancelToken
) {
    public Operation {
        targets = List.copyOf(targets);
        events = List.copyOf(events);
    }
}

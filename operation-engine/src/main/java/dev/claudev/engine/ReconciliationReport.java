package dev.claudev.engine;

import dev.claudev.domain.InstanceId;

import java.util.List;

/** Summary of one {@link Reconciler#reconcile()} pass. */
public record ReconciliationReport(
        List<InstanceId> running,
        List<InstanceId> stopped,
        List<InstanceId> orphaned,
        List<Integer> untrackedPids
) {
    public ReconciliationReport {
        running = List.copyOf(running);
        stopped = List.copyOf(stopped);
        orphaned = List.copyOf(orphaned);
        untrackedPids = List.copyOf(untrackedPids);
    }
}

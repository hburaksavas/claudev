package dev.claudev.engine;

import dev.claudev.domain.Instance;
import dev.claudev.domain.InstanceId;
import dev.claudev.domain.InstanceState;
import dev.claudev.domain.LaunchRecord;
import dev.claudev.persistence.InstanceRepository;
import dev.claudev.persistence.LaunchRecordRepository;
import dev.claudev.persistence.OptimisticLockException;
import dev.claudev.persistence.Versioned;
import dev.claudev.platform.windows.ProcessIdentity;
import dev.claudev.platform.windows.RunningProcessScanner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Re-derives every instance's observed state from live OS reality — never from a stored value at
 * face value (docs/PROCESS_SAFETY.md). Intentionally framework-agnostic (no Spring dependency);
 * {@code app-bootstrap} calls this once, synchronously, before the UI is shown, and again on a
 * timer thereafter.
 *
 * <p>The adapter-specific health-probe step from PROCESS_SAFETY.md's algorithm (step 4: does e.g.
 * the RabbitMQ management API respond) is not part of this class — no adapter exists yet (WP6/7/8).
 * A verified-alive process (steps 1-3) is the strongest signal available today and maps directly to
 * {@code RUNNING}; wiring in a real health probe per adapter is additive, not a redesign, when
 * those adapters exist.
 */
public final class Reconciler {

    private final InstanceRepository instanceRepository;
    private final LaunchRecordRepository launchRecordRepository;
    private final Path managedBinariesDirectory;

    public Reconciler(
            InstanceRepository instanceRepository,
            LaunchRecordRepository launchRecordRepository,
            Path managedBinariesDirectory) {
        this.instanceRepository = instanceRepository;
        this.launchRecordRepository = launchRecordRepository;
        this.managedBinariesDirectory = managedBinariesDirectory;
    }

    public ReconciliationReport reconcile() {
        List<InstanceId> running = new ArrayList<>();
        List<InstanceId> stopped = new ArrayList<>();
        List<InstanceId> orphaned = new ArrayList<>();
        Set<Integer> trackedPids = new HashSet<>();

        for (Versioned<Instance> versioned : instanceRepository.findAll()) {
            InstanceState newState = deriveState(versioned.value(), trackedPids);
            applyIfChanged(versioned, newState);

            switch (newState) {
                case RUNNING -> running.add(versioned.value().id());
                case STOPPED -> stopped.add(versioned.value().id());
                case ORPHANED -> orphaned.add(versioned.value().id());
                default -> { }
            }
        }

        List<Integer> untrackedPids = RunningProcessScanner.pidsWithImageUnder(managedBinariesDirectory)
                .stream()
                .filter(pid -> !trackedPids.contains(pid))
                .toList();

        return new ReconciliationReport(running, stopped, orphaned, untrackedPids);
    }

    private InstanceState deriveState(Instance instance, Set<Integer> trackedPids) {
        Optional<LaunchRecord> launchRecord = launchRecordRepository.findByInstanceId(instance.id());
        if (launchRecord.isEmpty()) {
            return InstanceState.STOPPED;
        }

        LaunchRecord record = launchRecord.get();
        boolean verified = ProcessIdentity.verify(
                Math.toIntExact(record.pid()), record.processCreationTime(), record.exeFingerprintSha256());

        if (verified) {
            trackedPids.add(Math.toIntExact(record.pid()));
            return InstanceState.RUNNING;
        }

        // The recorded process is gone or has been replaced by something else at the same pid —
        // this LaunchRecord no longer describes anything real. Remove it rather than leave a
        // record that will only ever fail verification again (docs/PROCESS_SAFETY.md: "a stop
        // deletes the row... rather than marking it inactive").
        launchRecordRepository.delete(instance.id());
        return InstanceState.ORPHANED;
    }

    private void applyIfChanged(Versioned<Instance> versioned, InstanceState newState) {
        Instance instance = versioned.value();
        if (instance.observedState() == newState) {
            return;
        }

        Instance updated = new Instance(
                instance.id(), instance.workspaceId(), instance.runtimeDefinitionId(), instance.name(),
                instance.ports(), instance.dataDir(), instance.logDir(),
                instance.desiredState(), instance.launchRecord(), newState);

        try {
            instanceRepository.update(updated, versioned.revision());
        } catch (OptimisticLockException e) {
            // Another writer changed this instance during this reconcile pass; leave it for the
            // next pass rather than fighting over it — a reconcile pass is not the sole writer of
            // instance state (operation-engine's future Start/Stop actions are), so losing this
            // particular race is expected, not an error.
        }
    }
}

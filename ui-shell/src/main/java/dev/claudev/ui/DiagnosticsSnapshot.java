package dev.claudev.ui;

import java.util.List;

/**
 * A point-in-time view of what the application can actually verify about itself right now. Every
 * field is a live-measured value (a real SQLite pragma read, a real DPAPI round trip, a real Job
 * Object creation) or data declared by an adapter manifest — nothing here is placeholder text.
 */
public record DiagnosticsSnapshot(
        String javaVersion,
        String javafxVersion,
        String os,
        String user,
        String dbPath,
        String journalMode,
        String busyTimeout,
        CheckResult secretStore,
        CheckResult jobObject,
        List<AdapterRow> adapters
) {
    public DiagnosticsSnapshot {
        adapters = List.copyOf(adapters);
    }

    /** A live check: {@code ok} plus the detail worth showing whether it passed or failed. */
    public record CheckResult(boolean ok, String detail) {
        public static CheckResult pass(String detail) {
            return new CheckResult(true, detail);
        }

        public static CheckResult fail(String detail) {
            return new CheckResult(false, detail);
        }
    }

    /** One row per compiled-in adapter, rendered generically off its {@code AdapterManifest}. */
    public record AdapterRow(String id, String version, String capabilities, String status) {}
}

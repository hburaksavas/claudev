package dev.claudev.ui;

import java.util.List;

/**
 * Supplies {@link DiagnosticsSnapshot}s to the UI. Implemented in {@code app-bootstrap} (which can
 * see the DataSource, secret stores, and adapter beans); {@code ui-shell} depends only on this
 * interface, so the UI module stays free of persistence/adapter dependencies.
 */
@FunctionalInterface
public interface DiagnosticsSource {

    DiagnosticsSnapshot snapshot();

    /** Fallback used when the shell is launched without a backend wired (e.g. directly from an IDE). */
    static DiagnosticsSource unavailable() {
        return () -> new DiagnosticsSnapshot(
                System.getProperty("java.version", "?"),
                System.getProperty("javafx.runtime.version", "?"),
                System.getProperty("os.name", "?") + " " + System.getProperty("os.version", ""),
                System.getProperty("user.name", "?"),
                "(no backend wired)",
                "-",
                "-",
                DiagnosticsSnapshot.CheckResult.fail("no backend wired"),
                DiagnosticsSnapshot.CheckResult.fail("no backend wired"),
                DiagnosticsSnapshot.CheckResult.fail("no backend wired"),
                List.of());
    }
}

package dev.claudev.platform.windows;

import com.sun.jna.platform.win32.WinNT.HANDLE;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * The identity contract behind docs/PROCESS_SAFETY.md: no kill/signal action fires against a pid
 * without confirming its creation time and executable fingerprint still match what was recorded at
 * launch. Windows recycles pids quickly under load — pid alone is never sufficient, and this class
 * is the single place that check is implemented.
 */
public final class ProcessIdentity {

    private final int pid;
    private final Instant creationTime;
    private final String imagePath;
    private final String fingerprintSha256;

    private ProcessIdentity(int pid, Instant creationTime, String imagePath, String fingerprintSha256) {
        this.pid = pid;
        this.creationTime = creationTime;
        this.imagePath = imagePath;
        this.fingerprintSha256 = fingerprintSha256;
    }

    public int pid() {
        return pid;
    }

    public Instant creationTime() {
        return creationTime;
    }

    public String imagePath() {
        return imagePath;
    }

    public String fingerprintSha256() {
        return fingerprintSha256;
    }

    /**
     * Re-derives identity for a running process from live OS state. Empty if the process no
     * longer exists, cannot be opened, or cannot be fully queried (any partial-failure case is
     * treated as "cannot verify," never as a partial match).
     */
    public static Optional<ProcessIdentity> lookup(int pid) {
        HANDLE handle = Kernel32Ext.Lib.INSTANCE.OpenProcess(
                Kernel32Ext.PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
        if (handle == null) {
            return Optional.empty();
        }

        try {
            Optional<Instant> creationTime = WindowsProcessQuery.creationTime(handle);
            if (creationTime.isEmpty()) {
                return Optional.empty();
            }

            String imagePath = WindowsProcessQuery.imagePath(handle);
            if (imagePath == null) {
                return Optional.empty();
            }

            String fingerprint = WindowsProcessQuery.sha256(Path.of(imagePath));
            if (fingerprint == null) {
                return Optional.empty();
            }

            return Optional.of(new ProcessIdentity(pid, creationTime.get(), imagePath, fingerprint));
        } finally {
            Kernel32Ext.Lib.INSTANCE.CloseHandle(handle);
        }
    }

    /**
     * The verification gate every kill/signal path must pass: the pid must currently resolve to a
     * process whose creation time and executable fingerprint match exactly what was recorded when
     * this application launched it. A pid that has been recycled to an unrelated process — even
     * one that happens to share the same executable — fails on creation time; a swapped binary at
     * the same path fails on fingerprint.
     */
    public static boolean verify(int pid, Instant expectedCreationTime, String expectedFingerprintSha256) {
        return lookup(pid)
                .filter(actual -> actual.creationTime.equals(expectedCreationTime))
                .filter(actual -> actual.fingerprintSha256.equals(expectedFingerprintSha256))
                .isPresent();
    }
}

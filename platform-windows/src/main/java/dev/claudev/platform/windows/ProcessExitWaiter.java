package dev.claudev.platform.windows;

import com.sun.jna.platform.win32.WinNT.HANDLE;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Waits for a process this application spawned (RabbitMQ is long-lived and never needs this — this
 * is for CLI-shaped work like a Git/Maven step or {@code ExecStep} that runs to completion and
 * whose exit code determines success/failure) to exit, and returns its exit code.
 *
 * <p>Re-verifies identity (creation time) before waiting: {@link WindowsProcessLauncher} closes its
 * own process handle once a component is under Job Object supervision, so getting the exit code
 * later means reopening a handle by pid — and pid reuse is exactly the failure mode
 * docs/PROCESS_SAFETY.md exists to prevent. If the pid no longer resolves to the expected process,
 * this returns empty rather than risk waiting on (or reporting an exit code for) an unrelated one.
 */
public final class ProcessExitWaiter {

    private ProcessExitWaiter() {
    }

    public static Optional<Integer> waitForExit(int pid, Instant expectedCreationTime, Duration timeout) {
        HANDLE handle = Kernel32Ext.Lib.INSTANCE.OpenProcess(
                Kernel32Ext.PROCESS_QUERY_LIMITED_INFORMATION | Kernel32Ext.SYNCHRONIZE, false, pid);
        if (handle == null) {
            return Optional.empty();
        }

        try {
            Optional<Instant> actualCreationTime = WindowsProcessQuery.creationTime(handle);
            if (actualCreationTime.isEmpty() || !actualCreationTime.get().equals(expectedCreationTime)) {
                return Optional.empty();
            }

            int waitResult = Kernel32Ext.Lib.INSTANCE.WaitForSingleObject(handle, Math.toIntExact(timeout.toMillis()));
            if (waitResult != Kernel32Ext.WAIT_OBJECT_0) {
                return Optional.empty();
            }

            int[] exitCode = new int[1];
            if (!Kernel32Ext.Lib.INSTANCE.GetExitCodeProcess(handle, exitCode)) {
                return Optional.empty();
            }
            return Optional.of(exitCode[0]);
        } finally {
            Kernel32Ext.Lib.INSTANCE.CloseHandle(handle);
        }
    }
}

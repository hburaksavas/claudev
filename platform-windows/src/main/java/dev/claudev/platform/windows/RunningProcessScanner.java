package dev.claudev.platform.windows;

import com.sun.jna.platform.win32.WinNT.HANDLE;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Enumerates live Windows processes to find ones whose executable resolves under a given
 * directory — the mechanism behind the reconciler's {@code UNTRACKED} classification
 * (docs/PROCESS_SAFETY.md): a process running from the app's own managed-binaries directory that
 * no {@code LaunchRecord} accounts for is a bookkeeping bug signal, not a legitimate orphan.
 */
public final class RunningProcessScanner {

    private RunningProcessScanner() {
    }

    private static final int INITIAL_CAPACITY = 1024;
    private static final int MAX_CAPACITY = 65536;

    public static List<Integer> pidsWithImageUnder(Path directory) {
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        List<Integer> matches = new ArrayList<>();

        for (int pid : allProcessIds()) {
            HANDLE handle = Kernel32Ext.Lib.INSTANCE.OpenProcess(
                    Kernel32Ext.PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
            if (handle == null) {
                // No access, or the process already exited between enumeration and this call —
                // skip; this is routine for a system-wide scan, not an error.
                continue;
            }
            try {
                String imagePath = WindowsProcessQuery.imagePath(handle);
                if (imagePath == null) {
                    continue;
                }
                Path normalizedImage = Path.of(imagePath).toAbsolutePath().normalize();
                if (normalizedImage.startsWith(normalizedDirectory)) {
                    matches.add(pid);
                }
            } finally {
                Kernel32Ext.Lib.INSTANCE.CloseHandle(handle);
            }
        }
        return matches;
    }

    private static List<Integer> allProcessIds() {
        int capacity = INITIAL_CAPACITY;
        while (capacity <= MAX_CAPACITY) {
            int[] pids = new int[capacity];
            int[] bytesReturned = new int[1];
            boolean ok = Kernel32Ext.Lib.INSTANCE.K32EnumProcesses(pids, pids.length * Integer.BYTES, bytesReturned);
            if (!ok) {
                throw new WindowsJobObject.WindowsApiException("K32EnumProcesses", Kernel32Ext.Lib.INSTANCE.GetLastError());
            }

            int count = bytesReturned[0] / Integer.BYTES;
            if (count < capacity) {
                List<Integer> result = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    if (pids[i] != 0) { // pid 0 is the System Idle Process — not a real, queryable process
                        result.add(pids[i]);
                    }
                }
                return result;
            }
            capacity *= 2; // buffer may have been exactly filled (truncated) — grow and retry
        }
        throw new IllegalStateException("System process count exceeds " + MAX_CAPACITY + " — refusing to grow further");
    }
}

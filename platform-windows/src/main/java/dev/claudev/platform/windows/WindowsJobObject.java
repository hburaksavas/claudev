package dev.claudev.platform.windows;

import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinNT.HANDLE;

/**
 * A Win32 Job Object with {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE} set at creation, so closing
 * the handle (including the implicit close on abnormal process exit) kills every process ever
 * assigned to it. This is the per-component (never per-workspace) unit from D5 — no parent/child
 * job nesting here by design.
 *
 * <p>Every {@link #assign} caller must have followed the spawn-suspended -> assign -> verify ->
 * resume ordering invariant from D5's compensating condition; this class only wraps the raw calls,
 * it does not itself enforce spawn ordering.
 *
 * <p><b>Unverified against Spike B</b> (app-already-inside-an-external-Job case) — treat this as a
 * binding layer proven to create/assign/terminate correctly on a plain developer machine
 * (see {@code WindowsJobObjectSmokeTest}), not yet proven under the corporate-AppLocker/EDR
 * scenario the design docs call out as a release-blocking risk.
 */
public final class WindowsJobObject implements AutoCloseable {

    private final HANDLE jobHandle;
    private volatile boolean closed;

    private WindowsJobObject(HANDLE jobHandle) {
        this.jobHandle = jobHandle;
    }

    public static WindowsJobObject createWithKillOnClose(String name) {
        HANDLE handle = Kernel32Ext.Lib.INSTANCE.CreateJobObjectW(null, name == null ? null : new WString(name));
        if (handle == null) {
            throw new WindowsApiException("CreateJobObjectW", Kernel32Ext.Lib.INSTANCE.GetLastError());
        }

        Kernel32Ext.JobObjectExtendedLimitInformation info = new Kernel32Ext.JobObjectExtendedLimitInformation();
        info.basicLimitInformation.limitFlags = Kernel32Ext.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        info.write();

        boolean ok = Kernel32Ext.Lib.INSTANCE.SetInformationJobObject(
                handle,
                Kernel32Ext.JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS,
                info,
                info.size());
        if (!ok) {
            int error = Kernel32Ext.Lib.INSTANCE.GetLastError();
            Kernel32Ext.Lib.INSTANCE.CloseHandle(handle);
            throw new WindowsApiException("SetInformationJobObject", error);
        }

        return new WindowsJobObject(handle);
    }

    /** {@code processHandle} must have been opened with at least {@code PROCESS_SET_QUOTA | PROCESS_TERMINATE}. */
    public void assign(HANDLE processHandle) {
        requireOpen();
        boolean ok = Kernel32Ext.Lib.INSTANCE.AssignProcessToJobObject(jobHandle, processHandle);
        if (!ok) {
            throw new WindowsApiException("AssignProcessToJobObject", Kernel32Ext.Lib.INSTANCE.GetLastError());
        }
    }

    /** Fan-out target for "Force Stop Workspace": call in parallel across every component job, never via a parent job (D5). */
    public void terminate(int exitCode) {
        requireOpen();
        Kernel32Ext.Lib.INSTANCE.TerminateJobObject(jobHandle, exitCode);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Kernel32Ext.Lib.INSTANCE.CloseHandle(jobHandle);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("WindowsJobObject already closed");
        }
    }

    public static final class WindowsApiException extends RuntimeException {
        public WindowsApiException(String apiCall, int lastError) {
            super(apiCall + " failed, GetLastError=" + lastError);
        }
    }
}

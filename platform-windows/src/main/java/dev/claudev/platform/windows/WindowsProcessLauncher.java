package dev.claudev.platform.windows;

import com.sun.jna.Memory;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinNT.HANDLE;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implements the D5 spawn ordering invariant end to end: {@code CreateProcessW} suspended, create
 * the component's {@code KILL_ON_JOB_CLOSE} Job Object, assign, verify, only then resume. No code
 * between assignment and resume can fail without also being able to terminate the still-suspended
 * child — see the {@code catch} block below.
 *
 * <p>This is the one place in the application that is allowed to call {@code CreateProcessW}
 * directly; every adapter that needs to spawn a supervised process (RabbitMQ, an {@code ExecStep},
 * a Git/Maven step) goes through here, not through a hand-rolled native call or
 * {@code ProcessBuilder} (which offers neither {@code CREATE_SUSPENDED} nor access to the raw
 * process/thread handles Job Object assignment needs).
 */
public final class WindowsProcessLauncher {

    private static final Logger LOG = Logger.getLogger(WindowsProcessLauncher.class.getName());

    private WindowsProcessLauncher() {
    }

    public static LaunchResult launch(LaunchSpec spec) {
        String builtCommandLine = WindowsCommandLine.build(spec.argv());
        // Logged *before* CreateProcessW runs (docs/MILESTONES.md WP8's "pre-execution argv is
        // logged and inspectable" acceptance line, generalized here to every real spawn rather than
        // just the FE pipeline steps it was originally scoped to) — so a hung or crashed spawn still
        // leaves a record of exactly what was about to run. Never logs `spec.environment()`: even
        // though this codebase's spawns are explicit-environment-only (docs/SECURITY.md), a future
        // caller's forwarded variable is not guaranteed secret-free, and argv is what the acceptance
        // line actually asked for.
        LOG.log(Level.INFO, "spawning [{0}] {1}", new Object[] {spec.jobName(), builtCommandLine});
        Memory commandLine = toNativeCommandLine(builtCommandLine);
        Memory environment = WindowsEnvironmentBlock.encode(spec.environment());

        Kernel32Ext.StartupInfo startupInfo = new Kernel32Ext.StartupInfo();
        Kernel32Ext.ProcessInformation processInfo = new Kernel32Ext.ProcessInformation();

        boolean redirecting = spec.stdoutFile().isPresent() || spec.stderrFile().isPresent();
        HANDLE stdinHandle = null;
        HANDLE stdoutHandle = null;
        HANDLE stderrHandle = null;

        int flags = Kernel32Ext.CREATE_SUSPENDED
                | Kernel32Ext.CREATE_UNICODE_ENVIRONMENT
                | (spec.suppressWindow() ? Kernel32Ext.CREATE_NO_WINDOW : 0);

        WString applicationName = new WString(spec.executable().toAbsolutePath().toString());
        WString workingDirectory = spec.workingDirectory() == null
                ? null
                : new WString(spec.workingDirectory().toAbsolutePath().toString());

        try {
            if (redirecting) {
                // STARTF_USESTDHANDLES requires all three to be valid together, so whichever of
                // stdout/stderr wasn't asked for still gets a real (NUL) handle, and stdin is
                // always NUL — a spawned CLI step must never be able to read this process's input.
                stdinHandle = openForRead(NUL_DEVICE);
                stdoutHandle = openForWrite(spec.stdoutFile().map(Path::toString).orElse(NUL_DEVICE));
                stderrHandle = openForWrite(spec.stderrFile().map(Path::toString).orElse(NUL_DEVICE));
                startupInfo.dwFlags |= Kernel32Ext.STARTF_USESTDHANDLES;
                startupInfo.hStdInput = stdinHandle;
                startupInfo.hStdOutput = stdoutHandle;
                startupInfo.hStdError = stderrHandle;
            }
            startupInfo.write();

            boolean created = Kernel32Ext.Lib.INSTANCE.CreateProcessW(
                    applicationName, commandLine, null, null, redirecting, flags,
                    environment, workingDirectory, startupInfo, processInfo);
            if (!created) {
                throw new WindowsJobObject.WindowsApiException("CreateProcessW", Kernel32Ext.Lib.INSTANCE.GetLastError());
            }
            processInfo.read();
        } finally {
            closeIfNotNull(stdinHandle);
            closeIfNotNull(stdoutHandle);
            closeIfNotNull(stderrHandle);
        }

        HANDLE processHandle = processInfo.hProcess;
        HANDLE threadHandle = processInfo.hThread;
        WindowsJobObject job = null;
        try {
            job = WindowsJobObject.createWithKillOnClose(spec.jobName());
            job.assign(processHandle); // throws on failure — caught below, suspended child is terminated

            Instant creationTime = WindowsProcessQuery.creationTime(processHandle)
                    .orElseThrow(() -> new WindowsJobObject.WindowsApiException(
                            "GetProcessTimes", Kernel32Ext.Lib.INSTANCE.GetLastError()));

            String imagePath = WindowsProcessQuery.imagePath(processHandle);
            if (imagePath == null) {
                throw new WindowsJobObject.WindowsApiException(
                        "QueryFullProcessImageNameW", Kernel32Ext.Lib.INSTANCE.GetLastError());
            }

            String fingerprint = WindowsProcessQuery.sha256(Path.of(imagePath));
            if (fingerprint == null) {
                throw new IllegalStateException("Could not fingerprint spawned image: " + imagePath);
            }

            int previousSuspendCount = Kernel32Ext.Lib.INSTANCE.ResumeThread(threadHandle);
            if (previousSuspendCount < 0) {
                throw new WindowsJobObject.WindowsApiException("ResumeThread", Kernel32Ext.Lib.INSTANCE.GetLastError());
            }

            return new LaunchResult(processInfo.dwProcessId, creationTime, imagePath, fingerprint, job);
        } catch (RuntimeException e) {
            // Still-suspended (or now-resumed-but-failed-afterward) process must never be left
            // running untracked — this is what makes "no code between assign and resume can fail
            // without also killing the suspended child" true in practice, not just in the javadoc.
            Kernel32Ext.Lib.INSTANCE.TerminateProcess(processHandle, 1);
            if (job != null) {
                job.close();
            }
            throw e;
        } finally {
            Kernel32Ext.Lib.INSTANCE.CloseHandle(threadHandle);
            Kernel32Ext.Lib.INSTANCE.CloseHandle(processHandle);
        }
    }

    private static final String NUL_DEVICE = "NUL";

    private static HANDLE openForWrite(String path) {
        Kernel32Ext.SecurityAttributes inheritable = inheritableSecurityAttributes();
        HANDLE handle = Kernel32Ext.Lib.INSTANCE.CreateFileW(
                new WString(path), Kernel32Ext.GENERIC_WRITE, Kernel32Ext.FILE_SHARE_READ,
                inheritable, Kernel32Ext.CREATE_ALWAYS, Kernel32Ext.FILE_ATTRIBUTE_NORMAL, null);
        if (handle == null || Kernel32Ext.INVALID_HANDLE_VALUE.equals(handle)) {
            throw new WindowsJobObject.WindowsApiException(
                    "CreateFileW(" + path + ")", Kernel32Ext.Lib.INSTANCE.GetLastError());
        }
        return handle;
    }

    private static HANDLE openForRead(String path) {
        Kernel32Ext.SecurityAttributes inheritable = inheritableSecurityAttributes();
        HANDLE handle = Kernel32Ext.Lib.INSTANCE.CreateFileW(
                new WString(path), 0x80000000 /* GENERIC_READ */, Kernel32Ext.FILE_SHARE_READ,
                inheritable, 3 /* OPEN_EXISTING */, Kernel32Ext.FILE_ATTRIBUTE_NORMAL, null);
        if (handle == null || Kernel32Ext.INVALID_HANDLE_VALUE.equals(handle)) {
            throw new WindowsJobObject.WindowsApiException(
                    "CreateFileW(" + path + ")", Kernel32Ext.Lib.INSTANCE.GetLastError());
        }
        return handle;
    }

    private static Kernel32Ext.SecurityAttributes inheritableSecurityAttributes() {
        Kernel32Ext.SecurityAttributes attributes = new Kernel32Ext.SecurityAttributes();
        attributes.bInheritHandle = true;
        attributes.write();
        return attributes;
    }

    private static void closeIfNotNull(HANDLE handle) {
        if (handle != null) {
            Kernel32Ext.Lib.INSTANCE.CloseHandle(handle);
        }
    }

    /** A native, writable, null-terminated UTF-16LE buffer — required because CreateProcessW may modify lpCommandLine in place. */
    private static Memory toNativeCommandLine(String commandLine) {
        byte[] utf16le = (commandLine + "\0").getBytes(StandardCharsets.UTF_16LE);
        Memory memory = new Memory(utf16le.length);
        memory.write(0, utf16le, 0, utf16le.length);
        return memory;
    }
}

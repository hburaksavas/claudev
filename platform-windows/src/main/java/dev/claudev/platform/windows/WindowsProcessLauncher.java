package dev.claudev.platform.windows;

import com.sun.jna.Memory;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinNT.HANDLE;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;

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

    private WindowsProcessLauncher() {
    }

    public static LaunchResult launch(LaunchSpec spec) {
        Memory commandLine = toNativeCommandLine(WindowsCommandLine.build(spec.argv()));
        Memory environment = WindowsEnvironmentBlock.encode(spec.environment());

        Kernel32Ext.StartupInfo startupInfo = new Kernel32Ext.StartupInfo();
        startupInfo.write();
        Kernel32Ext.ProcessInformation processInfo = new Kernel32Ext.ProcessInformation();

        int flags = Kernel32Ext.CREATE_SUSPENDED
                | Kernel32Ext.CREATE_UNICODE_ENVIRONMENT
                | (spec.suppressWindow() ? Kernel32Ext.CREATE_NO_WINDOW : 0);

        WString applicationName = new WString(spec.executable().toAbsolutePath().toString());
        WString workingDirectory = spec.workingDirectory() == null
                ? null
                : new WString(spec.workingDirectory().toAbsolutePath().toString());

        boolean created = Kernel32Ext.Lib.INSTANCE.CreateProcessW(
                applicationName, commandLine, null, null, false, flags,
                environment, workingDirectory, startupInfo, processInfo);
        if (!created) {
            throw new WindowsJobObject.WindowsApiException("CreateProcessW", Kernel32Ext.Lib.INSTANCE.GetLastError());
        }
        processInfo.read();

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

    /** A native, writable, null-terminated UTF-16LE buffer — required because CreateProcessW may modify lpCommandLine in place. */
    private static Memory toNativeCommandLine(String commandLine) {
        byte[] utf16le = (commandLine + "\0").getBytes(StandardCharsets.UTF_16LE);
        Memory memory = new Memory(utf16le.length);
        memory.write(0, utf16le, 0, utf16le.length);
        return memory;
    }
}

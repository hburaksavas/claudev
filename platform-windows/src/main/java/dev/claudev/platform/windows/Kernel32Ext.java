package dev.claudev.platform.windows;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.util.Arrays;
import java.util.List;

/**
 * Minimal, self-declared bindings for the kernel32.dll functions the process-supervision model
 * needs. Declared directly (not borrowed from jna-platform's own {@code Kernel32} interface) so
 * every field/signature here is something this module owns and can be audited against the Win32
 * SDK headers, rather than inherited from however a third-party binding happens to shape it.
 */
final class Kernel32Ext {

    private Kernel32Ext() {}

    /** DWORD JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE — WinNT.h. */
    static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000;

    /** JOBOBJECTINFOCLASS.JobObjectExtendedLimitInformation — WinNT.h. */
    static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS = 9;

    /** PROCESS_TERMINATE | PROCESS_SET_QUOTA — the minimum rights AssignProcessToJobObject needs. */
    static final int PROCESS_TERMINATE_AND_SET_QUOTA = 0x0001 | 0x0100;

    /** PROCESS_QUERY_LIMITED_INFORMATION — WinNT.h; enough for GetProcessTimes/QueryFullProcessImageNameW. */
    static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    /** dwCreationFlags — WinBase.h. */
    static final int CREATE_SUSPENDED = 0x00000004;
    static final int CREATE_NO_WINDOW = 0x08000000;
    static final int CREATE_UNICODE_ENVIRONMENT = 0x00000400;

    /** GetExitCodeProcess sentinel meaning "still running" — WinBase.h. */
    static final int STILL_ACTIVE = 259;

    static final int WAIT_OBJECT_0 = 0x00000000;
    static final int WAIT_TIMEOUT = 0x00000102;
    static final int WAIT_FAILED = -1;

    interface Lib extends StdCallLibrary {
        Lib INSTANCE = Native.load("kernel32", Lib.class, W32APIOptions.DEFAULT_OPTIONS);

        HANDLE CreateJobObjectW(Pointer lpJobAttributes, WString lpName);

        HANDLE OpenProcess(int desiredAccess, boolean inheritHandle, int processId);

        boolean AssignProcessToJobObject(HANDLE hJob, HANDLE hProcess);

        boolean SetInformationJobObject(
                HANDLE hJob,
                int jobObjectInfoClass,
                JobObjectExtendedLimitInformation lpJobObjectInfo,
                int cbJobObjectInfoLength);

        boolean TerminateJobObject(HANDLE hJob, int uExitCode);

        boolean CloseHandle(HANDLE hObject);

        int GetLastError();

        /**
         * {@code lpApplicationName} is the resolved, canonicalized executable path (required by
         * docs/SECURITY.md's centralized path validation — never left to PATH search).
         * {@code lpCommandLine} must be a writable buffer (the API is documented as free to modify
         * it in place), so callers pass a {@link Memory} they own, never a JNA {@code WString}
         * (which is a scratch copy JNA does not guarantee is independently writable across calls).
         */
        boolean CreateProcessW(
                WString lpApplicationName,
                Memory lpCommandLine,
                Pointer lpProcessAttributes,
                Pointer lpThreadAttributes,
                boolean bInheritHandles,
                int dwCreationFlags,
                Pointer lpEnvironment,
                WString lpCurrentDirectory,
                StartupInfo lpStartupInfo,
                ProcessInformation lpProcessInformation);

        int ResumeThread(HANDLE hThread);

        boolean TerminateProcess(HANDLE hProcess, int uExitCode);

        boolean GetProcessTimes(
                HANDLE hProcess, FileTime lpCreationTime, FileTime lpExitTime,
                FileTime lpKernelTime, FileTime lpUserTime);

        /**
         * {@code lpdwSize} is in/out: callers pass the buffer's char capacity, the API writes back
         * the actual length written (not including the null terminator).
         */
        boolean QueryFullProcessImageNameW(HANDLE hProcess, int dwFlags, char[] lpExeName, int[] lpdwSize);

        boolean GetExitCodeProcess(HANDLE hProcess, int[] lpExitCode);

        int WaitForSingleObject(HANDLE hHandle, int dwMilliseconds);
    }

    /** _STARTUPINFOW, WinBase.h — only the fields this module ever sets are named beyond padding needs. */
    public static class StartupInfo extends Structure {
        public int cb = size();
        public Pointer lpReserved;
        public Pointer lpDesktop;
        public Pointer lpTitle;
        public int dwX;
        public int dwY;
        public int dwXSize;
        public int dwYSize;
        public int dwXCountChars;
        public int dwYCountChars;
        public int dwFillAttribute;
        public int dwFlags;
        public short wShowWindow;
        public short cbReserved2;
        public Pointer lpReserved2;
        public HANDLE hStdInput;
        public HANDLE hStdOutput;
        public HANDLE hStdError;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList(
                    "cb", "lpReserved", "lpDesktop", "lpTitle", "dwX", "dwY", "dwXSize", "dwYSize",
                    "dwXCountChars", "dwYCountChars", "dwFillAttribute", "dwFlags", "wShowWindow",
                    "cbReserved2", "lpReserved2", "hStdInput", "hStdOutput", "hStdError");
        }
    }

    /** _PROCESS_INFORMATION, WinBase.h — the two HANDLEs must each be closed by the caller. */
    public static class ProcessInformation extends Structure {
        public HANDLE hProcess;
        public HANDLE hThread;
        public int dwProcessId;
        public int dwThreadId;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("hProcess", "hThread", "dwProcessId", "dwThreadId");
        }
    }

    /** FILETIME, WinBase.h — 100-ns ticks since 1601-01-01, split across two DWORDs. */
    public static class FileTime extends Structure {
        public int dwLowDateTime;
        public int dwHighDateTime;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("dwLowDateTime", "dwHighDateTime");
        }

        /** Combined 100-ns tick count, treating both halves as unsigned per the Win32 contract. */
        long toTicks() {
            return (((long) dwHighDateTime) << 32) | (dwLowDateTime & 0xFFFFFFFFL);
        }
    }

    /** _JOBOBJECT_BASIC_LIMIT_INFORMATION, WinNT.h — natural x64 alignment matches JNA defaults. */
    public static class JobObjectBasicLimitInformation extends Structure {
        public long perProcessUserTimeLimit;
        public long perJobUserTimeLimit;
        public int limitFlags;
        public long minimumWorkingSetSize;
        public long maximumWorkingSetSize;
        public int activeProcessLimit;
        public long affinity;
        public int priorityClass;
        public int schedulingClass;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList(
                    "perProcessUserTimeLimit", "perJobUserTimeLimit", "limitFlags",
                    "minimumWorkingSetSize", "maximumWorkingSetSize", "activeProcessLimit",
                    "affinity", "priorityClass", "schedulingClass");
        }
    }

    /** _IO_COUNTERS, WinNT.h. */
    public static class IoCounters extends Structure {
        public long readOperationCount;
        public long writeOperationCount;
        public long otherOperationCount;
        public long readTransferCount;
        public long writeTransferCount;
        public long otherTransferCount;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList(
                    "readOperationCount", "writeOperationCount", "otherOperationCount",
                    "readTransferCount", "writeTransferCount", "otherTransferCount");
        }
    }

    /** _JOBOBJECT_EXTENDED_LIMIT_INFORMATION, WinNT.h. */
    public static class JobObjectExtendedLimitInformation extends Structure {
        public JobObjectBasicLimitInformation basicLimitInformation = new JobObjectBasicLimitInformation();
        public IoCounters ioInfo = new IoCounters();
        public long processMemoryLimit;
        public long jobMemoryLimit;
        public long peakProcessMemoryUsed;
        public long peakJobMemoryUsed;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList(
                    "basicLimitInformation", "ioInfo", "processMemoryLimit",
                    "jobMemoryLimit", "peakProcessMemoryUsed", "peakJobMemoryUsed");
        }
    }
}

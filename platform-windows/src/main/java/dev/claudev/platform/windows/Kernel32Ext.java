package dev.claudev.platform.windows;

import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.util.Arrays;
import java.util.List;

/**
 * Minimal, self-declared bindings for the five kernel32.dll functions the Job Object supervision
 * model needs. Declared directly (not borrowed from jna-platform's own {@code Kernel32}
 * interface) so every field/signature here is something this module owns and can be audited
 * against the Win32 SDK headers, rather than inherited from however a third-party binding happens
 * to shape it.
 */
final class Kernel32Ext {

    private Kernel32Ext() {}

    /** DWORD JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE — WinNT.h. */
    static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000;

    /** JOBOBJECTINFOCLASS.JobObjectExtendedLimitInformation — WinNT.h. */
    static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS = 9;

    /** PROCESS_TERMINATE | PROCESS_SET_QUOTA — the minimum rights AssignProcessToJobObject needs. */
    static final int PROCESS_TERMINATE_AND_SET_QUOTA = 0x0001 | 0x0100;

    interface Lib extends StdCallLibrary {
        Lib INSTANCE = Native.load("kernel32", Lib.class, W32APIOptions.DEFAULT_OPTIONS);

        HANDLE CreateJobObjectW(com.sun.jna.Pointer lpJobAttributes, WString lpName);

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

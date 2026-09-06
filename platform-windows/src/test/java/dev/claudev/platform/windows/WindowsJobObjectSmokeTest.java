package dev.claudev.platform.windows;

import com.sun.jna.platform.win32.WinNT.HANDLE;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;

/**
 * Real (not mocked) exercise of the Kernel32 Job Object bindings on the actual machine running
 * the build: spawns a long-lived child process, assigns it into a KILL_ON_JOB_CLOSE job, then
 * terminates the job and confirms the child actually died. This is the smallest possible slice of
 * the "Job Object crash-safety" implementation spike from docs — it proves the struct layout and
 * function bindings are correct, not that the full spawn-suspended/assign/resume ordering (Spike
 * B, external-job case) holds.
 */
class WindowsJobObjectSmokeTest {

    @Test
    void terminatingTheJobKillsTheAssignedProcess() throws IOException {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        Process child = new ProcessBuilder("ping", "-n", "60", "127.0.0.1")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();

        try {
            assertThat(child.isAlive()).isTrue();

            HANDLE processHandle = Kernel32Ext.Lib.INSTANCE.OpenProcess(
                    Kernel32Ext.PROCESS_TERMINATE_AND_SET_QUOTA, false, (int) child.pid());
            assertThat(processHandle).isNotNull();

            try (WindowsJobObject job = WindowsJobObject.createWithKillOnClose("claudev-smoke-" + child.pid())) {
                job.assign(processHandle);
                job.terminate(1);

                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> child.waitFor());
                assertThat(child.isAlive()).isFalse();
            } finally {
                Kernel32Ext.Lib.INSTANCE.CloseHandle(processHandle);
            }
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        }
    }
}

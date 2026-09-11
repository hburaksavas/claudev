package dev.claudev.platform.windows;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the full D5 spawn ordering (suspend -&gt; create job -&gt; assign -&gt; verify -&gt;
 * resume) through real Win32 calls on the machine running the build — no mocking of
 * {@code CreateProcessW}, no mocking of Job Objects.
 */
class WindowsProcessLauncherTest {

    private static final Path SYSTEM_ROOT = Path.of(System.getenv("SystemRoot"));

    @Test
    void terminatingTheJobKillsTheWholeTreeIncludingAGrandchild() {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        Path cmdExe = SYSTEM_ROOT.resolve("System32").resolve("cmd.exe");
        LaunchSpec spec = new LaunchSpec(
                cmdExe,
                List.of(cmdExe.toString(), "/c", "ping -n 30 127.0.0.1 >nul"),
                null,
                Map.of("SystemRoot", SYSTEM_ROOT.toString(), "ComSpec", cmdExe.toString()),
                "claudev-wp1-grandchild-" + UUID.randomUUID(),
                true);

        LaunchResult result = WindowsProcessLauncher.launch(spec);
        try {
            ProcessHandle cmdHandle = ProcessHandle.of(result.pid())
                    .orElseThrow(() -> new AssertionError("Launched cmd.exe pid not visible to the JVM"));
            assertThat(cmdHandle.isAlive()).isTrue();

            // cmd.exe spawns ping.exe as a real OS child of the process we launched — this is the
            // same shape as mvn.cmd -> cmd.exe -> java.exe from docs/PROCESS_SAFETY.md.
            ProcessHandle grandchild = waitForChild(cmdHandle, Duration.ofSeconds(10));
            assertThat(grandchild.isAlive()).isTrue();

            result.job().terminate(1);

            waitUntilNotAlive(cmdHandle, Duration.ofSeconds(10));
            waitUntilNotAlive(grandchild, Duration.ofSeconds(10));
        } finally {
            result.job().close();
        }
    }

    @Test
    void identityVerificationHoldsWhileAliveAndFailsAfterExit() {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        Path pingExe = SYSTEM_ROOT.resolve("System32").resolve("ping.exe");
        LaunchSpec spec = new LaunchSpec(
                pingExe,
                List.of(pingExe.toString(), "-n", "1", "127.0.0.1"),
                null,
                Map.of("SystemRoot", SYSTEM_ROOT.toString()),
                "claudev-wp1-identity-" + UUID.randomUUID(),
                true);

        LaunchResult result = WindowsProcessLauncher.launch(spec);
        try {
            assertThat(ProcessIdentity.verify(result.pid(), result.creationTime(), result.fingerprintSha256()))
                    .as("identity must verify immediately after a successful launch")
                    .isTrue();

            ProcessHandle handle = ProcessHandle.of(result.pid())
                    .orElseThrow(() -> new AssertionError("Launched ping.exe pid not visible to the JVM"));
            waitUntilNotAlive(handle, Duration.ofSeconds(10));

            // The core PID-reuse defense: once the recorded process has exited, the same recorded
            // identity must never verify again, regardless of what pid a future process gets.
            assertThat(ProcessIdentity.verify(result.pid(), result.creationTime(), result.fingerprintSha256()))
                    .as("a stale LaunchRecord must not verify once the original process has exited")
                    .isFalse();

            // A mismatched creationTime against the (now nonexistent, but hypothetically reused) pid
            // must also fail — this is the discriminator that defeats an actual pid recycle.
            assertThat(ProcessIdentity.verify(result.pid(), Instant.EPOCH, result.fingerprintSha256())).isFalse();
        } finally {
            result.job().close();
        }
    }

    @Test
    void launchReportsAnAccurateFingerprintOfTheSpawnedImage() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        Path pingExe = SYSTEM_ROOT.resolve("System32").resolve("ping.exe");
        LaunchSpec spec = new LaunchSpec(
                pingExe,
                List.of(pingExe.toString(), "-n", "1", "127.0.0.1"),
                null,
                Map.of("SystemRoot", SYSTEM_ROOT.toString()),
                "claudev-wp1-fingerprint-" + UUID.randomUUID(),
                true);

        LaunchResult result = WindowsProcessLauncher.launch(spec);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String expected = HexFormat.of().formatHex(digest.digest(Files.readAllBytes(pingExe)));

            assertThat(result.fingerprintSha256()).isEqualToIgnoringCase(expected);
            assertThat(result.imagePath()).endsWithIgnoringCase("ping.exe");
        } finally {
            result.job().close();
        }
    }

    private static ProcessHandle waitForChild(ProcessHandle parent, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Optional<ProcessHandle> child = parent.children().findFirst();
            if (child.isPresent()) {
                return child.get();
            }
            sleep();
        }
        throw new AssertionError("No grandchild process appeared under pid " + parent.pid() + " within " + timeout);
    }

    private static void waitUntilNotAlive(ProcessHandle handle, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!handle.isAlive()) {
                return;
            }
            sleep();
        }
        throw new AssertionError("Process " + handle.pid() + " still alive after " + timeout);
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

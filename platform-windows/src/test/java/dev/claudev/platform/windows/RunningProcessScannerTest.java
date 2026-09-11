package dev.claudev.platform.windows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RunningProcessScannerTest {

    @Test
    void findsARealProcessRunningFromTheGivenDirectoryAndNoOther(@TempDir Path tempDir) throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        Path systemRoot = Path.of(System.getenv("SystemRoot"));
        Path realPing = systemRoot.resolve("System32").resolve("ping.exe");

        Path managedDir = tempDir.resolve("managed-bin");
        Files.createDirectories(managedDir);
        Path copiedPing = managedDir.resolve("dummy-" + UUID.randomUUID() + ".exe");
        Files.copy(realPing, copiedPing, StandardCopyOption.REPLACE_EXISTING);

        LaunchSpec spec = new LaunchSpec(
                copiedPing, List.of(copiedPing.toString(), "-n", "20", "127.0.0.1"),
                null, Map.of("SystemRoot", systemRoot.toString()),
                "claudev-scanner-test-" + UUID.randomUUID(), true);

        LaunchResult result = WindowsProcessLauncher.launch(spec);
        try {
            List<Integer> found = RunningProcessScanner.pidsWithImageUnder(managedDir);
            assertThat(found).contains(result.pid());

            // A directory the process is NOT running from must not match.
            List<Integer> unrelated = RunningProcessScanner.pidsWithImageUnder(tempDir.resolve("some-other-dir"));
            assertThat(unrelated).doesNotContain(result.pid());
        } finally {
            // Wait for actual exit, then retry-delete the file ourselves — Windows can lag briefly
            // releasing an exited process's executable file lock even after it's gone (the same
            // AV/EDR-adjacent delay class documented for writes in docs/PROCESS_SAFETY.md), long
            // enough to race JUnit's @TempDir cleanup, which runs immediately after this returns.
            ProcessHandle.of(result.pid()).ifPresentOrElse(handle -> {
                result.job().terminate(1);
                result.job().close();
                waitUntilNotAlive(handle, Duration.ofSeconds(10));
            }, () -> {
                result.job().terminate(1);
                result.job().close();
            });
            deleteWithRetry(copiedPing);
        }
    }

    private static void waitUntilNotAlive(ProcessHandle handle, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!handle.isAlive()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void deleteWithRetry(Path file) {
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                Files.deleteIfExists(file);
                return;
            } catch (java.io.IOException e) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}

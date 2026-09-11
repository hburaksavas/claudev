package dev.claudev.platform.windows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
            result.job().terminate(1);
            result.job().close();
        }
    }
}

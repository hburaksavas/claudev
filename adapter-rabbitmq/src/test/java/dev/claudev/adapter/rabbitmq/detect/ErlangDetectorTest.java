package dev.claudev.adapter.rabbitmq.detect;

import dev.claudev.platform.windows.detect.EnvironmentVariableReader;
import dev.claudev.platform.windows.detect.GlobDirectoryScanner;
import dev.claudev.platform.windows.detect.RegistryUninstallScanner;
import dev.claudev.platform.windows.detect.UninstallEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ErlangDetectorTest {

    @TempDir
    Path tempDir;

    private void makeErlangHome(Path home) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.createFile(home.resolve("bin").resolve("erl.exe"));
    }

    @Test
    void findsCandidateFromEnvVarAndParsesVersionFromDirectoryName() throws IOException {
        Path home = tempDir.resolve("erl-27.3.4.17");
        makeErlangHome(home);

        EnvironmentVariableReader env = new EnvironmentVariableReader(name ->
                "ERLANG_HOME".equals(name) ? home.toString() : null);
        RegistryUninstallScanner registry = mock(RegistryUninstallScanner.class);
        when(registry.findByDisplayNameContaining("Erlang")).thenReturn(List.of());
        GlobDirectoryScanner glob = mock(GlobDirectoryScanner.class);

        ErlangDetector detector = new ErlangDetector(env, registry, glob, tempDir.resolve("Program Files"));

        List<ErlangCandidate> candidates = detector.detect();

        assertThat(candidates).containsExactly(new ErlangCandidate(home, "27.3.4.17", DetectionSource.ENV_VAR));
    }

    @Test
    void findsCandidateFromRegistryUsingDisplayVersion() throws IOException {
        Path home = tempDir.resolve("some-erlang-dir");
        makeErlangHome(home);

        EnvironmentVariableReader env = new EnvironmentVariableReader(name -> null);
        RegistryUninstallScanner registry = mock(RegistryUninstallScanner.class);
        when(registry.findByDisplayNameContaining("Erlang"))
                .thenReturn(List.of(new UninstallEntry("Erlang/OTP 27", "27.3.4.17", Optional.of(home))));
        GlobDirectoryScanner glob = mock(GlobDirectoryScanner.class);

        ErlangDetector detector = new ErlangDetector(env, registry, glob, tempDir.resolve("Program Files"));

        List<ErlangCandidate> candidates = detector.detect();

        assertThat(candidates).containsExactly(new ErlangCandidate(home, "27.3.4.17", DetectionSource.REGISTRY));
    }

    @Test
    void findsCandidateFromProgramFilesGlob() throws IOException {
        Path programFiles = tempDir.resolve("Program Files");
        Path home = programFiles.resolve("erl-26.2.5");
        makeErlangHome(home);

        EnvironmentVariableReader env = new EnvironmentVariableReader(name -> null);
        RegistryUninstallScanner registry = mock(RegistryUninstallScanner.class);
        when(registry.findByDisplayNameContaining("Erlang")).thenReturn(List.of());
        GlobDirectoryScanner glob = new GlobDirectoryScanner();

        ErlangDetector detector = new ErlangDetector(env, registry, glob, programFiles);

        List<ErlangCandidate> candidates = detector.detect();

        assertThat(candidates).containsExactly(new ErlangCandidate(home, "26.2.5", DetectionSource.GLOB));
    }

    @Test
    void rejectsCandidatesWithoutARealErlExe() {
        Path home = tempDir.resolve("not-really-erlang");

        EnvironmentVariableReader env = new EnvironmentVariableReader(name ->
                "ERLANG_HOME".equals(name) ? home.toString() : null);
        RegistryUninstallScanner registry = mock(RegistryUninstallScanner.class);
        when(registry.findByDisplayNameContaining("Erlang")).thenReturn(List.of());
        GlobDirectoryScanner glob = mock(GlobDirectoryScanner.class);

        ErlangDetector detector = new ErlangDetector(env, registry, glob, tempDir.resolve("Program Files"));

        assertThat(detector.detect()).isEmpty();
    }
}

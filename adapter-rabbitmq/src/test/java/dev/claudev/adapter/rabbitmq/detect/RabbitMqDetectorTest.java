package dev.claudev.adapter.rabbitmq.detect;

import dev.claudev.platform.windows.detect.GlobDirectoryScanner;
import dev.claudev.platform.windows.detect.PathEnvironmentScanner;
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

class RabbitMqDetectorTest {

    @TempDir
    Path tempDir;

    private Path makeInstall(Path installLocation) throws IOException {
        Path sbin = installLocation.resolve("sbin");
        Files.createDirectories(sbin);
        Files.createFile(sbin.resolve("rabbitmq-server.bat"));
        return sbin;
    }

    @Test
    void findsCandidateFromRegistry() throws IOException {
        Path installLocation = tempDir.resolve("RabbitMQ Server\\rabbitmq_server-4.3.5");
        Path sbin = makeInstall(installLocation);

        RegistryUninstallScanner registry = mock(RegistryUninstallScanner.class);
        when(registry.findByDisplayNameContaining("RabbitMQ"))
                .thenReturn(List.of(new UninstallEntry("RabbitMQ Server", "4.3.5", Optional.of(installLocation))));
        GlobDirectoryScanner glob = mock(GlobDirectoryScanner.class);
        when(glob.matchingSubdirectories(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        PathEnvironmentScanner pathScanner = mock(PathEnvironmentScanner.class);
        when(pathScanner.findOnPath("rabbitmq-server.bat")).thenReturn(List.of());

        RabbitMqDetector detector = new RabbitMqDetector(registry, glob, pathScanner, tempDir.resolve("RabbitMQ Server"));

        assertThat(detector.detect()).containsExactly(new RabbitMqCandidate(sbin, "4.3.5", DetectionSource.REGISTRY));
    }

    @Test
    void findsCandidateFromProgramFilesGlob() throws IOException {
        Path rabbitmqServerDir = tempDir.resolve("RabbitMQ Server");
        Path installDir = rabbitmqServerDir.resolve("rabbitmq_server-4.3.5");
        Path sbin = makeInstall(installDir);

        RegistryUninstallScanner registry = mock(RegistryUninstallScanner.class);
        when(registry.findByDisplayNameContaining("RabbitMQ")).thenReturn(List.of());
        GlobDirectoryScanner glob = new GlobDirectoryScanner();
        PathEnvironmentScanner pathScanner = mock(PathEnvironmentScanner.class);
        when(pathScanner.findOnPath("rabbitmq-server.bat")).thenReturn(List.of());

        RabbitMqDetector detector = new RabbitMqDetector(registry, glob, pathScanner, rabbitmqServerDir);

        assertThat(detector.detect()).containsExactly(new RabbitMqCandidate(sbin, "4.3.5", DetectionSource.GLOB));
    }

    @Test
    void findsCandidateFromPath() throws IOException {
        Path installDir = tempDir.resolve("rabbitmq_server-4.3.5");
        Path sbin = makeInstall(installDir);

        RegistryUninstallScanner registry = mock(RegistryUninstallScanner.class);
        when(registry.findByDisplayNameContaining("RabbitMQ")).thenReturn(List.of());
        GlobDirectoryScanner glob = mock(GlobDirectoryScanner.class);
        when(glob.matchingSubdirectories(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        PathEnvironmentScanner pathScanner = mock(PathEnvironmentScanner.class);
        when(pathScanner.findOnPath("rabbitmq-server.bat")).thenReturn(List.of(sbin.resolve("rabbitmq-server.bat")));

        RabbitMqDetector detector = new RabbitMqDetector(registry, glob, pathScanner, tempDir.resolve("RabbitMQ Server"));

        assertThat(detector.detect()).containsExactly(new RabbitMqCandidate(sbin, "4.3.5", DetectionSource.PATH));
    }
}

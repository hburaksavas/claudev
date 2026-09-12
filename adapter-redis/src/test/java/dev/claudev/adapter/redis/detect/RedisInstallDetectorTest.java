package dev.claudev.adapter.redis.detect;

import dev.claudev.domain.detect.DetectedRedisInstall;
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

class RedisInstallDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void findsCandidateFromPath() throws IOException {
        Path exe = tempDir.resolve("redis-server.exe");
        Files.createFile(exe);

        PathEnvironmentScanner pathScanner = mock(PathEnvironmentScanner.class);
        when(pathScanner.findOnPath("redis-server.exe")).thenReturn(List.of(exe));
        RegistryUninstallScanner registry = mock(RegistryUninstallScanner.class);
        when(registry.findByDisplayNameContaining("Redis")).thenReturn(List.of());
        GlobDirectoryScanner glob = mock(GlobDirectoryScanner.class);
        when(glob.matchingSubdirectories(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        RedisInstallDetector detector = new RedisInstallDetector(
                pathScanner, registry, glob, tempDir.resolve("Program Files"), tempDir.resolve("choco-redis"));

        List<DetectedRedisInstall> found = detector.detect();

        assertThat(found).extracting(DetectedRedisInstall::redisServerExe).containsExactly(exe);
    }

    @Test
    void findsCandidateFromRegistry() throws IOException {
        Path installLocation = tempDir.resolve("Redis");
        Files.createDirectories(installLocation);
        Path exe = installLocation.resolve("redis-server.exe");
        Files.createFile(exe);

        PathEnvironmentScanner pathScanner = mock(PathEnvironmentScanner.class);
        when(pathScanner.findOnPath("redis-server.exe")).thenReturn(List.of());
        RegistryUninstallScanner registry = mock(RegistryUninstallScanner.class);
        when(registry.findByDisplayNameContaining("Redis"))
                .thenReturn(List.of(new UninstallEntry("Redis", "5.0.14.1", Optional.of(installLocation))));
        GlobDirectoryScanner glob = mock(GlobDirectoryScanner.class);
        when(glob.matchingSubdirectories(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        RedisInstallDetector detector = new RedisInstallDetector(
                pathScanner, registry, glob, tempDir.resolve("Program Files"), tempDir.resolve("choco-redis"));

        assertThat(detector.detect()).extracting(DetectedRedisInstall::redisServerExe).containsExactly(exe);
    }

    @Test
    void findsCandidateFromProgramFilesGlob() throws IOException {
        Path programFiles = tempDir.resolve("Program Files");
        Path installDir = programFiles.resolve("Redis");
        Files.createDirectories(installDir);
        Path exe = installDir.resolve("redis-server.exe");
        Files.createFile(exe);

        PathEnvironmentScanner pathScanner = mock(PathEnvironmentScanner.class);
        when(pathScanner.findOnPath("redis-server.exe")).thenReturn(List.of());
        RegistryUninstallScanner registry = mock(RegistryUninstallScanner.class);
        when(registry.findByDisplayNameContaining("Redis")).thenReturn(List.of());
        GlobDirectoryScanner glob = new GlobDirectoryScanner();

        RedisInstallDetector detector = new RedisInstallDetector(
                pathScanner, registry, glob, programFiles, tempDir.resolve("choco-redis"));

        assertThat(detector.detect()).extracting(DetectedRedisInstall::redisServerExe).containsExactly(exe);
    }

    @Test
    void findsCandidateFromChocolateyDirectory() throws IOException {
        Path chocoDir = tempDir.resolve("choco-redis");
        Files.createDirectories(chocoDir);
        Path exe = chocoDir.resolve("redis-server.exe");
        Files.createFile(exe);

        PathEnvironmentScanner pathScanner = mock(PathEnvironmentScanner.class);
        when(pathScanner.findOnPath("redis-server.exe")).thenReturn(List.of());
        RegistryUninstallScanner registry = mock(RegistryUninstallScanner.class);
        when(registry.findByDisplayNameContaining("Redis")).thenReturn(List.of());
        GlobDirectoryScanner glob = mock(GlobDirectoryScanner.class);
        when(glob.matchingSubdirectories(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        RedisInstallDetector detector = new RedisInstallDetector(
                pathScanner, registry, glob, tempDir.resolve("Program Files"), chocoDir);

        assertThat(detector.detect()).extracting(DetectedRedisInstall::redisServerExe).containsExactly(exe);
    }

    @Test
    void returnsEmptyWhenNothingFound() {
        PathEnvironmentScanner pathScanner = mock(PathEnvironmentScanner.class);
        when(pathScanner.findOnPath("redis-server.exe")).thenReturn(List.of());
        RegistryUninstallScanner registry = mock(RegistryUninstallScanner.class);
        when(registry.findByDisplayNameContaining("Redis")).thenReturn(List.of());
        GlobDirectoryScanner glob = mock(GlobDirectoryScanner.class);
        when(glob.matchingSubdirectories(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        RedisInstallDetector detector = new RedisInstallDetector(
                pathScanner, registry, glob, tempDir.resolve("Program Files"), tempDir.resolve("choco-redis"));

        assertThat(detector.detect()).isEmpty();
    }
}

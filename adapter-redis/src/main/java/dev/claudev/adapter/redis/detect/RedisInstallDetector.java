package dev.claudev.adapter.redis.detect;

import dev.claudev.adapter.redis.RedisInstallValidator;
import dev.claudev.domain.detect.DetectedRedisInstall;
import dev.claudev.platform.windows.detect.GlobDirectoryScanner;
import dev.claudev.platform.windows.detect.PathEnvironmentScanner;
import dev.claudev.platform.windows.detect.RegistryUninstallScanner;
import dev.claudev.platform.windows.detect.UninstallEntry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds an already-installed (not necessarily running) {@code redis-server.exe} via PATH, the
 * Windows Uninstall registry, a {@code Redis*} glob under Program Files (tporadowski-style
 * installs), and Chocolatey's fixed redis package directory — never asks the user to type a path
 * unless nothing is found. No pairing/compatibility step is needed (unlike RabbitMQ+Erlang): a
 * single binary is the whole candidate.
 */
public final class RedisInstallDetector {

    private static final String EXE_NAME = "redis-server.exe";
    private static final Path PROGRAM_FILES = Path.of("C:\\Program Files");
    private static final Path CHOCOLATEY_REDIS_DIR =
            Path.of("C:\\ProgramData\\chocolatey\\lib\\redis-64\\tools");

    private final PathEnvironmentScanner pathEnvironmentScanner;
    private final RegistryUninstallScanner registryUninstallScanner;
    private final GlobDirectoryScanner globDirectoryScanner;
    private final Path programFiles;
    private final Path chocolateyRedisDir;

    public RedisInstallDetector() {
        this(PathEnvironmentScanner.ofSystemEnvironment(), new RegistryUninstallScanner(),
                new GlobDirectoryScanner(), PROGRAM_FILES, CHOCOLATEY_REDIS_DIR);
    }

    RedisInstallDetector(PathEnvironmentScanner pathEnvironmentScanner, RegistryUninstallScanner registryUninstallScanner,
                          GlobDirectoryScanner globDirectoryScanner, Path programFiles, Path chocolateyRedisDir) {
        this.pathEnvironmentScanner = pathEnvironmentScanner;
        this.registryUninstallScanner = registryUninstallScanner;
        this.globDirectoryScanner = globDirectoryScanner;
        this.programFiles = programFiles;
        this.chocolateyRedisDir = chocolateyRedisDir;
    }

    public List<DetectedRedisInstall> detect() {
        Map<Path, String> bySource = new LinkedHashMap<>();

        for (Path exeOnPath : pathEnvironmentScanner.findOnPath(EXE_NAME)) {
            bySource.putIfAbsent(exeOnPath, "PATH");
        }

        for (UninstallEntry entry : registryUninstallScanner.findByDisplayNameContaining("Redis")) {
            entry.installLocation().map(loc -> loc.resolve(EXE_NAME))
                    .filter(RedisInstallValidator::looksLikeRedisServerExe)
                    .ifPresent(exe -> bySource.putIfAbsent(exe, "Registry"));
        }

        for (Path installDir : globDirectoryScanner.matchingSubdirectories(programFiles, "Redis*")) {
            Path exe = installDir.resolve(EXE_NAME);
            if (RedisInstallValidator.looksLikeRedisServerExe(exe)) {
                bySource.putIfAbsent(exe, "Program Files");
            }
        }

        Path chocolateyExe = chocolateyRedisDir.resolve(EXE_NAME);
        if (RedisInstallValidator.looksLikeRedisServerExe(chocolateyExe)) {
            bySource.putIfAbsent(chocolateyExe, "Chocolatey");
        }

        List<DetectedRedisInstall> results = new ArrayList<>();
        bySource.forEach((exe, source) -> results.add(new DetectedRedisInstall(exe, exe + " (" + source + ")")));
        return results;
    }
}

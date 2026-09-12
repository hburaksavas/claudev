package dev.claudev.adapter.rabbitmq.detect;

import dev.claudev.adapter.rabbitmq.ErlangInstallValidator;
import dev.claudev.platform.windows.detect.EnvironmentVariableReader;
import dev.claudev.platform.windows.detect.GlobDirectoryScanner;
import dev.claudev.platform.windows.detect.RegistryUninstallScanner;
import dev.claudev.platform.windows.detect.UninstallEntry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds candidate Erlang/OTP installs via {@code ERLANG_HOME}, the Windows Uninstall registry,
 * and an {@code erl-*} glob under Program Files — never asks the user to type a path.
 */
public final class ErlangDetector {

    private static final Path PROGRAM_FILES = Path.of("C:\\Program Files");

    private final EnvironmentVariableReader environmentVariableReader;
    private final RegistryUninstallScanner registryUninstallScanner;
    private final GlobDirectoryScanner globDirectoryScanner;
    private final Path programFiles;

    public ErlangDetector() {
        this(EnvironmentVariableReader.ofSystemEnvironment(), new RegistryUninstallScanner(),
                new GlobDirectoryScanner(), PROGRAM_FILES);
    }

    ErlangDetector(EnvironmentVariableReader environmentVariableReader, RegistryUninstallScanner registryUninstallScanner,
                   GlobDirectoryScanner globDirectoryScanner, Path programFiles) {
        this.environmentVariableReader = environmentVariableReader;
        this.registryUninstallScanner = registryUninstallScanner;
        this.globDirectoryScanner = globDirectoryScanner;
        this.programFiles = programFiles;
    }

    public List<ErlangCandidate> detect() {
        Map<Path, ErlangCandidate> byHome = new LinkedHashMap<>();

        environmentVariableReader.get("ERLANG_HOME")
                .filter(ErlangInstallValidator::looksLikeErlangHome)
                .ifPresent(home -> byHome.putIfAbsent(home, candidateFor(home, DetectionSource.ENV_VAR)));

        for (UninstallEntry entry : registryUninstallScanner.findByDisplayNameContaining("Erlang")) {
            entry.installLocation()
                    .filter(ErlangInstallValidator::looksLikeErlangHome)
                    .ifPresent(home -> byHome.putIfAbsent(home,
                            new ErlangCandidate(home, versionOrFallback(entry.displayVersion(), home), DetectionSource.REGISTRY)));
        }

        for (Path home : globDirectoryScanner.matchingSubdirectories(programFiles, "erl-*")) {
            if (ErlangInstallValidator.looksLikeErlangHome(home)) {
                byHome.putIfAbsent(home, candidateFor(home, DetectionSource.GLOB));
            }
        }

        return new ArrayList<>(byHome.values());
    }

    private ErlangCandidate candidateFor(Path home, DetectionSource source) {
        return new ErlangCandidate(home, VersionExtraction.fromDirectoryName(home).orElse(""), source);
    }

    private String versionOrFallback(String displayVersion, Path home) {
        if (displayVersion != null && !displayVersion.isBlank()) {
            return displayVersion;
        }
        return VersionExtraction.fromDirectoryName(home).orElse("");
    }
}

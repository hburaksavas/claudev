package dev.claudev.adapter.rabbitmq.detect;

import dev.claudev.adapter.rabbitmq.RabbitMqInstallValidator;
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
 * Finds candidate RabbitMQ installs via the Windows Uninstall registry, a
 * {@code rabbitmq_server-*} glob under {@code Program Files\RabbitMQ Server}, and PATH — never
 * asks the user to type a path.
 */
public final class RabbitMqDetector {

    private static final Path DEFAULT_RABBITMQ_SERVER_DIR = Path.of("C:\\Program Files\\RabbitMQ Server");
    private static final String SERVER_EXE = "rabbitmq-server.bat";

    private final RegistryUninstallScanner registryUninstallScanner;
    private final GlobDirectoryScanner globDirectoryScanner;
    private final PathEnvironmentScanner pathEnvironmentScanner;
    private final Path rabbitmqServerDir;

    public RabbitMqDetector() {
        this(new RegistryUninstallScanner(), new GlobDirectoryScanner(),
                PathEnvironmentScanner.ofSystemEnvironment(), DEFAULT_RABBITMQ_SERVER_DIR);
    }

    RabbitMqDetector(RegistryUninstallScanner registryUninstallScanner, GlobDirectoryScanner globDirectoryScanner,
                      PathEnvironmentScanner pathEnvironmentScanner, Path rabbitmqServerDir) {
        this.registryUninstallScanner = registryUninstallScanner;
        this.globDirectoryScanner = globDirectoryScanner;
        this.pathEnvironmentScanner = pathEnvironmentScanner;
        this.rabbitmqServerDir = rabbitmqServerDir;
    }

    public List<RabbitMqCandidate> detect() {
        Map<Path, RabbitMqCandidate> bySbin = new LinkedHashMap<>();

        for (UninstallEntry entry : registryUninstallScanner.findByDisplayNameContaining("RabbitMQ")) {
            entry.installLocation()
                    .map(this::sbinUnder)
                    .filter(RabbitMqInstallValidator::looksLikeRabbitMqSbin)
                    .ifPresent(sbin -> bySbin.putIfAbsent(sbin,
                            new RabbitMqCandidate(sbin, versionOrFallback(entry.displayVersion(), entry.installLocation().get()), DetectionSource.REGISTRY)));
        }

        for (Path installDir : globDirectoryScanner.matchingSubdirectories(rabbitmqServerDir, "rabbitmq_server-*")) {
            Path sbin = sbinUnder(installDir);
            if (RabbitMqInstallValidator.looksLikeRabbitMqSbin(sbin)) {
                bySbin.putIfAbsent(sbin, new RabbitMqCandidate(sbin, VersionExtraction.fromDirectoryName(installDir).orElse(""), DetectionSource.GLOB));
            }
        }

        for (Path exeOnPath : pathEnvironmentScanner.findOnPath(SERVER_EXE)) {
            Path sbin = exeOnPath.getParent();
            if (sbin != null && RabbitMqInstallValidator.looksLikeRabbitMqSbin(sbin)) {
                bySbin.putIfAbsent(sbin, new RabbitMqCandidate(sbin, VersionExtraction.fromDirectoryName(installDirFor(sbin)).orElse(""), DetectionSource.PATH));
            }
        }

        return new ArrayList<>(bySbin.values());
    }

    private Path sbinUnder(Path installLocation) {
        return installLocation.resolve("sbin");
    }

    private Path installDirFor(Path sbin) {
        Path parent = sbin.getParent();
        return parent != null ? parent : sbin;
    }

    private String versionOrFallback(String displayVersion, Path installLocation) {
        if (displayVersion != null && !displayVersion.isBlank()) {
            return displayVersion;
        }
        return VersionExtraction.fromDirectoryName(installLocation).orElse("");
    }
}

package dev.claudev.app;

import dev.claudev.adapter.rabbitmq.RabbitMqRuntimeProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Lazily builds the {@link RabbitMqRuntimeProvider} on first use, not at application startup —
 * provisioning the pinned pair is a real ~250MB network operation the first time it runs on a
 * machine, and doing that unconditionally on every {@code ClaudevApplication} launch (including
 * every test run that boots the Spring context) would be a real behavior/cost regression, not a
 * neutral wiring choice. A second call reuses the already-built provider at no extra cost.
 *
 * <p>WP10b: if both {@code claudev.rabbitmq.imported-erlang-home} and {@code
 * claudev.rabbitmq.imported-rabbitmq-sbin} are set, those paths are used directly (no download, no
 * checksum — {@code RuntimeSource.Imported}'s trust boundary, see docs/RABBITMQ_RUNTIME.md) instead
 * of provisioning the app-managed pinned pair. Misconfigured import paths fail loudly the first
 * time a RabbitMQ instance is started, rather than silently falling back to downloading — a typo in
 * a path a user deliberately set should never be masked by quietly doing something else.
 */
@Component
class RabbitMqProviderHolder {

    private final Path managedDir;
    private final String importedErlangHome;
    private final String importedRabbitmqSbin;
    private volatile RabbitMqRuntimeProvider provider;

    RabbitMqProviderHolder(
            @Value("${claudev.rabbitmq.managed-dir:${user.home}/.claudev/rabbitmq}") String managedDir,
            @Value("${claudev.rabbitmq.imported-erlang-home:}") String importedErlangHome,
            @Value("${claudev.rabbitmq.imported-rabbitmq-sbin:}") String importedRabbitmqSbin) {
        this.managedDir = Path.of(managedDir);
        this.importedErlangHome = importedErlangHome;
        this.importedRabbitmqSbin = importedRabbitmqSbin;
    }

    synchronized RabbitMqRuntimeProvider get() throws IOException, InterruptedException {
        if (provider == null) {
            provider = isImportedConfigured()
                    ? RabbitMqRuntimeProvider.fromImported(Path.of(importedErlangHome), Path.of(importedRabbitmqSbin))
                    : RabbitMqRuntimeProvider.provision(managedDir);
        }
        return provider;
    }

    boolean isImportedConfigured() {
        return !importedErlangHome.isBlank() && !importedRabbitmqSbin.isBlank();
    }

    /** For {@code RuntimeDefinition.source} bookkeeping only — not used to decide behavior (see {@link #get}). */
    Path describedSourcePath() {
        return isImportedConfigured() ? Path.of(importedRabbitmqSbin) : managedDir;
    }
}

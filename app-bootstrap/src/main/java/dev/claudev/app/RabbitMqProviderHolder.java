package dev.claudev.app;

import dev.claudev.adapter.rabbitmq.RabbitMqRuntimeProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Lazily provisions (downloads/verifies/extracts if not already present — see
 * docs/RABBITMQ_RUNTIME.md) the pinned RabbitMQ+Erlang pair on first use, not at application
 * startup. Provisioning is a real ~250MB network operation the first time it runs on a machine;
 * doing that unconditionally on every {@code ClaudevApplication} launch — including every test run
 * that boots the Spring context — would be a real behavior/cost regression, not a neutral wiring
 * choice. A second instance creation reuses the already-provisioned binaries at no network cost.
 */
@Component
class RabbitMqProviderHolder {

    private final Path managedDir;
    private volatile RabbitMqRuntimeProvider provider;

    RabbitMqProviderHolder(@Value("${claudev.rabbitmq.managed-dir:${user.home}/.claudev/rabbitmq}") String managedDir) {
        this.managedDir = Path.of(managedDir);
    }

    synchronized RabbitMqRuntimeProvider get() throws IOException, InterruptedException {
        if (provider == null) {
            provider = RabbitMqRuntimeProvider.provision(managedDir);
        }
        return provider;
    }
}

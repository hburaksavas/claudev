package dev.claudev.adapter.rabbitmq;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared "does this look like a real RabbitMQ sbin dir" check, used by both manual import
 * ({@code RabbitMqRuntimeProvider.fromImported}) and the {@code detect} subpackage's auto-scan —
 * public so it's visible outside this package, since Java package-private doesn't extend to subpackages.
 */
public final class RabbitMqInstallValidator {

    private RabbitMqInstallValidator() {
    }

    public static Path serverBatIn(Path rabbitmqSbin) throws IOException {
        Path serverBat = rabbitmqSbin.resolve("rabbitmq-server.bat");
        if (!Files.isRegularFile(serverBat)) {
            throw new IOException("rabbitmq-server.bat not found at " + serverBat + " — rabbitmqSbin does not look like a valid RabbitMQ sbin directory");
        }
        return serverBat;
    }

    public static boolean looksLikeRabbitMqSbin(Path rabbitmqSbin) {
        return Files.isRegularFile(rabbitmqSbin.resolve("rabbitmq-server.bat"));
    }
}

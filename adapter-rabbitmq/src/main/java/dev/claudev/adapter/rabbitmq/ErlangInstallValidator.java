package dev.claudev.adapter.rabbitmq;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared "does this look like a real Erlang/OTP install" check, used by both manual import
 * ({@code RabbitMqRuntimeProvider.fromImported}) and the {@code detect} subpackage's auto-scan —
 * public so it's visible outside this package, since Java package-private doesn't extend to subpackages.
 */
public final class ErlangInstallValidator {

    private ErlangInstallValidator() {
    }

    public static Path erlExeIn(Path erlangHome) throws IOException {
        Path erl = erlangHome.resolve("bin").resolve("erl.exe");
        if (!Files.isRegularFile(erl)) {
            throw new IOException("erl.exe not found at " + erl + " — erlangHome does not look like a valid Erlang/OTP install");
        }
        return erl;
    }

    public static boolean looksLikeErlangHome(Path erlangHome) {
        return Files.isRegularFile(erlangHome.resolve("bin").resolve("erl.exe"));
    }
}

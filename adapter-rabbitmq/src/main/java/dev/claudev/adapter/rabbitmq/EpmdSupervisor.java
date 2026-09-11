package dev.claudev.adapter.rabbitmq;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * EPMD (Erlang Port Mapper Daemon) is a machine-wide, ref-counted dependency shared by every
 * RabbitMQ node — it must never be born inside, or killed with, a single instance's Job Object
 * (docs/RABBITMQ_RUNTIME.md). Rather than let Erlang's own runtime lazily spawn epmd as a child of
 * the first node we launch (which, being that node's child, would inherit that node's Job Object
 * and die when we terminate it — exactly the failure this class exists to prevent), this proactively
 * starts epmd first, as a plain, unsupervised, non-job process, so by the time a node boots it finds
 * epmd already listening and never spawns its own.
 */
final class EpmdSupervisor {

    private static final int EPMD_PORT = 4369;

    private EpmdSupervisor() {
    }

    static void ensureRunning(Path erlangHome) throws IOException {
        if (isListening()) {
            return;
        }

        Path epmdExe = findEpmdExe(erlangHome);
        ProcessBuilder builder = new ProcessBuilder(epmdExe.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.start();

        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            if (isListening()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for epmd to start", e);
            }
        }
        throw new IOException("epmd did not start listening on port " + EPMD_PORT + " within 10s");
    }

    private static boolean isListening() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", EPMD_PORT), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Path findEpmdExe(Path erlangHome) throws IOException {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(erlangHome, "erts-*")) {
            for (Path child : children) {
                Path candidate = child.resolve("bin").resolve("epmd.exe");
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        throw new IOException("epmd.exe not found under any erts-* directory in " + erlangHome);
    }
}

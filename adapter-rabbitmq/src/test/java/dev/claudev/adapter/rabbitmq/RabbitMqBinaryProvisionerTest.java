package dev.claudev.adapter.rabbitmq;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real HTTP (a local loopback server, not a mock of the download call) and a real zip on disk — the
 * ~250MB real pinned RabbitMQ/Erlang download itself was exercised manually during the WP6 spike
 * (see docs/RABBITMQ_RUNTIME.md); repeating a multi-minute download on every test run isn't worth
 * the cost when the download/verify/extract *mechanics* can be proven against known bytes instead.
 */
class RabbitMqBinaryProvisionerTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void downloadsAndVerifiesMatchingChecksum(@TempDir Path tempDir) throws Exception {
        byte[] content = "pretend-zip-bytes".getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(content);
        String url = serve(content);

        RabbitMqBinaryProvisioner provisioner = new RabbitMqBinaryProvisioner();
        Path downloaded = provisioner.downloadAndVerify(url, sha256, tempDir, "file.bin");

        assertThat(Files.readAllBytes(downloaded)).isEqualTo(content);
    }

    @Test
    void rejectsAndDeletesOnChecksumMismatch(@TempDir Path tempDir) throws Exception {
        byte[] content = "pretend-zip-bytes".getBytes(StandardCharsets.UTF_8);
        String wrongSha256 = "0".repeat(64);
        String url = serve(content);

        RabbitMqBinaryProvisioner provisioner = new RabbitMqBinaryProvisioner();
        assertThatThrownBy(() -> provisioner.downloadAndVerify(url, wrongSha256, tempDir, "file.bin"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Checksum mismatch");

        assertThat(tempDir.resolve("file.bin")).doesNotExist();
    }

    @Test
    void extractsAValidZip(@TempDir Path tempDir) throws Exception {
        Path zip = tempDir.resolve("good.zip");
        writeZip(zip, entry -> {
            entry.accept("bin/tool.exe", "fake-exe-bytes");
            entry.accept("lib/readme.txt", "hello");
        });

        Path target = tempDir.resolve("extracted");
        RabbitMqBinaryProvisioner.extract(zip, target);

        assertThat(target.resolve("bin/tool.exe")).exists();
        assertThat(Files.readString(target.resolve("lib/readme.txt"))).isEqualTo("hello");
    }

    @Test
    void rejectsZipSlipEntries(@TempDir Path tempDir) throws Exception {
        Path zip = tempDir.resolve("evil.zip");
        writeZip(zip, entry -> entry.accept("../../evil.txt", "gotcha"));

        Path target = tempDir.resolve("safe-target");
        Files.createDirectories(target);

        assertThatThrownBy(() -> RabbitMqBinaryProvisioner.extract(zip, target))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("escapes target directory");

        assertThat(tempDir.resolve("evil.txt")).doesNotExist();
    }

    private String serve(byte[] content) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(content);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
    }

    private static String sha256Hex(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    @FunctionalInterface
    private interface ZipEntryWriter {
        void accept(String name, String content);
    }

    private static void writeZip(Path zip, java.util.function.Consumer<ZipEntryWriter> entries) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            entries.accept((name, content) -> {
                try {
                    zos.putNextEntry(new ZipEntry(name));
                    zos.write(content.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}

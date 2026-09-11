package dev.claudev.adapter.rabbitmq;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Ensures the {@link RabbitMqPinnedPair} is present on disk under a managed directory, downloading
 * and checksum-verifying it first if not (docs/RABBITMQ_RUNTIME.md's "Sourcing" section). Idempotent:
 * a second call against an already-provisioned directory does no network I/O.
 *
 * <p>Zip extraction rejects any entry whose normalized path would land outside the target directory
 * (a zip-slip guard) — the archives come from a checksum-pinned URL, but defense in depth here is
 * cheap and this is exactly the kind of check that's easy to skip by accident.
 */
final class RabbitMqBinaryProvisioner {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    RabbitMqInstallation ensureProvisioned(Path managedDir) throws IOException, InterruptedException {
        Path erlangHome = managedDir.resolve("erlang-" + RabbitMqPinnedPair.ERLANG_VERSION);
        Path rabbitmqRoot = managedDir.resolve("rabbitmq-" + RabbitMqPinnedPair.RABBITMQ_VERSION);

        if (!Files.isRegularFile(erlangHome.resolve("bin").resolve("erl.exe"))) {
            Files.createDirectories(managedDir);
            Path zip = downloadAndVerify(RabbitMqPinnedPair.ERLANG_ZIP_URL, RabbitMqPinnedPair.ERLANG_ZIP_SHA256, managedDir, "erlang.zip");
            extract(zip, erlangHome);
            Files.deleteIfExists(zip);
        }

        Path rabbitmqSbin = findSbinDirectory(rabbitmqRoot);
        if (rabbitmqSbin == null) {
            Files.createDirectories(managedDir);
            Path zip = downloadAndVerify(RabbitMqPinnedPair.RABBITMQ_ZIP_URL, RabbitMqPinnedPair.RABBITMQ_ZIP_SHA256, managedDir, "rabbitmq.zip");
            extract(zip, rabbitmqRoot);
            Files.deleteIfExists(zip);
            rabbitmqSbin = findSbinDirectory(rabbitmqRoot);
        }
        if (rabbitmqSbin == null || !Files.isRegularFile(rabbitmqSbin.resolve("rabbitmq-server.bat"))) {
            throw new IOException("rabbitmq-server.bat not found under extracted " + rabbitmqRoot);
        }

        return new RabbitMqInstallation(erlangHome, rabbitmqSbin);
    }

    /** The zip's top-level folder is named after the release (e.g. rabbitmq_server-4.3.5); find it rather than hardcode it twice. */
    private static Path findSbinDirectory(Path rabbitmqRoot) throws IOException {
        if (!Files.isDirectory(rabbitmqRoot)) {
            return null;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(rabbitmqRoot)) {
            for (Path child : children) {
                Path sbin = child.resolve("sbin");
                if (Files.isDirectory(sbin)) {
                    return sbin;
                }
            }
        }
        return null;
    }

    Path downloadAndVerify(String url, String expectedSha256, Path destDir, String fileName) throws IOException, InterruptedException {
        Path dest = destDir.resolve(fileName);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build();
        HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(
                dest, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING));
        if (response.statusCode() / 100 != 2) {
            Files.deleteIfExists(dest);
            throw new IOException("Download failed: HTTP " + response.statusCode() + " for " + url);
        }

        String actual = sha256Hex(dest);
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            Files.deleteIfExists(dest);
            throw new IOException("Checksum mismatch for " + url + ": expected " + expectedSha256 + " but got " + actual);
        }
        return dest;
    }

    private static String sha256Hex(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void extract(Path zip, Path targetDir) throws IOException {
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        Files.createDirectories(normalizedTarget);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = normalizedTarget.resolve(entry.getName()).normalize();
                if (!entryPath.startsWith(normalizedTarget)) {
                    throw new IOException("Zip entry escapes target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    if (entryPath.getParent() != null) {
                        Files.createDirectories(entryPath.getParent());
                    }
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }
}

package dev.claudev.adapter.redis.detect;

import dev.claudev.domain.detect.DetectedRedisEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real (not mocked): binds a plain TCP listener on 6379/6380/6381 as needed, since a real Redis
 * binary isn't required to exercise "nothing here" vs. "something here but it's not Redis" vs.
 * genuinely unreachable — only the "found a real PONG" case needs a real server, covered instead by
 * the manual verification in the RabbitMQ/Redis detection PR description.
 */
class RedisEndpointDetectorTest {

    private ExecutorService acceptorPool;

    @BeforeEach
    void setUp() {
        acceptorPool = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        acceptorPool.shutdownNow();
    }

    @Test
    void detectsNothingWhenNoPortIsListening() {
        Assumptions.assumeTrue(!isOpen(6379) && !isOpen(6380) && !isOpen(6381),
                "a real service is already listening on a probed port on this machine — skipping");

        List<DetectedRedisEndpoint> found = new RedisEndpointDetector().detect();

        assertThat(found).isEmpty();
    }

    @Test
    void treatsANonRedisListenerOnTheDefaultPortAsUnreachable() throws IOException {
        Assumptions.assumeTrue(!isOpen(6379), "port 6379 is already in use on this machine — skipping");

        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress("127.0.0.1", 6379));
            acceptorPool.submit(() -> acceptAndCloseForever(server));

            List<DetectedRedisEndpoint> found = new RedisEndpointDetector().detect();

            assertThat(found).noneMatch(e -> e.port() == 6379);
        }
    }

    private void acceptAndCloseForever(ServerSocket server) {
        try {
            while (!server.isClosed()) {
                try (Socket accepted = server.accept()) {
                    // A real TCP peer that never speaks RESP — Lettuce's connect handshake should fail cleanly.
                    OutputStream ignored = accepted.getOutputStream();
                }
            }
        } catch (IOException ignored) {
            // Expected once the test closes the server socket.
        }
    }

    private static boolean isOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

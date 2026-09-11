package dev.claudev.adapter.fepipeline;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real local HTTP server, real polling — {@code java.net.http}/{@code com.sun.net.httpserver} need no extra dependency. */
class HealthCheckExecutorTest {

    private final HealthCheckExecutor executor = new HealthCheckExecutor();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void succeedsAgainstARealServerRespondingImmediately() throws Exception {
        server = startServer(exchange -> respond(exchange, 200));

        assertThatCode(() -> executor.execute(Map.of(
                "url", "http://127.0.0.1:" + server.getAddress().getPort() + "/health",
                "timeoutSeconds", 5)))
                .doesNotThrowAnyException();
    }

    @Test
    void succeedsOnceTheServerStartsRespondingHealthyAfterInitialFailures() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        server = startServer(exchange -> respond(exchange, callCount.incrementAndGet() < 3 ? 503 : 200));

        assertThatCode(() -> executor.execute(Map.of(
                "url", "http://127.0.0.1:" + server.getAddress().getPort() + "/health",
                "timeoutSeconds", 10)))
                .doesNotThrowAnyException();
        assertThat(callCount.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void timesOutIfTheServerNeverBecomesHealthy() throws Exception {
        server = startServer(exchange -> respond(exchange, 500));

        assertThatThrownBy(() -> executor.execute(Map.of(
                "url", "http://127.0.0.1:" + server.getAddress().getPort() + "/health",
                "timeoutSeconds", 2)))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void timesOutIfNothingIsListeningAtAll() {
        assertThatThrownBy(() -> executor.execute(Map.of(
                "url", "http://127.0.0.1:1", // nothing listens on port 1
                "timeoutSeconds", 2)))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("timed out");
    }

    private static HttpServer startServer(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/health", handler);
        s.start();
        return s;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status) throws java.io.IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}

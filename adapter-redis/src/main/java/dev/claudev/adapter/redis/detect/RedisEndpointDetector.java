package dev.claudev.adapter.redis.detect;

import dev.claudev.domain.detect.DetectedRedisEndpoint;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Finds already-running Redis endpoints via a real TCP-connect + {@code PING} probe on the
 * default host and a handful of common ports — never launches or manages anything
 * (docs/REDIS_SCOPE.md: "no managed local Redis"). Covers native, WSL2 (whose default
 * {@code localhostForwarding} already surfaces its ports on {@code 127.0.0.1}), and
 * port-published Docker containers alike, since all three just look like "something is
 * listening on localhost" from here.
 */
public final class RedisEndpointDetector {

    private static final Logger LOG = Logger.getLogger(RedisEndpointDetector.class.getName());
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final List<Integer> COMMON_PORTS = List.of(6379, 6380, 6381);
    private static final Duration PROBE_TIMEOUT = Duration.ofMillis(300);

    public List<DetectedRedisEndpoint> detect() {
        List<DetectedRedisEndpoint> found = new ArrayList<>();
        for (int port : COMMON_PORTS) {
            if (isReachable(DEFAULT_HOST, port)) {
                found.add(new DetectedRedisEndpoint(DEFAULT_HOST, port, DEFAULT_HOST + ":" + port));
            }
        }
        return found;
    }

    private boolean isReachable(String host, int port) {
        RedisURI uri = RedisURI.Builder.redis(host, port).withTimeout(PROBE_TIMEOUT).build();
        RedisClient client = RedisClient.create(uri);
        try {
            try (StatefulRedisConnection<String, String> connection = client.connect()) {
                return "PONG".equalsIgnoreCase(connection.sync().ping());
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "No reachable Redis at " + host + ":" + port, e);
            return false;
        } finally {
            client.shutdown();
        }
    }
}

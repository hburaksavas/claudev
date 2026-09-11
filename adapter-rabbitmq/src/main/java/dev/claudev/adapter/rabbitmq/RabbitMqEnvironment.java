package dev.claudev.adapter.rabbitmq;

import java.util.Map;

/**
 * The minimal, explicit environment every spawn of {@code rabbitmq-server.bat}/{@code
 * rabbitmqctl.bat} needs on top of its own RabbitMQ-specific variables — per docs/SECURITY.md, a
 * spawn never inherits the caller's ambient environment wholesale, but {@code rabbitmq-env.bat}
 * shells out to ordinary Windows tools (hostname/findstr-style lookups) that need {@code PATH}, and
 * Erlang itself wants a writable per-user profile location. A real spawn attempt without these
 * failed with "No PATH variable (!)" during this session's WP6 build — found by the adapter's own
 * tests, not assumed upfront.
 */
final class RabbitMqEnvironment {

    private RabbitMqEnvironment() {
    }

    static void addBaseVars(Map<String, String> env) {
        putIfPresent(env, "SystemRoot");
        putIfPresent(env, "Path");
        putIfPresent(env, "PATHEXT");
        putIfPresent(env, "USERPROFILE");
        putIfPresent(env, "APPDATA");
        putIfPresent(env, "LOCALAPPDATA");
        putIfPresent(env, "TEMP");
        putIfPresent(env, "TMP");
        putIfPresent(env, "ComSpec");
    }

    private static void putIfPresent(Map<String, String> env, String name) {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) {
            env.put(name, value);
        }
    }
}

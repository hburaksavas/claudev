package dev.claudev.platform.windows;

import com.sun.jna.Memory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Encodes a UTF-16LE, double-null-terminated environment block for {@code CreateProcessW}'s
 * {@code lpEnvironment} parameter (used with {@code CREATE_UNICODE_ENVIRONMENT}).
 *
 * <p>Exists specifically so a spawn never inherits the caller's ambient environment wholesale —
 * per docs/SECURITY.md, the app process may be holding decrypted secrets in memory for unrelated
 * operations, and an inherited environment is an easy accidental exfiltration path. Every managed
 * spawn (RabbitMQ nodename/cookie/ports per docs/RABBITMQ_RUNTIME.md, {@code ExecStep} per
 * docs/SECURITY.md) must build its environment explicitly through this class rather than relying
 * on whatever the JVM's own process happened to inherit.
 */
public final class WindowsEnvironmentBlock {

    private WindowsEnvironmentBlock() {
    }

    /**
     * Builds the native block. Entries are sorted by key (case-insensitively), matching the
     * ordering Windows itself produces via {@code GetEnvironmentStringsW} — documented by
     * Microsoft as a requirement for a correctly formed block.
     *
     * <p><b>A real, reproduced bug fixed here, not a hypothetical</b>: for one or more entries, each
     * entry already ends with its own {@code '\0'}, so appending one final {@code '\0'} after the
     * loop correctly leaves two consecutive null characters at the end (the documented "double-null
     * termination"). But for zero entries, that same single trailing append leaves only ONE null
     * character total — and {@code CreateProcessW} with {@code CREATE_UNICODE_ENVIRONMENT} rejects
     * that with {@code ERROR_INVALID_PARAMETER} (confirmed via a real spawn of {@code ping.exe} with
     * a literally-empty environment map, not assumed). No real caller in this codebase ever passed a
     * truly empty environment before {@code RedisRuntimeProvider} did, which is why this went
     * unnoticed. The zero-entries case now explicitly emits two null characters to match.
     *
     * <p>The returned {@link Memory} is native, GC-managed, and freed automatically when
     * unreachable — callers do not need to release it explicitly, but must keep a reference to it
     * alive for the duration of the {@code CreateProcessW} call.
     */
    public static Memory encode(Map<String, String> environment) {
        Map<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        sorted.putAll(environment);

        StringBuilder block = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            validateNoEmbeddedNul(entry.getKey());
            validateNoEmbeddedNul(entry.getValue());
            block.append(entry.getKey()).append('=').append(entry.getValue()).append('\0');
        }
        block.append('\0');
        if (sorted.isEmpty()) {
            block.append('\0');
        }

        byte[] utf16le = block.toString().getBytes(StandardCharsets.UTF_16LE);
        Memory memory = new Memory(utf16le.length);
        memory.write(0, utf16le, 0, utf16le.length);
        return memory;
    }

    private static void validateNoEmbeddedNul(String value) {
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Environment entries must not contain NUL characters");
        }
    }
}

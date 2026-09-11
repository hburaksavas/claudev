package dev.claudev.platform.windows;

import com.sun.jna.Memory;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WindowsEnvironmentBlockTest {

    @Test
    void encodesEntriesSortedCaseInsensitivelyAsDoubleNullTerminatedUtf16() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("ZEBRA", "z");
        env.put("apple", "a");
        env.put("Mango", "m");

        List<String> entries = decode(WindowsEnvironmentBlock.encode(env));

        assertThat(entries).containsExactly("apple=a", "Mango=m", "ZEBRA=z");
    }

    @Test
    void emptyMapProducesAMinimalValidBlock() {
        List<String> entries = decode(WindowsEnvironmentBlock.encode(Map.of()));

        assertThat(entries).isEmpty();
    }

    @Test
    void rejectsEmbeddedNulCharacters() {
        Map<String, String> env = Map.of("KEY", "va\0lue");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> WindowsEnvironmentBlock.encode(env))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Mirrors how a Win32 caller reads the block: split on U+0000, drop the trailing empty terminator. */
    private static List<String> decode(Memory block) {
        int length = (int) block.size();
        byte[] bytes = block.getByteArray(0, length);
        String raw = new String(bytes, StandardCharsets.UTF_16LE);

        String[] parts = raw.split("\0", -1);
        return java.util.Arrays.stream(parts)
                .filter(part -> !part.isEmpty())
                .toList();
    }
}

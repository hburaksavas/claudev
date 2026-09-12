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

    /**
     * A real, reproduced bug (not a hypothetical): {@code CreateProcessW} with {@code
     * CREATE_UNICODE_ENVIRONMENT} rejects a block with only a single null-terminator character for
     * the zero-entries case — confirmed via a real spawn of {@code ping.exe} with a literally-empty
     * environment map, which failed with {@code ERROR_INVALID_PARAMETER} before this was fixed. The
     * decoded-entries view above can't catch this — an empty block and a too-short block both decode
     * to an empty list — so this asserts the raw byte length directly.
     */
    @Test
    void emptyMapIsDoubleNullTerminatedAtTheByteLevel() {
        Memory block = WindowsEnvironmentBlock.encode(Map.of());

        assertThat(block.size()).as("two UTF-16LE NUL characters = 4 bytes").isEqualTo(4);
        assertThat(block.getByteArray(0, 4)).containsExactly(0, 0, 0, 0);
    }

    /** The non-empty case already ends in two consecutive nulls (last entry's + the block's own) — assert that stays true at the byte level too. */
    @Test
    void nonEmptyMapEndsInTwoConsecutiveNullCharacters() {
        Memory block = WindowsEnvironmentBlock.encode(Map.of("KEY", "value"));

        int size = (int) block.size();
        byte[] lastFourBytes = block.getByteArray(size - 4, 4);
        assertThat(lastFourBytes).containsExactly(0, 0, 0, 0);
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

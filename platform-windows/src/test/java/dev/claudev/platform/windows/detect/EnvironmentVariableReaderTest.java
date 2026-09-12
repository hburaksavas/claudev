package dev.claudev.platform.windows.detect;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentVariableReaderTest {

    @Test
    void returnsPathWhenSet() {
        EnvironmentVariableReader reader = new EnvironmentVariableReader(
                Map.of("ERLANG_HOME", "C:\\Erlang")::get);

        assertThat(reader.get("ERLANG_HOME")).contains(Path.of("C:\\Erlang"));
    }

    @Test
    void returnsEmptyWhenUnsetOrBlank() {
        EnvironmentVariableReader reader = new EnvironmentVariableReader(name -> switch (name) {
            case "BLANK" -> "  ";
            default -> null;
        });

        assertThat(reader.get("MISSING")).isEqualTo(Optional.empty());
        assertThat(reader.get("BLANK")).isEqualTo(Optional.empty());
    }
}

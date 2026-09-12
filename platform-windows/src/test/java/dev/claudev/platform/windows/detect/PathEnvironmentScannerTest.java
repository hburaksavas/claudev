package dev.claudev.platform.windows.detect;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PathEnvironmentScannerTest {

    @Test
    void findsExeOnlyInDirectoriesWhereItExists() {
        String pathEnv = String.join(java.io.File.pathSeparator,
                "C:\\Windows\\System32", "C:\\Erlang\\bin", "C:\\Empty");
        Set<Path> existing = Set.of(Path.of("C:\\Erlang\\bin\\erl.exe"));

        PathEnvironmentScanner scanner = new PathEnvironmentScanner(pathEnv, existing::contains);

        List<Path> matches = scanner.findOnPath("erl.exe");

        assertThat(matches).containsExactly(Path.of("C:\\Erlang\\bin\\erl.exe"));
    }

    @Test
    void returnsEmptyWhenNothingMatches() {
        PathEnvironmentScanner scanner = new PathEnvironmentScanner("C:\\Windows", p -> false);

        assertThat(scanner.findOnPath("redis-server.exe")).isEmpty();
    }

    @Test
    void toleratesNullOrBlankPathValue() {
        PathEnvironmentScanner scanner = new PathEnvironmentScanner(null, p -> true);

        assertThat(scanner.findOnPath("erl.exe")).isEmpty();
    }
}

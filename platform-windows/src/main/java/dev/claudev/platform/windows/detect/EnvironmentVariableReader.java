package dev.claudev.platform.windows.detect;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/** Trivial, fake-injectable wrapper around {@link System#getenv(String)} for detection code. */
public final class EnvironmentVariableReader {

    private final Function<String, String> lookup;

    public EnvironmentVariableReader(Function<String, String> lookup) {
        this.lookup = lookup;
    }

    public static EnvironmentVariableReader ofSystemEnvironment() {
        return new EnvironmentVariableReader(System::getenv);
    }

    public Optional<Path> get(String name) {
        String value = lookup.apply(name);
        return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(Path.of(value));
    }
}

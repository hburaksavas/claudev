package dev.claudev.platform.windows.detect;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Scans the {@code PATH} environment variable for directories containing a given executable. */
public final class PathEnvironmentScanner {

    private final String pathEnvValue;
    private final Predicate<Path> exists;

    public PathEnvironmentScanner(String pathEnvValue, Predicate<Path> exists) {
        this.pathEnvValue = pathEnvValue == null ? "" : pathEnvValue;
        this.exists = exists;
    }

    public static PathEnvironmentScanner ofSystemEnvironment() {
        return new PathEnvironmentScanner(System.getenv("PATH"), java.nio.file.Files::isRegularFile);
    }

    /** Returns the full path of each {@code PATH} entry that contains {@code exeName}. */
    public List<Path> findOnPath(String exeName) {
        List<Path> matches = new ArrayList<>();
        for (String entry : pathEnvValue.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry.trim()).resolve(exeName);
            if (exists.test(candidate)) {
                matches.add(candidate);
            }
        }
        return matches;
    }
}

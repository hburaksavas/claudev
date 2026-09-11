package dev.claudev.adapter.fepipeline;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Resolves a system/imported executable to a validated, canonicalized absolute path — never left
 * to implicit shell PATH search at spawn time (docs/SECURITY.md's centralized path-validation
 * rule). An explicit environment-variable override takes precedence over searching {@code PATH},
 * for both testability and the "imported" install case docs/FE_PIPELINE_STEPS.md describes.
 */
final class ExecutableLocator {

    private ExecutableLocator() {
    }

    static Path locate(String exeName, String envOverrideVar) throws StepExecutionException {
        String override = System.getenv(envOverrideVar);
        if (override != null && !override.isBlank()) {
            Path candidate = Path.of(override);
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
            throw new StepExecutionException(envOverrideVar + " does not point to a real file: " + override);
        }

        String path = System.getenv("Path");
        if (path == null) {
            path = System.getenv("PATH");
        }
        if (path == null) {
            throw new StepExecutionException("No PATH environment variable to search for " + exeName);
        }

        for (String dir : path.split(Pattern.quote(File.pathSeparator))) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, exeName);
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new StepExecutionException(exeName + " not found on PATH (set " + envOverrideVar + " to override)");
    }
}

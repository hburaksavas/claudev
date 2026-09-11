package dev.claudev.platform.windows;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Everything {@link WindowsProcessLauncher} needs to spawn one supervised process.
 *
 * <p>{@code environment} is the *entire* environment the child receives — there is no merge with
 * the caller's own process environment (see {@link WindowsEnvironmentBlock}). Callers that want
 * the child to see standard variables (e.g. {@code SystemRoot}) must include them explicitly.
 */
public record LaunchSpec(
        Path executable,
        List<String> argv,
        Path workingDirectory,
        Map<String, String> environment,
        String jobName,
        boolean suppressWindow
) {
    public LaunchSpec {
        argv = List.copyOf(argv);
        environment = Map.copyOf(environment);
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("argv must contain at least the program name");
        }
    }
}

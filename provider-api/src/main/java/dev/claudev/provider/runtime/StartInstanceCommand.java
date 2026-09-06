package dev.claudev.provider.runtime;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public record StartInstanceCommand(
        String instanceId,
        String runtimeDefinitionId,
        Path dataDir,
        Path logDir,
        Set<Integer> requestedPorts,
        Map<String, String> environment
) {
    public StartInstanceCommand {
        environment = Map.copyOf(environment);
        requestedPorts = Set.copyOf(requestedPorts);
    }
}

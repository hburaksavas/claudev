package dev.claudev.domain;

import java.nio.file.Path;
import java.util.Optional;

public record Instance(
        InstanceId id,
        WorkspaceId workspaceId,
        RuntimeDefinitionId runtimeDefinitionId,
        String name,
        PortSet ports,
        Path dataDir,
        Path logDir,
        DesiredState desiredState,
        Optional<LaunchRecord> launchRecord,
        InstanceState observedState
) {}

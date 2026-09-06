package dev.claudev.domain;

import java.util.List;
import java.util.Map;

/** {@code dependsOn.get(nodeId)} lists the node ids that must complete before {@code nodeId} runs. */
public record OperationDag(List<String> nodeIds, Map<String, List<String>> dependsOn) {
    public OperationDag {
        nodeIds = List.copyOf(nodeIds);
        dependsOn = Map.copyOf(dependsOn);
    }
}

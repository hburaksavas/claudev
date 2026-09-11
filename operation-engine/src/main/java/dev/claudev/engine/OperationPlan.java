package dev.claudev.engine;

import java.util.List;

/** What a caller submits to {@link OperationEngine#submit}. */
public record OperationPlan(String kind, List<String> targets, List<OperationNode> nodes) {
    public OperationPlan {
        targets = List.copyOf(targets);
        nodes = List.copyOf(nodes);
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("OperationPlan must contain at least one node");
        }
    }
}

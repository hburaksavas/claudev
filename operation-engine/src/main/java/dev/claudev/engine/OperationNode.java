package dev.claudev.engine;

import java.util.List;

/**
 * One unit of work in an {@link OperationPlan}. Pairs a domain-core-visible id/dependency shape
 * (mirrored into a plain {@code OperationDag} for persistence — see docs/DOMAIN_MODEL.md) with the
 * actual executable action, which is deliberately not part of domain-core: an operation's
 * persisted structure must stay pure data, and "what Java code runs" is an operation-engine-only
 * runtime concern (the same domain/runtime split already used for provider DTOs, D2).
 */
public record OperationNode(String id, List<String> dependsOn, NodeAction action) {
    public OperationNode {
        dependsOn = List.copyOf(dependsOn);
    }
}

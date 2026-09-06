package dev.claudev.provider.pipeline;

import java.util.Map;

/**
 * {@code stepType} identifies a registry entry (e.g. "EnsureCheckout", "MavenBuild", "ExecStep");
 * {@code params} is validated against that step type's declared schema before execution, never
 * interpreted as a free-form shell string.
 */
public record StepInvocation(String stepType, Map<String, Object> params) {
    public StepInvocation {
        params = Map.copyOf(params);
    }
}

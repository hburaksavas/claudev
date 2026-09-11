package dev.claudev.adapter.rabbitmq;

import dev.claudev.provider.Ack;
import dev.claudev.provider.AdapterManifest;
import dev.claudev.provider.Capability;
import dev.claudev.provider.ProviderError;
import dev.claudev.provider.ProviderResult;
import dev.claudev.provider.runtime.InstanceHealth;
import dev.claudev.provider.runtime.RuntimeProvider;
import dev.claudev.provider.runtime.StartInstanceCommand;
import dev.claudev.provider.runtime.StartInstanceOutcome;
import dev.claudev.provider.runtime.StopInstanceCommand;

import java.util.List;

/**
 * Milestone M1 backlog item — see docs/RABBITMQ_RUNTIME.md and the Spike A feasibility criteria
 * (two simultaneous nodes, shared/external EPMD, non-ASCII user paths) in docs/RISK_REGISTER.md.
 * Deliberately not implemented yet: returns {@link ProviderError.Underlying} rather than pretending
 * to succeed, so callers cannot mistake a stub for a working adapter.
 */
public final class RabbitMqRuntimeProvider implements RuntimeProvider {

    @Override
    public AdapterManifest manifest() {
        return new AdapterManifest(
                "adapter-rabbitmq", "0.1.0",
                List.of(new Capability("runtime", "rabbitmq"), new Capability("status", "not-implemented")),
                "{}");
    }

    @Override
    public ProviderResult<StartInstanceOutcome> start(StartInstanceCommand command) {
        return notImplemented();
    }

    @Override
    public ProviderResult<Ack> stop(StopInstanceCommand command) {
        return notImplemented();
    }

    @Override
    public ProviderResult<InstanceHealth> healthCheck(String instanceId) {
        return notImplemented();
    }

    private static <T> ProviderResult<T> notImplemented() {
        return ProviderResult.err(new ProviderError.Underlying(
                "NOT_IMPLEMENTED", "adapter-rabbitmq is M1 backlog, not yet built"));
    }
}

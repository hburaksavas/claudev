package dev.claudev.provider.runtime;

import dev.claudev.provider.Ack;
import dev.claudev.provider.AdapterManifest;
import dev.claudev.provider.ProviderResult;

/**
 * Lifecycle port for a managed runtime component (RabbitMQ today; the shape does not assume
 * RabbitMQ or Redis concepts). Every method takes/returns only owned, serializable DTOs — no raw
 * PID, socket, or file handle ever crosses this boundary (D2, D5).
 */
public interface RuntimeProvider {

    AdapterManifest manifest();

    ProviderResult<StartInstanceOutcome> start(StartInstanceCommand command);

    ProviderResult<Ack> stop(StopInstanceCommand command);

    ProviderResult<InstanceHealth> healthCheck(String instanceId);
}

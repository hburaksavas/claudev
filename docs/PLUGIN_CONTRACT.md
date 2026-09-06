# Plugin / Provider Contract

## The three ports (plus SecretStore)

```java
interface RuntimeProvider {
    AdapterManifest manifest();
    ProviderResult<StartInstanceOutcome> start(StartInstanceCommand command);
    ProviderResult<Ack> stop(StopInstanceCommand command);
    ProviderResult<InstanceHealth> healthCheck(String instanceId);
}

interface ConnectionProvider {
    AdapterManifest manifest();
    ProviderResult<ScanPage> scan(String connectionId, String cursor, int pageSize);
    ProviderResult<String> getString(String connectionId, String key);
    ProviderResult<Ack> authorizeMutation(MutationRequest request);
    ProviderResult<MutationPreview> prepareMutation(BulkMutationRequest request);
    ProviderResult<Ack> commitMutation(String mutationToken);
}

interface ProjectPipelineProvider {
    AdapterManifest manifest();
    ProviderResult<Ack> execute(PipelineRunRequest request);
}

interface SecretStore {
    AdapterManifest manifest();
    ProviderResult<SecretHandle> store(String plaintextValue);
    ProviderResult<String> resolve(SecretHandle handle);
    ProviderResult<Ack> delete(SecretHandle handle);
}
```

All four live in `provider-api`, compiled in — no runtime plugin loader in V1 (see
[adr/ADR-001-modular-monolith-not-dynamic-plugins.md](adr/ADR-001-modular-monolith-not-dynamic-plugins.md)).

## `ProviderResult<T>` — why not exceptions

```java
sealed interface ProviderResult<T> {
    record Ok<T>(T value) implements ProviderResult<T> {}
    record Err<T>(ProviderError error) implements ProviderResult<T> {}
}

sealed interface ProviderError {
    record NotFound(String message) implements ProviderError {}
    record InvalidConfig(String message) implements ProviderError {}
    record Timeout(String message) implements ProviderError {}
    record PermissionDenied(String message) implements ProviderError {}
    record Conflict(String message) implements ProviderError {}
    record Underlying(String code, String message) implements ProviderError {}
}
```

A closed error taxonomy, not an exception hierarchy adapters can forget to declare — this is what
lets a future out-of-process protocol serialize failures without inventing a taxonomy
retroactively.

## Domain types ≠ wire DTOs

`provider-api` deliberately does not depend on `domain-core`. `StartInstanceOutcome`,
`InstanceHealth`, `StepInvocation`, etc. are their own types, mapped to/from `domain-core`
aggregates at the boundary (in `operation-engine`/adapters), not reused 1:1. This is a real
decision with a real cost (a mapping layer to write and keep in sync) — accepted because the two
sides change for different reasons: domain-core evolves with product semantics, provider-api's
shape only needs to evolve when the port contract itself changes.

`ProjectPipelineProvider.execute` takes `StepInvocation(stepType: String, params: Map<String,
Object>)` rather than one wire type per `domain-core.StepDefinition` variant — steps cross the
port boundary as data validated against a schema owned by the step type, not as a parallel sealed
hierarchy that has to be kept in lockstep with the domain one.

## AdapterManifest — data now, so a future protocol only changes transport

```java
record AdapterManifest(String id, String version, List<Capability> capabilities, String configJsonSchema) {}
```

Registered at compile time (a static list in V1, not a runtime plugin loader). The UI renders
"what runtimes/pipelines exist" generically off manifests instead of hardcoding adapter names, and
a post-V1 out-of-process protocol only has to swap the registry's population mechanism
(compiled-in list → discovered-over-IPC list) and the transport — the manifest and DTO shapes do
not change.

## Post-V1: out-of-process protocol (not built yet)

Deferred, but the DTO discipline above exists specifically to make this an extraction rather than
a rewrite when it happens. It will need, at minimum: version negotiation, capability negotiation,
event streaming (not just request/response), cancellation propagation, and a brokered way to hand
an adapter running in a different process access to secrets/files/processes without giving it a
raw handle. Track as its own ADR when V1 is stable enough to justify the investment.

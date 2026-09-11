# ADR-011: Give `ConnectionProvider` an explicit connect/disconnect lifecycle

## Status
Decided.

## Context
Building the real WP7 Redis adapter surfaced a genuine gap in `provider-api`, not an
implementation detail: `ConnectionProvider#scan`/`getString`/`authorizeMutation`/`prepareMutation`
all take a `connectionId` string, but nothing in the interface ever tells an implementation *what
that connection actually is* — no host, port, or credential ever crosses the port boundary. As
written, an adapter has no way to know what to connect to, only an opaque id to look one up by,
with no lookup mechanism defined. This wasn't a deliberate "resolved elsewhere" design (unlike
`RuntimeProvider`, where every command DTO — `StartInstanceCommand`, `StopInstanceCommand` — already
carries everything an adapter needs); it was simply unbuilt, since no adapter had reached real
implementation before now.

## Decision
Add two methods to `ConnectionProvider`:
```java
ProviderResult<Ack> connect(ConnectOptions options);
ProviderResult<Ack> disconnect(String connectionId);
```
`ConnectOptions(String connectionId, String host, int port, Optional<String> password)` carries
exactly what a real Redis client needs to open a connection — deliberately a plain, owned DTO
(D2): no `SecretRef`, no domain-core `Connection` type crosses this boundary. Resolving a
`SecretRef` to a plaintext password is the caller's job (the future application-layer service that
wires a domain `Connection` to this port), matching how `SecretStore` is a separate port entirely.
An implementation is expected to hold its own internal `connectionId -> client` map, exactly the
pattern `RabbitMqRuntimeProvider`/`DummyRuntimeProvider` already use for `instanceId -> process`.

## Alternatives rejected
- **Pass connection details on every call** (add host/port/password params to `scan`, `getString`,
  etc.). Rejected: repeats the same three fields on every method, and reopening a connection (or
  re-resolving a secret) on every single `scan`/`getString` call is real, avoidable per-call cost a
  Redis explorer UI would call frequently while a user browses keys.
- **Leave connection resolution entirely inside the adapter**, e.g. the adapter reads a config file
  or environment variable keyed by `connectionId`. Rejected: reintroduces exactly the kind of
  adapter-owned, out-of-band state this codebase's port/DTO discipline exists to avoid (D2) — a
  `ConnectionProvider` implementation should not need its own persistence or config-file access.

## Consequences
- Every `ConnectionProvider` implementation (currently just `adapter-redis`) must call `connect`
  before any other method is meaningful; calling `scan`/`getString`/`authorizeMutation` against an
  unconnected `connectionId` returns `ProviderError.NotFound`, not a null/empty result.
- This is a real, if narrow, precedent for extending `provider-api` after an adapter starts real
  implementation rather than getting every port method right up front — acceptable here because no
  other adapter or caller depended on the old three-method shape yet.
- Still open, not solved by this ADR: `MutationRequest(connectionId, operation, key, value)` has no
  way to express a Hash field name, so `HSET`/`HDEL` (docs/REDIS_SCOPE.md's typed edit table) aren't
  implementable through this DTO as it stands — see docs/MILESTONES.md WP7 for that as a separate,
  explicitly deferred follow-up rather than something this ADR silently works around.

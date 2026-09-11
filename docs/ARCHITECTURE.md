# Architecture

## Shape: single-JVM modular monolith

```
JavaFX UI (ui-shell)
  → in-process method calls, no network hop, no IPC
Application layer: Operation Engine (DAG scheduler, cancellation,
  retry/compensation), Reconciler (desired vs observed state), Event Bus
  → domain-core aggregates (pure Java, no I/O)
  → provider-api ports: RuntimeProvider, ConnectionProvider,
    ProjectPipelineProvider, SecretStore (owned serde-friendly DTOs only)
  → adapters: adapter-rabbitmq, adapter-redis, adapter-fe-pipeline,
    adapter-dummy-runtime, secret-store-dpapi, secret-store-legacy
  → platform-windows: JNA Win32 bindings (Job Objects, process handles)
  → persistence: SQLite (WAL), filesystem (logs/artifacts/config)
```

Spring Boot supplies dependency injection and the `persistence`/config plumbing; it does **not**
run an embedded web server or expose a network API. `app-bootstrap`'s `main()` starts the Spring
context, then launches the JavaFX `Application` against the already-running context — see
[adr/ADR-009-javafx-single-jvm-not-electron.md](adr/ADR-009-javafx-single-jvm-not-electron.md) for
why this replaced an earlier React+Electron+sidecar direction.

## Why no dynamic plugin loader in V1

Three compiled-in Rust-style trait ports (`RuntimeProvider`, `ConnectionProvider`,
`ProjectPipelineProvider`) plus `SecretStore`, each with a static `AdapterManifest`. Dynamic
third-party plugins raise ABI, trust, signing, crash-isolation, and versioning problems before the
product model itself is proven. See
[adr/ADR-001-modular-monolith-not-dynamic-plugins.md](adr/ADR-001-modular-monolith-not-dynamic-plugins.md).

## The DTO boundary discipline (load-bearing, not stylistic)

Every provider port method takes/returns only owned, serializable DTOs (`provider-api`'s own
records) — never a `domain-core` type directly, never a raw OS handle (PID, socket, file handle),
never a borrowed reference. Errors are the closed `ProviderError` enum, never an unchecked/opaque
exception. This is what makes a future out-of-process plugin protocol (JSON-RPC/gRPC over a named
pipe, deferred past V1) a transport swap instead of a redesign — see
[PLUGIN_CONTRACT.md](PLUGIN_CONTRACT.md).

## Desired vs. observed state

`domain-core.Instance` carries a `desiredState` (what the user asked for) separately from
`observedState` (what the Reconciler last verified). The Reconciler runs at app startup —
mandatory, blocking the UI until the first pass completes — and on a timer thereafter. It never
infers `Running` from a stored PID or an occupied port alone; see
[PROCESS_SAFETY.md](PROCESS_SAFETY.md) for the full identity-verification algorithm.

## The Event Bus

`OperationEvent`s (started, step-progress, log-line, warning, failed, completed, skipped) are one
representation, not two hand-maintained copies: forwarded live to `subscribe()`rs and
appended to the append-only `operation_events` table for the audit trail and post-crash
reconstruction. Implemented in `operation-engine`'s `InMemoryOperationEngine` (docs/MILESTONES.md
WP4) — `subscribe()` is a live in-process callback for now, not yet wired to the JavaFX thread
specifically, since `ui-shell` doesn't consume it yet (WP5).

## Component ownership at a glance

| Concern | Owner |
|---|---|
| Domain aggregates, invariants | `domain-core` |
| Port contracts, DTOs, error taxonomy | `provider-api` |
| DAG scheduling, cancellation, event bus | `operation-engine` |
| Win32 Job Objects, process handles | `platform-windows` |
| SQLite schema, WAL config, migrations | `persistence` |
| RabbitMQ lifecycle | `adapter-rabbitmq` |
| Redis connection/explorer | `adapter-redis` |
| Corporate FE pipeline steps | `adapter-fe-pipeline` |
| Secrets (default + legacy import) | `secret-store-dpapi`, `secret-store-legacy` |
| UI | `ui-shell` |
| Wiring, single-JVM lifecycle | `app-bootstrap` |
| jlink/jpackage | `packaging` (placeholder — see [RISK_REGISTER.md](RISK_REGISTER.md)) |

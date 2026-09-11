# Repository Layout

Maven multi-module, `dev.claudev` group id.

```
domain-core           Pure Java aggregates (Workspace, Instance, LaunchRecord, Connection,
                       Pipeline, Operation, SecretRef, AuditEntry). No I/O, no dependencies
                       on any other in-repo module.

provider-api           RuntimeProvider / ConnectionProvider / ProjectPipelineProvider /
                       SecretStore interfaces, their DTOs, ProviderError, ProviderResult,
                       AdapterManifest. No dependency on domain-core (see PLUGIN_CONTRACT.md).

operation-engine       DAG scheduler, cancellation, retry/compensation, Event Bus.
                       Depends on: domain-core, provider-api. [interface-only stub]

platform-windows       JNA Win32 bindings: Job Objects, CreateProcessW-suspended spawn, argv
                       quoting, explicit environment blocks, pid+creation-time+SHA-256 identity
                       verification (all real, tested against live Win32 APIs). Every raw
                       HANDLE the app touches lives here; WindowsProcessLauncher is the only
                       place allowed to call CreateProcessW directly.
                       Depends on: jna, jna-platform.

persistence            SQLite WAL/busy-timeout config (real, tested), migrations,
                       audit/operation-event tables.
                       Depends on: domain-core, spring-boot-starter-jdbc, sqlite-jdbc.

secret-store-dpapi     Default SecretStore: user-scoped DPAPI via JNA (real, tested).
                       Depends on: provider-api, jna.

secret-store-legacy    Base64 "encoded, not encrypted" import-only SecretStore, off by default.
                       Depends on: provider-api.

adapter-rabbitmq       RuntimeProvider for the pinned RabbitMQ+Erlang pair. [stub]
                       Depends on: provider-api, platform-windows.

adapter-redis          ConnectionProvider, connection-only, Lettuce client. [stub]
                       Depends on: provider-api, lettuce-core.

adapter-fe-pipeline    ProjectPipelineProvider: step catalog + ExecStep. [stub]
                       Depends on: provider-api, platform-windows.

ui-shell               JavaFX views/ViewModels; calls the application layer in-process.
                       Depends on: domain-core, provider-api, operation-engine, javafx-*.

app-bootstrap           Spring Boot main() + JavaFX Application entry point; single-JVM
                       lifecycle wiring (real, tested — see ClaudevApplicationTests).
                       Depends on: everything.

packaging              jlink/jpackage config — placeholder until the M5 packaging spike runs.
```

## Dependency direction

`domain-core` and `provider-api` depend on nothing else in-repo (by design — see
[PLUGIN_CONTRACT.md](PLUGIN_CONTRACT.md) for why they don't even depend on each other).
`platform-windows` depends only on JNA. Everything else depends inward toward these, never the
reverse — an adapter never becomes a dependency of `domain-core` or `provider-api`.

## What "stub" means here

A module marked `[stub]` compiles, implements its port interface, and returns
`ProviderResult.err(ProviderError.Underlying("NOT_IMPLEMENTED", ...))` from every method. This is
deliberate: a stub that silently "succeeds" with fake data would be worse than one that fails
loudly, since a caller could mistake it for working. See [MILESTONES.md](MILESTONES.md) for the
backlog that turns each stub into a real adapter.

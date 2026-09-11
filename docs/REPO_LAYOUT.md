# Repository Layout

Maven multi-module, `dev.claudev` group id.

```
domain-core           Pure Java aggregates (Workspace, Instance, LaunchRecord, Connection,
                       Pipeline, Operation, SecretRef, AuditEntry). No I/O, no dependencies
                       on any other in-repo module.

provider-api           RuntimeProvider / ConnectionProvider / ProjectPipelineProvider /
                       SecretStore interfaces, their DTOs, ProviderError, ProviderResult,
                       AdapterManifest. No dependency on domain-core (see PLUGIN_CONTRACT.md).

operation-engine       Reconciler and the DAG scheduler/cancellation/Event Bus
                       (InMemoryOperationEngine) — both real, tested against real spawned
                       processes and real SQLite, not mocked. Retry policy/per-step timeout/
                       compensation beyond "fail the node" are not built (no adapter needs them
                       yet — WP6/7/8 will drive what's actually required).
                       Depends on: domain-core, provider-api, platform-windows, persistence.

platform-windows       JNA Win32 bindings: Job Objects, CreateProcessW-suspended spawn, argv
                       quoting, explicit environment blocks, pid+creation-time+SHA-256 identity
                       verification, process enumeration (K32EnumProcesses), exit-code waiting
                       (ProcessExitWaiter, with the same identity re-verification discipline —
                       never wait on a pid without confirming it), and optional stdout/stderr
                       file redirection for CLI-shaped spawns (all real, tested against live
                       Win32 APIs). Every raw HANDLE the app touches lives here;
                       WindowsProcessLauncher is the only place allowed to call CreateProcessW
                       directly.
                       Depends on: jna, jna-platform.

persistence            SQLite WAL/busy-timeout config, a self-contained migration runner (not
                       Flyway — no SQLite support exists there), the 9-table schema, and
                       repositories for Workspace/Instance/LaunchRecord/AuditEntry with
                       optimistic concurrency (all real, tested against real on-disk SQLite
                       files). Connection/pipeline/operation repositories not built yet.
                       Depends on: domain-core, spring-boot-starter-jdbc, sqlite-jdbc.

secret-store-dpapi     Default SecretStore: user-scoped DPAPI via JNA (real, tested).
                       Depends on: provider-api, jna.

secret-store-legacy    Base64 "encoded, not encrypted" import-only SecretStore, off by default.
                       Depends on: provider-api.

adapter-rabbitmq       RuntimeProvider for the pinned RabbitMQ+Erlang pair (WP6) — real, tested
                       against real spawned nodes (start/stop/health, two simultaneous nodes,
                       EPMD survival, a Turkish-character data dir), not mocked. Not yet wired as
                       a Spring bean in app-bootstrap (see MILESTONES.md WP6 for why).
                       Depends on: provider-api, platform-windows.

adapter-redis          ConnectionProvider, connection-only, Lettuce client. [stub]
                       Depends on: provider-api, lettuce-core.

adapter-fe-pipeline    ProjectPipelineProvider: the full step catalog (EnsureCheckout..HealthCheck)
                       + ExecStep, real — tested against a real public git repo (real clone,
                       fetch, blocked-on-dirty, blocked-on-diverged, real fast-forward) and a real
                       local Maven build, not mocked.
                       Depends on: provider-api, platform-windows.

adapter-dummy-runtime  RuntimeProvider that spawns a real, trivial, long-lived process under a Job
                       Object — not RabbitMQ/Redis, but real, wired as the active RuntimeProvider
                       bean while adapter-rabbitmq/adapter-redis remain stubs (WP5, see
                       MILESTONES.md). Swap back to adapter-rabbitmq once WP6 lands.
                       Depends on: provider-api, platform-windows.

ui-shell               JavaFX views/ViewModels; calls the application layer in-process. Two tabs:
                       diagnostics (WP0) and workspaces (WP5) — workspace/instance CRUD, start/stop
                       dispatched as real operations, a live operation event log, and a system tray
                       icon for minimize-to-tray.
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

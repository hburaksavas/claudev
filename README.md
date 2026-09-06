# claudev

Windows desktop application for creating and orchestrating developer workspaces containing
RabbitMQ, Redis, and corporate front-end server projects — open-source, user-space install, no
admin requirement.

## Stack

Java 21 + Spring Boot + JavaFX, **single JVM** (no Electron, no sidecar process — see
[docs/adr/ADR-009-javafx-single-jvm-not-electron.md](docs/adr/ADR-009-javafx-single-jvm-not-electron.md)).
Maven multi-module build.

## Status

Design/skeleton stage. `domain-core`, `provider-api`, `platform-windows`, `persistence`
(pragma config), `secret-store-dpapi`, `secret-store-legacy`, and the `app-bootstrap`/`ui-shell`
single-JVM lifecycle wiring are real, compiled, and test-verified **on a live Windows machine**
(not mocked) — see the smoke tests in `platform-windows` and `secret-store-dpapi`. The three
runtime adapters (`adapter-rabbitmq`, `adapter-redis`, `adapter-fe-pipeline`) and `operation-engine`
are interface-only stubs; see [docs/MILESTONES.md](docs/MILESTONES.md) for what's next.

## Quick start

```bash
mvn compile                              # requires JDK 21 — see docs/USAGE.md if this fails
mvn test                                 # includes real (not mocked) Win32/DPAPI/SQLite tests
mvn -pl app-bootstrap -am spring-boot:run # launches the JavaFX shell against the Spring context
```

Full setup, testing, running, packaging status, and how to pick up a stub adapter:
**[docs/USAGE.md](docs/USAGE.md)**.

## Module map

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full layering and
[docs/REPO_LAYOUT.md](docs/REPO_LAYOUT.md) for what belongs in each module.

## Documents

| Doc | Covers |
|---|---|
| [USAGE.md](docs/USAGE.md) | Setup, build, test, run, package status, extending a stub adapter |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Layering, component responsibilities, single-JVM lifecycle |
| [DOMAIN_MODEL.md](docs/DOMAIN_MODEL.md) | Aggregates, enums, invariants (`domain-core`) |
| [PLUGIN_CONTRACT.md](docs/PLUGIN_CONTRACT.md) | Provider ports, DTOs, `ProviderError`, `AdapterManifest` |
| [PROCESS_SAFETY.md](docs/PROCESS_SAFETY.md) | LaunchRecord identity, Job Object topology, reconciler states |
| [PERSISTENCE.md](docs/PERSISTENCE.md) | SQLite schema approach, WAL/busy-timeout, audit/event tables |
| [SECURITY.md](docs/SECURITY.md) | SecretStore, Redis destructive-op guard, ExecStep sandboxing |
| [REDIS_SCOPE.md](docs/REDIS_SCOPE.md) | V1 Redis operation allow-list and the authorization+audit gate |
| [FE_PIPELINE_STEPS.md](docs/FE_PIPELINE_STEPS.md) | Step catalog, Git integration choice, ExecStep |
| [RABBITMQ_RUNTIME.md](docs/RABBITMQ_RUNTIME.md) | Pinned pair, EPMD lifecycle, node identity |
| [REPO_LAYOUT.md](docs/REPO_LAYOUT.md) | Module responsibilities and dependency graph |
| [TESTING_STRATEGY.md](docs/TESTING_STRATEGY.md) | Unit/integration/native-call test approach |
| [MILESTONES.md](docs/MILESTONES.md) | M0–M5 backlog with acceptance gates |
| [RISK_REGISTER.md](docs/RISK_REGISTER.md) | Licensing, corporate proxy/CA, AV interference, spikes |
| [adr/](docs/adr/) | One file per durable, hard-to-reverse decision |

## Why these documents exist

This architecture went through two rounds of adversarial review (challenging assumptions,
proposing alternatives, converging on explicit decisions) before any code was written. The `adr/`
folder is where that survives in a form worth reading later — every ADR names the alternative that
was rejected and why, not just the choice that was made.

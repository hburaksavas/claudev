# ADR-001: In-process modular monolith, not a dynamic native-plugin platform (V1)

## Status
Decided.

## Context
The original product context called for a "plugin-oriented architecture" where core must not
depend directly on RabbitMQ or Redis concepts. The naive reading of that is a dynamically loaded
native-plugin system (e.g. Rust-style `dylib`/Java `ClassLoader`-per-plugin).

## Decision
V1 is a modular monolith: three compiled-in ports (`RuntimeProvider`, `ConnectionProvider`,
`ProjectPipelineProvider`) plus `SecretStore`, each with first-party adapters compiled directly
into the application, each with a static `AdapterManifest` for metadata/UI routing.

## Alternatives rejected
- **Dynamically loaded native/JAR plugins in V1.** Raises ABI/classloader isolation, code-signing
  and trust, crash-isolation, and versioning problems before the product model itself is proven.
  Solving those problems well is a substantial project on its own; doing it before V1 even has a
  working core is solving the wrong problem first.

## Consequences
- Extensibility for V1 means "add a new compiled-in adapter," not "a user installs a third-party
  plugin."
- A post-V1 out-of-process plugin protocol (JSON-RPC/gRPC over a named pipe) is deferred to its own
  future ADR — see [PLUGIN_CONTRACT.md](../PLUGIN_CONTRACT.md). The DTO/error-taxonomy discipline
  in ADR-adjacent design (owned serializable types, closed `ProviderError`, manifest-as-data) exists
  specifically so that future step is a transport swap, not a rewrite.

# ADR-008: Backend is Java + Spring Boot, not Rust

## Status
Decided (product owner's explicit, authoritative constraint — not derived from a technical
trade-off analysis).

## Context
An earlier design round worked out a full architecture assuming a Rust core (Tauri backend,
`RuntimeProvider`/`ConnectionProvider`/`ProjectPipelineProvider` as Rust traits, Cargo workspace
layout). The product owner then set a hard requirement: the backend must be Java + Spring Boot.

## Decision
Java 21 + Spring Boot for all domain, application, and adapter logic. Maven multi-module build
(not Gradle — chosen for this repo specifically because it's what's available and verifiable in
this environment; either would have worked).

## Consequences
- Every product-safety decision made under the Rust design (desired/observed state split,
  `LaunchRecord` identity model, Job Object topology, DTO/error-taxonomy discipline at the provider
  boundary, Redis/RabbitMQ scope decisions) carries over unchanged — those are product decisions,
  not language-specific ones.
- What changes is *mechanism*, not *policy*: Java has no built-in Job Object or
  `CreateProcessSuspended` support, so `platform-windows` binds the needed Win32 calls via JNA
  rather than relying on `java.lang.ProcessBuilder`, which offers neither. JNA does not manage
  native handle lifetime the way Rust's ownership model would have — every acquired
  process/thread/job `HANDLE` must be explicitly closed (`try`/`finally` or `AutoCloseable`), or it
  leaks for the life of the JVM process. This is a standing implementation discipline, not a
  one-time spike.
- Java's `ProcessBuilder`/`CreateProcessW` argv-quoting behavior must be independently verified
  (not assumed to inherit whatever guarantee the earlier Rust design relied on from
  `std::process::Command`) — see [SECURITY.md](../SECURITY.md) and
  [TESTING_STRATEGY.md](../TESTING_STRATEGY.md).
- Packaging moves from a Tauri installer to `jlink` (minimal custom runtime image) + `jpackage`
  (native Windows installer), still targeting "no admin requirement, user-space install."

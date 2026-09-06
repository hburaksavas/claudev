# ADR-009: JavaFX + Spring Boot in one JVM, not React + Electron + a Spring Boot sidecar

## Status
Decided by the product owner, after both directions were designed and compared.

## Context
Once the backend was fixed as Java + Spring Boot (ADR-008), the UI question was open: reuse a
React UI via Electron (talking to the Java backend as a spawned sidecar process), or use JavaFX
in the same JVM as Spring Boot.

Both were fully designed before this decision was made. The Electron+sidecar direction required:
Electron spawning the Spring Boot jar as a child process; a Job Object (or equivalent) to keep the
sidecar from surviving an Electron crash; a readiness handshake before showing the UI; a
loopback-only, per-launch-token-authenticated HTTP/WS API as the *only* channel between renderer
and backend; a restart-vs-fatal policy for an independently-crashing sidecar; and a doubled
packaging footprint (a full JRE plus a Chromium/Node runtime, both needing independent CVE
tracking) on the same "no admin, corporate AV/EDR fleet" target this tool already has to satisfy
for one bundled runtime (RabbitMQ+Erlang).

## Decision
JavaFX and the Spring `ApplicationContext` share one process and one heap. The operation engine,
providers, reconciler, and Job Object supervisor are called as ordinary Java objects from the UI,
not over a network API.

## Alternatives rejected
- **React + Electron + Spring Boot sidecar.** Reuses a larger UI component ecosystem and hiring
  pool, but reintroduces — for the app's *own* backend — exactly the class of cross-process trust
  and lifecycle-supervision problem this design spends most of its effort building safeguards
  against for RabbitMQ/Redis/FE-pipeline child processes. Paying that cost to secure a relationship
  (UI ↔ its own backend) that a single-JVM design removes entirely, rather than merely governs,
  runs against the architecture's own stated priorities.

## Consequences
- No loopback HTTP/WS API is required for the core UI — an entire authentication/authorization
  surface the Electron path would have had to build and defend does not exist. An optional,
  disabled-by-default loopback automation API remains a possible *future* addition (useful for
  scripted integration tests or a future second UI), but is explicitly not required for M0–M3.
- Java has no built-in Job Object support (see ADR-008's consequences) — `platform-windows`'s JNA
  bindings are how the single-JVM app itself supervises RabbitMQ/Redis/pipeline child processes.
  There is no second-level "who supervises the supervisor" problem, because there is only one
  process.
- The cost accepted: JavaFX's component/theming ecosystem and hiring pool are smaller than React's,
  and UI iteration speed is generally slower with FXML/Scene Builder than a web-based renderer.
  This is a real trade, made deliberately.
- Reversible: `provider-api` and `operation-engine` remain UI-agnostic (per the domain/DTO
  separation in [PLUGIN_CONTRACT.md](../PLUGIN_CONTRACT.md)), so a future web or Electron frontend
  remains possible later behind an explicitly added API, without having been forced into existence
  now.
- Packaging uses `jlink` + `jpackage` against a single application jar — see
  [MILESTONES.md](../MILESTONES.md) M3 and [RISK_REGISTER.md](../RISK_REGISTER.md) for the
  not-yet-run packaging spike.

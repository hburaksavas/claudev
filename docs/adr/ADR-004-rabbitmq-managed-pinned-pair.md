# ADR-004: RabbitMQ V1 is one pinned, checksum-verified RabbitMQ+Erlang/OTP pair

## Status
Decided; feasibility spike not yet run (see Consequences).

## Context
RabbitMQ requires a compatible Erlang/OTP runtime, has nontrivial licensing/size/cookie/node-name/
data-path/Windows-path constraints, and the original context's "bundled RabbitMQ" promise didn't
specify a version strategy at all.

## Decision
Support a runtime catalog with `Managed`, `Imported`, and `System` sources. A `Managed` package is
**one explicitly tested RabbitMQ+Erlang/OTP version pair per platform build**, checksum-verified
before first launch — never "whatever binaries happen to be bundled."

## Alternatives rejected
- **Bundle "current" RabbitMQ + "whatever Erlang is on the machine."** Untested version
  combinations are a well-known source of RabbitMQ startup failures; this would push a
  compatibility-matrix problem onto every user instead of solving it once, centrally.

## Consequences
- Per-instance isolation requirements follow directly: unique nodename/ports/cookie environment
  per instance, EPMD as a separate ref-counted dependency never owned by a single instance's
  lifecycle — see [RABBITMQ_RUNTIME.md](../RABBITMQ_RUNTIME.md).
- A release-blocking feasibility spike (two simultaneous nodes, shared/external EPMD, non-ASCII
  paths, safe single-node stop) has not yet been run — see
  [RISK_REGISTER.md](../RISK_REGISTER.md). This ADR's decision holds only if that spike passes; a
  failure there is scoped as changing the RabbitMQ approach, not abandoning the pinned-pair
  principle itself.
- Licensing/SBOM review of the pinned pair (RabbitMQ MPL-2.0 + the chosen Erlang/OTP distribution)
  is a milestone-1 exit criterion, not a release-day discovery.

# ADR-003: Redis V1 is connection-only — no bundled/managed local Redis

## Status
Decided.

## Context
The original product context promised "bundled RabbitMQ and Redis runtimes." Official Redis has no
supported Windows server distribution; the only ways to get a local Redis on Windows today are an
unofficial community fork (e.g. tporadowski/redis-windows), a commercial reimplementation (e.g.
Memurai), or a WSL2/Docker bridge.

## Decision
V1 Redis support is connection-only: remote, imported, and system (discovered) sources. **No
managed local Redis is bundled or lifecycle-owned.** Existing WSL2/Docker Redis endpoints may be
discovered and offered as connection targets without the app taking ownership of their lifecycle.

## Alternatives rejected
- **Bundle an unofficial Windows fork under a "managed runtime" abstraction.** For an open-source
  tool, this means redistributing unaudited third-party binaries under the project's own trust
  umbrella while implying (via the same `RuntimeManager` concept used for RabbitMQ) that it's an
  audited first-party artifact. That's the same mistake the original spec made at whole-product
  scale, just repeated at smaller scope.
- **Automatically enable WSL2 to host a managed Redis.** Enabling WSL2 needs admin rights and
  typically a reboot, which directly violates the "no admin requirement" constraint. The app may
  *detect and use* an already-enabled WSL2/Docker Redis, but must never attempt to enable one
  itself, and must never silently degrade a feature without telling the user why.

## Consequences
- The product's Redis story for V1 is "manage the Redis you already have," not "spin up Redis for
  you" — a real, stated scope reduction from "rich explorer/editor/CLI," accepted because a rich
  CLI is an arbitrary-command surface that would undercut the safety model in
  [SECURITY.md](../SECURITY.md) anyway.
- A future "managed local Redis via WSL2" direction remains open, but is a product decision someone
  has to make explicitly (it trades away "no admin requirement"), not something to enable quietly
  in architecture.
- See [REDIS_SCOPE.md](../REDIS_SCOPE.md) for the concrete V1 operation allow-list this scope
  reduction implies.

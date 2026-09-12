# Redis V1 Scope

## Status: adapter's data-plane built and verified — see docs/MILESTONES.md WP7

`adapter-redis`'s `RedisConnectionProvider` really connects (Lettuce), and really implements every
row of the typed edit table below — String, Key/TTL, Hash, List, Set, ZSet — plus `SCAN` paging and
a real bulk pattern-DEL preview/commit token flow with TOCTOU rejection, tested against a genuine
Windows Redis 5.0.14.1 build (`tporadowski/redis`, test-only infrastructure, never bundled — see
"Sourcing" below). Hash/List/Set/ZSet writes needed a `field` slot `MutationRequest` didn't have —
see [ADR-012](adr/ADR-012-redis-typed-edit-set-dto-shape.md) for that DTO extension and why `HGETALL`/
`SMEMBERS` reads are size-capped rather than cursor-paged like `SCAN`. The `environmentClass`/
audit-entry authorization gate below is also built and verified, in `app-bootstrap`'s
`SpringConnectionControlPort`, not in `authorizeMutation` itself (see MILESTONES.md WP7 for why).
**Not built**: any UI.

## Sourcing (no bundled binary; two lifecycle shapes exist)

**No bundled/managed local Redis.** Official Redis has no supported Windows server distribution;
bundling an unofficial fork (tporadowski/redis-windows) or a commercial reimplementation (Memurai)
under a "managed runtime" abstraction that implies an audited first-party artifact repeats the
mistake this design explicitly rejected for RabbitMQ, at smaller scale. If a truly managed-local
story is wanted later, WSL2-orchestrated real upstream Redis is the more defensible direction — but
it conflicts with the "no admin requirement" constraint (enabling WSL2 needs admin + reboot) and is
a product decision to make explicitly, not something architecture should quietly enable.

**Connection-only (WP10a)**: `adapter-redis`'s `RedisConnectionProvider` points at a Redis the user
already has running, wherever it runs (native, WSL2, Docker, remote). Existing WSL2/Docker Redis
endpoints may be *discovered* (`RedisEndpointDetector`: a TCP-connect + `PING` probe on common
localhost ports) and offered as a connection target, without the app ever taking lifecycle
ownership of them.

**App-launched, user-owned binary (WP10f, added after this doc's original "connection-only"
framing)**: `adapter-redis`'s `RedisRuntimeProvider` spawns/stops/health-checks a `redis-server.exe`
the user already has installed (Memurai, tporadowski/redis-windows, a Chocolatey package — the app
never chooses or cares which), via the same Job Object primitive every other instance uses. This is
**not** a reversal of the "no bundled/managed local Redis" rule above: no binary is downloaded,
selected, or checksummed by this app — the trust boundary is identical to RabbitMQ's
`RuntimeSource.Imported` mode, which this document's rule was never about (it targets *bundling*,
i.e. `Managed`). `RedisInstallDetector` (registry/PATH/well-known-directory scan, mirroring
`adapter-rabbitmq.detect`'s shape minus the Erlang-style pairing step) finds an already-installed
binary the same way the RabbitMQ dialog finds an install, so the user still isn't asked to type a
path unless nothing is found.

## The typed edit set (concrete, not "small")

A scope description like "a deliberately small typed edit set" isn't acceptance-testable. This is
the concrete allow-list:

| Type | Read | Write |
|---|---|---|
| String | `GET` | `SET` (optional TTL), `APPEND` |
| Hash | `HGETALL` (bounded field count) | `HSET` one field, `HDEL` one field |
| List | `LRANGE` (bounded window) | `LPUSH`/`RPUSH` one value, `LPOP`/`RPOP` |
| Set | `SMEMBERS` (bounded) | `SADD` one member, `SREM` one member |
| ZSet | `ZRANGE`/`ZRANGEBYSCORE` (bounded) | `ZADD` one member+score, `ZREM` one member |
| Key/TTL | `TTL`, `EXISTS`, `TYPE` | `EXPIRE`, `PERSIST`, single exact-key `DEL` (never glob) |

**Explicitly excluded from V1:** `FLUSHDB`, `FLUSHALL`, unbounded `KEYS`, pattern-based multi-key
`DEL`/`UNLINK`, `EVAL`/`SCRIPT`, `CONFIG`/`CLUSTER`/admin commands, a raw command console.

## The authorization+audit gate applies to every mutation, regardless of size

A single `SET` overwriting one key, or a single `DEL` of one key, can be exactly as damaging as a
bulk flush if that key happens to be a live feature flag, a session-of-record, or a lock on a
mis-targeted connection. Bulk-vs-single is not the same axis as harmless-vs-dangerous:

- **Every** mutation (single-key or bulk) requires the connection to be explicitly unlocked from
  read-only, and writes an `AuditEntry`.
- **Only** bulk/glob/flush-class operations additionally require `prepareMutation` (bounded impact
  preview + short-lived single-use token) → typed confirmation → `commitMutation`. Impact preview
  is meaningless for a single key the user already typed by name.
- The `prepareMutation`/`commitMutation` token must be rejected on `commit` if the underlying data
  changed since preview, not merely on a timer expiry (TOCTOU).

## SCAN is not a snapshot

Concurrent mutation during a browse session can skip or repeat keys. The UI must present a stated,
bounded page size and an explicit "this is a live, possibly-incomplete view" affordance — never
imply a consistency guarantee the protocol doesn't provide.

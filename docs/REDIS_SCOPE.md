# Redis V1 Scope

## Status: adapter partially built and verified — see docs/MILESTONES.md WP7

`adapter-redis`'s `RedisConnectionProvider` really connects (Lettuce), and really implements the
String and Key/TTL rows of the typed edit table below, `SCAN` paging, and a real bulk pattern-DEL
preview/commit token flow with TOCTOU rejection — tested against a genuine Windows Redis 5.0.14.1
build (`tporadowski/redis`, test-only infrastructure, never bundled — see "Sourcing" below). Hash/
List/Set/ZSet operations are **not** built: `MutationRequest` has no field/member slot to address
them with, and inventing an encoding (e.g. packing a field name into the `value` string) was judged
worse than leaving them out — see MILESTONES.md for the exact, scoped follow-up.

## Sourcing (not managed, connection-only)

V1 ships `imported`/`system`/remote connection sources only — point at a Redis the user already
has running, wherever it runs (native, WSL2, Docker, remote). **No bundled/managed local Redis.**
Official Redis has no supported Windows server distribution; bundling an unofficial fork
(tporadowski/redis-windows) or a commercial reimplementation (Memurai) under a "managed runtime"
abstraction that implies an audited first-party artifact repeats the mistake this design
explicitly rejected for RabbitMQ, at smaller scale. If a managed-local story is wanted later,
WSL2-orchestrated real upstream Redis is the more defensible direction — but it conflicts with the
"no admin requirement" constraint (enabling WSL2 needs admin + reboot) and is a product decision to
make explicitly, not something architecture should quietly enable.

Existing WSL2/Docker Redis endpoints may be *discovered* (detected and offered as a connection
target) without the app ever taking lifecycle ownership of them.

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

# ADR-012: How the Hash/List/Set/ZSet typed edit set fits `provider-api`'s DTOs

## Status
Decided.

## Context
WP7 built the String and Key/TTL rows of docs/REDIS_SCOPE.md's typed edit table
(`GET`/`SET`/`APPEND`/`DEL`/`EXPIRE`/`PERSIST`) through the existing `MutationRequest(connectionId,
operation, key, value)` and left Hash/List/Set/ZSet explicitly unbuilt: `HSET` needs a field *and* a
value, `ZADD` needs a member *and* a score — two payloads, and `MutationRequest` only carries one
(`value`). Packing a second value into the existing `value` string (e.g. `"field=value"`) was
rejected in WP7 as an undocumented, adapter-invented wire format outside this port's DTO discipline.

Reads have the same gap in the other direction: `ConnectionProvider` has only `getString` (String)
and `scan` (key names) — nothing returns a hash's fields, a list's elements, a set's members, or a
sorted set's range.

## Decision
**Writes**: add one field to `MutationRequest`: `Optional<String> field`, meaning depends on
`operation` — the hash field name for `HSET`/`HDEL`, the score (as a string) for `ZADD`, unused for
everything else. Documented per-operation on the record itself, not left to be inferred.

**Reads**: four new `ConnectionProvider` methods, one per type, each shaped around how that type is
actually bounded rather than forcing a single generic shape:
- `getHash(connectionId, key)` → the hash's fields, capped at a fixed size ceiling (not a live
  cursor like `scan`) — a `HGETALL`-then-truncate approach, honestly documented as a size cap, not
  a resumable page.
- `getListRange(connectionId, key, start, stop)` → `LRANGE` with caller-supplied bounds; genuinely
  bounded by construction, no capping needed.
- `getSetMembers(connectionId, key)` → `SMEMBERS`, same size-cap approach as the hash read.
- `getSortedSetRange(connectionId, key, start, stop)` → `ZRANGE` by index, caller-supplied bounds,
  genuinely bounded like the list read.

## Alternatives rejected
- **A single generic `getCollection(connectionId, key, kind, ...)` method** dispatching internally
  by a `kind` enum, mirroring `authorizeMutation`'s string-operation dispatch. Rejected: the four
  types don't share a return shape (`Map` vs `List` vs `Set`) or a boundedness model (size-capped
  vs caller-windowed) closely enough for one signature to be honest about either — a caller would
  need to know the type anyway to interpret the result, so a single method buys generality without
  removing any real caller-side type-checking.
- **`HSCAN`/`SSCAN`/`ZSCAN` cursor-based paging for every type**, matching `scan`'s live-view model
  exactly. More consistent, and worth doing later if a real caller needs to browse a multi-thousand-
  entry hash/set/zset — not attempted here because it means three more cursor-shaped return types
  (`MapScanCursor`/`ValueScanCursor`/`ScoredValueScanCursor` in Lettuce terms) for a V1 feature this
  session has no UI consumer for yet. The size-cap approach for Hash/Set is a real, honestly-labeled
  simplification, not a hidden one — revisit if a real caller needs true cursor-based paging over a
  large collection.
- **Extending `BulkMutationRequest`/the token flow to cover per-type bulk ops** (e.g. "delete all
  members matching X"). Out of scope — WP7's bulk flow already covers pattern-based `DEL`, and
  REDIS_SCOPE.md's own exclusion list keeps most bulk operations out of V1 regardless of type.

## Consequences
- `MutationRequest.field` is `Optional.empty()` for every operation this document's own table
  doesn't require it for — an adapter should never read it for `SET`/`APPEND`/`DEL`/`EXPIRE`/
  `PERSIST`.
- The two size-capped reads (`getHash`, `getSetMembers`) are not resumable and not a live view the
  way `scan` is — a caller building a UI around them must not present them as paginated.
- If a future need requires true cursor-based Hash/Set/ZSet browsing, revisit this ADR rather than
  silently bolting cursor support onto the size-capped methods.
